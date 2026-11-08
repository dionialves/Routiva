# Fila persistente e agregação direta

O limite permanece em **10.000 hosts ativos por coletor**. O intervalo dos pings continua configurado por host (padrão 1 s). Os 100 ms fecham lotes incompletos, sem limitar o envio a um lote por intervalo.

## Entrega

1. O Go agrupa até 500 resultados, ou fecha o lote após 100 ms.
2. Confirma a gravação no SQLite local (WAL, synchronous=FULL).
3. Envia um lote e aguarda ACK.
4. Java valida o lote e agrupa por host, destino e minuto original da medição.
5. Uma transação grava resumos de minuto, rollups de hora/dia, alterações incrementais e recibo.
6. Após o commit, Java envia ACK e Go apaga o lote do SQLite.

Reenvios preservam a identidade e não duplicam as contagens. Novos pings individuais não são inseridos no PostgreSQL. A fila guarda protobufs brutos somente enquanto aguardam confirmação ou retenção.

Resumos preservam respostas saudáveis/degradadas, perdas, erros internos, soma de latências e última medição. Mínimo/máximo são armazenados para novos minutos; ficam desconhecidos em minutos históricos sem extremos completos. Os gráficos existentes mantêm média ponderada e perda. Ausência de resposta não vira latência zero; erro de coleta não vira perda de rede.

## Configuração

| Variável | Padrão | Efeito |
|---|---:|---|
| COLLECTOR_SPOOL_PATH | /var/lib/routiva/queue.db | Arquivo SQLite local |
| COLLECTOR_SPOOL_MAX_MIB | 256 | Orçamento dos payloads pendentes; mínimo 1 MiB |
| COLLECTOR_SPOOL_MAX_AGE_HOURS | 24 | Idade desde a entrada na fila; entre 1 e 48 horas |
| COLLECTOR_BATCH_SIZE | 500 | Máximo de resultados no lote; até 5.000 |
| COLLECTOR_FLUSH_MS | 100 | Prazo de fechamento do lote incompleto |
| COLLECTOR_RESULT_BUFFER | 32768 | Resultados em RAM aguardando formação de lotes |

O serviço Compose repassa os limites do `.env` ao coletor e usa o volume `collector_data`. O caminho no Compose é fixado em `/var/lib/routiva/queue.db`. No systemd, `StateDirectory=routiva` cria o diretório persistente. Na execução manual, exporte as variáveis no ambiente do processo Go e use um caminho gravável.

O orçamento de payload não é o consumo total de disco. Há overhead de páginas/índices; o arquivo principal tem teto aproximado de duas vezes o orçamento mais 4 MiB, com WAL adicional e checkpoints automáticos. Páginas apagadas são reutilizadas. Monitore também o espaço livre do volume.

Ao atingir tamanho/idade, elimina os lotes mais antigos, exceto o lote em envio. Se necessário, descarta novos resultados para respeitar o orçamento. A idade também é verificada antes do replay, mesmo sem novos resultados. `queue_dropped_total` persiste após reinícios. Descartes por retenção não são recuperáveis.

Falhas de escrita mantêm o lote em formação para nova tentativa; os buffers em RAM continuam limitados e podem descartar resultados quando cheios. Resultados ainda em RAM não são recuperáveis após queda abrupta. Persistência local não protege contra perda do disco/máquina.

## Migração

O esquema adiciona colunas sem apagar resumos existentes. O consumidor drena o legado em páginas de 10.000 registros, com uma transação por página, antes da ingestão nova. O agendador também pode finalizar esse legado e continua avançando a referência temporal do painel; ele não precisa ler novos pings brutos.

`raw_cursor_id` controla exclusivamente o legado; `cursor_id` identifica revisões dos resumos. A limpeza usa o cursor bruto e nunca apaga dados legados pendentes. Recibos duram sete dias além da janela de aceitação (limitada a 48 horas), cobrindo a retenção máxima da fila. Resultados fora da janela são ignorados sem ressuscitar o histórico.

Lotes inválidos recebem rejeição terminal persistida em `collector_batch.rejection` antes do ACK, com log e contador `rejectedResults`. O lote inteiro é descartado, sem conservar payload bruto no app. Falhas temporárias de banco são repetidas.

Não misture gravadores de versões diferentes durante a atualização. O primeiro upgrade ainda parte de um coletor antigo com fila em memória; espere o escoamento antes de pará-lo quando possível. Preserve os volumes. Um retorno à versão antiga exige migração dos cursores; não basta substituir o binário.

## Acompanhamento

```sh
docker compose exec -T collector routiva-collector --health | jq '{
  coletor: .collector_id,
  lotes: .metrics.queue_batches,
  bytes: .metrics.queue_bytes,
  limite_bytes: .metrics.queue_max_bytes,
  idade_segundos: .metrics.queue_oldest_age_seconds,
  descartes_fila: .metrics.queue_dropped_total,
  persistencia_ok: .metrics.persistence_healthy,
  erros_gravacao: .metrics.storage_errors_total
}'
```

`pending` continua representando pings em andamento. `result_buffer` inclui o buffer do motor e lotes persistidos; `forming_results` informa a parte em formação. Contadores do motor reiniciam com o processo, mesmo que a identidade da fila permaneça igual. Descarte indica lacuna de observação, não perda de rede. A saúde do app fica DOWN em falha de persistência; o fluxo continua apto a escoar lotes já gravados.

## Validação em 08/11/2026

- Suíte Java: 150 testes, zero falhas/erros e dois ignorados, com H2, incluindo deduplicação, minutos originais, contribuições tardias, separação de erros, migração e preservação de rollups/alterações do painel.
- Testes Go com detector de concorrência, replay gRPC, confirmação da fila persistente, recuperação de processo encerrado sem Close, identidades estáveis, exclusão mútua, limites de bytes/idade e rollback de falha de escrita.
- Compilação cruzada para Linux amd64, CGO desativado.
- Teste sintético Java: 20.000 resultados de 10.000 hosts, em 40 lotes, aproximadamente 1,4–1,8 s no H2 local; nenhuma linha bruta gravada.
- Microbenchmark da gravação local: 20 lotes de 500 resultados por operação, 5 operações, aproximadamente 7,24 ms por operação no macOS ARM64. Não mede transporte, ICMP, PostgreSQL ou persistência em outro hardware.

A capacidade sustentada com 10.000 destinos reais, recuperação de atraso e comportamento sob falha física de disco ainda requerem ensaio no ambiente de implantação. O limite de hosts é uma restrição do inventário, não uma certificação de vazão.
