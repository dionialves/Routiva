# Carga progressiva dos indicadores

## Objetivo e escopo

Quando não existe checkpoint utilizável, disponibilizar primeiro os 10 mil registros mais recentes e recuperar o restante do histórico sem exigir uma leitura integral antes da primeira publicação. O mesmo estado em memória alimenta o painel e os gráficos. As requisições HTTP continuam sem consultar `ping_sample` nem recalcular os indicadores.

A carga progressiva é usada tanto no primeiro uso quanto após rejeição de todos os checkpoints. Um checkpoint válido continua sendo a primeira opção: ele é restaurado e os registros posteriores são incorporados. Falha de acesso ao banco não é interpretada como ausência de checkpoint.

A primeira publicação processa **até 10 mil registros**, começando pelas medições mais recentes. As páginas seguintes seguem para trás no histórico, também com até 10 mil registros. Uma página pode abranger segundos, minutos ou horas, conforme o volume de hosts. Amostras futuras ficam na fila até seu horário chegar. Não existe promessa de duração fixa para consultas, publicação ou recuperação completa.

## Responsabilidades no código

| Componente | Responsabilidade |
| --- | --- |
| `IndicatorBootstrap` | Cursor de leitura histórica e paginação descendente por horário/ID, limitada por quantidade de registros. Não calcula indicadores nem persiste dados. |
| `SampleStore.readBootstrapPage` | Consulta limitada e ordenada pelo índice `(measured_at, id)`. |
| `PacketLossAnalysisService` | Coordena novos registros, histórico, expiração, amostras futuras, publicação e elegibilidade do checkpoint. É o único escritor dos históricos em memória. |
| `IndicatorLoadStatus` | Informa a cobertura e a fase da mesma publicação que contém os resultados. |
| `IncrementalPingHistory` / `AvailabilityHistory` | Incorporam contribuições fora de ordem e mantêm as regras existentes de cálculo e retenção. |
| `AnalyticsCheckpointStore` | Persiste uma captura completa e consistente, utilizando a transação e os blocos imutáveis existentes. |

## Partição dos leitores e invariantes

Na ausência de checkpoint, registrar o horário `T0` e o limite confirmado de IDs `B0`. O protocolo de `committedBoundary()` utiliza o bloqueio de ingestão existente para que nenhum escritor participante confirme posteriormente um ID menor ou igual a `B0`.

1. O leitor histórico é dono exclusivamente de `id <= B0`.
2. O leitor de novos dados começa com cursor `B0` e lê exclusivamente IDs posteriores, em ordem crescente.
3. A responsabilidade depende do ID, não do horário da medição. Um registro novo com horário antigo pertence ao leitor de novos dados e pode corrigir um minuto já publicado.
4. O leitor histórico considera todo o horizonte ainda retido e ordena por `measured_at DESC, id DESC`. Não divide a consulta em intervalos de cinco minutos. Registros posteriores ao horário atual entram na fila de amostras futuras, não nos cálculos.
5. Cada consulta traz até `page-size` registros, padrão de 10 mil, independentemente da distância temporal entre eles.
6. A próxima página usa a chave estritamente menor que `(measured_at, id)` da última linha consumida. Não utiliza `OFFSET`. O ID desempata medições no mesmo milissegundo, sem repetição nem perda nas bordas de página.
7. Uma página com menos registros que o limite confirma o fim da carga. Se o total for múltiplo exato, uma consulta vazia adicional confirma a conclusão. Não existem consultas vazias por intervalo de tempo.
8. Durante a carga, `loadedFrom` é o milissegundo posterior ao da última linha consumida: outras linhas com o mesmo horário podem estar na página seguinte. Essa marca conservadora evita declarar completo um horário parcialmente carregado. Se ainda só foram lidas amostras futuras, a cobertura publicada é nula.
9. O limite de retenção avança com o relógio. Registros que expiraram antes de serem recuperados são dispensados; a conclusão significa cobertura do histórico ainda necessário, não recuperação de registros já expirados ou removidos.

Essas regras evitam duplicidades sem manter um conjunto adicional de todos os IDs em memória. São importantes especialmente para disponibilidade, cujas contribuições não são deduplicadas individualmente. Não reexecutar uma página sobre um estado já alterado sem restaurar/reconstruir esse estado.

A implementação pressupõe que os pings persistidos são imutáveis, os IDs são positivos e todos os escritores respeitam o protocolo de ingestão. Alterações diretas em linhas antigas não são capturadas pelo cursor. Substituições de base exigem invalidar checkpoints conforme a documentação de recuperação.

## Execução e concorrência

O agendador `indicatorScheduler` tem uma única thread. A atualização periódica de dez segundos e a continuação da carga compartilham esse agendador e o bloqueio de `refreshAt`. Não há dois escritores concorrentes dos históricos.

Cada etapa executa:

1. Obtém o limite confirmado atual e tenta restauração quando necessário.
2. Expira contribuições fora das janelas móveis.
3. Processa prioritariamente um intervalo limitado de IDs novos.
4. Processa no máximo uma página histórica de até 10 mil registros.
5. Libera amostras futuras cujo horário já chegou.
6. Publica indicadores, gráficos e cobertura em um único snapshot imutável.
7. Se a cobertura e o cursor estiverem completos, agenda o checkpoint.

A continuação usa atraso fixo, padrão de um segundo **após terminar a etapa anterior**, e só trabalha enquanto houver histórico ou IDs confirmados pendentes. Também acelera a atualização após uma restauração com muitos IDs novos. Fora dessas situações, permanece a cadência normal de dez segundos.

O limite do leitor novo é um intervalo de no máximo `page-size` IDs por etapa, portanto nunca contém mais que essa quantidade de linhas. Lacunas de IDs podem exigir etapas adicionais sem registros. O leitor histórico usa `LIMIT page-size`; uma etapa pode incorporar até duas páginas de dados no total, além da liberação de amostras futuras. Não há carregamento de 48 horas de objetos `PingSample` em uma lista única.

A consulta histórica tem timeout de cinco segundos. Isso não estabelece um prazo rígido para o ciclo: bloqueio para obter o limite confirmado, leitura incremental, incorporação, liberação de amostras futuras e publicação também têm custo. Um ciclo lento adia os próximos porque o escritor é serial. O HTTP permanece lendo o último snapshot, sem aguardar esse escritor.

## Contrato de cobertura e frontend

`IndicatorLoadStatus` acompanha o snapshot do painel e o campo `load` dos relatórios de disponibilidade:

| Campo | Significado |
| --- | --- |
| `phase` | `STARTING`, `LOADING_HISTORY`, `CATCHING_UP` ou `COMPLETE`. |
| `loadedFrom` | Início da faixa histórica completamente percorrida. Exclui conservadoramente o horário da última linha da página, pois pode haver outros IDs nesse mesmo horário. Nulo antes de haver cobertura publicada. Não é a data da primeira amostra existente. |
| `evaluatedAt` | Referência temporal da publicação. Nulo antes da primeira publicação. |
| `targetFrom` | Limite inferior necessário para indicadores e gráficos naquela referência. |
| `liveCursor` | Limite de IDs já tratado pelo leitor incremental. Não representa sozinho a conclusão histórica. |
| `committedBoundary` | Limite confirmado que o ciclo pretende alcançar. |

`COMPLETE` exige histórico esgotado e `liveCursor == committedBoundary`. Isso descreve um ponto consistente de leitura; novos pings podem chegar imediatamente depois. Também não significa que a gravação no banco já terminou: a confirmação durável é `CHECKPOINT_SAVED`.

O painel sinaliza resultados parciais e o início da faixa carregada. O detalhe do host sinaliza a carga em andamento, indica a cobertura e identifica minutos ainda não integralmente recuperados. Não apresenta o aviso de período definitivamente sem dados durante uma carga parcial. Percentuais e contagens durante a carga são calculados apenas sobre as amostras disponíveis e podem mudar conforme o histórico chega; não representam a janela inteira até a conclusão. Lacunas não viram zero por cento de perda ou cem por cento de disponibilidade.

O snapshot garante consistência entre metadados e resultados. `currentSnapshot()` deve ser preferido por novos consumidores que precisam dos dois. Cobertura de carregamento é diferente de cobertura da coleta: mesmo após `COMPLETE`, podem existir períodos sem pings.

## Persistência, reinício e falhas

- Nenhuma captura é aceita enquanto o histórico estiver pendente ou o cursor novo estiver atrasado. `captureCheckpoint()` também aplica essa regra, além do agendador.
- Ao concluir, a captura ocorre sob o bloqueio do ciclo. A escrita segue assíncrona e transacional, com blocos congelados/cópia na alteração. A atualização em memória continua durante a gravação.
- Falha de escrita mantém os indicadores e tenta novamente respeitando o intervalo de checkpoint. Não reinicia a carga histórica.
- Falha de leitura/processamento descarta o estado de trabalho e preserva a publicação anterior. No próximo ciclo normal, tenta restaurar um checkpoint ou reinicia pelos registros mais recentes. A idade de `evaluatedAt` permite detectar a publicação desatualizada.
- Antes do primeiro checkpoint completo, o progresso histórico existe apenas em memória. Reiniciar nesse período perde esse progresso, por decisão explícita de persistir apenas a carga completa.
- Recuo do relógio ou do limite de IDs invalida o estado de trabalho e provoca reconstrução. Mudanças de regras incompatíveis continuam rejeitando checkpoints antigos.
- Não se alterou o formato persistido nem a assinatura de compatibilidade, pois um checkpoint completo tem o mesmo conteúdo e as mesmas regras anteriores.

Para o formato, integridade, identidade de origem e restauração, consultar [Checkpoints dos indicadores e gráficos](checkpoints-indicadores.md).

## Configuração e dimensionamento

| Variável | Padrão | Efeito |
| --- | --- | --- |
| `INDICATORS_BOOTSTRAP_PAGE_SIZE` | `10000` | Máximo por página histórica e amplitude de IDs novos por etapa. Aceita de 1 a 100000. |
| `INDICATORS_BOOTSTRAP_DELAY_MS` | `1000` | Pausa entre etapas de continuação. Usar valor positivo; valores muito pequenos aumentam consultas e publicações. |
| `INDICATORS_CHECKPOINT_INTERVAL_MS` | `300000` | Intervalo entre tentativas de persistência após a carga completa. |
| `PING_RETENTION_HOURS` | `48` | Retenção dos gráficos, limitada a 48 horas. O horizonte de leitura também contempla o necessário para os indicadores. |

O lote é definido por quantidade, não por tempo. Uma base com 100 mil registros requer dez páginas de 10 mil mais uma consulta vazia de confirmação; uma base com 8 mil registros conclui em uma página, mesmo que cubra 48 horas. A última página pode conter menos registros. Diminua a página se os ciclos demorarem; aumente gradualmente se o banco e a memória comportarem.

Cada etapa prioriza os novos IDs e depois lê uma página histórica. Portanto, 10 mil é o limite de cada página histórica, não o limite total de contribuições incorporadas no ciclo, que também inclui novos registros e liberação de amostras futuras. A duração total depende do número de páginas, da pausa configurada e do custo de consulta, cálculo e publicação; não depende de percorrer 576 janelas temporais vazias.

A memória final continua proporcional ao histórico retido e à quantidade de hosts, com custo adicional durante a captura de checkpoint. Paginação limita o volume temporário de leitura; não torna o estado final de 48 horas de tamanho constante. Medir heap, pausas de GC, duração das publicações e capacidade de acompanhar a taxa de ingestão antes de aumentar limites.

## Implantação e índice

`schema.sql` adiciona `idx_ping_time_id` sobre `(measured_at, id)`. O índice é essencial para a leitura descendente e continuação sem revarrer todo o histórico.

Em banco grande, criar esse índice pelo inicializador pode alongar a inicialização e bloquear escritas durante a construção. Para implantação em PostgreSQL com tráfego, o operador deve criá-lo previamente, fora de uma transação, com:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ping_time_id
ON ping_sample (measured_at, id);
```

Validar que o índice terminou e está válido antes de iniciar a versão nova; uma criação concorrente interrompida pode deixar um índice inválido que `IF NOT EXISTS` não repara. Não é necessário apagar dados nem checkpoints. O índice antigo de horário permanece; eventual remoção deve ser decidida com base nos demais planos de consulta.

## Observabilidade e diagnóstico

Os logs continuam em `routiva.indicators`:

- `INITIAL_LOAD_STARTED`: fixa a divisão inicial de IDs.
- `INITIAL_LOAD_PROGRESS`: publicação parcial, com `load_phase`, `loaded_from`, `target_from`, `cursor` e `boundary`.
- `HISTORY_LOAD_COMPLETED`: não resta histórico necessário; ainda conferir o cursor novo.
- `CYCLE_COMPLETED`: ciclo concluído, com cobertura e atraso identificáveis pelos mesmos campos.
- `CHECKPOINT_SAVED`: primeira carga durável ou atualização posterior confirmada.
- `CYCLE_FAILED` / `CHECKPOINT_WRITE_FAILED`: distinguir reconstrução de estado e falha somente de persistência.

Antes de concluir que a carga parou, verificar se `loaded_from` está recuando ou se páginas com horários iguais estão sendo consumidas (`fetched`), e se `cursor` acompanha `boundary`. Conferir `query_ms`, `processing_ms`, `availability_ms`, `total_ms` e `schedule_overrun`. Não há heartbeat enquanto uma consulta está bloqueada. Para avaliar tempo até o front, medir desde `INITIAL_LOAD_STARTED` até a primeira publicação parcial, não até o checkpoint.

## Testes e manutenção

Executar:

```sh
mvn test
node --test src/test/js/*.test.cjs
```

`ProgressiveBootstrapTests` usa SQL real em H2 para validar ordem recente/antigo, fronteiras entre páginas, paginação com horários iguais, limite de página, chegada de dados atrasados, fila futura, retenção móvel, falha de página e proibição de persistência parcial. Os testes de cálculo e recuperação existentes passam a concluir explicitamente a carga antes de comparar todos os períodos. `AvailabilityCacheTests` mantém a comparação com as contribuições brutas em todas as janelas; `AnalyticsCheckpointTests` valida equivalência após reinício e falhas de persistência. Os testes JavaScript verificam o aviso de cobertura parcial.

H2 verifica resultados e sintaxe compatível, mas não comprova o plano de execução ou desempenho no PostgreSQL de produção. Em homologação, usar o volume real de até 48 horas, conferir o plano da consulta paginada e medir primeira publicação, duração total, custo por página, heap máximo, atraso dos IDs novos e duração de gravação/restauração.

Ao modificar a estratégia, preservar especialmente: a divisão fixa de IDs, páginas sem sobreposição, cursor atualizado apenas após consumo bem-sucedido, cobertura conservadora para horários compartilhados entre páginas, publicação imutável e bloqueio de checkpoints parciais. Se forem introduzidos escritores paralelos, retomada parcial persistida ou mudança do formato, revisar explicitamente esses invariantes e a assinatura do checkpoint.
