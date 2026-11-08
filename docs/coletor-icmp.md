# Coletor ICMP Java + Go

## Diagnóstico e decisão de integração

Routiva usa Java 21, Spring Boot 3.5.7, JDBC e PostgreSQL (H2 nos testes). `ZabbixService` carrega hosts e grupos pelo `ZabbixClient`; não há cadastro local independente. Antes desta implementação, `tick()` criava uma tarefa Java e um subprocesso `ping` para cada tentativa. Um conjunto `running` impedia sobreposição por host, e o semáforo incluía espera pelo banco. O intervalo efetivo aumentava quando timeout superava a cadência.

O app recebe os lotes e grava pings brutos em `ping_sample` junto ao recibo da entrega. O ACK depende somente desse commit. A agregação agendada a cada 60 segundos lê os pings em páginas e atualiza `ping_minute`, resumos de hora/dia e alterações incrementais do painel. Os gráficos continuam consultando os resumos.

O novo fluxo é inventário Java → gRPC → heap de agendamento Go → fila de envio → socket ICMP → recepção independente → resultados → lotes limitados → stream gRPC → consumidor Java → transação JDBC → ACK. O Go usa SQLite local para a fila de entrega; não acessa o PostgreSQL do app. `PingProbe` mantém somente o tipo de resultado legado e um adaptador que informa erro de coleta; não executa testes ICMP.

A proposta anterior em `arquitetura-coleta-assincrona.md`, já presente no workspace, não foi alterada. Sua sugestão de subprocessos Java foi substituída pelo pedido de coletor Go.

## Protocolo e identidade

Fonte única: `src/main/proto/collector.proto`, pacote `routiva.collector.v1`, versão 1. Java é gerado pelo Maven e Go fica versionado em `collector/gen`. O `collector_id` é criado na primeira abertura da fila SQLite e preservado em reinícios. O ID do lote usa AUTOINCREMENT persistente, sem reutilização após ACK. A tentativa mantém contador aleatório por execução; a deduplicação da ingestão é por `(collector_id, batch_id)`.

`Connect` é bidirecional. O primeiro comando deve conter configuração em até 10 segundos. Snapshots substituem atomicamente o inventário; upsert/remove permitem alterações individuais e `enabled=false` remove o agendamento. O backend envia snapshots após sincronização Zabbix e reconexão. IP inválido ou IPv6 não é coletado nesta etapa. Remoção para novos envios; tentativas já admitidas podem terminar com destino original. Alterações de IP preservam a separação dos históricos.

Intervalo permitido: 100 ms–1 hora; timeout: 100 ms–60 s. Padrões: 1000/1500 ms. Até 10.000 hosts configuráveis por instância. A configuração preserva a fase dos hosts inalterados. O limite de latência também acompanha a configuração e a tentativa, preservando a política em retransmissões após reinício do Java.

Os timestamps são Unix **nanoseconds**. O minuto da agregação usa `scheduled_at / 1.000.000`, nunca o horário de chegada. `sent_at` e `received_at` ficam zerados quando não aplicáveis. O RTT é uma subtração de `time.Time` com relógio monotônico. A sequência lógica identifica lacunas; o payload usa marcador e token de 64 bits, validando também origem e sequência do Echo Reply. O kernel pode reescrever o ID ICMP de sockets datagram; ele não é usado como identidade de aplicação.

O agendador central usa heaps de hosts e prazos, sem goroutine por host/tentativa. Há envio e recepção em workers fixos. Um host pode ter várias tentativas pendentes. Conclusão remove imediatamente o prazo do heap. As fases são distribuídas por hash do host. Ao atrasar um ou mais intervalos, registra `SKIPPED` resumindo o número de slots em `SCHEDULER_LATE:N`, avança a sequência e envia somente a tentativa atual, sem rajada de recuperação.

`SUCCESS`, `TIMEOUT` e `ICMP_ERROR` alimentam o denominador da perda; os dois últimos são perdas de rede. `SEND_ERROR`, `COLLECTION_ERROR` e `SKIPPED` são erros internos, não perda. Erros ICMP com payload citado suficiente são correlacionados e registrados como `ICMP_ERROR` (por exemplo destino inalcançável); citações mínimas sem token são ignoradas, nunca correlacionadas por sequência curta. Sem Echo Reply válido, a tentativa pode expirar. Falha de leitura do socket invalida as pendências como erro de coleta, evitando declarar perda durante falha local de recepção. Respostas duplicadas, origem errada e respostas após o prazo são ignoradas.

## Filas, backpressure e entrega

| Controle | Padrão |
|---|---:|
| Fila de envio | 1.024 tentativas |
| Pendências | 100.000 |
| Eventos internos | 32.768 |
| `COLLECTOR_RESULT_BUFFER` | 32.768 resultados |
| `COLLECTOR_BATCH_SIZE` | 500 (máximo 5.000) |
| `COLLECTOR_FLUSH_MS` | 100 ms |
| `COLLECTOR_SPOOL_MAX_MIB` | 256 MiB de payloads pendentes |
| `COLLECTOR_SPOOL_MAX_AGE_HOURS` | 24 horas (máximo 48) |
| `COLLECTOR_SPOOL_PATH` | `/var/lib/routiva/queue.db` |
| Fila Java | 8 lotes, um consumidor |
| Prazo de ACK | 30 s |

O lote é enviado ao atingir tamanho ou prazo. Uma conexão Java por coletor, com um lote em trânsito aguardando confirmação. O ACK é posterior ao commit dos pings brutos e do recibo de recebimento, independentemente da análise. A recepção gRPC usa controle manual de fluxo; callbacks não executam SQL. Java repete falhas de persistência a cada segundo mantendo o mesmo lote. Reconexão usa espera exponencial com jitter de aproximadamente 500 ms até 30 s, seguida de snapshot do inventário. O servidor encerra a sessão sem ACK em 30 s; a persistência pendente pode terminar depois disso e a retransmissão será idempotente.

`collector_batch` guarda o recibo na mesma transação dos pings individuais. A análise posterior atualiza os resumos, rollups e alterações do painel em outra transação. Reenvios do mesmo lote não somam novamente. Recibos são retidos por sete dias além da janela de aceitação de resultados (até 48 horas). Lotes permanentemente inválidos são rejeitados integralmente: o motivo é persistido no recibo e registrado em log antes do ACK; não bloqueiam os lotes seguintes. Falhas temporárias de banco continuam sendo repetidas.

Quando Java/banco fica indisponível, o Go continua coletando e persistindo lotes localmente. Ao atingir o orçamento de payload ou idade, descarta os lotes mais antigos, protegendo o lote em trânsito; se ele ocupar todo o orçamento, pode descartar o lote novo. Descartes são contabilizados, não convertidos em perda de rede. Se o disco falhar, mantém o lote em formação para repetição e limita o consumo de RAM; o buffer do motor pode descartar resultados novos. `persistence_healthy=0` sinaliza a falha sem impedir o envio dos lotes já persistidos.

**A fila usa SQLite WAL com `synchronous=FULL`.** Cada lote é confirmado localmente antes de ficar disponível ao gRPC e removido somente após ACK. Um lock de arquivo impede dois processos de usar a mesma fila. O volume `collector_data` mantém a fila ao recriar o contêiner; systemd usa `StateDirectory=routiva`. A recuperação exige preservar esse diretório e seus arquivos SQLite/WAL. O limite em MiB é de payload: há espaço adicional para páginas, índices e WAL; o arquivo principal tem teto de aproximadamente duas vezes o orçamento mais 4 MiB, e páginas liberadas são reutilizadas. Isso não protege contra perda física do disco. Resultados ainda no motor ou em formação não têm garantia de recuperação após queda abrupta.

SIGTERM/SIGINT interrompem o motor e permitem até cinco segundos para confirmar lotes. O fechamento persiste o lote em formação e drena o buffer disponível; lotes sem ACK ficam no SQLite para a próxima execução. Falhas de disco no fechamento são registradas explicitamente. Java tenta drenar sua fila por até dez segundos. Após reiniciar, o Go precisa receber novamente o inventário do app para retomar os pings, mas os lotes existentes continuam disponíveis para replay.

## Compilação e Linux

Requisitos: Go 1.24+, Java 21, Maven e PostgreSQL. Compilação normal usa os arquivos Go gerados já versionados:

```sh
mvn -B verify -Dcollector.enabled=false
cd collector
go test -race ./...
go build -trimpath -o routiva-collector ./cmd/collector
```

Para regenerar o protocolo (após editar o `.proto`), instale `protoc` 3.25.6 e os geradores fixados:

```sh
go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.6
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1
# Na raiz do repositório; GOPATH/bin deve estar no PATH:
protoc -I src/main/proto --go_out=collector --go_opt=module=routiva/collector \
  --go-grpc_out=collector --go-grpc_opt=module=routiva/collector src/main/proto/collector.proto
mvn generate-sources
```

O transporte usa `SOCK_DGRAM/IPPROTO_ICMP` via `icmp.ListenPacket("udp4", ...)`. Configure o GID efetivo do usuário do coletor em `net.ipv4.ping_group_range`. Exemplo de instalação com UID/GID 10001, ajustando se já existirem:

```sh
sudo groupadd -g 10001 routiva
sudo useradd -u 10001 -g routiva -m routiva
sudo install -m 0755 collector/routiva-collector /usr/local/bin/routiva-collector
sudo install -m 0644 deploy/routiva-collector.service /etc/systemd/system/
echo 'net.ipv4.ping_group_range = 10001 10001' | sudo tee /etc/sysctl.d/90-routiva.conf
sudo sysctl --system
sudo systemctl daemon-reload
sudo systemctl enable --now routiva-collector
sudo -u routiva /usr/local/bin/routiva-collector --health
```

O usuário Java precisa pertencer ao grupo do socket. O diretório `/run/routiva` é criado por systemd (0750), e o socket recebe 0660. Não é necessário `CAP_NET_RAW` nesse modo. Não há fallback silencioso para raw socket ou comando externo; erro de permissão impede a inicialização e informa a configuração necessária. O suporte real a ping sockets deve ser verificado no kernel/namespaces usados.

Java usa `COLLECTOR_ADDRESS=unix:///run/routiva/collector.sock`, com Netty epoll nativo para Linux x86_64/aarch64. Execução local via UDS está direcionada a Linux. Em desenvolvimento, `COLLECTOR_EXECUTABLE=/caminho/absoluto/routiva-collector` permite iniciar **um** processo pelo Java. `COLLECTOR_LISTEN` precisa apontar para o mesmo socket; o diretório deve existir e ter acesso permitido. Em produção use supervisão independente.

## Docker

```sh
docker compose up -d --build --wait
docker compose exec collector routiva-collector --health
docker compose logs --tail=100 collector app
```

Compose cria três serviços, compartilha somente o volume do socket entre Go/Java e aplica `ping_group_range` ao Go. O coletor usa UID/GID 10001 e remove capacidades Linux. Nenhuma porta gRPC é publicada. O banco mantém o volume anterior. O coletor precisa de rota para os destinos; NAT, firewall, limites ICMP e políticas da rede podem restringir o resultado real. Docker não foi executado neste ambiente macOS.

## Coletores remotos

O servidor aceita `COLLECTOR_LISTEN=tcp://ENDERECO:50051` somente com mTLS, exigindo `COLLECTOR_TLS_CERT`, `COLLECTOR_TLS_KEY` e `COLLECTOR_TLS_CA`. Java usa `COLLECTOR_ADDRESS=https://nome-do-coletor:50051` e suas próprias credenciais nas variáveis correspondentes. O certificado do servidor deve conter o hostname no SAN; Java valida CA/hostname e Go exige certificado de cliente válido. Não há modo TCP plaintext em produção. A CLI `--health` é para UDS; em modo remoto use o cliente Java/RPC de saúde. Os relógios de parede devem ser sincronizados; o Java recusa timestamps de mais de 60 segundos no futuro. O protocolo permite coletores remotos, mas atribuição de hosts a vários coletores não foi adicionada.

## Saúde e métricas

`Health` informa versão e identidade, hosts ativos, enviados/respondidos/timeouts/erros/skips cumulativos, pendências, atraso recente/máximo de agendamento, latência local de processamento, CPU acumulada, heap, goroutines, resultados em buffer, tempo de ACK (inclui commit), conexões e descartes. Taxas por segundo são deltas dos contadores divididos pelo tempo entre consultas. O Java consulta a cada segundo e integra `/actuator/health` com fila, gravações e falhas. Para visualizar detalhes em ambiente controlado, use `management.endpoint.health.show-details=always`; o padrão não expõe esses detalhes publicamente. Logs gerais do Go na saída do processo são JSON. O arquivo `/var/log/routiva/collector.log` registra eventos de lotes em texto estruturado, em inglês, sem pings individuais. Possui sequência por execução, rotação e volume persistente no Compose. Consulte [Arquivo de log dos lotes](../README.md#arquivo-de-log-dos-lotes) para comandos, configuração e limitações.

Saúde do processo e perda de rede são distintas. Uma conexão ativa não certifica capacidade de armazenamento: monitore também `writeFailures`, fila, descartes e saúde do datasource. Contadores do motor reiniciam a cada execução; `queue_dropped_total` persiste com a fila, mesmo mantendo a identidade. Tempo de ACK mede persistência e transporte juntos, não somente latência da rede.

## Persistência, retenção e capacidade não certificada

O esquema mantém tabelas e consultas existentes, com colunas opcionais e índices adicionais. Migração ocorre via `schema.sql`. Em banco grande, adicionar índices pode bloquear escritas; execute a atualização numa janela de manutenção e avalie criação concorrente manual com DBA antes da subida.

A 10.000 hosts e um ping por segundo são 864 milhões de resultados/dia. A gravação bruta voltou ao PostgreSQL, com retenção de até 48 horas e limpeza incremental limitada. Isso exige dimensionar disco, índices, WAL e vacuum; o limite de 10.000 hosts não é certificação de vazão sustentada.

A transição preserva os resumos e recibos já gravados pela agregação direta. Lotes com recibo existente continuam sendo reconhecidos, sem recriar pings nem duplicar resumos. Lotes pendentes no SQLite passam a ser gravados como dados brutos e analisados no ciclo agendado. Os cursores bruto e de revisão continuam separados, sem reset ou reconstrução destrutiva do histórico. Não misture gravadores de versões diferentes.

O SQLite continua temporário: os 256 MiB não garantem 24 horas de coleta no limite de hosts. O primeiro limite atingido determina o descarte. O inventário não é persistido; após reiniciar, o coletor aguarda a configuração do app para voltar a pingar.

Veja [operação e validação da fila persistente](fila-persistente.md).

## Validação e roteiro de aceite

Executado neste ambiente macOS ARM64 em 22/10/2026:

- Suíte Java: 64 testes passando, incluindo painel, análises, idempotência, horários originais, rollback e confirmação somente após repetição de uma falha de gravação.
- Go: testes determinísticos de sobreposição, timeout, ordem de respostas, duplicata, origem errada, sequência além de 16 bits, alteração de inventário, atraso e limites de buffer. Integração gRPC em memória valida replay após desconexão, ACK e descarte limitado sem consumidor. Detector de races habilitado.
- Build cruzado Linux amd64, sem afirmar execução real nesse sistema.
- Carga simulada: `go run ./cmd/load`, 10 segundos por faixa. Resultados detalhados em `collector-load.jsonl`. Cinco goroutines do harness/motor, sem gRPC, Java, banco ou pacotes de rede nesse ensaio.

| Hosts | Resultados/s | CPU (% de um núcleo) | Heap aproximado | Maior atraso | Skips/descartes |
|---:|---:|---:|---:|---:|---:|
| 800 | 800 | 3,20% | 7,7 MB | 11,44 ms | 0 / 0 |
| 2.000 | 2.000 | 4,94% | 8,8 MB | 11,76 ms | 0 / 0 |
| 5.000 | 4.999,7 | 7,40% | 7,0 MB | 13,61 ms | 0 / 0 |
| 10.000 | 10.002,2 | 11,93% | 10,9 MB | 13,09 ms | 0 / 0 |

Pequena diferença na taxa vem das bordas de início/fim da medição. O ensaio curto com respostas imediatas **não mede capacidade ICMP real nem estabilidade de longa duração**. Microbenchmarks executados estão registrados em `collector-microbench.txt` (cada operação representa 1 ms de relógio simulado, não uma tentativa). Os microbenchmarks determinísticos estão disponíveis em `go test -run '^$' -bench BenchmarkEngine -benchmem ./internal/engine`.

Em Linux, execute o teste real opt-in:

```sh
cd collector
ROUTIVA_REAL_ICMP=1 go test -run TestLinuxPingSocketLoopback -v ./internal/engine
```

Para aceite operacional, repetir 800/2000/5000/10000 destinos reais controlados por pelo menos uma hora, depois soak de 24–48 h. Incluir destinos sem resposta/perda intermitente, remover/adicionar hosts, matar/reiniciar Go e Java separadamente, interromper UDS e parar/limitar PostgreSQL. Registrar contadores, captura de pacotes autorizada, CPU/RSS, heap Java, atraso máximo/p95/p99, vazão SQL, descartes e disco. Confirmar que falha interna não entra no denominador e que duplicatas não alteram o histórico. Esses ensaios reais permanecem necessários antes de implantação com alta escala.
