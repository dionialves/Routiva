# ScriptPing

Monitor de latência com Java 21, Spring Boot, Thymeleaf, HTMX e PostgreSQL em Docker.

## Executar

Pré-requisitos: JDK 21, Maven 3.6.3+, Docker com Compose e comando `ping` instalado e autorizado a enviar ICMP. A aplicação roda na máquina local e o PostgreSQL roda em contêiner.

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

O usuário precisa ter acesso de leitura aos hosts/grupos e permissão para usar a API. O cliente usa `host.get`, `selectHostGroups` e `selectInterfaces`. Coleta nome visível, identificador e IP da interface principal de agente, quando disponível, com fallback para outra interface. Inclui hosts habilitados; todos os grupos de cada host são preservados. Hosts sem IP válido aparecem com falha explícita. Interfaces configuradas somente por DNS precisam ter o IP preenchido.

O inventário é carregado ao iniciar e sincronizado a cada 60 segundos. Em falha de sincronização, mantém o último inventário e informa o problema no painel. Hosts removidos deixam de ser monitorados na próxima sincronização; seus registros permanecem até expirar a retenção. Uma coleta em andamento pode terminar após a remoção.

## Páginas e filtros

- `/hosts`: cards de hosts, com pesquisa pelo nome do host.
- `/grupos`: cards de grupos, com pesquisa pelo nome do grupo.
- `/`: abre a página de hosts.

Na página de grupos, o link “Ver hosts do grupo” abre `/hosts?groupId=ID`, exibindo apenas os membros daquele grupo. A página de hosts também oferece um seletor de grupos; essa seleção permanece ativa durante a pesquisa, o filtro de status e as atualizações automáticas. O vínculo usa o identificador do grupo, incluindo hosts que pertencem a vários grupos. Grupos removidos ou inexistentes mostram uma lista vazia, sem exibir hosts de outros grupos.

Cada página permite combinar pesquisa por nome e status de ping (todos, verde, amarelo, vermelho ou neutro). A pesquisa ignora maiúsculas/minúsculas e acentos. Os filtros são mantidos durante a atualização automática, e os resultados acompanham as mudanças de status. A URL reflete os filtros para permitir favoritos e compartilhamento. O botão “Limpar filtros” restaura a lista completa da página. Médias de grupo sempre consideram todos os membros, independentemente da pesquisa.

## Coleta e painel

Cada host recebe um ping ICMP a cada aproximadamente 5 segundos, desde que sua coleta anterior tenha terminado. Até 64 pings executam em paralelo, sem sobreposição por host. Inventários grandes podem aumentar o intervalo efetivo. Os hosts precisam ser alcançáveis pela máquina que executa a aplicação. O executor suporta Linux/macOS e Windows com saída de ping em inglês ou português; outros idiomas podem exigir ajuste do parser.

Resultados, falhas e horários são persistidos no PostgreSQL. A retenção padrão é de 7 dias, com limpeza a cada hora. O IP é guardado junto ao identificador para evitar misturar médias quando o destino muda.

HTMX, servido localmente, atualiza o painel a cada 2 segundos sem F5. Novas medições seguem o intervalo do ping. Cada card mostra nome, IP/grupos (ou quantidade de hosts), média de 60 minutos, média de 1 minuto e último ping.

As janelas são móveis e usam apenas respostas válidas; falhas nunca contam como zero. Antes de completar uma hora, a média usa os dados disponíveis. Grupos exibem a média das médias por host em cada janela, com peso igual para cada host. O atual do grupo usa os últimos pings válidos e recentes. Hosts em vários grupos participam de todos eles.

- Vermelho: qualquer métrica acima de 20 ms, ou host sem resposta/com coleta atrasada. Grupos ficam vermelhos se qualquer membro estiver nessa condição.
- Amarelo: qualquer métrica entre 15 e 20 ms, inclusive, sem condição vermelha.
- Verde: todas as três métricas abaixo de 15 ms.
- Neutro: aguardando dados, sem condição amarela ou vermelha. Grupos não ficam verdes enquanto aguardam a primeira coleta de um membro.

Uma coleta é atrasada depois do maior valor entre 15 segundos e três intervalos de ping mais o timeout. O atual de um host atrasado aparece indisponível. Horários do painel usam America/Sao_Paulo.

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
| PORT | 8080 | Porta web |

Os intervalos precisam ser positivos. O painel consulta o banco por host e é destinado inicialmente a inventários pequenos/médios. Não possui autenticação e escuta apenas localmente por padrão. Para acesso em rede, configure `SERVER_ADDRESS` e um proxy com autenticação e HTTPS conforme seu ambiente.

## Testes

```sh
mvn test
```

Os testes usam H2 em memória e uma API Zabbix simulada. Cobrem limites das cores, persistência de falhas, médias sem zeros artificiais, renderização com/sem hosts, HTMX local, login por usuário/senha, reutilização/renovação da sessão, logout, credenciais inválidas e leitura do `.env`. A integração real exige PostgreSQL disponível, acesso ao seu Zabbix e conectividade ICMP.

Referências: [user.login](https://www.zabbix.com/documentation/current/en/manual/api/reference/user/login), [API Zabbix](https://www.zabbix.com/documentation/7.0/en/manual/api), [imagem oficial PostgreSQL](https://hub.docker.com/_/postgres).
