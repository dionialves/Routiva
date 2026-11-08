# Fila persistente e agregação agendada

O limite permanece em **10.000 hosts ativos por coletor**. O intervalo dos pings continua configurado por host (padrão 1 s). Os 100 ms fecham lotes incompletos, sem limitar o envio a um lote por intervalo.

## Entrega

1. O Go agrupa até 500 resultados, ou fecha o lote após 100 ms.
2. Confirma a gravação no SQLite local (WAL, synchronous=FULL).
3. Negocia uma janela limitada e envia até 16 lotes sem aguardar cada ACK individual.
4. Java reúne até 16 lotes e 5.000 resultados, esperando no máximo 100 ms para formar o grupo. Grava os brutos em `ping_sample` e os recibos em uma transação.
5. Após o commit, Java envia um ACK para cada lote original. Go valida a ordem e apaga apenas os lotes confirmados; ACKs disponíveis juntos são removidos em um commit SQLite.
6. Independentemente da entrega, a cada 60 segundos o agregador lê os novos pings brutos em páginas e atualiza os resumos de minuto, rollups de hora/dia e alterações do painel.

Reenvios preservam a identidade e não duplicam pings nem contagens. O ACK não espera a análise, não consulta resumos e não exige o bloqueio de progresso. A fila local protege resultados ainda não recebidos; o PostgreSQL protege resultados recebidos ainda não analisados.

`INDICATORS_AGGREGATION_INTERVAL_MS=60000` define a cadência padrão e `INDICATORS_AGGREGATION_PAGE_SIZE=10000` limita cada página transacional. As páginas do mesmo ciclo são processadas consecutivamente até alcançar o limite capturado. `MINUTE_BATCH_COMMITTED` e `MINUTE_AGGREGATION_COMPLETED` registram avanço e conclusão em `indicators.log`.

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

Ao atingir tamanho/idade, elimina os lotes mais antigos, exceto os lotes em envio. Se necessário, descarta novos resultados para respeitar o orçamento. A idade também é verificada antes do replay, mesmo sem novos resultados. `queue_dropped_total` persiste após reinícios. Descartes por retenção não são recuperáveis.

Falhas de escrita mantêm o lote em formação para nova tentativa; os buffers em RAM continuam limitados e podem descartar resultados quando cheios. Resultados ainda em RAM não são recuperáveis após queda abrupta. Persistência local não protege contra perda do disco/máquina.

## Janela e gravação de grupos

| Variável do app | Padrão | Limites |
|---|---:|---|
| COLLECTOR_SEND_WINDOW | 16 | 1..32 lotes por stream |
| COLLECTOR_PERSISTENCE_MAX_RESULTS | 5000 | 5000..20000 resultados por transação |
| COLLECTOR_PERSISTENCE_WAIT_MS | 100 | 0..1000 ms para reunir o grupo |

A janela usa metadados gRPC `x-routiva-window`, mantendo o protocolo e os arquivos antigos da fila. Clientes antigos que não enviam esse metadado recebem somente um lote por vez. Um app novo também funciona com coletor antigo, mas nesse caso o agrupamento normalmente terá apenas um lote e o ganho depende de atualizar o coletor.

Os limites de quantidade de lotes, resultados e tempo são aplicados juntos. A fila de recepção Java tem capacidade fixa de 64 entregas, e o controle manual gRPC libera novos créditos somente após o commit. Um lote que ultrapassaria o orçamento do grupo fica reservado para o próximo grupo. SQL nunca é executado no callback de recepção.

Todos os lotes enviados ficam protegidos contra descarte por retenção até receber ACK ou encerrar a conexão. Uma falha de banco repete o mesmo grupo, sem ACK. Se o commit concluir e a conexão cair antes das confirmações, os recibos permitem reconhecer o replay sem duplicar resultados. ACKs fora da ordem ou de outra identidade encerram a sessão preservando os pendentes. Lotes com resultados inválidos são rejeitados integralmente no mesmo commit dos demais; os lotes válidos seguem normalmente.

A melhoria também atende aos lotes antigos já acumulados, sem reempacotar ou trocar suas identidades. A agregação permanece a cada 60 segundos. Nenhuma configuração de durabilidade do PostgreSQL é desligada.

### Medições

`RAW_GROUP_COMMITTED` em `indicators.log` é emitido depois do commit e inclui:

- `batches`, `results`, `duplicate_batches`, `rejected_results`.
- `queue_wait_ms`: da primeira entrega até iniciar a tentativa de gravação, incluindo formação do grupo e eventuais repetições.
- `group_wait_ms`: tempo de formação do grupo.
- `lock_ms`: espera pelo bloqueio curto de ingestão.
- `prepare_ms`: validação e consulta dos recibos.
- `insert_ms`: inserção bruta e preparação dos recibos.
- `commit_ms`: finalização da transação, incluindo flush dos recibos e commit.
- `database_ms`: chamada transacional completa, incluindo aquisição da conexão e commit.

O health detalhado do app apresenta `committedGroups`, `lastGroupBatches`, `lastDatabaseMs`, `lastCommitMs`, `lastQueueWaitMs`, `lastLockMs` e `lastInsertMs`. São métricas da última operação, não médias. No coletor, `send_window` informa a janela negociada e `in_flight_batches` os lotes aguardando confirmação.

Para acompanhar recuperação, compare a idade do lote mais antigo em várias leituras e o avanço dos pings no banco. O ACK pode incluir a espera para agrupar e não precisa diminuir para a vazão melhorar. Grupos com vários lotes reduzem o número de commits, mas não garantem capacidade de produção por si só.

### Atualização

Atualize o app primeiro, mantendo o coletor em execução:

```sh
docker compose up -d --build --no-deps app
```

Aguarde o app concluir a inicialização e voltar a conectar. Depois atualize o coletor, preservando `collector_data`:

```sh
docker compose up -d --build --no-deps collector
```

Isso evita reiniciar o coletor antes de uma inicialização longa do app. O inventário ainda precisa ser reenviado após o reinício do Go; a fila persistida permanece disponível. A ativação da janela pode ser confirmada por `WINDOW_NEGOTIATED window=16` e pelo health. Configurar `COLLECTOR_SEND_WINDOW=1` no app permite voltar ao envio sequencial, mantendo os formatos existentes.

## Transição da agregação direta

A restauração da persistência bruta é uma mudança nova, sem reverter os commits do SQLite ou da limpeza incremental. Não exige apagar tabelas, resumos ou arquivos da fila.

Resumos, rollups e recibos anteriores são preservados. Um lote já confirmado na versão de agregação direta continua sendo reconhecido pelo recibo e não é inserido novamente como bruto. Lotes sem recibo são gravados em `ping_sample` e analisados no ciclo agendado. Os pings da etapa direta não podem ser reconstruídos individualmente a partir de resumos, mas seus indicadores permanecem disponíveis.

`raw_cursor_id` continua controlando quais IDs brutos foram processados, enquanto `cursor_id` permanece como revisão monotônica do painel. A agregação não usa a revisão para filtrar IDs brutos: eles podem estar abaixo dela após a transição. O estado pendente e o escoamento das páginas também respeitam essa separação.

Recibos duram sete dias além da janela de aceitação (limitada a 48 horas). Resultados expirados são ignorados sem ressuscitar o histórico. Lotes inválidos recebem rejeição terminal persistida em `collector_batch.rejection` antes do ACK, com log e contador `rejectedResults`. Falhas temporárias de banco continuam sendo repetidas.

Atualize somente o app, preservando o coletor em execução e todos os volumes:

```sh
docker compose up -d --build --no-deps app
```

Não misture gravadores de versões diferentes. Os testes verificam a transição com revisão adiantada, IDs brutos menores, reenvios de lotes já confirmados e ausência de bloqueio da ingestão enquanto o progresso está ocupado.

## Limpeza incremental dos dados brutos

A limpeza dos pings brutos lê o cursor confirmado sem `FOR UPDATE`. Ela não bloqueia `ping_aggregation_progress` nem `ping_ingestion_guard`. Cada lote usa uma transação independente com prazo de cinco segundos e apaga somente registros expirados cujo ID já foi agregado. Os resumos não são apagados.

| Variável | Padrão | Efeito |
|---|---:|---|
| PING_CLEANUP_ENABLED | true | Habilita a limpeza agendada; false permite suspendê-la |
| PING_CLEANUP_INTERVAL_SECONDS | 600 | Espera após o fim de cada ciclo |
| PING_CLEANUP_BATCH_SIZE | 1000 | Máximo por tabela e transação; de 1 a 10.000 |
| PING_CLEANUP_MAX_BATCHES | 10 | Máximo de transações por ciclo; de 1 a 100 |

O padrão remove até 10.000 pings brutos e até 10.000 recibos por ciclo, com commit entre lotes. O agendamento tem uma thread própria. Falha ou timeout interrompe o ciclo, preservando os lotes já confirmados e repetindo o restante no próximo ciclo. Recibos continuam respeitando sete dias além da janela de aceitação. Um índice por data e identidade evita varrer todos os recibos para selecionar o lote.

Em bases grandes, a remoção é gradual. Aumentar limites pode aumentar a disputa de disco com a ingestão; ajuste usando as medições do servidor. Alterações no `.env` exigem recriar o serviço app. A nova configuração só é reconhecida após instalar a versão corrigida.

`RETENTION_BATCH_COMMITTED` registra `page`, `raw_deleted`, `receipts_deleted`, `raw_cursor` e `elapsed_ms` depois do commit. `RETENTION_BATCH_FAILED` indica repetição no próximo ciclo. Consulte `/var/log/routiva/indicators.log` no serviço app (localmente, `logs/indicators.log`).

O problema anterior era um DELETE sem limite na mesma transação que segurava o progresso; mesmo com todo o legado agregado, ele impedia novos lotes de obter ACK. Os testes de regressão mantêm esse registro bloqueado em outra conexão e verificam que a limpeza termina, preserva os resumos e respeita seus limites, incluindo execução em PostgreSQL 17.

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

## Validação da restauração

Testes cobrem gravação bruta antes da análise, deduplicação por lote e tentativa, horários originais, rollback de dados e recibo, correção de minutos atrasados e preservação de resumos existentes. Um teste mantém o progresso bloqueado em outra conexão e confirma que a ingestão bruta conclui sem esperar. A suíte de agregação verifica IDs brutos abaixo da revisão herdada da versão direta e o processamento de todas as páginas.

A validação inclui H2 e PostgreSQL 17 isolado. O teste sintético separa o tempo de persistência de 20.000 resultados de 10.000 hosts do tempo total com agregação. Esses ensaios não certificam ICMP real, recuperação sustentada nem vazão no banco de produção. O comportamento da fila Go permanece o mesmo, incluindo recuperação após reinícios, limites, exclusão mútua e ACK após persistência.
