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

- `/hosts`: tabela de hosts, com uma linha por host e pesquisa pelo nome. Colunas: nome, IP, grupos, médias de 60 min/1 min, ping atual, perdas na hora/minuto, status e última coleta. A linha mantém as cores de saúde e a tabela permite rolagem horizontal em telas pequenas.
- `/grupos`: cards de grupos, com pesquisa pelo nome do grupo.
- `/`: abre a página de hosts.

Na página de grupos, o link “Ver hosts do grupo” abre `/hosts?groupId=ID`, exibindo apenas os membros daquele grupo. A página de hosts também oferece um seletor de grupos; essa seleção permanece ativa durante a pesquisa, o filtro de status e as atualizações automáticas. O vínculo usa o identificador do grupo, incluindo hosts que pertencem a vários grupos. Grupos removidos ou inexistentes mostram uma lista vazia, sem exibir hosts de outros grupos.

Cada página permite combinar pesquisa por nome e status de ping (todos, verde, amarelo, vermelho, cinza ou neutro). A pesquisa ignora maiúsculas/minúsculas e acentos. Os filtros são mantidos durante a atualização automática, e os resultados acompanham as mudanças de status. A URL reflete os filtros para permitir favoritos e compartilhamento. O botão “Limpar filtros” restaura a lista completa da página. Médias de grupo sempre consideram todos os membros, independentemente da pesquisa.

## Coleta e painel

Cada host recebe um ping ICMP a cada aproximadamente 5 segundos, desde que sua coleta anterior tenha terminado. Até 64 pings executam em paralelo, sem sobreposição por host. Inventários grandes podem aumentar o intervalo efetivo. Os hosts precisam ser alcançáveis pela máquina que executa a aplicação. O executor suporta Linux/macOS e Windows com saída de ping em inglês ou português; outros idiomas podem exigir ajuste do parser.

Resultados, falhas e horários são persistidos no PostgreSQL. A retenção padrão é de 7 dias, com limpeza a cada hora. O IP é guardado junto ao identificador para evitar misturar médias quando o destino muda.

HTMX, servido localmente, atualiza o painel sem F5. O cabeçalho permite escolher 5, 10, 15 ou 60 segundos (padrão: 5 segundos) ou usar “Atualizar agora”. A escolha é salva no navegador e compartilhada entre as páginas de hosts e grupos. As atualizações preservam todos os filtros. O próximo intervalo começa após a conclusão da atualização, evitando acumular consultas quando a resposta demora. Novas medições seguem o intervalo do ping. A página de hosts mostra os dados em tabela; os grupos continuam em cards. As duas visualizações incluem as médias de 60 minutos/1 minuto, ping atual, perdas e status.

As janelas são móveis e usam apenas respostas válidas; falhas nunca contam como zero. Antes de completar uma hora, a média usa os dados disponíveis. Grupos exibem a média das médias por host em cada janela, com peso igual para cada host. O atual do grupo usa os últimos pings válidos e recentes. Hosts em vários grupos participam de todos eles.

- Vermelho: qualquer métrica acima de 20 ms, perda de pacote nos últimos 60 minutos, ou host sem resposta/com coleta atrasada. Grupos ficam vermelhos se qualquer membro estiver nessa condição.
- Amarelo: qualquer métrica entre 15 e 20 ms, inclusive, sem condição vermelha.
- Verde: todas as três métricas abaixo de 15 ms.
- Cinza: host sem IP válido (ausente, malformado ou endereço não especificado como `0.0.0.0`/`::`). Não participa das médias, da avaliação de falhas nem da espera por dados do grupo. Um grupo sem nenhum host com IP válido fica cinza. O filtro cinza é separado de “aguardando dados”.
- Neutro: aguardando dados, sem condição amarela ou vermelha. Grupos não ficam verdes enquanto aguardam a primeira coleta de um membro.

Uma coleta é atrasada depois do maior valor entre 15 segundos e três intervalos de ping mais o timeout. O atual de um host atrasado aparece indisponível. Horários do painel usam America/Sao_Paulo.

## Perda de pacotes e status dos cards

A janela de perda é de **60 minutos móveis**. Qualquer ping perdido dentro dela mantém o card vermelho, mesmo que o ping atual já tenha voltado a responder. O card exibe dois percentuais, lado a lado: **Perda · última hora** e **Perda · último minuto**. Cada um usa apenas os pings da respectiva janela móvel. A contagem fica disponível ao passar o mouse sobre o percentual. Sem pings elegíveis na janela, aparece `—`, em vez de uma porcentagem zero. O alerta de perda desaparece quando a última perda sai da janela; a cor ainda depende da latência e da disponibilidade das coletas.

O rodapé mostra:

- **Normal**: três latências abaixo de 15 ms, sem perdas recentes, com coleta disponível e recente.
- **Latência alta**: pelo menos uma das três latências a partir de 15 ms, sem perdas; amarelo de 15 a 20 ms, vermelho acima de 20 ms.
- **Com perda de pacotes**: pelo menos uma perda nos últimos 60 minutos, sem latência alta; vermelho.
- **Com latência e perda de pacotes**: as duas condições ao mesmo tempo; vermelho.

“Sem IP válido”, “Aguardando dados”, “Coleta atrasada” e “Falha na coleta” continuam diferenciados. Timeout e ausência de resposta ICMP contam como perdas. Erros locais ao executar o ping e coletas interrompidas não contam como pacotes perdidos nem entram no denominador da porcentagem. O detalhe da última falha continua visível abaixo do status quando necessário. As médias de latência continuam usando apenas respostas recebidas.

Nos grupos, qualquer perda de um membro com IP válido deixa o card vermelho. Para cada janela (hora e minuto), a porcentagem do grupo é o total de pacotes perdidos dividido pelo total de pings válidos para essa contagem, somando todos os membros. A avaliação de latência usa as três médias exibidas no card do grupo. Hosts sem IP válido continuam excluídos de todas essas avaliações. Grupos sem membros válidos ficam cinza.

Os filtros de situação permitem buscar Normal, Latência alta, Com perda de pacotes e Com latência e perda de pacotes. Os filtros de latência e perda também incluem os casos em que ambas ocorrem.

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
| PING_INTERVAL_MS | 5000 | Intervalo de disparo das coletas |
| PING_TIMEOUT_MS | 2000 | Limite de espera por resposta |
| PING_CONCURRENCY | 64 | Máximo de pings simultâneos |
| PING_RETENTION_HOURS | 168 | Retenção do histórico |
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

Os testes Java usam H2 em memória e uma API Zabbix simulada. Cobrem limites das cores, persistência de falhas, médias sem zeros artificiais, renderização com/sem hosts, HTMX local, login por usuário/senha, reutilização/renovação da sessão, logout, credenciais inválidas e leitura do `.env`. A integração real exige PostgreSQL disponível, acesso ao seu Zabbix e conectividade ICMP.

Referências: [user.login](https://www.zabbix.com/documentation/current/en/manual/api/reference/user/login), [API Zabbix](https://www.zabbix.com/documentation/7.0/en/manual/api), [imagem oficial PostgreSQL](https://hub.docker.com/_/postgres).
