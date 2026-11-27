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
- Multi-selection lists show selected items first, preserving the original order within selected and unselected items and keeping the select-all row at the top.
- Search ignores case and accents, changes only the visible options, and preserves selection. Multiple selection keeps the menu open. Support keyboard navigation, Escape, focus indication, empty results, and closing on outside click.
- Use this standard for new and updated host/group selectors. Preserve single selection where the operation accepts only one host or group (for example a map element). Plain text search over an already visible table remains a table filter.

- Availability filter values (including Period and Host group) share the application font family at 13px, weight 600, matching the Host group selector. Keep typography in their common CSS rule rather than per-control overrides.

- Page-level host, host-group, and period queries use `query-filters.css`: a shared dark bordered bar, icon and visible label above each control, 46px controls, 13px/600 values, and separators between fields. Use `query-filter-bar`, `query-filter-row`, `query-filter-field`, and `query-filter-label` in templates. Stack fields on small screens. This layout applies to Availability, Incidents, Access Points, group lists/details, and host details. Settings and the Access Point import modal are explicitly excluded; never target their controls through global selectors.

- The Map library search also uses the shared page-query layout, with the search icon and label above the field.

- Map library favorites are stored per authenticated user in `network_map_favorite`, independent of map revision. Favorites are listed before other maps (most recently favorited first), including before pagination; other maps retain updated-at ordering. Both USER and ADMIN can change their own favorites.

- All application action buttons follow the Zabbix settings reference through `buttons.css`: `system-button system-button-primary` for blue primary actions and `system-button` for dark bordered secondary/cancel actions. Use 36px height, 12px/600 typography, 6px corners, 16px leading icons, and an 8px icon/text gap. Do not override these styles per page or enlarge heading actions. `query-primary-action` is only a placement hook for primary page actions (including “Importar”, “Novo mapa”, and “Novo usuário”). Use `system-button-loading` with `aria-busy="true"` and `disabled` to replace the leading icon with the shared loading spinner during an action.

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
| Tabela | Use `design-system :: dataTable(label, sections)` para listas e históricos, com Clientes Rádio como referência de densidade, cabeçalho, rolagem e links. Passe `thead`, `tbody` e, quando necessário, `caption` e `colgroup` no fragmento local. Use `dataTableRegion` quando a região precisar de ID, classe ou estado inicial próprios e `dataTableContent` dentro de uma região especializada, como o popover de comentários. `data-table.css` centraliza a aparência e `system-table-link` estiliza links e ações equivalentes. Posicione o link principal na primeira coluna de dados, depois de controles como favorito ou seleção. A tela define colunas, larguras, `--data-table-min-width` e ações específicas. `table-sort.js` habilita a ordenação por cabeçalho para tabelas locais e mantém `aria-sort`. Páginas com paginação própria usam `TableSort.bind` para ordenar o conjunto filtrado antes do recorte, ou enviam o critério ao servidor. Use `data-sort-value` para valores que não podem ser extraídos da apresentação e `data-sort-disabled` nas colunas de ações. |
| Tráfego no mapa | `map-traffic.js` reutiliza o path de `MapLinks` para pulsos luminosos SVG somente A → B; tráfego aparece apenas no tooltip ao passar o mouse ou focar a conexão, sem legenda no mapa. Para conexões sem estilo visual salvo, enlaces entre duas portas Wireless e entre Rádio e Grupo de Hosts (pela porta Wireless ou sem porta explícita) usam pontos imóveis e cápsulas luminosas somente A → B, com a mesma paleta de utilização; a base segue a utilização válida (cinza sem dados) e a capacidade manual da porta de origem é usada exclusivamente em enlaces wireless. Nesse modo, conexões cabeadas preservam a linha contínua. Na visualização, taxas e estado vêm dos itens do Zabbix via `MapZabbixReadings`; capacidade de enlaces cabeados usa exclusivamente o item válido `net.if.speed` da origem; wireless usa exclusivamente a capacidade configurada na interface de origem, sem fallback. Linha e tooltip compartilham `MapTraffic.connectionCapacity`, independentemente do estilo visual. Respeite `prefers-reduced-motion` e a preferência global “Animar tráfego”. Dados ausentes não equivalem a zero nem a porta DOWN. `map-connection-tooltip.js` apresenta os detalhes das conexões mapeadas em um painel HTML, com posição limitada à viewport e acesso por foco; status operacional exige leitura recente e válida, independente das métricas de tráfego. |
| Menu de ações do equipamento | `equipment-action-menu.css` compartilha o visual entre Ações do AP e `host-menu.js`; hosts cadastrados como AP também oferecem painel e avaliação de latência. |
| Botão | Use design-system :: action(buttonId, iconName, label) para uma ação secundária simples. Inclua o fragmento actions e aplique system-button e as variantes descritas acima. Forneça nome acessível para ações apenas com ícone. Para exclusão use a variante compartilhada system-button-danger. |
| Cabeçalho da tabela | Nas tabelas de página, use `design-system :: tableHeading(titleId, title, iconName, tableBodyId)` com o ícone do menu, título e contagem dos resultados filtrados. Os submenus de Configuração usam o ícone do menu pai. Filtros e ações ficam fora do cabeçalho. Na Integração Zabbix, a tabela de seleção de hosts mantém apenas o título do grupo com sua quantidade de hosts. O resultado da importação mantém o ícone de arquivo e o status da execução no cabeçalho. |
| Estados da tabela | `dataTableContent` inclui o corpo de estado. Use `TableState.loading(bodyId)`, `done(bodyId, descriçãoOpcional)` e `error(bodyId, mensagemOpcional)` nos limites das consultas. O componente mantém linhas durante atualizações e exibe aviso de dados desatualizados em falhas com registros. A paginação fica desativada durante consultas e informa “Exibindo 0 de 0 registros” após uma resposta vazia. Listas locais detectam mudanças no corpo automaticamente. |
| Paginação | Toda tabela usa `design-system :: tablePagination(label, navId, unit, tableBodyId, localPageSize)`, com `pagination.css` e `table-pagination.js` na base. O intervalo, primeira, anterior, até cinco páginas numeradas, próxima e última ficam juntos à direita. Exiba a página 1 selecionada e desative os controles quando houver uma página ou nenhum registro. Para tabelas que já controlam a consulta ou o recorte, passe `null` em `localPageSize` e use `TablePagination.render` com a página atual (base 1), tamanho, total e callback. Para linhas já carregadas no DOM, informe `localPageSize` e use `TablePagination.reset` quando filtros ou pesquisa mudarem. Quando o controle é criado durante a renderização, clone um `template` com o fragmento. Preserve os tamanhos de página existentes. Na lista principal de Incidentes, exiba paginação somente nos agrupamentos com mais de 50 registros. |
| Abas em modais | `.analysis-tabs` em modal-tabs.css (carregado pela base), com tokens compartilhados, contorno completo, destaque ciano, rolagem horizontal e visibilidade da seleção via modal-tabs.js. Preserve roles e a navegação por teclado dos consumidores; títulos, ícones e subtítulos são opcionais conforme o conteúdo existente. Não comprima ou sobrescreva as abas por página. |
| Modal | Todos os modais nativos reutilizam o componente `design-system :: applicationDialog(dialogId, titleId, descriptionId, content)` ou `modalDialog(..., modalClasses)` para uma variante; os aliases `formDialog` e `analysisDialog` também geram essa moldura compartilhada. `app-modal` mantém 900px × 820px (limitado a 95vw × 90dvh), com borda e fundo comuns. Não defina dimensões por tela. Para formulários, use `form-dialog-frame` com `form-dialog-header`, `form-dialog-content` e `form-dialog-footer`: cabeçalho e rodapé fixos e conteúdo rolável. Abas reutilizam `analysis-tabs` de analysis-dialog.css e permanecem fora da região rolável. Para análises, use `analysisDialog` e `analysisDialogStyles` com `analysis-header`, `analysis-tabs`, `analysis-body` e `analysis-footer`; grupos de botões usam `settings-actions`, sem separadores adicionais. Inclua `dialogStyles`. Use `formSection(titleId, title, iconName, content)` para seções. Preserve IDs, roles e navegação; abra com `showModal()` e mantenha fechamento por teclado. Use a classe `app-modal-adaptive` em `modalDialog` para acompanhar a preferência de tema do sistema com os tokens compartilhados. |

Antes de criar uma tela, procure o componente no catálogo e confira um consumidor em produção. O CSS da página cuida da composição e das particularidades daquela tela. Para uma variação recorrente, acrescente uma classe modificadora ao CSS do componente. Extraia marcação repetida para um fragmento com parâmetros e comportamento repetido para um módulo JS usado por todos os consumidores. Preserve IDs referenciados por scripts, formulários, HTMX e atributos ARIA.

Ao introduzir um padrão novo, implemente-o na camada compartilhada, migre as ocorrências equivalentes e atualize esta tabela na mesma alteração. Verifique telas pequenas, foco, teclado e os estados vazio, carregando e erro quando aplicáveis. Execute os testes JS e, para mudanças nos templates, os testes Maven.

## Switches no mapa

- Na edição, seleção e movimentação são gestos padrão, sem botão próprio: arrastar no fundo seleciona em área; clicar e arrastar no segundo clique consecutivo move a câmera; clique simples seleciona o elemento e arrastar move a seleção; duplo clique no elemento ou conexão abre suas configurações. Arrastar não conta como clique duplo. O modo Conectar elementos permanece separado e pode ser desativado pelo próprio botão ou Escape.

- A rolagem sobre o mapa aproxima/afasta em edição e visualização, preservando a coordenada sob o cursor. Os botões de zoom mantêm o centro da câmera; a rolagem não altera elementos ou conexões e não interfere em gestos de arraste ativos.

- `map-switch.js` compartilha composição SVG, geometria e pontos de conexão entre o mapa e a prévia. Reutilize `MapSwitch` para equipamentos com portas; imagens em `static/map-assets/`. Portas têm IDs estáveis e coordenadas locais. Uma porta PON permite vários enlaces, com âncora compartilhada e caminhos independentes; as demais permitem um enlace por porta. Enlaces duplicados entre as mesmas extremidades continuam inválidos.

- O conector PON reutiliza a arte SFP. A descoberta Zabbix reconhece nomes nativos de portas PON e tags de conector. Quando a origem do enlace mapeado é uma PON, o tooltip identifica TX/RX como totais da porta e a utilização agregada não colore nem anima os ramais.

- As conexões animadas distribuem fases de partida ao longo do ciclo. A distância entre pulsos é 50% maior que a original (195px no contínuo, 1,5 vezes o período anterior no tracejado/legado), preservando as velocidades de 60px/s e 55px/s. Ambas reutilizam a cápsula luminosa do tracejado (até 20px, núcleo de 2,5px e halo suave), com o mesmo estilo visual. `MapTraffic.pulsePhases` atribui fases distintas aos enlaces incidentes no mesmo elemento em ordem persistida, independente da seleção e da ordem de desenho.

- Conexões animadas contínuas e tracejadas ficam vermelhas, sem pulsos, quando qualquer extremidade possui leitura DOWN válida e recente. O tracejado é preservado, inclusive na seleção; leituras ausentes ou vencidas não indicam DOWN.

- `MapTraffic.synchronizePulses` ancora as animações CSS no relógio compartilhado após anexar a geometria SVG. Redesenhos, seleção, atualização de status e recarga da página conservam a fase dos pulsos; ocultar pulsos usa visibilidade, sem reiniciar o ciclo.

- Pulsos de linhas contínuas sobrepostas usam máscaras SVG nos trechos geometricamente compartilhados: somente a conexão mais acima anima o trecho, e selecionar uma conexão oculta os pulsos próprios e dos trechos compartilhados inferiores, sem modificar o JSON.

- Conexões simples cinzas são desenhadas abaixo dos enlaces mapeados, independentemente da ordem de cadastro; a conexão selecionada mantém prioridade acima de todas. A ordenação visual não modifica o JSON persistido.

- Legendas de hosts, grupos e conexões aguardam 1s de hover; sair antes cancela a abertura. Movimentos na mesma conexão não reiniciam o prazo. Acesso por foco via teclado permanece imediato. Nos hosts e grupos, clicar cancela a legenda pendente/visível e abre somente o menu; enquanto qualquer menu de elemento estiver aberto, nenhuma legenda de elemento pode abrir. Após o clique, um novo hover inicia o prazo novamente.

- Clicar em um enlace na visualização destaca o trajeto em amarelo queimado acima dos outros enlaces, sem alterar a ordem persistida. O destaque tem borda amarelo-clara, prevalece sobre as cores de tráfego, oculta os pulsos no trajeto selecionado e preserva o tracejado Wireless, inclusive na borda clara; oferece seleção por teclado. Um novo clique no enlace selecionado remove o destaque. Na edição, selecionar preserva a cor original da linha e não desenha a borda amarela.

- Ao arrastar cantos, `MapLinks.snap` atrai para trechos horizontais/verticais próximos de outros enlaces com alcance de 8px na tela e sobreposição de extensão, preservando âncoras e os demais enlaces.

- `map-links.js` concentra a geometria das conexões: reta ou caminho com cantos editáveis. Os pontos intermediários são coordenadas do mapa; extremidades continuam vinculadas ao equipamento/porta.

- Conexões separam a geometria do estilo visual. Tipo de linha combina `style` (straight/corners) e `lineStyle` (straight/curved); suavizar preserva os pontos. O campo opcional `appearance` define linha contínua ou tracejada, simples em cinza ou verde com pulsos A → B, independente da categoria. Mapas antigos sem esse campo preservam o desenho original até editar a conexão. A preferência global de animação e `prefers-reduced-motion` também se aplicam aos pulsos configurados. Duplo clique abre o modal compartilhado.

- Switches usam slots de interfaces (`id`, `name`, `connector`, `capacity`, `count`). `MapSwitch.slotsFor` migra portas antigas; `portsForSlots` preserva IDs por slot e numera os nomes. Conector e capacidade são independentes: `connector` identifica electrical, sfp, sfpplus, sfp28, qsfpplus, sfp56, qsfp28, qsfp56, qsfpdd, qsfpdd800, wireless ou pon; `capacity` usa 10m, 100m, 1g, 10g, 25g, 40g, 50g, 100g, 200g, 400g ou 800g (`unknown` quando não identificada). `MapSwitch.normalizeSlot` migra as capacidades legadas sem alterar IDs; a arte da porta depende do conector. A capacidade máxima das portas SFP pode ser identificada pelo nome nativo (`default-name`, ou `name` quando ausente): sfp=1G, sfp-sfpplus=10G, sfp28=25G, sfp56=50G, qsfpplus=40G, qsfp28=100G, qsfp56=200G e qsfp56-dd=400G. Nas demais portas, use `supported`/`supported-rates` de `/interface/ethernet/monitor ... once`; nunca use `rate` ou `speed`. Em portas elétricas sem `supported`/`supported-rates`, use o maior modo do próprio `advertising` (pode ser limitado pela configuração); nunca use `link-partner-advertising`. Nomes não reconhecidos sem essas informações ficam como `unknown`. QSFP representa a capacidade física do conector, não a velocidade individual de uma lane em breakout. `speed` registra a leitura atual do link. Agrupe portas por slot, com espaçamento compacto uniforme e numeração de baixo para cima.

- O seletor de ícones reutilizável é `single-picker.js`/`single-picker.css`: seleção única com miniatura, pesquisa e popover nativo acima de modais. A biblioteca vem das opções do select, sem entradas fictícias. `interfacesEnabled` é independente de `icon` e preserva portas/slots quando desativado; não descarte dados ao trocar o ícone.

- O LED único do switch usa o último ping do coletor, via `/api/maps/ping-status`, atualizado a cada 2s sem redesenhar o mapa. Resposta = verde; timeout ICMP = vermelho; edição, erro de coleta ou amostra com mais de 10s = cinza. Não use severidade de incidentes ou médias de latência para colorir esse LED.

- `switchColor` personaliza apenas o gabinete do switch, via filtro SVG compartilhado em `MapSwitch.draw`. Preserve `color` e o comportamento do círculo; nunca aplique a personalização às portas ou ao LED de ping. `null` restaura a arte original.

- Categorias do seletor de ícones seguem o vínculo do elemento: grupo de hosts oferece Círculo; host oferece Switch; elemento livre mantém ambos. `data-bindings` nas opções declara a biblioteca por categoria; trocar tipo só ajusta as opções e não apaga slots ou `switchColor`.

- Portas conectadas usam arte própria para Elétrica, SFP1G/10G, SFP40G e SFP100G, em `map-assets/*-plugged.svg`. A ocupação deriva dos links, sem estado persistido separado. `MapSwitch.portVisual` compartilha arte e centro de encaixe do cabo cinza; `endpoint` e `cableDirection` respeitam a fileira. Portas 40G usam moldura cinza também quando vazias.

## Conexões mapeadas

- `Link.connection` mantém nome, hosts e interfaces dentro do próprio mapa; `Link.mappedConnectionId` identifica essa ligação local. Não há cadastro ou menu separado de conexões. Alterações são persistidas ao salvar o mapa e devem corresponder aos hosts A/B da ligação. Conexões simples preservam o comportamento anterior.
- Interfaces dos mapas são descobertas por itens `net.if.status`/`net.if.operstatus` no Zabbix; o nome canônico vem das tags ifname/interface/ifdescr, do nome padrão do item ou do parâmetro da chave. O mesmo nome é usado na importação, seleção, validação e leitura das conexões. Não utilize credenciais do equipamento nos endpoints de mapas.
- `/api/maps/{mapId}/connections/readings` recebe os IDs das conexões em uma única requisição por mapa, a cada 60s. Valida as definições persistidas e usa `MapZabbixReadings`, sem SSH/RouterOS/HTTPS nos equipamentos durante a visualização. `?force=true` atualiza imediatamente os valores já disponíveis no Zabbix (não força SNMP).
- `MapZabbixReadings` compartilha cache em memória por host/interface entre mapas e usuários. A primeira carga e a atualização manual compartilham uma execução; consultas normais leem memória. O agendador atualiza interesses ativos a cada 60s e remove interesses/cache após 5min sem consultas. Descoberta de itens `net.if.*` por host é reutilizada por 10min; valores são consultados em lotes de até 500 IDs. O retorno tem versão SHA-256; o front envia `X-Map-Readings-Version` e recebe `unchanged=true` sem repetir as leituras quando a versão coincide. Correspondência exata por chave, nome padrão de interface ou tags interface/ifname/ifdescr; ausência/ambiguidade é explícita.
- Estado e tráfego conservam seus horários de amostra do Zabbix, separados do horário de consulta. Validade mínima de 180s ou três intervalos simples do item; intervalo ausente, dependente ou macro usa 180s. Taxas em bits/s são usadas diretamente, bytes/s são convertidos; contadores acumulados não são taxas. Falhas preservam a amostra e informam o erro. Sem utilização válida, a linha fica cinza. Descoberta e seleção de interfaces do mapa também usam exclusivamente o Zabbix, sem autenticação no equipamento.
- O botão Carregar interfaces do elemento usa `MapZabbixReadings.discover`; os seletores dos enlaces usam somente as portas cadastradas nos elementos, com cache de um minuto e consulta em lote de estado, ifType e velocidade. Tipos virtuais conhecidos não viram portas físicas. O app classifica conectores por tag explícita, tipo SNMP e famílias nativas (Elétrica, Wireless, SFP/QSFP); sem evidência, preserva o conector existente ou usa Elétrico provisoriamente com aviso. Velocidade atual não define capacidade física. Múltiplas interfaces Wireless ficam disponíveis para revisão dos slots, sem escolher ou truncar silenciosamente; salvar exige no máximo uma Wireless.
- Os gerenciadores de sessões diretas continuam disponíveis para funcionalidades de equipamento; a visualização dos mapas usa exclusivamente o cache Zabbix. Não reintroduza coleta direta como fallback silencioso.

## Cadastro local de hosts

- `hosts` centraliza o espelho persistido do cadastro Zabbix (`app_host`), autenticação exclusiva do app (`host_authentication`) e validação de acesso MikroTik/Ubiquiti.
- Listagem, opções e abertura do modal usam o banco local. Sincronização inicial, periódica (60s) ou manual reconcilia o Zabbix; alterações compartilhadas só são consideradas salvas após confirmação remota.
- Exclusões são replicadas nos dois sentidos: após uma consulta completa bem-sucedida, hosts ausentes são removidos do cadastro, autenticação, APs e vínculos de mapas. Falhas da API preservam o cadastro local. Retirar a seleção de monitoramento não exclui o host.
- Autenticação fica na aba do host. Importar um AP testa a configuração do host; excluir a classificação AP preserva as credenciais do host.
- A migração reutiliza a chave `ap.secrets-key-file` (padrão `./data/ap-secrets.key`); preserve esse arquivo junto aos backups. Senhas e snapshots de configuração Zabbix são protegidos com a mesma chave.
- Abas em formulários também reutilizam `.analysis-tabs` de `analysis-dialog.css`, com `role=tablist`, `tab`, `tabpanel`, foco e navegação por setas.

- O ícone `radio` é exclusivo de hosts e reutiliza `MapSwitch` para LED de ping, portas, prévia e conexões. Sua arte fica em `map-assets/radio.svg`; interfaces ficam na base, em uma fileira de até quatro. O limite total é validado no editor e no servidor, inclusive com interfaces desativadas; trocar o ícone preserva os slots para revisão, sem truncar portas ou remover vínculos silenciosamente.

- Na descoberta/configuração direta (separada da visualização via Zabbix), o conector `wireless` usa tipo de porta `wireless`, arte `map-assets/wireless.svg` e âncoras compartilhadas de MapSwitch. Mimosa descobre Ethernet e Wireless separadamente; o estado wireless vem de `connection.connected`. Tráfego do enlace usa `TPutPer.php` (`tput1min`: TX/RX em bytes/s, convertidos para bits/s), com amostras recentes e sem reutilizar contadores Ethernet nem taxas PHY. Capacidade desconhecida permanece `unknown`.

- O rádio usa a arte de referência em `radio.svg`, sem LED ou símbolo wireless fixos. MapSwitch desenha o LED de ping na caixa e a porta Wireless na ponta da antena somente quando cadastrada e habilitada; vínculo no mapa deixa o símbolo azul-claro. É permitida no máximo uma interface Wireless por equipamento, inclusive com interfaces desativadas; editor e servidor validam sem truncar dados.

- Sessões HTTPS Mimosa de consultas diretas legadas (fora da visualização dos mapas) reutilizam autenticação/cookies por host, com consultas serializadas e renovação única após falha; IP, porta ou senha alterados substituem a sessão. Expiram após 45s sem consultas, com limpeza a cada 10s. Não há coleta autônoma. Em `tput1min`, ignore pontos inválidos (`nan,nan`) e use o ponto válido mais recente com até 30s de idade e no máximo 5s no futuro.

- No painel do enlace, TX/RX são os itens da origem A, preservando o sentido A → B; ambos os lados têm estado consultado no Zabbix. Não some medições das duas pontas nem trate ausência como zero/DOWN.

- `equipment/MappedEquipmentSessions` centraliza sessões de consultas diretas legadas (fora da visualização dos mapas) por host para MikroTik, Datacom e Mimosa: todas as interfaces e papéis A/B usam a mesma sessão, com consultas serializadas, uma renovação após falha de transporte e expiração após 45s ociosos. A limpeza roda a cada 10s. Datacom mantém SSH e shell CLI; o timeout é por comando, sem prazo de vida de 60s. Mimosa compartilha cookies e a mesma fábrica TLS para reaproveitar HTTPS/keep-alive, consumindo as respostas antes da próxima consulta. Sem coleta autônoma após fechar o mapa.

- Transformações de Switch/Rádio usam `rotation` (0–360°, exclusivo), `scale` (0.25–3) e `flipX`, com padrões 0/1/false. `MapSwitch.transformPoint` e `bounds` compartilham espelhamento → escala → rotação entre desenho, portas e conexões. Rótulos permanecem horizontais. Alças aparecem apenas na seleção individual em edição; Escape cancela o gesto e cada arraste gera uma etapa de desfazer.

- `map-element-controls.js` desenha borda de seleção e quatro controles vetoriais de 24px integrados à borda (girar, redimensionar, inverter por clique, restaurar por clique), com tooltip de hover/foco e tamanho estável no zoom. Durante o gesto de redimensionamento, uma legenda acima do elemento mostra a escala em porcentagem (100% original, passos de 1%), desaparecendo ao soltar ou cancelar. Restaurar aplica `scale=1`, `rotation=0` e `flipX=false`, como um equipamento recém-inserido, preservando a posição atual, propriedades técnicas e vínculos. `origin` mantém compatibilidade com mapas já salvos, mas não define o tamanho ou a orientação padrão.

- O ícone `telecom-site` (Estação de telecomunicações) usa `map-assets/telecom-site.webp` e é exclusivo de Grupo de Hosts, com restrição no seletor e no servidor. Compartilha prévia e desenho do mapa, indicador de estado agregado, rótulo e navegação para submapas.

- Estação de telecomunicações reutiliza os controles de transformação de `MapElementControls` e os limites transformados de `MapSwitch.bounds`; escala, rotação e espelhamento afetam a arte e o indicador, mantendo o rótulo horizontal.

- Conexões mapeadas em elementos de Grupo de Hosts selecionam primeiro um host membro de cada grupo, usando o seletor pesquisável compartilhado `setupGroupPicker`, e depois carregam suas interfaces. `Link.connection` persiste esses hosts A/B; o servidor valida a associação ao grupo. Trocar o host invalida as interfaces e consultas em andamento. A coleta continua usando os hosts da conexão, com tráfego apenas no lado A.

- Na descoberta/configuração direta (separada da visualização via Zabbix), Cisco ASR1002-X (IOS XE) usa SSH com credenciais do host e `CiscoEquipmentProvider`: `terminal length 0`/`terminal width 512` preparam apenas a sessão; `show version` identifica o equipamento, `show interfaces` lista interfaces e contadores em uma consulta e `show interfaces <nome>` coleta a origem. O destino usa somente `show interfaces description`, sem contadores. Consultas diretas legadas reutilizam `MappedEquipmentSessions`, com a mesma serialização, renovação e expiração dos demais fabricantes. Taxas usam deltas de bytes; ausência de contadores em subinterfaces permanece indisponível. `BW` configurável não define capacidade física; no scan Cisco, interfaces físicas TenGigabitEthernet usam a representação SFP+ (`connector=sfpplus`) e capacidade 10 Gbps da biblioteca do mapa, inclusive cartões XFP.
- SSH Cisco usa `connectCisco`: acrescenta `diffie-hellman-group14-sha1` após os algoritmos modernos para IOS XE 16.9 antigo, preservando a verificação de chaves e o perfil dos outros fabricantes. O ASR testado oferece chave `ssh-rsa`, AES-CTR e HMAC-SHA2.

- Interfaces descobertas no Zabbix e portas dentro de cada slot usam ordenação natural por nome (ether1, ether2, …, ether10; incluindo números de chassi/slot/porta). Preserve a ordem dos grupos configurados e os IDs das portas/vínculos. `MapSwitch.geometry` também ordena a apresentação de mapas já salvos sem alterar os dados persistidos.

- Na configuração de enlaces, as opções de interfaces vêm somente de `ports` dos elementos do mapa (para grupos, do elemento do host selecionado presente no mapa), sem redescoberta no Zabbix. O botão Carregar interfaces do elemento continua descobrindo no Zabbix. Opções removidas não são restauradas no seletor; valide a associação às portas configuradas ao salvar o mapa.

- Elementos de grupo armazenam `linkInterfaces` no JSON do mapa: entradas `{hostId, name, connector, capacity}`. Permita várias interfaces do mesmo host, nunca duplique o par host/interface; valide associação ao grupo no servidor. O formulário usa o seletor pesquisável compartilhado de hosts, descobre interfaces no Zabbix apenas ao configurar a lista e permite revisar conector/capacidade. Enlaces do grupo consultam somente essa lista e não exigem elementos individuais dos hosts. Remover uma entrada usada exige reconfigurar/remover primeiro o enlace. Listas ausentes em mapas antigos mantêm compatibilidade; ao editar o grupo, pré-preencha os pares dos enlaces existentes. Nenhuma tabela adicional.

- O editor de interfaces habilitadas de grupos usa formulário expansível em `group-link-interfaces.js`: tabela sempre visível, descoberta automática ao selecionar host, cancelamento local e proteção de pares usados em enlaces. Seletores pesquisáveis via `setupGroupPicker` respeitam `disabled` no select e nas opções; chame `sync()` após atualizar opções ou estado.
- A descoberta para `linkInterfaces` de grupos usa `includeLogical=true` no endpoint existente, compartilhando o cache; a descoberta padrão de portas físicas continua excluindo tipos virtuais conhecidos.

- O ícone `pop` (POP Status Node) tem uma única opção verde no seletor e na prévia. Na visualização, usa o estado agregado e os filtros do mapa: normal verde, informação azul, atenção/média amarelo, alta/desastre vermelho e sem dados/não classificado cinza. O nome configurado é desenhado em cápsula abaixo da arte, com a mesma cor de contorno; texto e cápsula mantêm tamanho fixo ao redimensionar a arte. Não persista a variante de estado.

- O ícone `cloud` (Nuvem) usa a imagem fornecida em `map-assets/cloud.webp`, disponível para hosts, grupos e elementos livres. Reutiliza prévia, indicador de estado e transformações compartilhadas; rótulos permanecem horizontais.

- Imagens raster usam WebP sem perda; SVGs de equipamentos preservam máscaras e geometria com WebP embutido recortado. Portas conectadas têm até 512px. A moldura 40G vazia reutiliza `qsfp.svg` com filtro neutro em MapSwitch. Ao trocar imagens, atualize a versão nas URLs; o cache de imagens dura 30 dias.

- A legenda de hosts individuais no mapa usa `PopSummary.renderHost` e `/api/maps/hosts/{id}/summary`, com disponibilidade operacional publicada de 24h, latência/perda e cinco eventos de problema ativos ou resolvidos do Zabbix. Grupos preservam a legenda agregada. P95 exige amostras brutas completas da mesma janela; sem elas, exiba Sem dados. O histórico completo abre `/incidentes?hostId=...&history=all`.

- Os ícones `tower-server` (Servidor em torre) e `vm-server` (VM Server) usam as imagens fornecidas em WebP sem perda, disponíveis para hosts, grupos e elementos livres; compartilham prévia, estado e transformações dos ícones raster.

- Servidor em torre e VM Server usam LED dinâmico do ping recente (2s; validade 10s): verde com resposta abaixo de 15ms, amarelo a partir de 15ms, vermelho no timeout e cinza em edição/erro/ausência. O LED da VM substitui a cor fixa por sobreposição SVG neutralizada; o da torre fica no canto superior direito. Grupos agregam seus hosts, priorizando timeout e depois ausência de dados.

- As imagens de Servidor em torre e VM Server têm fundo transparente e recorte justo em WebP sem perda. Geometria, prévia e LEDs seguem as proporções dos arquivos recortados.

- O ícone `battery` (Bateria) usa `map-assets/battery.webp`, recortado e transparente, para hosts, grupos e elementos livres; reutiliza transformações, prévia e LED dinâmico de ping dos servidores (2s; validade 10s).

- Nomes de hosts clicáveis usam o menu compartilhado `HostMenu`, incluindo mapas. Na listagem Access Point, o nome do AP abre diretamente seu painel (`/ap/{hostId}`), sem menu. A seção HOST oferece Inventário e Dados Recentes desativados, Incidentes filtrados e Configurações abrindo o modal compartilhado na página atual, sem navegação. Em Configurações → Hosts, o nome continua abrindo diretamente o modal de edição.

- O ícone `sector-radio` (Antena setorial) é exclusivo de hosts e reutiliza MapSwitch, interfaces configuradas, transformações e LED do ping (verde UP, vermelho timeout, cinza sem amostra recente). A arte transparente recortada está em `map-assets/sector-radio.webp`; portas elétricas ficam na base do rádio e a âncora Wireless invisível no centro da antena.

- Na visualização do mapa, clicar em um grupo de hosts abre o menu compartilhado HostMenu com Incidentes filtrados, Hosts do Grupo (diagnóstico) e Submapa sempre visível, desativado quando não configurado. O submapa mantém a navegação hierárquica. A legenda agregada não contém o link Ver detalhes do POP. Em edição, clicar continua selecionando/configurando o elemento.

- Entre dois elementos de Grupo de Hosts, enlaces mapeados paralelos são permitidos quando os pares de host/interface diferem; a duplicação é verificada nos dois sentidos. A criação de um enlace paralelo abre o modal como rascunho mapeado, com ponto intermediário deslocado para facilitar a seleção; cancelar não altera o mapa. Conexões simples duplicadas e regras de ocupação das portas físicas permanecem restritas. Interfaces mapeadas são ocupadas por host/interface no próprio mapa (inclusive em outros elementos e nos dois sentidos); opções ocupadas permanecem visíveis e desabilitadas com “Já usada”, exceto a própria conexão em edição e portas PON. O servidor valida a ocupação ao salvar.
