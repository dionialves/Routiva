# ScriptPing

Monitor de latência com Java 21, Spring Boot, Thymeleaf, HTMX e PostgreSQL em Docker.

## Executar

Pré-requisitos: JDK 21, Maven 3.6.3+, Docker com Compose e comando `ping` instalado e autorizado a enviar ICMP. Você pode executar Spring e PostgreSQL em contêineres separados (veja abaixo), ou rodar Java localmente com apenas o banco em Docker.

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
mvn spring-boot:run
```

Abra http://localhost:8080. As tabelas e índices são criados automaticamente no PostgreSQL. Sem credenciais do Zabbix, a aplicação abre o painel vazio com instruções, desde que o banco esteja disponível.

Para gerar e executar o JAR, na pasta que contém `.env`:

```sh
mvn clean package
java -jar target/scriptping-1.0.0.jar
```

## Servidor: Spring e PostgreSQL em contêineres separados

Pré-requisitos no servidor: Git, [Docker Engine](https://docs.docker.com/engine/install/) e Docker Compose v2 ou superior. Não é necessário instalar Java ou Maven no servidor; o Docker compila e testa o projeto em uma etapa de build e gera a imagem final com Java 21 e `ping` instalado.

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

Inicie os dois contêineres:

```sh
docker compose up -d --build --wait
docker compose ps
docker compose logs --tail=100 app
```

Abra `http://IP_DO_SERVIDOR:8080/hosts` ou `/grupos`. Libere a porta TCP 8080 no firewall da rede/servidor quando necessário. O painel não tem autenticação; este exemplo é para a rede de testes.

O serviço `app` aguarda o healthcheck do PostgreSQL. Ele se conecta a `postgres:5432` pela rede interna do Compose, independentemente de `POSTGRES_HOST`/`POSTGRES_PORT` no `.env` (essas duas opções continuam servindo ao modo Java local). Dentro do contêiner, o Spring sempre escuta em `0.0.0.0:8080`; `APP_PORT` controla a porta publicada no servidor. O banco permanece acessível no servidor apenas por loopback. Seu volume `postgres_data` é preservado.

O contêiner Java roda com usuário sem privilégios de administrador. O Compose permite ICMP para o grupo desse usuário por `ping_group_range`; não precisa de modo privilegiado. O servidor e a rede Docker precisam ter rota para o Zabbix e os hosts monitorados. Para verificar a saída ICMP, substitua o IP abaixo por um host da sua rede:

```sh
docker compose exec app ping -n -c 1 192.0.2.10
```

A saúde da aplicação é verificada por `/actuator/health`, incluindo a conexão com o banco. Falhas ICMP de hosts aparecem nos cards e não deixam o contêiner indisponível. `restart: unless-stopped` reinicia contêineres após falha do processo ou reinício do Docker; um healthcheck com falha, sozinho, não reinicia o contêiner.

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

Atualizações da aplicação preservam o banco. Para parar a aplicação, use `docker compose stop app`; para remover os dois contêineres preservando o banco, use `docker compose down`. Não use `down -v` se quiser manter os dados. Após mudar o `.env`, use `docker compose up -d` para recriar o contêiner com os novos valores; apenas `restart` não atualiza variáveis de ambiente.

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

A coleta passa a usar **1 ping por segundo por host** como padrão (`PING_INTERVAL_MS=1000`). Revise o `.env` existente: valores antigos, como 5000, continuam tendo prioridade. O executor não sobrepõe pings do mesmo host e respeita o timeout e o limite de concorrência. Pings demorados ou excesso de hosts podem reduzir a frequência efetiva; os cálculos usam as amostras reais e a cobertura temporal, sem inventar pacotes durante lacunas de coleta. Ajuste concorrência e recursos do servidor para seu inventário.

O comando `ping` precisa estar instalado/autorizado e os destinos precisam ser alcançáveis pela máquina/contêiner da aplicação. Respostas válidas são gravadas imediatamente. Perdas são confirmadas somente após o timeout; erros de execução, falhas de interpretação e interrupções do coletor ficam separados dos timeouts e não entram no denominador de perda.

Os dados originais continuam no PostgreSQL. **Regra provisória:** retenção configurável, com padrão de 48 horas e limpeza ao iniciar e depois a cada 600 segundos. Registros mais antigos são removidos na próxima execução. Não há alteração no esquema do banco nem novas dependências.

As tabelas, colunas, filtros, ordenação e controles de atualização foram preservados. O cabeçalho permite atualizar agora ou escolher 5, 10, 15 ou 60 segundos, com preferência salva no navegador. Esse intervalo atualiza a tela; ele não muda o intervalo dos pings.

## Regras de perda de pacotes

Os percentuais são **sempre reais**, inclusive abaixo dos limiares. A regra anterior que zerava perdas até 1% foi substituída: `PING_PACKET_LOSS_IGNORE_PERCENT` não é mais utilizado. O percentual é `perdidos / tentativas concluídas elegíveis × 100`; timeout é perda, erro interno não é. Sem tentativas elegíveis, aparece `—`. Os limites são comparados sem arredondamento; apenas a exibição é formatada. A contagem real fica no tooltip.

As janelas são móveis, com intervalo `(agora - duração, agora]`; os contadores não reiniciam na virada do relógio. Minuto e hora são classificados de forma independente.

### Qualidade recente — 1 minuto

| Perdas equivalentes em 60 pacotes | Classificação |
| --- | --- |
| 0 | NORMAL |
| 1 | EVENTO_ISOLADO |
| 2 | ATENCAO |
| 3 a 5 | DEGRADADO |
| 6 ou mais | CRITICO |

Para frequências diferentes, a quantidade equivalente é proporcional aos pings reais: `perdas × 60 / tentativas`. Por exemplo, em coleta de 5 segundos, 1 perda em 12 tentativas equivale a 5 em 60 e classifica como DEGRADADO. A classificação exige cobertura temporal mínima de 80% e pelo menos duas amostras elegíveis; sem isso retorna DADOS_INSUFICIENTES. Intervalos longos sem coleta não contam como tempo coberto. O timeout participa da margem de lacuna permitida, para não confundir uma resposta aguardando confirmação com ausência do coletor.

### Qualidade por janela — 8 horas, 1 hora e 5 minutos

| Percentual real | Classificação |
| --- | --- |
| Até 0,05%, inclusive | NORMAL |
| Acima de 0,05% e abaixo de 0,1% | ATENCAO |
| De 0,1% até abaixo de 0,5% | DEGRADADO |
| 0,5% ou mais | CRITICO |

A linha e o status seguem a maior gravidade entre as janelas de 8 horas, 1 hora e 5 minutos, usando os limites percentuais acima. A recuperação recente não remove o amarelo/vermelho enquanto qualquer janela permanecer acima do permitido. Grupos seguem o pior membro elegível, mantendo os percentuais agregados.

### Cores e situação atual

- **Verde:** perda dentro do limite nas três janelas, sem alerta operacional e com latência normal.
- **Amarelo:** atenção/degradação em qualquer uma das três janelas, perda recente, degradação recorrente, falha/interrupção de coleta ou latência entre 15 e 20 ms.
- **Vermelho:** perda crítica em qualquer uma das três janelas, três perdas consecutivas confirmadas, seis perdas equivalentes em um minuto com dados suficientes, ou quantidade crítica confirmada proporcional à cadência configurada. Latência acima de 20 ms também continua vermelha.
- **Neutro:** dados insuficientes para avaliar o período recente.
- **Cinza:** host sem IP válido; não participa da qualidade nem das médias dos grupos.

A indicação operacional crítica é imediata ao confirmar a terceira perda consecutiva, mesmo durante o aquecimento da janela. Resposta válida reinicia a sequência; erro interno a interrompe. O detalhe do status inclui a maior sequência de perdas do minuto e da hora.

Para **recorrência**, são examinados os últimos três minutos completos do relógio (somente esse detector usa períodos fechados). Dois desses três minutos com pelo menos duas perdas equivalentes cada, com cobertura suficiente, identificam DEGRADAÇÃO RECORRENTE. O minuto ainda incompleto não participa. Isso altera apenas a cor e o status: não há notificações, sistema de eventos ou componentes novos no dashboard.

A situação operacional de perda é mantida até cinco minutos de respostas válidas contínuas, sem nova perda, erro interno ou lacuna excessiva. A recuperação exige novas respostas que comprovem todo esse período; parar o coletor ou deixar de receber amostras não recupera o host. O percentual e a classificação histórica permanecem reais após a recuperação.

Nos grupos, a cor e o status refletem o membro válido com a situação mais grave. Os percentuais das janelas de 8 horas, 1 hora e 5 minutos são calculados pelos totais agregados de pings, e as latências continuam sendo a média das médias dos membros. O detalhe identifica a classificação do pior membro, para não confundir a situação de um host com o percentual agregado. Grupos sem hosts válidos ficam cinza. Os filtros de perda referem-se à situação recente/operacional, sem marcar perda histórica isolada como falha ativa.

## Ajuste rápido no application.properties

As regras estão centralizadas em `src/main/resources/application.properties`, na seção **ANÁLISE DE PERDA DE PACOTES**:

| Propriedade `monitor.loss.*` | Padrão | Finalidade |
| --- | --- | --- |
| minute-window-seconds | 60 | Janela móvel recente |
| hour-window-seconds | 3600 | Janela móvel histórica |
| minute-reference-packets | 60 | Referência para normalizar outras frequências |
| minute-attention-losses | 2 | Início da atenção recente |
| minute-degraded-losses | 3 | Início da degradação recente |
| minute-critical-losses | 6 | Início da condição crítica recente |
| hour-normal-max-percent | 0.05 | Limite normal horário inclusivo |
| hour-degraded-min-percent | 0.1 | Início da degradação horária |
| hour-critical-min-percent | 0.5 | Início da condição crítica horária |
| consecutive-critical | 3 | Sequência crítica imediata |
| recurrence-bucket-seconds | 60 | Duração dos períodos completos de recorrência |
| recurrence-buckets | 3 | Quantos períodos completos analisar |
| recurrence-required-buckets | 2 | Quantos períodos devem apresentar perdas |
| recurrence-bucket-losses | 2 | Mínimo equivalente de perdas por período |
| recovery-seconds | 300 | Tempo contínuo confirmado para recuperar |
| minimum-coverage-ratio | 0.8 | Fração mínima de tempo coberto por amostras |
| minimum-samples | 2 | Mínimo de amostras elegíveis |
| max-gap-multiplier | 3 | Margem de lacuna em múltiplos da cadência; considera também timeout + cadência |
| cache-millis | 1000 | Reutilização de uma análise entre leituras do painel |

As durações avançadas são configuráveis para evolução da regra; as classificações de perda continuam usando as janelas operacionais de 1 minuto/1 hora. As colunas de latência e perda usam janelas fixas de 8 horas, 1 hora e 5 minutos. Para ajustar a cadência use `PING_INTERVAL_MS`, não as janelas. Limites e durações inválidos impedem a inicialização, em vez de aplicar classificações incoerentes.

A retenção provisória continua no mesmo arquivo:

```properties
monitor.retention-hours=${PING_RETENTION_HOURS:48}
monitor.cleanup-interval-seconds=${PING_CLEANUP_INTERVAL_SECONDS:600}
```

Valores no `.env` prevalecem sobre esses dois padrões. Para mudar regras no properties, reconstrua a imagem: `docker compose up -d --build --wait app`. Para alterar só o `.env`, recrie o serviço com `docker compose up -d app`.

### Integração e eficiência

O coletor grava cada resultado concluído e o encaminha ao analisador após a confirmação da escrita. Na inicialização, o histórico recente é restaurado uma vez, com leitura paginada pelo driver PostgreSQL. Depois disso, a apresentação reutiliza buffers compactos por host/IP e uma análise curta em cache, sem consultar o banco a cada atualização de tela. Eventos internos de amostra repetidos são deduplicados pelo ID persistido; amostras atrasadas são ordenadas pelo horário real e amostras futuras não entram nas janelas antes de seu horário. Alterar o IP separa os históricos. Resultados revertidos por rollback não entram no histórico em memória.

O estado visual é reconstruído com o histórico disponível ao reiniciar. Sem amostras suficientes, não se declara recuperação nem normalidade. Não existe serviço de notificações ou registro de alertas externos. Execute uma única instância coletora por banco; não há coordenação distribuída de coleta entre réplicas. Para inventários grandes, dimensione timeout, concorrência e recursos do servidor para a cadência desejada.

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
| PING_TIMEOUT_MS | 2000 | Limite de espera por resposta |
| PING_CONCURRENCY | 64 | Máximo de pings simultâneos |
| PING_RETENTION_HOURS | 48 | Retenção provisória em horas |
| PING_CLEANUP_INTERVAL_SECONDS | 600 | Intervalo provisório de limpeza em segundos |
| SERVER_ADDRESS | 127.0.0.1 | Interface de escuta da aplicação |
| PORT | 8080 | Porta web no modo Java local |
| APP_BIND_ADDRESS | 0.0.0.0 | Interface publicada pelo Docker; use 127.0.0.1 com Nginx local |
| APP_PORT | 8080 | Porta do servidor publicada pelo contêiner Spring |

Os intervalos precisam ser positivos. O painel consulta o banco por host e é destinado inicialmente a inventários pequenos/médios. Não possui autenticação e escuta apenas localmente por padrão. Para acesso em rede, configure `SERVER_ADDRESS` e um proxy com autenticação e HTTPS conforme seu ambiente.

## Testes

```sh
mvn test
```

Para validar os controles de atualização no navegador com Node.js 18 ou superior (apenas para desenvolvimento):

```sh
node --test src/test/js/dashboard.test.cjs
```

Esses testes verificam os quatro intervalos, a atualização manual, a persistência da escolha e a recuperação após erros. Node.js não é necessário para executar a aplicação ou os contêineres.

Os testes Java usam H2 em memória e uma API Zabbix simulada. Incluem perdas isoladas, duas perdas, perdas consecutivas, seis perdas distribuídas, recorrência em períodos completos, limites horários exatos, recuperação, falhas/lacunas do coletor, janelas incompletas, outras cadências, amostras atrasadas/duplicadas/futuras, restauração após reinício e percentuais reais na apresentação. Cobrem limites das cores, persistência de falhas, médias sem zeros artificiais, renderização com/sem hosts, HTMX local, login por usuário/senha, reutilização/renovação da sessão, logout, credenciais inválidas e leitura do `.env`. A integração real exige PostgreSQL disponível, acesso ao seu Zabbix e conectividade ICMP.

Referências: [user.login](https://www.zabbix.com/documentation/current/en/manual/api/reference/user/login), [API Zabbix](https://www.zabbix.com/documentation/7.0/en/manual/api), [imagem oficial PostgreSQL](https://hub.docker.com/_/postgres).

### Dashboard individual e disponibilidade do host

Clique no nome de um host para abrir `/hosts/{id}`. O painel mostra nome, IP, grupos, disponibilidade operacional (principal), técnica, perda, degradação e gráficos históricos. Os períodos aceitos são 1, 2, 4, 8, 12, 24 e 48 horas, contados a partir da consulta. Use “Aplicar período / atualizar” para atualizar os dados.

Os cálculos ficam no backend: H = respostas dentro do limite, D = respostas acima dele, P = pings sem resposta; T = H + D + P. Disponibilidade técnica = 100 × (H + D) / T; operacional = 100 × H / T. Erros internos não entram no denominador. Sem amostras válidas, os percentuais são nulos (`SEM_DADOS`). O total usa contagens de todas as amostras, nunca médias dos percentuais das janelas.

O limite padrão é 20 ms e pode ser alterado por host na própria página (maior que zero, até 60000 ms). A migração atribui 20 ms às amostras anteriores. Novas amostras guardam o limite vigente na persistência, preservando o histórico após alterações. Não há nova coleta de ping. As consultas consideram o IP atual e a retenção disponível. Os gráficos agrupam em 1 minuto (1–2 h), 5 minutos (4–12 h) ou 15 minutos (24–48 h). Janelas vazias ou com apenas falhas internas têm valores nulos; janelas parcialmente coletadas usam somente amostras válidas e não implicam cobertura integral.

API: `GET /api/hosts/{id}/availability?hours=24`, com totais, contagens e série temporal. A configuração é persistida em `host_latency_policy`; cada amostra possui `latency_limit`.
