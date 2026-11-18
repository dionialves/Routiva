# ScriptPing — Agent Quick Reference

## Stack
- Java 21, Spring Boot 3.5.7, Maven, PostgreSQL (H2 in tests)
- Go collector: gRPC + protobuf, ICMP ping engine
- Frontend: Thymeleaf + HTMX + vanilla JS (Node.js for JS tests)

## Commands

```sh
# Run locally (PostgreSQL in Docker, Java locally)
docker compose up -d --wait postgres
mvn spring-boot:run

# Full Docker stack (app + collector + postgres)
docker compose up -d --build --wait

# Build and package JAR (from directory with .env)
mvn clean package
java -jar target/scriptping-1.0.0.jar

# Java tests (H2 in-memory, Zabbix mocked, collector disabled)
mvn test -Dcollector.enabled=false

# JS tests (Node.js 18+)
node --test src/test/js/*.test.cjs
```

## Architecture

- **Entrypoint**: `ScriptPingApplication.java` — Spring Boot app, two `ThreadPoolTaskScheduler` beans (indicator + monitor)
- **Go collector**: `collector/cmd/collector/main.go` — ICMP engine, gRPC server (streaming `Connect` + unary `Health`)
- **gRPC proto**: `src/main/proto/collector.proto` — generates Java stubs (protobuf-maven-plugin) and Go code (`collector/gen/`)
- **DB schema**: `src/main/resources/schema.sql` — idempotent DDL (create + alter + drop retired tables)
- **Config loading**: `.env` via `dotenv-java` (loaded before Spring boot), env vars override `.env`
- **Three Docker services**: `app` (Spring), `collector` (Go), `postgres` (PostgreSQL 17 Alpine)

## Key files

| Area | File |
|---|---|
| Java config | `src/main/resources/application.properties` |
| YAML overrides | `src/main/resources/application.yml` |
| Docker compose | `compose.yaml` |
| Java Dockerfile | `Dockerfile` |
| Collector Dockerfile | `collector/Dockerfile` |
| Collector Go module | `collector/go.mod` (Go 1.24.0) |
| .env template | `.env.example` |

## Environment variables (commonly changed)

- `ZABBIX_URL` / `ZABBIX_USERNAME` / `ZABBIX_PASSWORD` — Zabbix API integration
- `POSTGRES_*` — database connection (overridden in compose for container-to-container)
- `PING_INTERVAL_MS` (default 1000) — ping cadence per host
- `PING_TIMEOUT_MS` (default 1500) — per-ping timeout
- `COLLECTOR_ADDRESS` — gRPC endpoint (Unix socket by default)
- `SERVER_ADDRESS` / `PORT` — local bind; `APP_BIND_ADDRESS` / `APP_PORT` — Docker publish

## Gotchas

- `.env` is `.gitignore`d; copy from `.env.example`. Use single quotes for passwords containing `#`, `$`, or spaces.
- `mvn test` sets `collector.enabled=false` — the collector is not available in tests.
- JS tests use `.cjs` extension and Node.js 18+. No npm/pnpm — they run directly with `node --test`.
- Protobuf compilation is automatic via Maven plugin. Do not manually run `protoc`.
- `schema.sql` runs `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` — safe to run repeatedly. Retired checkpoint tables are dropped.
- In Docker Compose, `POSTGRES_HOST` is overridden to `postgres` (service name). Local `.env` values only apply to local Java runs.
- `docker compose down -v` destroys the `postgres_data` volume. Use `down` (without `-v`) to preserve data.
- Changing `.env` requires `docker compose up -d` (not just `restart`) to pick up new values.
- `collector_socket` volume maps to `/run/routiva` in both app and collector containers for Unix socket sharing.

## Code style (from .editorconfig)

- JS: 2-space indent
- Java: 4-space indent
- Go: tab indent, 4-space
- SQL / Dockerfile: 4-space indent

## Prettier (JS)

```sh
# Prettier config at .prettierrc.json — 2 spaces, 100 char printWidth, single quotes
```

## Running the collector health check

```sh
docker compose exec -T collector routiva-collector --health
# Formatted:
docker compose exec -T collector routiva-collector --health | jq '{coletor: .collector_id, hosts_monitorados: .metrics.active_hosts, pings_pendentes: .metrics.pending}'
```

## Logs

- Aggregation: `logs/indicators.log` (local) or `/var/log/routiva/indicators.log` (Compose)
- Collector events: `/var/log/routiva/collector.log` (inside collector container)

## Zabbix integration notes

- Auto-detects Zabbix version: 6.0/6.2 uses JSON-RPC `auth` field; 6.4+ uses Bearer header.
- Inventory syncs every 60s. Failed sync keeps last inventory and shows error on dashboard.
- No API token needed — session-based auth via `user.login` / `user.logout`.

## UI standard — host and host-group search

- Host and host-group selection must use the searchable dropdown checklist shown in the existing shared multi-picker: dark panel, search at the top, cyan checkboxes, a select-all row, scrollable results, and a selected-count summary.
- Reuse `templates/multi-picker.html`, `static/multi-picker.js`, and `static/multi-picker.css`; do not introduce a new visual style per page. The Availability adapter in `group-picker.js` must use the same shared stylesheet.
- Search ignores case and accents, changes only the visible options, and preserves selection. Multiple selection keeps the menu open. Support keyboard navigation, Escape, focus indication, empty results, and closing on outside click.
- Use this standard for new and updated host/group selectors. Preserve single selection where the operation accepts only one host or group (for example a map element). Plain text search over an already visible table remains a table filter.

- Availability filter values (including Period and Host group) share the application font family at 13px, weight 600, matching the Host group selector. Keep typography in their common CSS rule rather than per-control overrides.

- Page-level host, host-group, and period queries use `query-filters.css`: a shared dark bordered bar, icon and visible label above each control, 46px controls, 13px/600 values, and separators between fields. Use `query-filter-bar`, `query-filter-row`, `query-filter-field`, and `query-filter-label` in templates. Stack fields on small screens. This layout applies to Availability, Incidents, Access Points, group lists/details, and host details. Settings and the Access Point import modal are explicitly excluded; never target their controls through global selectors.

- The Map library search also uses the shared page-query layout, with the search icon and label above the field.

- All application action buttons follow the Zabbix settings reference through `buttons.css`: `system-button system-button-primary` for blue primary actions and `system-button` for dark bordered secondary/cancel actions. Use 36px height, 12px/600 typography, 6px corners, 16px leading icons, and an 8px icon/text gap. Do not override these styles per page or enlarge heading actions. `query-primary-action` is only a placement hook for primary page actions (including “Importar”, “Novo mapa”, and “Novo usuário”).

- Place “Importar” (Access Points) and “Novo mapa” in the upper-right page heading, aligned with the title, outside the filter bar. Use `page-action-heading` and preserve the shared `query-primary-action` style.

## Design system do front-end

Toda página Thymeleaf carrega a base no head com `th:replace="~{design-system :: base}"`. O fragmento em `templates/design-system.html` concentra os endereços e versões dos estilos compartilhados. A base importa `static/design-tokens.css`, fonte da paleta e da família tipográfica. Use propriedades como `var(--panel-bg)`, `var(--text)`, `var(--border)` e `var(--focus)` inclusive nas variantes. Mantenha os componentes no contexto visual da aplicação com os fragmentos sidebar, topbar e icon de shell.html.

| Necessidade | Implementação a reutilizar |
|---|---|
| Layout | Fragmento layout e page-layout.css para títulos, espaçamento e ações da página. |
| Filtros | Para períodos use design-system :: period(controlId, fieldName, selectedMinutes). Fragmentos filters e picker, classes query-filter-* e multi-picker.html (checklist(kind, label, iconName, allLabel, searchLabel) para seletores genéricos de múltipla seleção). Em modais, o checklist mantém as linhas horizontais pelo estilo compartilhado de multi-picker.css. Use multi-picker.js para a interação e query-filters.js para as ações comuns. Pesquisas textuais usam input[type=search] diretamente em query-filter-field e o estilo compartilhado em query-filters.css (Clientes Rádio, Usuários e biblioteca de Mapas). |
| Seleção única com miniatura | `single-picker.js` e `single-picker.css`, via `setupSinglePicker(select)`: pesquisa por nome, miniatura e popover acima de modais; preserve o select como fonte dos valores. |
| Ativação em formulários | `input[type=checkbox][role=switch].system-switch`, em form-dialog.css; estado explícito independente do ícone escolhido. |
| Sinal wireless | Barras em signal-indicators.css, compartilhadas com APs; Clientes Rádio usa signal-bars-trailing e signalSeverity obtido da leitura original, sem recalcular o diagnóstico da conexão. |
| Indicador simples | Fragmento indicator(title, valueId, description, descriptionId). Passe um ID único por valor atualizado por JS e null para descrição estática. Para o conjunto de disponibilidade com explicações, inclua availability-components :: metrics e availability.css. |
| Gráfico | availability-components :: historyChart(groupView) fornece título, controles, legenda e área de desenho. availability-chart.js desenha a série de disponibilidade. performance-chart.js usa AvailabilityView e AvailabilityChart para gráficos de desempenho. Carregue as dependências antes do consumidor e use history-charts.css para os controles. Consulte availability.html e diagnostics.html como exemplos. |
| Tabela | Use design-system :: dataTable(label, sections) para listas, passando thead e tbody como um fragmento local. A base define cabeçalhos, células e rolagem. Para o layout compacto de Clientes Rádio e Mapas, use system-table-compact na seção da lista e --compact-table-min-width para a largura mínima. Declare as colunas no template consumidor, use th com scope e atualize aria-sort quando houver ordenação. |
| Tráfego no mapa | `map-traffic.js` reutiliza o path de `MapLinks` para pulsos luminosos SVG A → B e legenda TX/RX. Taxas vêm dos deltas de contadores de 64 bits; capacidade vem da interface. Respeite `prefers-reduced-motion` e a preferência global “Animar tráfego”. Dados ausentes não equivalem a zero nem a porta DOWN. `map-connection-tooltip.js` apresenta os detalhes das conexões mapeadas em um painel HTML, com posição limitada à viewport e acesso por foco; status operacional exige leitura recente e válida, independente das métricas de tráfego. |
| Menu de ações do equipamento | `equipment-action-menu.css` compartilha o visual entre Ações do AP e `host-menu.js`; hosts cadastrados como AP também oferecem painel e avaliação de latência. |
| Botão | Use design-system :: action(buttonId, iconName, label) para uma ação secundária simples. Inclua o fragmento actions e aplique system-button e as variantes descritas acima. Forneça nome acessível para ações apenas com ícone. Para exclusão use a variante compartilhada system-button-danger. |
| Paginação | Use system-pagination (pagination.css, carregado pela base): Anterior à esquerda, intervalo “Exibindo início–fim de total registros” no centro e Próxima à direita. Preserve os tamanhos de página de cada tela e desabilite as ações nos limites. |
| Modal | Para formulários extensos, use `form-dialog-frame` com `form-dialog-header`, `form-dialog-content` e `form-dialog-footer` (form-dialog.css): cabeçalho e rodapé fixos, conteúdo com rolagem. Abas em formulários reutilizam analysis-tabs de analysis-dialog.css, com navegação por setas. Para análise com abas, use analysisDialog e analysisDialogStyles de design-system, com analysis-header, analysis-tabs, analysis-body e analysis-footer (grupos de botões do rodapé usam settings-actions, sem separadores adicionais); o padrão é compartilhado por Clientes Rádio e Avaliação de latência. Inclua o fragmento dialogStyles. Use design-system :: formDialog(dialogId, titleId, descriptionId, content) com conteúdo local, título ligado por aria-labelledby, connection-form e settings-actions. Ajuste a largura com --form-dialog-width. Para seções de formulários longos use design-system :: formSection(titleId, title, iconName, content), com form-dialog-section e form-dialog-section-fields compartilhados. Abra com showModal() e mantenha o fechamento por teclado. |

Antes de criar uma tela, procure o componente no catálogo e confira um consumidor em produção. O CSS da página cuida da composição e das particularidades daquela tela. Para uma variação recorrente, acrescente uma classe modificadora ao CSS do componente. Extraia marcação repetida para um fragmento com parâmetros e comportamento repetido para um módulo JS usado por todos os consumidores. Preserve IDs referenciados por scripts, formulários, HTMX e atributos ARIA.

Ao introduzir um padrão novo, implemente-o na camada compartilhada, migre as ocorrências equivalentes e atualize esta tabela na mesma alteração. Verifique telas pequenas, foco, teclado e os estados vazio, carregando e erro quando aplicáveis. Execute os testes JS e, para mudanças nos templates, os testes Maven.

## Switches no mapa

- `map-switch.js` compartilha composição SVG, geometria e pontos de conexão entre o mapa e a prévia. Reutilize `MapSwitch` para equipamentos com portas; imagens em `static/map-assets/`. Portas têm IDs estáveis, coordenadas locais e uma conexão por porta.

- `map-links.js` concentra a geometria das conexões: reta ou caminho com cantos editáveis. Os pontos intermediários são coordenadas do mapa; extremidades continuam vinculadas ao equipamento/porta.

- Conexões separam `style` (straight/corners: Direta/Com ponto) de `lineStyle` (straight/curved: Reta/Curvada). `MapLinks.path` suaviza os cantos sem alterar pontos e âncoras; duplo clique abre o modal compartilhado de configuração.

- Switches usam slots de interfaces (`id`, `name`, `connector`, `capacity`, `count`). `MapSwitch.slotsFor` migra portas antigas; `portsForSlots` preserva IDs por slot e numera os nomes. Conector e capacidade são independentes: `connector` identifica electrical, sfp, sfpplus, sfp28, qsfpplus, sfp56, qsfp28, qsfp56, qsfpdd ou qsfpdd800; `capacity` usa 10m, 100m, 1g, 10g, 25g, 40g, 50g, 100g, 200g, 400g ou 800g (`unknown` quando não identificada). `MapSwitch.normalizeSlot` migra as capacidades legadas sem alterar IDs; a arte da porta depende do conector. A capacidade máxima das portas SFP pode ser identificada pelo nome nativo (`default-name`, ou `name` quando ausente): sfp=1G, sfp-sfpplus=10G, sfp28=25G, sfp56=50G, qsfpplus=40G, qsfp28=100G, qsfp56=200G e qsfp56-dd=400G. Nas demais portas, use `supported`/`supported-rates` de `/interface/ethernet/monitor ... once`; nunca use `rate` ou `speed`. Em portas elétricas sem `supported`/`supported-rates`, use o maior modo do próprio `advertising` (pode ser limitado pela configuração); nunca use `link-partner-advertising`. Nomes não reconhecidos sem essas informações ficam como `unknown`. QSFP representa a capacidade física do conector, não a velocidade individual de uma lane em breakout. `speed` registra a leitura atual do link. Agrupe portas por slot, com espaçamento compacto uniforme e numeração de baixo para cima.

- O seletor de ícones reutilizável é `single-picker.js`/`single-picker.css`: seleção única com miniatura, pesquisa e popover nativo acima de modais. A biblioteca vem das opções do select, sem entradas fictícias. `interfacesEnabled` é independente de `icon` e preserva portas/slots quando desativado; não descarte dados ao trocar o ícone.

- O LED único do switch usa o último ping do coletor, via `/api/maps/ping-status`, atualizado a cada 2s sem redesenhar o mapa. Resposta = verde; timeout ICMP = vermelho; edição, erro de coleta ou amostra com mais de 10s = cinza. Não use severidade de incidentes ou médias de latência para colorir esse LED.

- `switchColor` personaliza apenas o gabinete do switch, via filtro SVG compartilhado em `MapSwitch.draw`. Preserve `color` e o comportamento do círculo; nunca aplique a personalização às portas ou ao LED de ping. `null` restaura a arte original.

- Categorias do seletor de ícones seguem o vínculo do elemento: grupo de hosts oferece Círculo; host oferece Switch; elemento livre mantém ambos. `data-bindings` nas opções declara a biblioteca por categoria; trocar tipo só ajusta as opções e não apaga slots ou `switchColor`.

- Portas conectadas usam arte própria para Elétrica, SFP1G/10G, SFP40G e SFP100G, em `map-assets/*-plugged.svg`. A ocupação deriva dos links, sem estado persistido separado. `MapSwitch.portVisual` compartilha arte e centro de encaixe do cabo cinza; `endpoint` e `cableDirection` respeitam a fileira. Portas 40G usam moldura cinza também quando vazias.

## Conexões mapeadas

- `Link.connection` mantém nome, hosts e interfaces dentro do próprio mapa; `Link.mappedConnectionId` identifica essa ligação local. Não há cadastro ou menu separado de conexões. Alterações são persistidas ao salvar o mapa e devem corresponder aos hosts A/B da ligação. Conexões simples preservam o comportamento anterior.
- Interfaces Ethernet MikroTik usam autenticação por host (`hosts.HostAuthentication` / `HostConnections`), sem exigir cadastro em Access Points. Credenciais gerais do `.env` não são usadas nas conexões mapeadas. Nomes de interface persistidos; IDs RouterOS resolvidos novamente a cada coleta.
- `/api/maps/{mapId}/connections/readings` consulta sob demanda, com cache por host/interface de 9,5s; mapas consultam a cada 10s enquanto visíveis, sem sobrepor consultas. Erro de API e leitura antiga não significam porta DOWN.
- Leituras de interfaces mapeadas reutilizam uma sessão RouterOS por host em `MappedRouterSessions`, com consultas serializadas, reconexão única após queda e encerramento após 45s ociosos (verificação a cada 10s). IP ou credenciais alterados substituem a sessão; demais consultas mantêm conexões temporárias. Não há coleta autônoma após fechar o mapa.

## Cadastro local de hosts

- `hosts` centraliza o espelho persistido do cadastro Zabbix (`app_host`), autenticação exclusiva do app (`host_authentication`) e validação de acesso MikroTik/Ubiquiti.
- Listagem, opções e abertura do modal usam o banco local. Sincronização inicial, periódica (60s) ou manual reconcilia o Zabbix; alterações compartilhadas só são consideradas salvas após confirmação remota.
- Exclusões são replicadas nos dois sentidos: após uma consulta completa bem-sucedida, hosts ausentes são removidos do cadastro, autenticação, APs e vínculos de mapas. Falhas da API preservam o cadastro local. Retirar a seleção de monitoramento não exclui o host.
- Autenticação fica na aba do host. Importar um AP testa a configuração do host; excluir a classificação AP preserva as credenciais do host.
- A migração reutiliza a chave `ap.secrets-key-file` (padrão `./data/ap-secrets.key`); preserve esse arquivo junto aos backups. Senhas e snapshots de configuração Zabbix são protegidos com a mesma chave.
- Abas em formulários também reutilizam `.analysis-tabs` de `analysis-dialog.css`, com `role=tablist`, `tab`, `tabpanel`, foco e navegação por setas.
