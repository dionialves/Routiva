# Dashboard de disponibilidade

## Publicação em segundo plano

O job `MinuteAggregationScheduled` roda a cada 60 segundos por padrão, configurado por
`indicators.aggregation.interval-ms` / `INDICATORS_AGGREGATION_INTERVAL_MS`.
O agendador possui uma thread e o job é sincronizado: execuções não se sobrepõem.
Cada ciclo drena a ingestão até um limite confirmado e, depois de concluir as transações
de agregação, chama `AvailabilitySnapshotCache.refresh()`.

`PacketLossAnalysisService.build()` lê os resumos em uma transação `REPEATABLE_READ`,
preparando todos os hosts e períodos em uma mesma versão. A base são os resumos incrementais
por minuto, hora e dia. O snapshot guarda contagens, soma de latências, última amostra e
minutos válidos, por host/IP atual e intervalo. Não guarda apenas percentuais, para permitir
combinações ponderadas de qualquer grupo.

O início do histórico é obtido em **uma única consulta com os pares host/IP**, em vez de
uma chamada SQL por host. Ela usa MIN correlacionado com o índice de host/IP/tempo,
evita agrupar e percorrer todo o histórico de minutos de dois anos.
Os intervalos de cada período são repartidos entre minuto, hora e dia, sem sobreposição.
O SQL agrupa por intervalo e host: **até três consultas por período**, independentemente
do número de hosts e pontos. As bordas usam resoluções menores, respeitando os limites
exatos do período e o calendário de São Paulo. Janelas curtas usam somente minutos.
Uma consulta adicional de cinco minutos determina saúde/atualidade da coleta, separada
da classificação histórica. Não há mais consultas de 1h e 8h para classificar uma seleção.

A geração dos relatórios ainda é refeita no job a cada ciclo, usando os resumos incrementais
já existentes. Não existe invalidação incremental de pontos de relatórios; isso evita
ignorar correções recebidas tardiamente para intervalos antigos.

## Persistência, cache e reinício

`AvailabilitySnapshotRepository` codifica a geração inteira em JSON comprimido com gzip
(base64 na coluna TEXT). A tabela `availability_snapshot` mantém somente a última versão
completa, com versão de formato e horário de construção. Substituir essa linha é uma
transação independente: falha no INSERT também desfaz o DELETE.

Somente após o commit o cache troca uma referência `volatile` para a nova geração imutável.
Consultas em andamento continuam na geração anterior. Falha de cálculo ou persistência
preserva a última versão em memória e no banco. O snapshot não tem TTL de remoção:
dados antigos continuam consultáveis, acompanhados do horário de consolidação.

Na inicialização, o cache restaura a última geração antes de atender requisições.
Esse carregamento não precisa acessar o histórico nem depender do inventário remoto.
Versão ausente, incompatível ou corrompida deixa o dashboard em carregamento até o próximo
ciclo conseguir publicar. A falha é registrada; não há reconstrução disparada pelo HTTP.

O cache pertence ao processo Java. Em instalações com múltiplas instâncias, cada instância
possui seu próprio cache e job; coordenação distribuída não faz parte desta implementação.

## Contrato HTTP e front

- `/disponibilidade`: estrutura Thymeleaf; os dados chegam por JavaScript.
- `/api/availability/dashboard?minutes=2880&groupId=&page=1&sort=name&direction=asc&q=`:
  grupos, relatório, página de até 20 hosts e situação da coleta.
- `/api/availability`: mesma seleção completa, sem paginação.
- `/api/hosts/{id}/availability?hours=24`: relatório individual no cache; aceita 1, 8, 24 e 48 horas.
- Períodos do dashboard: 5, 15 e 30 minutos; 1, 3, 6 e 12 horas; 1, 2, 7 e 30 dias;
  3 e 6 meses; 1 e 2 anos. A API mantém 480 minutos por compatibilidade. Padrão: 2 dias.

Os endpoints não acessam repositórios, inventário persistido, amostras ou o builder.
O inventário e os grupos fazem parte da geração publicada; mudanças entram no próximo ciclo.
A primeira seleção de um grupo combina as observações por host **em memória**; resultados
combinados são reutilizados na mesma geração. Há limite de 128 visões derivadas, sem limitar
o acesso a outros grupos: a base de todos os hosts e períodos permanece no cache.
Pesquisa, ordenação e paginação são feitas em memória e não alteram os totais do grupo.
O endpoint individual também usa somente essa base em memória.

Todas as respostas mantêm `Cache-Control: no-store` para impedir cache HTTP do navegador;
isso não desabilita o cache do servidor. Sem uma primeira geração, o dashboard retorna
`view: null` e `collectionStatus: CARREGANDO`; o endpoint individual retorna 503.

O navegador consulta novamente 10 segundos após cada resposta, com limite de 9 segundos
por consulta. Mudanças de filtro cancelam requisições anteriores. Erros preservam os últimos
dados do filtro. Essas consultas nunca iniciam o job nem cálculos a partir do banco.

## Indicadores e classificação

- Técnica: `(healthy + degraded) / (healthy + degraded + lost)`.
- Operacional: `healthy / (healthy + degraded + lost)`.
- Perda: `lost / (healthy + degraded + lost)`.
- Latência: soma das latências / quantidade de respostas válidas.
- Erros internos ficam fora dos percentuais; zero é válido; ausência é `null`.
- Cards, tabela e gráfico ponderam pelas contagens das medições, não pela média dos percentuais.

**Estado no período** usa somente as contagens e a latência média do período escolhido.
Perda crítica e atenção respeitam `PacketLossPolicy`; a latência mantém as faixas anteriores
(atenção a partir de 15 ms, crítico acima de 20 ms). Sem tentativas válidas, informa ausência
de dados ou falha de coleta; IP inválido é sinalizado. Reconhecimento atual de um incidente
não substitui a classificação histórica. Saúde e atraso da coleta aparecem separadamente
no cabeçalho, sem transformar a classificação histórica em estado atual.

A referência final é o minuto consolidado, não o instante do clique. O front informa
`from`, `to`, `evaluatedAt`, `lastSampleAt`, processamento pendente e atraso superior a 3 minutos.
Dados restaurados antigos continuam visíveis com o aviso de atraso.

`retainedFrom` indica o primeiro registro entre os hosts selecionados; isso não comprova
cobertura dos demais hosts. O aviso de cobertura também considera `observedMillis`:
minutos com pelo menos uma tentativa válida, em média por host selecionado. Hosts sem dados
entram no divisor. O tempo disponível/indisponível é uma estimativa derivada dessa cobertura
e dos percentuais; não é uma reconstrução dos incidentes. Lacunas não viram indisponibilidade.

## Gráfico e interação

O servidor define o agrupamento automaticamente conforme `AvailabilityPeriod`; meses e
semanas seguem `America/Sao_Paulo`. `availability-chart.js` renderiza SVG com linhas/barras,
lacunas, intervalos parciais e tooltip por mouse, toque ou teclado. A expansão não faz
consultas adicionais. O renderer reutiliza a mesma versão entre consultas do navegador.

## Validação e carga

`AvailabilitySnapshotCacheTests` cobre restauração de todos os períodos, seleção de grupos,
pesquisa/paginação, ausência de acesso ao armazenamento em leituras, falhas de publicação,
classificação por período, IP atual e cobertura parcial. `DashboardTests` verifica também
rollback real da substituição persistida. `HistoricalAvailabilityTests` compara os buckets
agrupados com a leitura por minuto em todos os períodos.

Teste sintético opcional (H2, banco isolado; não usa dados reais):

```sh
mvn test -Dcollector.enabled=false -Dtest=AvailabilitySnapshotBenchmarkTests -Davailability.benchmark=true
```

A carga tem 500 hosts, 720 mil resumos por minuto nas últimas 24 horas, 12 mil resumos por
hora e 365 mil resumos diários para dois anos. São aceleradores sintéticos; o teste não
materializa dois anos de pings/minutos brutos. Mede geração dos 16 períodos, persistência,
restauração, tamanho comprimido, diferença aproximada de heap após GC e combinação em memória.
Os índices de host/IP/tempo e resolução/tempo existentes atendem às novas consultas.
O resultado local não substitui EXPLAIN ANALYZE e teste de concorrência em PostgreSQL com
o volume e o hardware da implantação.

Logs: `AVAILABILITY_PUBLISHED` (versão, hosts, períodos, bytes comprimidos, duração),
`AVAILABILITY_RESTORED`, `AVAILABILITY_REFRESH_FAILED` e `AVAILABILITY_RESTORE_FAILED`.

### Medição local em 08/11/2026

Java 21, H2, carga acima, execução explícita do benchmark:

| Operação | Resultado |
| --- | ---: |
| SELECTs para construir os 16 períodos dos 500 hosts | 25 |
| Construção dos 16 períodos | 20.930 ms |
| Compressão e persistência | 249 ms |
| Restauração do banco | 194 ms |
| Payload gzip | 226.752 bytes |
| Diferença aproximada de heap após construção e GC | 22.682.216 bytes |
| Combinação de 250 hosts em memória, média de 100 operações | 314 µs |

A diferença de heap inclui estruturas do processamento e do banco de teste, não é uma
medição exata do tamanho isolado do cache. A combinação medida alterna 24 horas e dois anos;
não representa latência HTTP completa. O custo da primeira agregação/backfill dos dados
brutos fica fora dessa medição. Na suíte padrão o benchmark é ignorado por ser uma carga
opcional; foi executado explicitamente para esta medição.
