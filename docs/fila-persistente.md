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

## Caminho SQLite e diagnóstico

O coletor mantém WAL, `synchronous=FULL`, sincronização antes do envio e remoção somente após ACK válido. O formato da fila não muda; não é necessário apagar ou recriar `collector_data`.

As operações SQLite não seguram mais o mutex geral do servidor. A fila reserva atomicamente os IDs selecionados antes de disponibilizá-los ao envio, protegendo-os contra retenção enquanto o produtor continua gravando. A reserva é liberada ao encerrar o stream. O SQLite continua com uma conexão e escrita serializada internamente: a melhoria não transforma o banco em múltiplos escritores paralelos.

O health e os logs de estado consultam um snapshot em memória, atualizado somente após commits bem-sucedidos. Eles não executam SQL nem esperam pelo mutex da fila. Durante uma escrita lenta, mostram o último estado confirmado, sem expor alterações ainda em transação. Alterações externas no arquivo SQLite durante a execução não são suportadas.

A recepção reúne ACKs por até 2 ms, ou até completar a janela pendente, sem segurar bloqueios nessa espera. A exclusão usa um único `DELETE ... IN (...)` e um único commit para o grupo de até 32 IDs. A reunião é limitada por tempo, portanto um commit do app não garante sempre um único grupo de exclusão no coletor; os contadores abaixo mostram o resultado real.

A leitura normal passa a buscar a próxima linha e seu horário em uma consulta, sem executar `MIN(created)` separadamente a cada lote. A limpeza de expirados durante a leitura remove no máximo 128 lotes por transação e continua nas próximas tentativas; nunca envia um lote expirado. As políticas de limite de armazenamento e proteção dos lotes em trânsito continuam aplicadas.

### Métricas do SQLite

`--health` expõe os prefixos `sqlite_append`, `sqlite_read` e `sqlite_ack`:

| Sufixo | Significado |
|---|---|
| `_operations_total`, `_errors_total` | Operações concluídas e falhas desde o início do processo |
| `_wait_ms` | Última espera para obter o mutex interno da fila |
| `_operation_ms` | Tempo após obter o mutex, incluindo SQL e commit; exclui a espera |
| `_commit_ms` | Tempo do último commit; leitura normal não faz commit |
| `_wait_ms_total`, `_operation_ms_total`, `_commit_ms_total` | Tempos acumulados para comparação entre leituras |
| `_max_wait_ms`, `_max_operation_ms` | Piores tempos observados nesta execução |

`sqlite_ack_batches_total` conta os lotes efetivamente removidos após commits bem-sucedidos. `sqlite_ack_last_batches` informa quantos foram removidos no último grupo bem-sucedido. Esses contadores de desempenho reiniciam com o processo; o contador de descarte da fila continua persistido.

```sh
docker compose exec -T collector routiva-collector --health | jq '{
  lotes_pendentes: .metrics.queue_batches,
  idade_fila_segundos: .metrics.queue_oldest_age_seconds,
  gravacao_espera_ms: .metrics.sqlite_append_wait_ms,
  gravacao_operacao_ms: .metrics.sqlite_append_operation_ms,
  gravacao_commit_ms: .metrics.sqlite_append_commit_ms,
  leitura_espera_ms: .metrics.sqlite_read_wait_ms,
  leitura_operacao_ms: .metrics.sqlite_read_operation_ms,
  exclusao_espera_ms: .metrics.sqlite_ack_wait_ms,
  exclusao_operacao_ms: .metrics.sqlite_ack_operation_ms,
  exclusao_commit_ms: .metrics.sqlite_ack_commit_ms,
  lotes_no_ultimo_grupo_ack: .metrics.sqlite_ack_last_batches
}'
```

O evento `SQLITE_STATUS` registra essas métricas a cada dez segundos em `/var/log/routiva/collector.log`. Ele pode atrasar se o próprio produtor estiver esperando uma escrita; o RPC de health permanece independente. Uma espera alta indica disputa interna; operação/commit altos indicam trabalho de banco ou armazenamento e precisam ser correlacionados com I/O do servidor. Valores isolados não são médias.

### Validação da melhoria

Foram testados rollback sem publicar contadores, reservas durante retenção concorrente, descarte de expirados em lotes limitados e health durante uma inserção bloqueada dentro do SQLite. A suíte Go usa detector de concorrência; há compilação cruzada para Linux amd64 sem CGO.

No ensaio local macOS ARM64, com fila de 50.000 lotes, a mediana da exclusão de 16 IDs caiu de aproximadamente 0,44 ms para 0,27 ms. A consulta de estado deixou de executar SQL. O ensaio não reproduz o disco Linux de produção nem a disputa com PostgreSQL; não é uma certificação de vazão ou de tempo de fsync nesse servidor.

Para instalar apenas esta melhoria, depois de atualizar o código no servidor:

```sh
docker compose up -d --build --no-deps collector
```

Mantenha o app em execução para reenviar o inventário após a reconexão. Preserve o volume `collector_data`.

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

O pacote `cleanup` agenda uma rodada a cada hora cheia. O planejador lê o cursor bruto confirmado e identifica os IDs elegíveis em páginas pelo índice de data e ID. A fila em memória executa os pacotes em sequência em uma thread dedicada. Cada exclusão confirma sua própria transação. A descoberta busca a próxima página após o commit e reduz pela metade a janela de tempo quando a consulta excede cinco segundos. O cursor e o corte de retenção ficam fixos durante toda a rodada, inclusive nas tentativas após timeout. Pings agregados depois desse cursor entram na próxima rodada horária.

| Variável | Padrão | Efeito |
|---|---|---|
| PING_KEEP_RAW | no | Com no, todos os pings já agregados são elegíveis. Com yes, aplica o prazo desde a medição. |
| PING_RAW_RETENTION_MINUTES | 2880 | Permanência em minutos quando a retenção está ativa. |
| PING_CLEANUP_ENABLED | true | Habilita o agendamento. |
| PING_CLEANUP_BATCH_SIZE | 1000 | Máximo de registros por exclusão, de 1 a 10.000. |

Após interrupção ou falha, a próxima rodada redescobre o trabalho pendente no banco. Os recibos de ingestão expiram após nove dias para proteger o replay. Os resumos por minuto permanecem disponíveis.
