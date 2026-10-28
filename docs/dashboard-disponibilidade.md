# Dashboard de disponibilidade

A página `/disponibilidade` consulta `/api/availability/dashboard?minutes=2880&groupId=&page=1&sort=name&direction=asc&q=`. O endpoint anterior `/api/availability` permanece compatível.

- Períodos permitidos, em minutos: 5, 60, 480, 1440 e 2880. Padrão: 48 horas.
- Grupo vazio inclui todos os hosts do inventário atual, inclusive hosts sem grupo. Os filtros são preservados na URL.
- Cards e gráfico usam contagens agregadas, não média de percentuais dos hosts. Latência é ponderada pelo número de respostas. Erros internos não entram no denominador.
- Técnica: respostas / tentativas elegíveis. Operacional: respostas dentro do limite histórico / tentativas elegíveis. Perda: perdas / tentativas elegíveis.
- Todos os hosts selecionados participam desta visão histórica, inclusive os com incidentes reconhecidos; a regra de exclusão das estatísticas da lista de grupos não é aplicada aqui.
- `AvailabilityDashboard` prepara todos os grupos e períodos em segundo plano a cada 10 segundos, a partir de uma única publicação imutável. A API apenas lê essa publicação; não consulta medições nem dispara cálculos.
- O histórico por minuto permanece limitado a 48 horas. A chave interna 0 dos relatórios representa a janela de cinco minutos; as APIs existentes de detalhes do host continuam usando horas e não aceitam essa chave.
- A API pesquisa por nome, IP ou identificação, ordena e retorna no máximo 20 hosts por página. Esses controles não alteram os totais dos cards ou gráfico. A resposta inclui número de páginas, total encontrado e total de hosts do grupo.
- A interface consulta após cada resposta, com intervalo de 10 segundos e limite de 9 segundos por consulta. Mudanças de filtro cancelam a consulta anterior e descartam respostas antigas. Falhas preservam a última resposta do mesmo filtro.
- Lacunas são mantidas no gráfico. Histórico em carregamento, retenção insuficiente e publicações atrasadas são sinalizados. Antes da primeira publicação, nenhum horário de 1970 é exibido.

Validação: testes de recorte exato de cinco minutos, ponderação, estados vazios, integração dos cinco períodos e grupos, rejeição de filtros inválidos, paginação e descarte de respostas antigas. Layout conferido em navegador com dados simulados.

## Gráfico interativo

O renderer SVG existente foi isolado em `availability-chart.js`, sem dependências externas. O componente oferece intervalos de 1, 5 (padrão), 15 e 60 minutos, alinhados no tempo e recortados no período selecionado. Os percentuais de cada intervalo são recalculados pelas contagens `healthy`, `degraded` e `lost` recebidas da API. A seleção de granularidade não altera os cards, a tabela ou o período.

A barra inferior foi removida. Mouse, toque e teclado (setas, Home, End e Escape) consultam o tooltip com data, intervalo, disponibilidade técnica e operacional. O preenchimento operacional usa gradiente teal; a linha técnica permanece ciano, sem preenchimento. Intervalos vazios interrompem linha e área. O renderer utiliza poucos elementos SVG, independentemente da quantidade de medições, e ajusta as marcações à largura disponível.


## Redesign da página

Os cards, filtros, tabela e cabeçalho seguem superfícies navy, bordas discretas e ícones SVG do padrão existente. O gráfico oferece linhas ou barras, expansão com Escape e preserva o seletor de granularidade. O menu lateral vira um painel retrátil em telas de até 760 pixels. Estilos específicos ficam em `availability.css`; a apresentação das linhas da tabela fica em `availability-view.js`, separada da consulta.

O estado atual do host usa a classificação `PacketLossAnalysis.level`, com sinalização separada de ausência de medições, IP inválido, incidente reconhecido, falha e atraso de coleta. Não representa o estado histórico do intervalo selecionado. O indicador geral de coleta usa `fresh` e `collectorHealthy` da mesma publicação dos indicadores; falha HTTP não é considerada evidência de coleta saudável. Não foram adicionadas novas faixas de classificação.

Comparação com período anterior e exportação não são apresentadas: os contratos existentes não oferecem essas operações. A página distingue carregamento, grupo sem hosts, busca vazia, ausência de medições, dados parciais e falha de consulta com nova tentativa manual. Filtros, pesquisa, ordenação e página são preservados na URL.
