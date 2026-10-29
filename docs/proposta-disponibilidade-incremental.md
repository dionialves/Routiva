> Documento histórico da arquitetura anterior. A implementação atual usa [resumos persistidos por minuto](agregacao-por-minuto.md), sem históricos brutos em memória ou checkpoints.

# Proposta: disponibilidade incremental no detalhe do host

Status: primeira etapa implementada em 28/10/2026. Este documento preserva a análise e as decisões da proposta; consulte [implementação e validação](disponibilidade-incremental.md) para o comportamento entregue. A transmissão de deltas permanece uma evolução opcional.

## Situação atual

- `HostDetailsController` chama `HostAvailabilityService.report()` tanto ao abrir `/hosts/{id}` quanto ao consultar a API. Cada chamada lê amostras do banco e recalcula totais e gráficos. Atualizar a página ou trocar o período provoca uma nova consulta.
- `host.html` tem quatro cards: disponibilidade operacional (principal), disponibilidade técnica, perda de pacotes e degradação por latência. Há três gráficos: disponibilidades, perda/degradação e latência média.
- `host.js` desenha o relatório recebido no HTML. Não há atualização periódica; a troca do período submete o formulário.
- Os períodos são 1, 2, 4, 8, 12, 24 e 48 horas, com 24 horas como padrão. Os pontos atuais agrupam 1, 5 ou 15 minutos conforme o período, com início relativo ao instante da consulta.
- As fórmulas atuais estão adequadas: contagens ponderadas por amostras, latência somente de respostas e erros internos excluídos dos percentuais. Cada amostra usa o limite de latência vigente na coleta.
- `PacketLossAnalysisService` já tem o ciclo independente de 10 segundos, cursor de IDs confirmados, leitura incremental, tratamento de atrasos/futuros, expiração e publicação atômica em memória. Falhas preservam a publicação anterior e provocam reconstrução no próximo ciclo.

## Comportamento proposto

Manter os quatro cards e os três gráficos, reutilizando o ciclo existente. Abrir a página, trocar período ou atualizar o navegador somente lê o último relatório publicado. Não dispara coleta, consulta de pings nem cálculo de indicadores.

O backend publica a cada 10 segundos. O navegador consulta a API a cada 10 segundos, sem requisições sobrepostas, e atualiza os componentes somente quando muda a versão publicada. A cadência do navegador é independente: a visualização pode ter atraso adicional de até um intervalo de consulta, além do processamento e da rede.

Mostrar “Indicadores calculados às …” e “Última medição às …”, separadamente. A primeira data não comprova que a coleta está funcionando. Antes da primeira publicação, mostrar “Carregando indicadores”; após uma publicação sem amostras elegíveis, mostrar “Sem dados”. Em falha de consulta, manter os valores anteriores com aviso; em falha do ciclo, a idade da publicação evidencia a desatualização. Proposta inicial: aviso após 30 segundos sem publicação, configurável.

Os cards continuam representando o período selecionado, não o estado instantâneo do host. Remover a aparência permanentemente saudável do card operacional: sem dados usa apresentação neutra; perdas e degradação devem ter rótulos explícitos. Limiares para cores de disponibilidade exigem uma política própria e não devem ser copiados dos limiares de perda automaticamente.

## Pontos de 60 segundos e perdas

Usar intervalos fixos alinhados ao relógio, por exemplo `[14:32:00, 14:33:00)`, indexados pelo horário da medição em UTC e apresentados no fuso do navegador. Todos os períodos usam a mesma resolução de um minuto.

Para cada minuto armazenar:

- H: respostas dentro do limite histórico da amostra;
- D: respostas acima desse limite;
- P: perdas de rede;
- E: erros internos de coleta, separados;
- soma de latências e quantidade de respostas;
- primeira/última medição, versão do ponto e indicação de intervalo parcial.

Com T = H + D + P:

| Indicador | Cálculo |
| --- | --- |
| Disponibilidade operacional | 100 × H / T |
| Disponibilidade técnica | 100 × (H + D) / T |
| Perda | 100 × P / T |
| Degradação | 100 × D / T |
| Latência média | soma das latências / (H + D) |

Divisão por zero resulta em valor nulo. Nunca atribuir latência zero ou o tempo de timeout a um pacote perdido. Não tirar média dos percentuais dos minutos para calcular os cards; somar as contagens primeiro.

Exemplo com coleta de um ping por segundo e respostas de 10 ms, todas dentro do limite:

| Resultados no minuto | Latência média | Perda | Disponibilidade técnica |
| --- | --- | --- | --- |
| 60 respostas | 10 ms | 0% | 100% |
| 59 respostas e 1 perda | 10 ms | 1,67% | 98,33% |
| 50 respostas e 10 perdas | 10 ms | 16,67% | 83,33% |
| 60 perdas | Sem resposta | 100% | 0% |
| Nenhuma tentativa elegível | Sem dados | Indefinida | Indefinida |

**Solução visual recomendada:** manter a curva de latência média e adicionar uma faixa de eventos abaixo dela, com marcador vermelho em todo minuto com P > 0. O marcador fica visível mesmo com uma única perda e independe da escala de latência. O gráfico de perda exibe o percentual real. O tooltip informa intervalo, contagens H/D/P/E, percentual de perda e latência média. Os mesmos dados devem estar acessíveis por foco ou seleção, sem depender exclusivamente de cor ou hover.

No minuto com 100% de perda, interromper a curva de latência e manter o marcador vermelho. No minuto sem coleta ou somente com erros internos, interromper a curva e mostrar indicação neutra de ausência de dados, nunca 100% de perda. Intervalos com poucas amostras devem expor a contagem e alertar que a coleta pode ser incompleta; uma resposta não comprova disponibilidade durante todo o minuto.

A primeira entrega não deve converter quantidade de perdas em segundos de indisponibilidade: pings perdidos podem estar separados e a cadência pode variar. Duração contínua de incidentes exigiria uma regra de transição e tratamento de lacunas.

## Minuto em andamento e períodos

Recomendação: incluir o minuto atual como ponto provisório, atualizado no mesmo lugar a cada 10 segundos. Um novo ponto surge somente quando muda o minuto. Isso mantém visíveis falhas recentes sem produzir seis pontos por minuto. Identificar “minuto em andamento” no tooltip. Minutos encerrados ainda podem ser corrigidos por amostras atrasadas.

Preservar os períodos móveis atuais: cards calculados sobre `[referência − período, referência)`. Os buckets das bordas são recortados a esse intervalo e identificados como parciais; não incorporar amostras anteriores ao período. Assim, a soma das contagens dos pontos corresponde aos cards. Um período de 48 horas pode intersectar até 2.881 buckets de minuto, contando ambas as bordas parciais.

## Integração com o processamento existente

1. Estender a amostra incremental para carregar `latency_limit`. Hoje `PingSample` e `SampleStore.readIncrement()` não transportam esse campo, embora esteja no banco e seja usado pelo relatório atual. Preservar compatibilidade com os consumidores existentes e testar mudanças do limite.
2. Dentro do mesmo ciclo, distribuir cada amostra para o histórico atual de latência/perda e para um acumulador de disponibilidade por `(hostId, IP, minuto)`. Manter um único cursor e uma única leitura dos IDs novos, sem criar outro agendador nem consultas por página.
3. A carga inicial deve alcançar o maior horizonte necessário: o detalhe oferece até 48 horas, mas o analisador atual precisa de no mínimo 8 horas. Ler o horizonte ampliado em fluxo; encaminhar ao histórico bruto de latência/perda apenas as amostras de sua janela. Para disponibilidade, reter agregados de minutos, evitando multiplicar por seis todo o histórico bruto existente.
4. Agregados de minuto, sozinhos, não permitem recortar exatamente as bordas dos períodos móveis. Para preservar a precisão atual, manter timestamps e contribuições compactas intraminuto durante o horizonte disponível, usando-os somente no recorte das bordas. Essa retenção adicional exige medição de memória; não presumir o custo de um cache de agregados puros. A alternativa mais econômica é alinhar também os períodos a minutos completos, aceitando até 60 segundos de atraso nos cards. A proposta prioriza preservar os períodos móveis e tornar esse custo explícito; o dimensionamento é um critério de aceite antes da implantação.
5. Atualizar contagens apenas dos buckets afetados e expirar os vencidos, mesmo sem novas amostras. Processar atrasos pelo horário da medição; não congelar minutos encerrados. Preservar a identidade host/IP, sem misturar históricos após alteração do destino.
6. Preparar totais dos sete períodos no background. Publicar indicadores e disponibilidade juntos em um único snapshot imutável, com `runId`, `version`, `evaluatedAt` e última medição por host. Se qualquer etapa falhar, manter o snapshot completo anterior; reconstruir o estado de trabalho no próximo ciclo usando o protocolo existente.
7. `HostAvailabilityService` passa a ler esse snapshot. A página e a API apenas selecionam o host/período e serializam dados já calculados. Nenhuma ausência de cache pode acionar consulta ao banco.

A retenção configurada pode ser inferior a 48 horas. Informar a extensão efetivamente disponível; não preencher o restante como sucesso ou falha. A política de expiração deve impedir que o cache apresente histórico além da retenção suportada.

## Atualização do frontend e contrato

Preservar `GET /api/hosts/{id}/availability?hours=24` e os campos atuais, adicionando versão, referência do cálculo, última medição e metadados dos pontos. `bucketMillis` passa a 60000. Na troca de período, buscar o relatório em memória e atualizar a URL sem recarregar toda a página. Descartar respostas antigas que cheguem depois de uma nova seleção.

Primeira etapa: retornar o snapshot completo do período a cada consulta e atualizar gráficos existentes sem acumular SVGs nem observadores. A agregação do backend já é incremental; transmitir deltas não é requisito para essa etapa. Uma versão igual permite ignorar a atualização visual. Avaliar tamanho do JSON e renderização de 48 horas antes de introduzir mais complexidade.

Segunda etapa, se o volume justificar: consulta com versão conhecida e retorno apenas de pontos alterados/removidos, incluindo correções tardias. Após reinício, mudança de período ou versão não mais disponível, retornar o snapshot completo. Não usar somente “pontos depois do último horário”, pois isso perderia correções de minutos anteriores.

## Entrega incremental e validação

1. Motor de disponibilidade no ciclo existente, incluindo limite histórico, períodos, publicação conjunta e testes de paridade contra o relatório SQL atual em referências fixas.
2. Página/API somente em memória, polling de 10 segundos, estados de carregamento/desatualização e testes que comprovem ausência de SQL ou recálculo ao abrir, recarregar e trocar período.
3. Gráficos de um minuto, marcador de perdas, pontos provisórios e interação acessível. Validar 0, 1, várias e 100% de perdas; ausência de coleta; erros internos; médias com quantidades diferentes de respostas; bordas de minuto/período e chegada tardia.
4. Testar reinício, falha durante o ciclo, IP alterado, relógio retrocedendo, amostras futuras e retenção reduzida. Leitores devem observar somente publicações completas.
5. Medir memória, carga inicial, duração dos ciclos e custo de renderizar 48 horas com o inventário realista. Ampliar os logs existentes com buckets mantidos/alterados, tempo de disponibilidade e tamanho do snapshot. Se o ciclo ultrapassar 10 segundos, reportar atraso, sem sobrepor execuções.

Aceite principal: o volume de acessos ao frontend não altera a quantidade de coletas nem dispara cálculos/consultas de pings; perdas de um único pacote permanecem identificáveis mesmo quando a latência média do minuto está normal.
