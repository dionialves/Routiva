# ScriptPing

Monitor de latência com Java 21, Spring Boot, Thymeleaf, HTMX e PostgreSQL em Docker.

## Executar

Pré-requisitos: JDK 21, Maven 3.6.3+, Docker com Compose e o coletor Go descrito em [Operação do coletor](docs/coletor-icmp.md). Você pode executar Spring e PostgreSQL em contêineres separados (veja abaixo), ou rodar Java localmente com apenas o banco em Docker.

1. Crie sua configuração:

```sh
cp .env.example .env
```

2. Edite `.env`:

```dotenv
ZABBIX_URL=https://seu-zabbix/api_jsonrpc.php
ZABBIX_USERNAME=seu-usuario
ZABBIX_PASSWORD='sua-senha-zabbix'
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=scriptping
POSTGRES_USER=scriptping
POSTGRES_PASSWORD='sua-senha-postgres'
```

Use valores literais em uma linha. Aspas simples permitem senhas com espaços, `#` e `$`; não coloque apóstrofos dentro dessas aspas. Não use interpolação de outras variáveis dentro do `.env`. O arquivo é carregado automaticamente do diretório de execução, tanto com Maven quanto com o JAR. Variáveis já definidas no ambiente têm prioridade. `.env` está ignorado pelo Git; apenas `.env.example` é versionado.

3. Com Docker em execução, inicie o banco e depois a aplicação:

```sh
docker compose up -d --wait postgres
# Inicie o coletor local em Linux conforme docs/coletor-icmp.md.
mvn spring-boot:run
```

Abra http://localhost:8080. As tabelas e índices são criados automaticamente no PostgreSQL. Sem credenciais do Zabbix, a aplicação abre o painel vazio com instruções, desde que o banco esteja disponível.

Para gerar e executar o JAR, na pasta que contém `.env`:

```sh
mvn clean package
java -jar target/scriptping-1.0.0.jar
```

## Servidor: Spring e PostgreSQL em contêineres separados

Pré-requisitos no servidor: Git, [Docker Engine](https://docs.docker.com/engine/install/) e Docker Compose v2 ou superior. Não é necessário instalar Java ou Maven no servidor; o Docker compila e testa o projeto em uma etapa de build e gera a imagem final com Java 21, além da imagem independente do coletor Go.

```sh
git clone https://github.com/dionialves/Routiva.git
cd Routiva
cp .env.example .env
nano .env
```

Preencha `ZABBIX_URL`, `ZABBIX_USERNAME`, `ZABBIX_PASSWORD` e `POSTGRES_PASSWORD`. As credenciais são lidas do `.env` em tempo de execução e não entram na imagem. Para acesso direto pela rede:

```dotenv
APP_BIND_ADDRESS=0.0.0.0
APP_PORT=8080
```

Inicie os três serviços (Java, Go e PostgreSQL):

```sh
docker compose up -d --build --wait
docker compose ps
docker compose logs --tail=100 app
```

Abra `http://IP_DO_SERVIDOR:8080/hosts` ou `/grupos`. Libere a porta TCP 8080 no firewall da rede/servidor quando necessário. O painel não tem autenticação; este exemplo é para a rede de testes.

O serviço `app` aguarda o healthcheck do PostgreSQL. Ele se conecta a `postgres:5432` pela rede interna do Compose, independentemente de `POSTGRES_HOST`/`POSTGRES_PORT` no `.env` (essas duas opções continuam servindo ao modo Java local). Dentro do contêiner, o Spring sempre escuta em `0.0.0.0:8080`; `APP_PORT` controla a porta publicada no servidor. O banco permanece acessível no servidor apenas por loopback. Seu volume `postgres_data` é preservado.

Java e Go rodam sem privilégios de administrador. O Compose permite ICMP somente no serviço `collector`, por `ping_group_range`, e compartilha o Unix Domain Socket com o Java. O coletor precisa ter rota para os hosts; Java precisa alcançar o Zabbix. Para verificar a saúde e os contadores do motor:

```sh
docker compose exec collector routiva-collector --health
```

A saúde da aplicação é verificada por `/actuator/health`, incluindo a conexão com o banco. Falhas ICMP de hosts aparecem nos cards e não deixam o contêiner indisponível. `restart: unless-stopped` reinicia contêineres após falha do processo ou reinício do Docker; um healthcheck com falha, sozinho, não reinicia o contêiner.

### Acompanhar o coletor e interpretar as métricas

Execute na pasta do projeto, no servidor:

```sh
docker compose exec -T collector routiva-collector --health | jq
```

`docker compose exec` executa um comando dentro do serviço `collector`. A opção `-T` desativa o terminal interativo, facilitando encaminhar a saída pelo pipe (`|`). O programa `routiva-collector --health` consulta o processo Go em execução pelo socket local e retorna sua identificação e métricas em JSON; ele não inicia outro motor de coleta. O `jq` formata esse JSON com indentação e deve estar instalado no servidor, não no container. Sem `jq`, retire `| jq` para ver a saída original.

Para atualizar a leitura a cada dois segundos, se `watch` estiver instalado no servidor:

```sh
watch -n 2 'docker compose exec -T collector routiva-collector --health | jq'
```

Encerre o acompanhamento com `Ctrl+C`. A consulta é uma fotografia do estado atual, não um histórico de eventos nem uma lista de lotes.

| Campo | Significado e unidade |
| --- | --- |
| `collector_id` | Identificador desta execução do coletor. Muda quando o processo Go reinicia; junto ao ID do lote ou da tentativa, permite identificar seus registros. |
| `protocol_version` | Versão do protocolo de comunicação entre Java e Go; atualmente `1`. Não é a versão do aplicativo. |
| `metrics` | Objeto que reúne os indicadores abaixo. |

Os contadores acumulados abaixo são reiniciados quando o processo Go reinicia. Compare leituras com o mesmo `collector_id`. Valores instantâneos e tempos da última ocorrência não são médias.

| Campo em `metrics` | Significado e unidade |
| --- | --- |
| `ack_latency_ns` | Tempo entre o envio e o recebimento do ACK do último lote confirmado, em nanossegundos. Inclui transporte, espera e persistência no Java/banco; não é somente latência de rede. |
| `active_hosts` | Quantidade atual de hosts configurados no agendador. |
| `collection_errors_total` | Total acumulado de tentativas encerradas por erro interno de coleta, por exemplo falha de recepção ou encerramento do motor. Não representa perda de rede. |
| `connections_total` | Total acumulado de sessões de coleta aceitas pelo Go, incluindo reconexões. Não conta consultas `--health` nem representa conexões simultâneas. |
| `cpu_seconds` | Tempo acumulado de CPU do processo Go, somando usuário e sistema, em segundos. Não é percentual de CPU nem tempo desde a inicialização. |
| `dropped_total` | Total acumulado de resultados descartados por falta de espaço no buffer de resultados ou na fila de lotes. Não é quantidade de pings sem resposta. |
| `goroutines` | Quantidade atual de goroutines, as tarefas concorrentes do runtime Go. Não é quantidade de hosts nem de threads do sistema operacional. |
| `heap_bytes` | Bytes atualmente alocados no heap do Go. Divida por `1048576` para MiB. Não representa toda a memória do container. |
| `icmp_errors_total` | Total acumulado de tentativas encerradas por erro ICMP correlacionado, como destino inalcançável. Entra como perda de rede. |
| `pending` | Quantidade atual de tentativas de ping admitidas e ainda não concluídas, podendo incluir espera de envio. Não é quantidade de lotes pendentes. |
| `processing_latency_ns` | Atraso local entre a recepção do último evento de resposta e seu processamento pelo motor, em nanossegundos. Não é o RTT do host. |
| `receive_errors_total` | Total acumulado de erros ao ler o socket ICMP. Uma falha pode encerrar várias tentativas e aumentar `collection_errors_total`. |
| `replies_total` | Total acumulado de respostas válidas que concluíram tentativas com sucesso. Respostas duplicadas, tardias ou sem correspondência não aumentam esse contador. |
| `result_buffer` | Quantidade de resultados na fila interna do motor somada aos resultados dos lotes ainda não confirmados, incluindo o lote enviado que aguarda ACK. Não inclui resultados temporariamente reunidos para formar o próximo lote. Não é uma contagem de lotes. |
| `scheduler_lag_ns` | Último atraso observado entre o horário planejado e o processamento do agendamento, em nanossegundos. |
| `scheduler_max_lag_ns` | Maior atraso de agendamento observado nesta execução, em nanossegundos. Pode continuar alto após um incidente já resolvido. |
| `send_errors_total` | Total acumulado de tentativas encerradas por falha local no envio ICMP. Não é timeout do destino. |
| `sent_total` | Total acumulado de envios ICMP concluídos sem erro local. Não conta lotes enviados nem garante resposta do destino. |
| `skipped_total` | Total acumulado de oportunidades de coleta puladas por atraso do agendador ou limites de fila de envio/pendências. Não entra como perda de rede. |
| `timeouts_total` | Total acumulado de tentativas expiradas sem resposta válida dentro do prazo. Entra como perda de rede. |

Campos terminados em `_ns` usam nanossegundos: divida por `1000000` para milissegundos. Por exemplo, `ack_latency_ns: 126078521` equivale a aproximadamente **126,08 ms** para a última confirmação.

Para exibir um resumo com nomes mais claros e unidades convertidas:

```sh
docker compose exec -T collector routiva-collector --health | jq '{
  coletor: .collector_id,
  hosts_monitorados: .metrics.active_hosts,
  pings_pendentes: .metrics.pending,
  resultados_no_buffer: .metrics.result_buffer,
  resultados_descartados: .metrics.dropped_total,
  ultima_confirmacao_ms: (.metrics.ack_latency_ns / 1000000),
  pings_enviados: .metrics.sent_total,
  respostas_recebidas: .metrics.replies_total,
  timeouts: .metrics.timeouts_total,
  erros_de_envio: .metrics.send_errors_total,
  memoria_heap_MiB: (.metrics.heap_bytes / 1048576)
}'
```

**Como interpretar o fluxo:** o Go mantém um lote enviado aguardando ACK por vez. O Java grava as amostras e o recibo em `collector_batch` na mesma transação e só depois confirma ao Go. Ao receber o ACK, o Go remove o lote da memória e pode enviar o próximo. O arquivo de log registra o ciclo dos lotes e os ACKs, mas não armazena os resultados para recuperação; os recibos e amostras no PostgreSQL seguem a retenção configurada. O recibo comprova a gravação, mas não que o Go recebeu a confirmação.

Compare leituras ao longo do tempo: aumento de `dropped_total` indica novos descartes; queda de `result_buffer` indica redução do acúmulo naquele intervalo. Um buffer estável, sozinho, não comprova equilíbrio, pois pode coexistir com descartes. Para calcular uma taxa, divida a diferença de um contador pelo tempo entre as consultas. A saída atual não expõe os IDs dos lotes pendentes nem separa lotes aguardando envio daqueles aguardando ACK.

Para acompanhar os logs disponíveis dos dois processos:

```sh
docker compose logs -f --timestamps --tail=100 collector app
```

O Docker captura os logs gerais dos processos. O coletor também mantém um **arquivo sequencial de eventos por lote**, com mensagens e campos em inglês, em `/var/log/routiva/collector.log`. Não registra pings, IPs ou resultados individuais.

### Arquivo de log dos lotes

O arquivo permite acompanhar o caminho de cada lote: criação → envio ao Java → recebimento e validação do ACK → remoção da memória. Também registra reconexões, reenvios, descartes e o estado das filas. As mensagens são em inglês e não incluem pings individuais.

O caminho `/var/log/routiva/collector.log` fica **dentro do container `collector`**, no volume `collector_logs`; não é um arquivo criado diretamente na pasta do projeto no servidor. Execute os comandos abaixo na pasta do projeto, onde está o `compose.yaml`.

#### Ativar e abrir o log

Após atualizar o código, reconstrua o coletor para ativar o arquivo e o volume persistente:

```sh
docker compose up -d --build --wait collector
```

A recriação reinicia o coletor: resultados ainda não confirmados em memória podem ser perdidos. Essa etapa é necessária para aplicar a implementação, não a cada consulta do log.

Para abrir as últimas 100 linhas, sem continuar acompanhando:

```sh
docker compose exec -T collector tail -n 100 /var/log/routiva/collector.log
```

Para acompanhar novos eventos em tempo real, inclusive após rotação:

```sh
docker compose exec -T collector tail -n 100 -F /var/log/routiva/collector.log
```

Pressione `Ctrl+C` para sair do acompanhamento; isso não para o coletor. `-n 100` seleciona as últimas 100 linhas e `-F` continua seguindo o arquivo quando ele é substituído pela rotação.

#### Consultar um lote e abrir arquivos anteriores

Para consultar somente os eventos de um lote (substitua a identidade do coletor e o número):

```sh
docker compose exec -T collector cat /var/log/routiva/collector.log | grep -F 'collector_id=ID_DO_COLETOR ' | grep -E 'batch_id=123( |$)'
```

O arquivo ativo contém os eventos mais recentes. Arquivos anteriores são `collector.log.1` (o mais recente dos arquivos rotacionados), `collector.log.2` e assim por diante. Para exportar o arquivo ativo:

```sh
docker compose cp collector:/var/log/routiva/collector.log ./collector-export.log
```

Depois, abra a cópia no servidor com um editor de texto ou com `less`, se instalado:

```sh
less collector-export.log
```

No `less`, use as setas para navegar, `/ACK_RECEIVED` para pesquisar e `q` para sair. A cópia é uma fotografia do arquivo; ela não recebe novos eventos.

Para listar os arquivos disponíveis e consultar as últimas linhas do arquivo anterior:

```sh
docker compose exec -T collector ls -lh /var/log/routiva
docker compose exec -T collector tail -n 100 /var/log/routiva/collector.log.1
```

O arquivo `.1` só existe depois da primeira rotação. Se o arquivo ativo ainda não existir, confira se o serviço atualizado está em execução com `docker compose ps collector` e procure erros em `docker compose logs --tail=100 collector`.

#### Entender os eventos

Cada linha contém `time`, `level`, `msg`, `seq`, `event` e os campos do evento. `seq` ordena os registros da execução e reinicia com o processo. `collector_id` e `batch_id` identificam um lote; os primeiros eventos de inicialização podem ocorrer antes da criação da identidade do motor. Os horários incluem o fuso do processo, normalmente UTC no container.

| Evento | Significado |
| --- | --- |
| `STARTING`, `SOCKET_READY`, `ICMP_READY`, `LIMITS` | Inicialização e limites configurados. |
| `CONNECTION_ACCEPTED`, `CONNECTION_REJECTED`, `CONNECTION_CLOSED` | Abertura, rejeição ou encerramento de sessão Java. |
| `CONFIG_RECEIVED`, `CONFIG_APPLIED`, `CONFIG_REJECTED`, `CONFIG_TIMEOUT`, `PROTOCOL_REJECTED` | Configuração e validação do protocolo, sem listar hosts. |
| `BATCH_CREATED` | Lote formado e colocado na fila, com quantidade de resultados e motivo. |
| `SEND_STARTED`, `SEND_COMPLETED`, `SEND_FAILED` | Início, retorno bem-sucedido do envio gRPC ou falha. `send_attempt` maior que 1 indica reenvio do mesmo lote. Envio concluído não comprova gravação no banco. |
| `ACK_RECEIVED`, `ACK_VALIDATED`, `ACK_REJECTED`, `ACK_TIMEOUT` | Recebimento, validação, rejeição ou ausência de confirmação no prazo. `ack_ms` mede envio até validação, incluindo persistência. |
| `BATCH_REMOVED` | Remoção do lote da memória após ACK válido. |
| `BATCH_DROPPED` | Novo lote descartado por falta de espaço; inclui ID e quantidade. |
| `QUEUE_STATUS` | Fotografia da fila a cada dez segundos; inclui resultados em formação, buffer do motor e descartes acumulados. |
| `SHUTDOWN_REQUESTED`, `ENGINE_STOPPED`, `SHUTDOWN_UNCONFIRMED`, `SHUTDOWN_COMPLETED`, `COLLECTOR_FAILED` | Encerramento, dados restantes ou falha do processo. |

`retained_batches` inclui o lote em voo; `waiting_to_send` exclui esse lote; `awaiting_ack` é zero ou um. `in_flight_batch_id` identifica o lote em transmissão/espera de ACK (zero se não houver). Descartes anteriores à formação de lote aparecem somente no total agregado de `QUEUE_STATUS`, pois ainda não possuem ID de lote.

#### Rotação, persistência e limites

O volume Docker `collector_logs` preserva os arquivos ao recriar o container ou executar `docker compose down`. `down -v` remove também esse volume. A rotação padrão usa **100 MiB por arquivo, até dez arquivos incluindo o ativo**, eliminando o mais antigo. Ajuste no `.env` e recrie o serviço:

```dotenv
COLLECTOR_LOG_MAX_MIB=100
COLLECTOR_LOG_FILES=10
```

Fora do Compose, `COLLECTOR_LOG_PATH` permite mudar o caminho do arquivo; o diretório precisa ser gravável pelo usuário do coletor. O serviço systemd fornecido cria `/var/log/routiva` com o proprietário correto.

A escrita usa uma fila limitada em memória para não bloquear a coleta por lentidão do disco. Se a fila do logger lotar ou a escrita falhar, eventos podem ser perdidos: `LOG_EVENTS_DROPPED` e `LOG_WRITE_FAILED` são avisados na saída do processo, consultável com `docker compose logs collector`. Lacunas em `seq` podem indicar eventos perdidos. Falha ao abrir o arquivo na inicialização impede a subida. Encerramento normal drena a fila de logs; uma interrupção abrupta pode perder registros recentes. O arquivo não substitui os recibos do PostgreSQL nem permite recuperar resultados descartados.


### Usando o Nginx instalado no mesmo servidor

Se o Nginx já ocupa a porta 8080, configure no `.env`:

```dotenv
APP_BIND_ADDRESS=127.0.0.1
APP_PORT=8081
```

Mantenha o Nginx com `listen 8080` e `proxy_pass http://127.0.0.1:8081;`. Aplique as alterações:

```sh
docker compose up -d --build --wait
sudo nginx -t
sudo systemctl reload nginx
```

O acesso continua em `http://IP_DO_SERVIDOR:8080`. Nesse modo, a porta do contêiner só é publicada no loopback do servidor.

### Atualizar, acompanhar e parar

```sh
git pull --ff-only
docker compose up -d --build --wait
docker compose logs -f --tail=100 app
```

Atualizações da aplicação preservam o banco. Para parar a aplicação, use `docker compose stop app`; para remover os três contêineres preservando o banco, use `docker compose down`. Não use `down -v` se quiser manter os dados. Após mudar o `.env`, use `docker compose up -d` para recriar o contêiner com os novos valores; apenas `restart` não atualiza variáveis de ambiente.

## PostgreSQL em Docker

O Compose usa a imagem oficial `postgres:17-alpine`, expõe a porta apenas em `127.0.0.1` e mantém os dados no volume `postgres_data` do projeto. Só as credenciais do banco são passadas ao contêiner; as credenciais Zabbix ficam na aplicação.

```sh
docker compose ps
docker compose logs postgres
docker compose down
```

Parar/remover o contêiner com `down` preserva o volume e o histórico. `down -v` apaga o volume e todos os dados. Alterar usuário, senha ou nome do banco no `.env` não altera um banco já inicializado: as variáveis `POSTGRES_*` inicializam apenas um volume vazio. Em banco existente, faça a alteração no PostgreSQL e ajuste o `.env` de acordo.

O banco padrão passou a ser PostgreSQL; arquivos H2 de versões anteriores não são migrados automaticamente. H2 é usado somente nos testes automatizados.

## Conexão Zabbix

Integração com Zabbix 6.0 ou superior, com detecção automática da versão. Em 6.0/6.2 a sessão é enviada no campo JSON-RPC `auth`; em 6.4 ou superior, no cabeçalho Bearer. No Zabbix 6.0 a consulta usa `selectGroups`/`groups`; nas versões seguintes, `selectHostGroups`/`hostgroups`. A aplicação chama `user.login` com `ZABBIX_USERNAME` e `ZABBIX_PASSWORD`, mantém a sessão em memória e usa o identificador retornado para as consultas. Não é necessário gerar ou configurar token API. Quando a sessão expira, a aplicação tenta autenticar novamente uma vez por sincronização. No encerramento normal, chama `user.logout`.

O usuário precisa ter acesso de leitura aos hosts/grupos e permissão para usar a API. O cliente usa `host.get`, `selectHostGroups` e `selectInterfaces`. Coleta nome visível, identificador e IP da interface principal de agente, quando disponível, com fallback para outra interface. Inclui hosts habilitados; todos os grupos de cada host são preservados. Hosts sem IP válido aparecem em cinza e não são enviados para a coleta de ping. Interfaces configuradas somente por DNS precisam ter o IP preenchido.

O inventário é carregado ao iniciar e sincronizado a cada 60 segundos. Em falha de sincronização, mantém o último inventário e informa o problema no painel. Hosts removidos deixam de ser monitorados na próxima sincronização; seus registros permanecem até expirar a retenção. Uma coleta em andamento pode terminar após a remoção.

## Páginas e filtros

- `/hosts`: tabela de hosts, com uma linha por host e pesquisa pelo nome. Colunas: nome, IP, médias de latência das últimas 8 horas, 1 hora e 5 minutos, perdas nas últimas 8 horas, 1 hora e 5 minutos e status. A linha mantém as cores de saúde e a tabela permite rolagem horizontal em telas pequenas.
- `/grupos`: tabela de grupos, com nome, quantidade de hosts, latências, perdas, status e atualização. O nome do grupo é um link para seus hosts.
- `/`: abre a página de hosts.

Na página de grupos, clicar no nome do grupo abre `/hosts?groupId=ID`, exibindo apenas os membros daquele grupo. A página de hosts também oferece um seletor de grupos; essa seleção permanece ativa durante a pesquisa, o filtro de status e as atualizações automáticas. O vínculo usa o identificador do grupo, incluindo hosts que pertencem a vários grupos. Grupos removidos ou inexistentes mostram uma lista vazia, sem exibir hosts de outros grupos.

Cada página permite combinar pesquisa por nome e status de ping (todos, verde, amarelo, vermelho, cinza ou neutro). A pesquisa ignora maiúsculas/minúsculas e acentos. Os filtros são mantidos durante a atualização automática, e os resultados acompanham as mudanças de status. A URL reflete os filtros para permitir favoritos e compartilhamento. O botão “Limpar filtros” restaura a lista completa da página. Médias de grupo sempre consideram todos os membros, independentemente da pesquisa.

Clique no cabeçalho de qualquer coluna para ordenar; clique novamente para inverter crescente/decrescente. Latências, percentuais e quantidades usam valores numéricos, IPs usam ordem numérica e horários usam o instante real da coleta. Valores sem dados ficam no final em ambas as direções. A seta indica a coluna e a direção ativas. Pesquisa, filtros, atualização manual e automática preservam a ordenação, que também fica na URL.

## Coleta e apresentação da qualidade

A coleta usa **1 ping por segundo por host** (`PING_INTERVAL_MS=1000`) no processo Go, com um socket ICMP compartilhado e recepção assíncrona. O intervalo é contado pelo horário planejado; timeout não bloqueia o próximo envio. O padrão de timeout é 1500 ms. Valores explícitos no `.env` continuam prevalecendo.

Java sincroniza o inventário por gRPC, recebe lotes e confirma depois do commit. Filas limitadas e retransmissão em memória separam o motor da latência do banco. Não há execução de comandos externos de ping. Timeouts são perdas; erros internos e tentativas puladas ficam fora do denominador. Detalhes, limites, métricas e evidências de teste estão em [Operação do coletor](docs/coletor-icmp.md).

Os dados originais continuam no PostgreSQL, com novas colunas opcionais para identidade, status e horários da tentativa. A retenção permanece limitada a 48 horas e a limpeza ocorre a cada 600 segundos. A capacidade do banco e do histórico Java não está certificada para 10.000 hosts.

As tabelas, colunas, filtros, ordenação e controles de atualização foram preservados. O cabeçalho permite atualizar agora ou escolher 5, 10, 15 ou 60 segundos, com preferência salva no navegador. Esse intervalo atualiza a tela; ele não muda o intervalo dos pings.

## Regras de perda de pacotes

A perda tem somente três níveis, aplicados às janelas móveis de **5 minutos, 1 hora e 8 horas**:

| Percentual real de perda | Classificação | Cor |
| --- | --- | --- |
| Até **0,33%**, inclusive | NORMAL | Verde |
| Acima de **0,33%** e abaixo de **1%** | ATENCAO | Amarelo |
| **1% ou mais**, inclusive | CRITICO | Vermelho |

O valor exato de 1% é crítico. A cor e o status usam a maior gravidade entre as três janelas. Nos grupos, vale o pior membro elegível, enquanto os percentuais apresentados usam os totais agregados de tentativas e perdas.

O cálculo é `perdidos / tentativas concluídas elegíveis × 100`. Timeout conta como perda; erro interno e lacuna de coleta não contam como perda nem entram no denominador. Sem tentativas elegíveis, aparece `—`. Os percentuais reais são sempre exibidos e os limites são comparados **sem arredondamento**. Por exemplo, 1 perda em 300 tentativas representa aproximadamente 0,3333% e já é atenção, mesmo que a tela mostre 0,33%.

Não existem mais regras especiais de perda por minuto, perdas consecutivas, recorrência, nível degradado ou espera de cinco minutos para recuperação. A classificação é recalculada pelos percentuais das janelas disponíveis. Uma janela histórica acima do limite continua mantendo o alerta até seu percentual baixar.

As regras de **latência permanecem**: abaixo de 15 ms é normal, de 15 a 20 ms é amarelo e acima de 20 ms é vermelho. A maior gravidade entre latência e perda determina a cor final. Hosts sem IP válido ou com incidente ICMP reconhecido no Zabbix ficam cinza e são excluídos das estatísticas dos grupos. Sem dados para classificação, o estado é neutro.

## Ajuste rápido no application.properties

Os dois limites que definem os três níveis estão em `src/main/resources/application.properties`:

```properties
# Limite superior inclusivo do nível normal.
monitor.loss.hour-normal-max-percent=0.33
# Limite inferior inclusivo do nível crítico.
monitor.loss.hour-critical-min-percent=1.0
```

Apesar do prefixo `hour-`, os dois valores se aplicam igualmente às janelas de **5 minutos, 1 hora e 8 horas**. Atenção é o intervalo entre esses limites. O limite normal deve ser maior ou igual a zero e menor que o crítico; o crítico deve ser no máximo 100. Configurações inválidas impedem a inicialização.

As demais propriedades `monitor.loss.*` controlam a coleta e o diagnóstico, sem criar outros níveis ou gatilhos de perda. Para ajustar a cadência de coleta, use `PING_INTERVAL_MS`.

A retenção provisória continua no mesmo arquivo:

```properties
monitor.retention-hours=${PING_RETENTION_HOURS:48}
monitor.cleanup-interval-seconds=${PING_CLEANUP_INTERVAL_SECONDS:600}
```

Valores no `.env` prevalecem sobre esses dois padrões. Para mudar regras no properties, reconstrua a imagem: `docker compose up -d --build --wait app`. Para alterar só o `.env`, recrie o serviço com `docker compose up -d app`.

### Integração e eficiência

O coletor grava os resultados no PostgreSQL sem disparar o recálculo dos indicadores. Um processo em background, com agendamento próprio a cada 10 segundos e primeira execução na inicialização, carrega inicialmente o histórico necessário (no mínimo 8 horas) e depois consulta apenas IDs novos de `ping_sample`. O histórico permanece em blocos compactos por host/IP. As somas de latência e contagens de respostas/perdas de cada janela são incrementadas pelas entradas e decrementadas pelas saídas. Os diagnósticos de cobertura e sequências de perda reutilizam resumos dos blocos, inspecionando registros individuais somente nas bordas ou nos blocos modificados.

Cada ciclo captura um limite de IDs confirmados: os escritores da aplicação e a captura desse limite usam a linha de `ping_ingestion_guard` com `FOR UPDATE`. Isso serializa a alocação dos IDs até o commit, evitando perder uma transação que termine depois com ID menor. A captura libera o bloqueio antes da leitura do histórico. A consulta incremental usa `id > cursor AND id <= limite AND measured_at >= início`, com transferência em blocos de 1.000 registros. Escritas externas diretas devem respeitar o mesmo protocolo; IDs não podem ser reutilizados ou registros existentes alterados durante a execução.

Pings atrasados entram nas janelas correspondentes ao horário da medição. Registros futuros ficam pendentes em memória até seu horário chegar; registros vencidos são descartados. As janelas expiram mesmo sem novas gravações. Em falha parcial, o conjunto publicado é preservado, o estado de trabalho é descartado e o ciclo seguinte reconstrói o histórico do banco antes de retomar o cursor incremental. Reinício ou retrocesso do relógio também provocam reconstrução.

A página consulta somente o último conjunto concluído de indicadores em memória: nenhum acesso à página dispara consulta de pings ou recálculo das janelas. A publicação é atômica, e leitores continuam usando os valores anteriores enquanto o próximo ciclo é processado ou se ele falhar. Antes da primeira publicação, hosts sem indicadores aparecem sem dados. Os ciclos não se sobrepõem; se um processamento exceder 10 segundos, a atualização seguinte atrasa. Alterar o IP separa os históricos. Resultados revertidos por rollback não entram nos indicadores. O detalhe do host participa da mesma publicação: sua carga inicial alcança a retenção configurada (até 48 horas), e os gráficos e totais são preparados em memória no mesmo ciclo. O histórico bruto de latência/perda mantém sua janela própria; a disponibilidade retém contribuições compactas por minuto para preservar as bordas exatas dos períodos móveis.

O estado visual é reconstruído com o histórico disponível ao reiniciar. Sem tentativas elegíveis, a perda fica sem classificação. Não existe serviço de notificações ou registro de alertas externos. Execute uma única instância coletora por banco; não há coordenação distribuída de coleta entre réplicas. Para inventários grandes, dimensione o coletor, o banco e a memória do histórico conforme os limites documentados.

### Log dos ciclos de indicadores

Cada ciclo concluído ou com falha gera uma linha em inglês, com timestamp UTC. O arquivo local padrão é `logs/indicators.log`. No Compose, fica em `/var/log/routiva/indicators.log`, no volume persistente `indicator_logs` do serviço `app`.

```bash
# Execução local
tail -F logs/indicators.log

# Docker Compose
docker compose exec app tail -F /var/log/routiva/indicators.log
```

Eventos: `INITIAL_LOAD_COMPLETED`, `CYCLE_COMPLETED` e `CYCLE_FAILED`. `run_id` identifica uma execução do serviço e `cycle` cresce durante essa execução, incluindo ciclos que falharam. `reference_at` é o horário de referência dos cálculos; o timestamp da linha é o horário do registro. Não são emitidos logs por ping ou por consulta da página.

Exemplo ilustrativo de um ciclo concluído e de uma falha (valores fictícios):

```text
2026-10-27T17:00:10.205Z INFO  event=CYCLE_COMPLETED run_id=8c85a18e-338b-4f85-9b19-6c0c5b14f458 cycle=2 reference_at=2026-10-27T17:00:10Z hosts=700 fetched=7000 incorporated=7000 late_arrivals=0 future_queued=0 future_released=0 pending_future=0 discarded=0 expired_5m=7000 expired_1h=7000 expired_8h=7000 removed_pings=7000 retained_pings=20160000 query_ms=120 processing_ms=85 total_ms=205 schedule_overrun=false cursor=20167000 reset_reason=none indicators_published=true
2026-10-27T17:00:23.000Z ERROR event=CYCLE_FAILED run_id=8c85a18e-338b-4f85-9b19-6c0c5b14f458 cycle=3 reference_at=2026-10-27T17:00:20Z mode=incremental stage=database_query error=DataAccessResourceFailureException fetched=0 incorporated=0 discarded=0 total_ms=3000 indicators_published=false last_published_cycle=2 next_cycle=initial_load
```

No ciclo concluído do exemplo, entraram 7.000 pings e saíram definitivamente outros 7.000, mantendo 20.160.000 registros no histórico ativo. Na falha, a página continua usando os indicadores publicados no ciclo 2.

| Campo | Significado |
|---|---|
| `hosts` | Quantidade de pares host/IP com histórico ativo |
| `fetched` | Registros entregues pela consulta incremental, inclusive na carga inicial |
| `incorporated` | Registros incorporados ao histórico ativo, incluindo futuros liberados neste ciclo |
| `late_arrivals` | Entre os recém-consultados e incorporados, medições anteriores à referência do ciclo passado; zero na carga inicial |
| `future_queued`, `future_released`, `pending_future` | Entradas na fila futura, saídas da fila e tamanho final da fila |
| `discarded` | Registros lidos ou liberados da fila que foram rejeitados por IP inválido ou janela vencida |
| `expired_5m`, `expired_1h`, `expired_8h` | Registros que saíram de cada janela; não devem ser somados como remoções da memória |
| `removed_pings`, `retained_pings` | Remoções definitivas por expiração e quantidade final do histórico ativo, sem a fila futura |
| `query_ms` | Tempo da captura do limite e da consulta SQL, descontado o processamento dos registros no Java |
| `processing_ms`, `total_ms` | Demais operações do ciclo e duração total, antes da escrita da linha de log |
| `schedule_overrun` | Indica que o processamento ultrapassou 10 segundos |
| `cursor`, `reset_reason` | Último ID confirmado e motivo de reconstrução por relógio ou limite do banco |
| `indicators_published` | Se o ciclo publicou um conjunto completo de indicadores |

Contagens representam registros de ping, inclusive erros internos; as fórmulas de latência e perda continuam excluindo esses erros. `incorporated` pode superar `fetched` quando pings futuros são liberados. Registros já excluídos pelo filtro SQL não entram em `discarded`. Em falha, os contadores são parciais e não representam publicação: `last_published_cycle` identifica o último resultado disponível e `next_cycle=initial_load` indica reconstrução. O campo `error` contém somente o nome da classe da exceção, sem mensagens SQL ou credenciais.

A rotação ocorre diariamente ou ao atingir 10 MB. Os arquivos antigos são comprimidos, com retenção de 7 dias e limite total de 100 MB para arquivos arquivados; o arquivo ativo é adicional. A limpeza ocorre nas rotações e na inicialização. Os limites são configuráveis por `INDICATORS_LOG_MAX_FILE_SIZE`, `INDICATORS_LOG_MAX_HISTORY` e `INDICATORS_LOG_TOTAL_SIZE_CAP`. `INDICATORS_LOG_PATH` permite mudar o caminho local; o Compose fixa o caminho dentro do volume. O diretório deve permitir escrita pelo usuário da aplicação. Falhas de escrita são sinalizadas pelo mecanismo de logging, mas o arquivo não é um registro transacional dos pings.

## Configurações no `.env`

| Variável | Padrão | Uso |
| --- | --- | --- |
| ZABBIX_URL | vazio | URL completa da API JSON-RPC |
| ZABBIX_USERNAME | vazio | Usuário do Zabbix |
| ZABBIX_PASSWORD | vazio | Senha do Zabbix |
| ZABBIX_SYNC_MS | 60000 | Intervalo de sincronização |
| POSTGRES_HOST | localhost | Endereço do banco |
| POSTGRES_PORT | 5432 | Porta do banco publicada pelo Compose |
| POSTGRES_DB | scriptping | Nome do banco |
| POSTGRES_USER | scriptping | Usuário do banco |
| POSTGRES_PASSWORD | obrigatório no Compose | Senha do banco |
| PING_INTERVAL_MS | 1000 | Intervalo de disparo das coletas |
| PING_TIMEOUT_MS | 1500 | Limite de espera por resposta |
| COLLECTOR_ADDRESS | unix:///run/routiva/collector.sock | Endpoint gRPC local do Go |
| INDICATORS_LOG_PATH | logs/indicators.log | Caminho local; no Compose é fixado em /var/log/routiva/indicators.log |
| INDICATORS_LOG_MAX_FILE_SIZE | 10MB | Tamanho para rotação de cada arquivo |
| INDICATORS_LOG_MAX_HISTORY | 7 | Retenção dos arquivos arquivados, em dias |
| INDICATORS_LOG_TOTAL_SIZE_CAP | 100MB | Limite total dos arquivos arquivados; não inclui o ativo |
| PING_RETENTION_HOURS | 48 | Retenção em horas, limitada a no máximo 48 |
| PING_CLEANUP_INTERVAL_SECONDS | 600 | Intervalo provisório de limpeza em segundos |
| SERVER_ADDRESS | 127.0.0.1 | Interface de escuta da aplicação |
| PORT | 8080 | Porta web no modo Java local |
| APP_BIND_ADDRESS | 0.0.0.0 | Interface publicada pelo Docker; use 127.0.0.1 com Nginx local |
| APP_PORT | 8080 | Porta do servidor publicada pelo contêiner Spring |

Os intervalos precisam ser positivos. O painel lê os indicadores em memória; o processo em background realiza a carga inicial e as consultas incrementais ao banco. A capacidade para inventários grandes depende de validação de carga. Não possui autenticação e escuta apenas localmente por padrão. Para acesso em rede, configure `SERVER_ADDRESS` e um proxy com autenticação e HTTPS conforme seu ambiente.

## Testes

```sh
mvn test -Dcollector.enabled=false
```

Para validar os controles de atualização no navegador com Node.js 18 ou superior (apenas para desenvolvimento):

```sh
node --test src/test/js/*.test.cjs
```

Esses testes verificam os quatro intervalos do painel, a atualização manual, a persistência da escolha e a recuperação após erros, além do polling de 10 segundos do detalhe, troca de período, descarte de respostas antigas e representação de perdas. Node.js não é necessário para executar a aplicação ou os contêineres.

Os testes Java usam H2 em memória e uma API Zabbix simulada. Incluem os limites percentuais exatos, remoção dos gatilhos antigos de perda, falhas/lacunas do coletor, janelas incompletas, outras cadências, amostras atrasadas/duplicadas/futuras, restauração após reinício e percentuais reais na apresentação. Cobrem limites das cores, persistência de falhas, médias sem zeros artificiais, renderização com/sem hosts, HTMX local, login por usuário/senha, reutilização/renovação da sessão, logout, credenciais inválidas e leitura do `.env`. A integração real exige PostgreSQL disponível, acesso ao seu Zabbix e conectividade ICMP.

Referências: [user.login](https://www.zabbix.com/documentation/current/en/manual/api/reference/user/login), [API Zabbix](https://www.zabbix.com/documentation/7.0/en/manual/api), [imagem oficial PostgreSQL](https://hub.docker.com/_/postgres).

### Dashboard individual e disponibilidade do host

Clique no nome de um host para abrir `/hosts/{id}`. O painel mostra nome, IP, grupos, disponibilidade operacional (principal), técnica, perda, degradação e gráficos históricos. Os períodos aceitos são 1, 2, 4, 8, 12, 24 e 48 horas, contados a partir da referência da última publicação em memória. O período padrão é 24 horas. A seleção no topo busca o relatório do novo período sem recarregar a página. Tanto a página quanto a API apenas leem relatórios prontos: abrir, recarregar ou trocar período não consulta pings nem recalcula indicadores. O navegador consulta a publicação a cada 10 segundos, sem sobreposição de requisições.

Os cálculos ficam no backend: H = respostas dentro do limite, D = respostas acima dele, P = pings sem resposta; T = H + D + P. Disponibilidade técnica = 100 × (H + D) / T; operacional = 100 × H / T. Erros internos não entram no denominador. Sem amostras válidas, os percentuais são nulos (`SEM_DADOS`). O total usa contagens de todas as amostras, nunca médias dos percentuais das janelas.

O limite padrão é 20 ms e é definido nas configurações do aplicativo (maior que zero, até 60000 ms). A migração atribui 20 ms às amostras anteriores. Novas amostras guardam o limite enviado na configuração da tentativa, preservando o histórico após alterações. Não há nova coleta de ping. As consultas consideram o IP atual e a retenção disponível. Os gráficos agrupam em minutos fixos em todos os períodos, inclusive 48 horas. O minuto atual é provisório e atualizado no mesmo ponto a cada publicação. As bordas são recortadas ao período móvel exato; minutos encerrados podem ser corrigidos por amostras atrasadas. Janelas vazias ou com apenas falhas internas têm valores nulos; janelas parcialmente coletadas usam somente amostras válidas e não implicam cobertura integral.

Perdas recebem marcadores vermelhos no gráfico de latência, mesmo quando a média está normal. Perda total interrompe a curva de latência; ausência de dados recebe marcador cinza. Clique nos gráficos ou use o controle de minutos com as setas para consultar contagens, percentuais e erros internos. A página informa separadamente o horário do cálculo e o da última medição, a retenção limitada e publicações com mais de 30 segundos. Falhas de consulta preservam os valores visíveis.

API: `GET /api/hosts/{id}/availability?hours=24`, com totais, contagens, série temporal de 60 segundos, `version`, `evaluatedAt`, `lastSampleAt` e `retainedFrom`. Os pontos incluem `at`, `from`, `to`, `partial` e `provisional`; `metrics.errors` informa erros internos separados das perdas. A resposta usa `Cache-Control: no-store`. O limite global vem de `monitor.latency-limit-ms` (`PING_LATENCY_LIMIT_MS`, padrão 20 ms), sem ajuste por host. Cada amostra preserva o `latency_limit` vigente na coleta.


Detalhes de implementação, limites e validação: [Disponibilidade incremental](docs/disponibilidade-incremental.md).
