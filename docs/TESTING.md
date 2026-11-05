# Requisito de qualidade e testes funcionais

Cada funcionalidade deve possuir testes automatizados que executem o código de produção e verifiquem resultados observáveis. Os cenários devem cobrir o fluxo esperado, entradas inválidas, limites relevantes e falhas de dependências. Funcionalidades com estado devem verificar preservação, persistência e recuperação; operações assíncronas devem considerar duplicidade, atraso e concorrência quando aplicável.

Não fazem parte deste requisito testes de layout, estrutura HTML, cores, classes CSS, dimensões ou comparação de screenshots. Comportamentos de interface continuam no escopo: filtros, seleção, teclado, envio de parâmetros, cancelamento de requisições e mensagens de resultado.

## Critérios de aceitação

- Um teste deve falhar se o comportamento que protege for quebrado. Testar uma implementação alternativa criada somente nos testes não comprova o produto.
- Usar preparação, ação e verificação claramente identificáveis. Nomear o teste pelo comportamento e resultado esperado.
- Simular dependências externas, mantendo real o componente avaliado. Para persistência e transações, executar o repositório e o banco de teste; para contratos HTTP, exercitar rotas e serialização.
- Verificar não apenas a exceção, mas também ausência de efeitos indevidos: não salvar seleção inválida, não confirmar lote antes de persistir, não apagar inventário após falha de consulta.
- Cada teste deve funcionar isoladamente e não depender da ordem de execução, da rede externa nem de um Zabbix real. Testes de socket usam loopback. Testes temporais do motor avançam instantes explícitos, sem depender de esperas reais.
- Uma correção deve vir acompanhada de um teste de regressão que reproduza o defeito. Novas funcionalidades devem atualizar esta matriz.
- Executar todas as suítes afetadas e a integração contínua antes da entrega. Testes ignorados e limitações do ambiente devem ser informados; uma suíte não executada não conta como aprovada.
- Cobertura de linhas e ramificações serve para localizar lacunas. Não equivale a cobertura de requisitos nem garante ausência de defeitos; não adicionar asserções sem valor apenas para aumentar percentuais.

## Matriz de funcionalidades e evidências

Os caminhos Java abaixo são relativos a `src/test/java/br/com/scriptping`; JavaScript a `src/test/js`; Go a `collector/internal`.

| Funcionalidade | Evidência automatizada | Cenários protegidos |
| --- | --- | --- |
| Configuração de ambiente | `DotenvTests` | Credenciais com caracteres especiais, precedência do ambiente, arquivo ausente |
| Validação de endereços | `IpAddressTests`, `engine/edge_cases_test.go` | Endereços numéricos, entradas malformadas, ausência de resolução DNS, limites de configuração |
| Classificação de medições | `PingSampleTests`, `PacketLossPolicyTests` | Zero válido, timeout, falha interna, NaN/infinito/negativos, limites percentuais exatos |
| Conexão Zabbix | `ZabbixClientTests` | Login, sessão reutilizada/expirada, renovação limitada, versões 6.0/6.2/6.4/7, versão inválida, credenciais rejeitadas |
| Inventário remoto | `ZabbixClientTests` | Seleções explícitas, grupos ordenados, interface prioritária, host sem interface/grupo, problemas ICMP reconhecidos |
| Configuração persistida | `ZabbixSettingsTests`, `ZabbixSettingsPersistenceTests`, `ZabbixSettingsRepositoryTests` | Restauração, valores iniciais, dados corrompidos, compatibilidade com JSON existente, inserção/atualização via JPA, falha de escrita sem publicar estado não salvo |
| Autorização de coleta | `ZabbixServiceTests`, `ZabbixSettingsTests` | Interseção host/grupo, metadados filtrados, remoção de autorização, senha preservada, troca de servidor limpa inventário |
| Sincronização manual | `ZabbixServiceTests`, `ApiContractTests` | Sem consulta no início, conexão não configurada, falha preserva inventário/data, recuperação, autenticação antes da sincronização |
| APIs de configuração | `ApiContractTests`, `ZabbixSettingsTests` | CSRF ausente/inválido/de outra sessão, ação desconhecida, validação antes de gravar, mensagem segura para erro inesperado |
| API de histórico do host | `ApiContractTests` | Períodos aceitos, padrão 24h, período inválido/não numérico/overflow, host desconhecido, ausência de cache, ausência de dados |
| Dashboard | `DashboardTests`, `AvailabilityDashboardTests`, `DashboardBehaviorTests` | Grupos, períodos, paginação, totais globais, busca com acentos, ordenação com zero/nulo/empates, coleta parcial/sem dados/carregando |
| Ingestão e confirmação | `CollectorPersistenceTests`, `CollectorClientTests` | Repetição idempotente, rollback, timestamps/limites históricos, falha no banco, reconexão, confirmação após persistência |
| Agregação por minuto | `MinuteAggregationTests` | Limites temporais, páginas, atraso, reinício, concorrência, cursor transacional, atualização de minuto já consolidado, troca de IP, esquema idempotente |
| Relatórios e indicadores | `MinuteReportTests` | Média ponderada, lacunas, grupos, pares host/IP exatos, backlog, dados antigos, correção por chegada tardia |
| Retenção | `SampleStoreRetentionTests`, `MinuteAggregationTests` | Corte temporal, configurações inválidas, teto de 48h, exclusão somente após processamento, resumos preservados |
| Atualização do dashboard no navegador | `availability.test.cjs` | Parâmetros, busca com debounce, abortar/ignorar resposta antiga, erro HTTP/rede, recuperação, duração medida sem inventar indisponibilidade |
| Agregação e interação do gráfico | `availability-chart.test.cjs` | Contagens ponderadas, intervalos vazios, ausência de interpolação através de lacunas, consulta por toque/teclado |
| Seleção de grupos | `group-picker.test.cjs` | Busca com acentos, confirmação explícita, Escape, resultado vazio, teclado, atualização do inventário, mesma seleção sem novo evento |
| Seleção de hosts | `zabbix-settings.test.cjs` | Grupos compartilhados, busca, seleção/limpeza parcial, envio completo de seleção grande e token CSRF |
| Detalhes e notificações | `availability-tooltips.test.cjs`, `shell.test.cjs` | Foco/toque, fechamento, pausa da expiração durante leitura, descarte manual, mensagens tratadas como texto |
| Motor ICMP | `engine/engine_test.go`, `engine/edge_cases_test.go` | Cadência, prazo contado do envio, atraso, duplicidade, origem incorreta, limite de pendências, fila expirada, erros de envio/recepção separados de perda |
| Transporte do coletor | `server/server_test.go` | Streaming, replay e confirmação, limite do buffer offline |
| Auditoria do coletor | `audit/log_test.go` | Concorrência, escape, rotação, continuação de arquivo, fila cheia e falha de escrita |
| Inicialização e saúde do coletor | `cmd/collector/main_test.go` (relativo a `collector`) | Configuração numérica inválida, valores padrão, health JSON por socket Unix, rejeição de endereço remoto e proteção de arquivo comum no caminho do socket |
| ICMP Linux real | `engine/icmp_linux_test.go` | Envio/recepção no loopback; exige execução explícita com `ROUTIVA_REAL_ICMP=1` |

## Execução

```sh
mvn test -Dcollector.enabled=false
node --test src/test/js/*.test.cjs
(cd collector && go test -race -count=1 -timeout=120s -coverprofile=coverage.out ./...)
```

Requisitos: Java 21, Maven, Node.js 18+ e Go conforme `collector/go.mod`. Os testes HTTP/gRPC precisam abrir portas locais. Os testes Java usam H2 e simuladores locais; não exigem credenciais reais.

Relatórios Java: `target/surefire-reports` e `target/site/jacoco/index.html`. O JaCoCo mede somente código da aplicação, excluindo os stubs protobuf gerados. A automação `.github/workflows/tests.yml` executa Java, JavaScript e Go, incluindo detector de condições de corrida do Go e ICMP real no loopback do executor Linux, e guarda relatórios.

Para testar também o socket ICMP em Linux configurado para permitir ping:

```sh
cd collector
ROUTIVA_REAL_ICMP=1 go test -count=1 -run TestLinuxPingSocketLoopback ./internal/engine
```

## Limites e cuidados

- H2 não comprova todas as diferenças de PostgreSQL. A suíte não substitui validação de migração com dados e volume reais.
- Simuladores Zabbix comprovam os contratos exercitados, mas não substituem homologação de permissões e conectividade da instalação do cliente.
- Os testes JavaScript executam o código real com uma superfície de eventos simulada. Não são testes de compatibilidade de navegadores nem de aparência.
- O teste ICMP real é separado por depender do sistema operacional. Resultados de suíte padrão devem informar esse teste como ignorado quando a variável não estiver habilitada.
- O ciclo completo de inicialização/encerramento do binário, o streaming Java–Go por socket Unix e a implantação Compose ainda precisam de validação operacional integrada. Não se declara cobertura integral de implantação com base em testes unitários.

## Correções desta revisão

Removida a classe `PingProbe` exclusiva de testes e seus testes que sugeriam validar timeout/interrupção sem executar o coletor. Sua cobertura útil de classificação foi substituída por testes de `PingSample`; prazos de ICMP são verificados no motor Go real. Removidas asserções de aparência HTML, posicionamento CSS e quantidade de elementos gráficos; mantidas as verificações funcionais e de não exposição de senha.

## Resultado da validação desta revisão

Em 04/11/2026:

- Java: `mvn clean test -Dcollector.enabled=false`, 123 testes aprovados, nenhuma falha ou teste ignorado.
- JavaScript: 30 testes aprovados, nenhuma falha ou teste ignorado.
- Go: 28 testes principais aprovados (além dos subcasos parametrizados), com `-race`, incluindo ICMP real e health por socket Unix em contêiner Linux; nenhum teste ignorado.
- JaCoCo: 860 de 987 linhas (87,1%) e 436 de 578 ramificações (75,4%) do código Java medido. Não representa cobertura integral.
- Go, com ICMP real: motor 67,9%, servidor 70,9%, auditoria 78,4%, entrada do coletor 31,6% das instruções. A ferramenta de carga não tem testes automatizados; os arquivos protobuf são gerados.
- A configuração da integração contínua foi adicionada; sua execução remota depende do próximo push/PR. As suítes acima foram executadas localmente.
