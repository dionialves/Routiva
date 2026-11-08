# Dashboard de disponibilidade

## Agregação e publicação independentes

`MinuteAggregationScheduled` continua consolidando pings em minutos, horas e dias,
com `indicatorScheduler` exclusivo. Não chama mais o publicador do dashboard.
O intervalo da agregação continua em `INDICATORS_AGGREGATION_INTERVAL_MS`.

`AvailabilitySnapshotCache.refresh()` possui seu próprio `availabilityScheduler`, com
uma thread, execução sincronizada e intervalo de 60 segundos **após concluir** cada ciclo.
Configuração: `INDICATORS_AVAILABILITY_INTERVAL_MS` (60000) e
`INDICATORS_AVAILABILITY_INITIAL_DELAY_MS` (0). Não há execuções sobrepostas nem uma fila
de publicações atrasadas. Uma publicação lenta não ocupa a thread da agregação.
Ambos ainda compartilham os recursos físicos do banco: separar threads não elimina disputa de I/O.

`PacketLossAnalysisService.advance()` captura progresso, diário e resumos em uma mesma
transação `REPEATABLE_READ`. A referência temporal é `complete_through`; o cursor de
alterações pode incluir lotes confirmados de um ciclo ainda em recuperação. Nunca são
incluídas amostras posteriores ao fim do período consolidado.

## Atualização incremental

A mesma transação que grava os resumos também grava `availability_change`: revisão
(cursor do lote), host/IP, minuto, contagens adicionais, soma de latências, última amostra
e incremento de cobertura. Um minuto com erros que recebe sua primeira tentativa válida
ganha cobertura uma única vez. Rollback da agregação também desfaz as entradas do diário.

A versão persistida já contém os pontos por host/período e a revisão consumida
(`load.liveCursor`); não é necessário guardar todos os minutos de dois anos em memória.
A atualização reutiliza esses pontos e:

1. Aplica contribuições do diário posteriores à revisão anterior, inclusive chegadas tardias.
2. Descarta pontos que saíram completamente da janela.
3. Subtrai a faixa expirada dos pontos que continuam parcialmente na janela.
4. Soma as faixas que entraram e preserva os demais pontos.
5. Recompõe totais e última medição a partir dos poucos pontos de cada host/período.

As leituras de faixas são compartilhadas por todos os hosts e períodos: a nova faixa
final geralmente é a mesma para os 16 períodos. Um ciclo normal lê apenas as bordas que
mudaram, em vez de reler 24h ou dois anos. Os pontos mantêm contagens e somas, não apenas
percentuais, preservando médias ponderadas e cobertura. A saúde da coleta usa os cinco
minutos do próprio cache atualizado, sem outra consulta histórica.

Os limites seguem `AvailabilityPeriod` e São Paulo, incluindo semanas, meses, anos e
encurtamento de fevereiro. O cálculo também trata quando a subtração de meses faz o início
da janela recuar. Dados retirados das janelas **não são apagados do histórico**.
Se a última medição sair da janela, a recomposição usa a maior data dos pontos restantes;
uma janela vazia volta a ter valores nulos, sem inventar indisponibilidade.

Novos hosts/IPs carregam seu próprio histórico uma vez; os demais reutilizam a base.
Remoções deixam de participar da próxima geração. Alterações de nome/grupo e limiares de
classificação são aplicadas sobre os dados reutilizados.

## Persistência, recuperação e limpeza do diário

`AvailabilitySnapshotRepository` mantém JSON comprimido com gzip/base64 em
`availability_snapshot`. A substituição do payload, a confirmação da revisão em
`availability_change_state.floor_revision` e a exclusão das alterações consumidas
ocorrem na **mesma transação**. Alterações mais novas que a revisão capturada ficam no diário
para o próximo ciclo. A revisão confirmada não pode recuar.

Depois do commit, o cache troca uma referência `volatile` pela nova geração imutável.
Leitores em andamento continuam na geração anterior. Falha no cálculo ou na gravação
preserva a versão anterior e o diário não confirmado; repetir não duplica contribuições.

No reinício, a última geração é restaurada antes do atendimento HTTP. O ciclo seguinte
retoma sua revisão. O formato de snapshot anterior continua legível. Na primeira atualização
após esta migração, se o snapshot for anterior ao início do diário, haverá uma reconstrução
controlada, mantendo os dados anteriores disponíveis ao front.

Reconstrução completa fica restrita a: primeira geração, checkpoint incompatível/perdido,
revisão anterior ao diário disponível, inconsistência de contadores ou recuperação com mais
de 200 mil pares host/IP/minuto alterados. Esse limite evita replay sem limite de memória.
A reconstrução usa os aceleradores por hora/dia e limites temporais explícitos no SQL;
ainda pode ser custosa, mas não ocupa o agendador da agregação.

Sem geração salva, o front permanece no skeleton até a primeira publicação. Snapshot antigo
não expira por TTL: continua disponível com horário e sinalização de atraso.

O cache pertence ao processo Java. Coordenação de publicadores em múltiplas instâncias
não faz parte deste desenho; a revisão monotônica impede uma gravação mais antiga de
substituir uma revisão já confirmada por outro publicador.

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
O resultado local não substitui medição no hardware da implantação. O benchmark de PostgreSQL
abaixo usa schema isolado e executa também EXPLAIN ANALYZE de uma consulta curta.

Logs: `AVAILABILITY_INCREMENTAL` (revisões, alterações, novos hosts, faixas lidas e pontos
reutilizados), `AVAILABILITY_REBUILD` (motivo), `AVAILABILITY_PUBLISHED` (versão, hosts, períodos, bytes comprimidos, duração),
`AVAILABILITY_RESTORED`, `AVAILABILITY_REFRESH_FAILED` e `AVAILABILITY_RESTORE_FAILED`.

### Medição anterior à atualização incremental, em 08/11/2026

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

### Medição incremental local (H2)

Na mesma carga sintética de 500 hosts, com 500 novas medições por ciclo, os três ciclos
incrementais, incluindo compressão e persistência, levaram **987, 916 e 880 ms**.
Foram reutilizados de 239 mil a 242 mil pontos por ciclo, com 13–14 faixas históricas lidas.
O cálculo inicial permaneceu separado (21.264 ms). Valores de H2 não são previsão para PostgreSQL.

### Carga opcional de PostgreSQL

Use um PostgreSQL **de testes**, com usuário `postgres`, banco acessível e senha
`routiva-test-only`. A suíte cria e remove um schema próprio; não apontar para produção.
O teste materializa 4,5 milhões de resumos por minuto, aceleradores e dois anos de resumos
diários, e compara cinco ciclos incrementais com o cálculo completo de todos os períodos.
Imprime duração inicial, ciclos incrementais e plano de uma consulta de um minuto.

```sh
ROUTIVA_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/postgres \
  mvn test -Dcollector.enabled=false -Dtest=AvailabilityPostgresBenchmarkTests \
  -Davailability.postgres-benchmark=true
```

`AvailabilityIncrementalTests` e os testes de agregação/cache também aceitam essa URL para
validar transações, cobertura e equivalência em PostgreSQL. A suíte padrão mantém os testes
de carga opcionais desabilitados.

### Medição incremental em PostgreSQL 17.11 (08/11/2026)

Executado em container temporário local, com 4,5 milhões de resumos por minuto,
500 hosts e dois anos de resumos diários. A preparação inicial dos 16 períodos levou
**5.129 ms**. Os cinco ciclos incrementais, incluindo compressão e persistência, levaram
**1.067, 897, 1.013, 931 e 954 ms**. Cada ciclo recebeu novas medições de 500 hosts;
o terceiro também recebeu uma correção de 100 dias atrás. O resultado final dos 16 períodos
foi comparado ao cálculo completo. A consulta curta de um minuto usou o índice temporal
e levou 0,362 ms no EXPLAIN ANALYZE da mesma execução.

São medições locais em carga sintética, sem concorrência de ingestão contínua e sem
representar o hardware de produção. A montagem inicial dos aceleradores e a agregação
dos novos pings não entram no tempo do publicador. Testes funcionais separados em PostgreSQL
cobrem concorrência, rollback da agregação, diário, retomada e equivalência de calendários.
