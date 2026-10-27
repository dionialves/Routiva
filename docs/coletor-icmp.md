# Coletor ICMP Java + Go

## Diagnóstico e decisão de integração

Routiva usa Java 21, Spring Boot 3.5.7, JDBC e PostgreSQL (H2 nos testes). `MonitorService` carrega hosts e grupos pelo `ZabbixClient`; não há cadastro local independente. Antes desta implementação, `tick()` criava uma tarefa Java e um subprocesso `ping` para cada tentativa. Um conjunto `running` impedia sobreposição por host, e o semáforo incluía espera pelo banco. O intervalo efetivo aumentava quando timeout superava a cadência.

`SampleStore` grava `ping_sample` e publica `PingSample` depois do commit. `PacketLossAnalysisService`, `RollingPingHistory`, `PacketLossAnalyzer` e `HostAvailabilityService` alimentam as APIs, cores, percentuais e gráficos existentes. Foram preservados esses consumidores e a publicação pós-commit. Nenhum arquivo de frontend foi alterado.

O novo fluxo é inventário Java → gRPC → heap de agendamento Go → fila de envio → socket ICMP → recepção independente → resultados → lotes limitados → stream gRPC → consumidor Java → transação JDBC → ACK. O Go não acessa o banco. `PingProbe` mantém somente o tipo de resultado legado e um adaptador que informa erro de coleta; não executa testes ICMP.

A proposta anterior em `arquitetura-coleta-assincrona.md`, já presente no workspace, não foi alterada. Sua sugestão de subprocessos Java foi substituída pelo pedido de coletor Go.

## Protocolo e identidade

Fonte única: `src/main/proto/collector.proto`, pacote `routiva.collector.v1`, versão de comando/lote 1. Java é gerado pelo Maven. Go gerado fica versionado em `collector/gen`. Cada inicialização cria `collector_id` aleatório; a tentativa tem contador de 64 bits iniciado em posição aleatória de 48 bits. A unicidade persistida é `(collector_id, attempt_id)`; não depende da sequência ICMP de 16 bits.

`Connect` é bidirecional. O primeiro comando deve conter configuração em até 10 segundos. Snapshots substituem atomicamente o inventário; upsert/remove permitem alterações individuais e `enabled=false` remove o agendamento. O backend envia snapshots após sincronização Zabbix e reconexão. IP inválido ou IPv6 não é coletado nesta etapa. Remoção para novos envios; tentativas já admitidas podem terminar com destino original. Alterações de IP preservam a separação dos históricos.

Intervalo permitido: 100 ms–1 hora; timeout: 100 ms–60 s. Padrões: 1000/1500 ms. Até 10.000 hosts configuráveis por instância. A configuração preserva a fase dos hosts inalterados. O limite de latência também acompanha a configuração e a tentativa, preservando a política em retransmissões após reinício do Java.

Os timestamps são Unix **nanoseconds**. `measured_at` existente recebe `scheduled_at / 1.000.000`, nunca o horário de persistência. `sent_at` e `received_at` ficam zerados quando não aplicáveis. O RTT é uma subtração de `time.Time` com relógio monotônico. A sequência lógica identifica lacunas; o payload usa marcador e token de 64 bits, validando também origem e sequência do Echo Reply. O kernel pode reescrever o ID ICMP de sockets datagram; ele não é usado como identidade de aplicação.

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
| `COLLECTOR_MAX_BATCHES` | 128 lotes não confirmados |
| Fila Java | 8 lotes, um consumidor |
| Prazo de ACK | 30 s |

O lote é enviado ao atingir tamanho ou prazo. Uma conexão Java por coletor, com um lote em trânsito aguardando confirmação. O ACK é posterior ao commit e à publicação das amostras. A recepção gRPC usa controle manual de fluxo; callbacks não executam SQL. Java repete falhas de persistência a cada segundo mantendo o mesmo lote. Reconexão usa espera exponencial com jitter de aproximadamente 500 ms até 30 s, seguida de snapshot do inventário. O servidor encerra a sessão sem ACK em 30 s; a persistência pendente pode terminar depois disso e a retransmissão será idempotente.

`collector_batch` guarda o recibo da transação. A escrita JDBC em lote, o recibo e as amostras são atômicos. Índice único de tentativa protege também reenvio em outro lote. Os eventos só são publicados após commit, mantendo IDs reais do banco. A política pressupõe uma instância Java gravadora por banco/coletor, como a aplicação anterior. Não é uma solução de coordenação distribuída entre réplicas.

Quando o Java/banco fica indisponível, o Go continua coletando. Filas cheias descartam **novos resultados** e aumentam `dropped_total`; lotes antigos não confirmados permanecem. Fila de envio ou pendências cheias geram `SKIPPED`. A indisponibilidade do banco não muda a cadência ICMP. Sem espaço para comunicar a própria falha, o contador de descarte permanece disponível na saúde.

**Não há spool persistente.** Queda/reinício do Go perde resultados ainda não confirmados. Java pode reiniciar e receber replay enquanto o Go permanece vivo. Reinício do Go gera nova identidade e requer ressincronização. Para sobreviver à queda dos dois processos com garantia de entrega, será necessário um diário local durável, limitado e testado; filas em memória não oferecem essa garantia.

SIGTERM/SIGINT interrompem o motor, finalizam pendências como erro de encerramento e permitem até cinco segundos para confirmar lotes. O restante é informado em log. Java tenta drenar sua fila por até dez segundos. Supervisores reiniciam os processos. Um lock local e a verificação do socket permitem remover sockets abandonados sem substituir um coletor ativo.

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

Saúde do processo e perda de rede são distintas. Uma conexão ativa não certifica capacidade de armazenamento: monitore também `writeFailures`, fila, descartes e saúde do datasource. Contadores Go reiniciam com a nova identidade. Tempo de ACK mede persistência e transporte juntos, não somente latência da rede.

## Persistência, retenção e capacidade não certificada

O esquema mantém tabelas e consultas existentes, com colunas opcionais e índices adicionais. Migração ocorre via `schema.sql`. Em banco grande, adicionar índices pode bloquear escritas; execute a atualização numa janela de manutenção e avalie criação concorrente manual com DBA antes da subida.

A 10.000 hosts/segundo são 864 milhões de registros/dia, até 1,728 bilhão em 48 horas. Somente os arrays de histórico de oito horas representam cerca de 7,2 GB (25 bytes × 288 milhões), fora capacidade excedente, objetos, cache e JVM. A 800 hosts são cerca de 576 MB. O processamento incremental mantém contagens e somas por janela e resumos em blocos compactos. A ocupação parcial dos blocos, seus resumos e a organização por host/IP acrescentam memória à estimativa dos dados brutos; a capacidade real ainda depende de ensaios. Esses limites não desaparecem com a troca do transporte.

**O sistema completo não tem suporte comprovado a 10.000 hosts.** O motor e protocolo têm limites explícitos e foram ensaiados com essa quantidade simulada; PostgreSQL real, análise Java, rede e estabilidade prolongada não foram certificados. Não foi introduzida agregação que alterasse as regras históricas. Antes de expandir, medir ingestão sustentada, WAL, índices, vacuum, limpeza e heap; definir particionamento/retenção curta de amostras e agregações compatíveis com os cálculos. O `DELETE` periódico existente pode ser caro nesse volume. Recibos expiram junto à retenção; resultados antigos não ressuscitam amostras já expiradas.

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
