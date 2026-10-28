# Disponibilidade em memória

Implementado em 28/10/2026 a partir da proposta de disponibilidade incremental.

## Fluxo entregue

O `PacketLossAnalysisService` continua sendo o único coordenador de indicadores, no agendador de 10 segundos. A captura do limite de IDs confirmados e a leitura incremental do `SampleStore` alimentam tanto os indicadores existentes quanto o novo `AvailabilityHistory`. A leitura agora inclui `latency_limit`, preservando a classificação histórica da amostra.

A inicialização restaura um checkpoint válido ou começa vazia no limite atual de IDs. A leitura incremental considera o maior horizonte entre o analisador e a disponibilidade, limitada a 48 horas. Amostras antigas utilizadas apenas pela disponibilidade não aumentam o histórico bruto do analisador de latência/perda. O novo histórico mantém arrays compactos de deslocamento no minuto, latência e classificação, além dos totais de cada minuto. As contribuições individuais são usadas para recortar as bordas exatas dos períodos; os minutos completos reutilizam seus totais. Os totais dos sete períodos são compostos dessas contagens no background, nunca na requisição HTTP.

A publicação usa uma única referência imutável para latência, perda e disponibilidade. Falhas preservam a publicação anterior e permitem repetir a página no ciclo seguinte. A versão combina a identidade da execução e a sequência do ciclo. Dados atrasados corrigem minutos anteriores; futuros aguardam sua referência; períodos expiram mesmo sem novas amostras. Mudanças de IP separam o histórico.

`HostAvailabilityService` é uma fachada de leitura. A página e a API não contêm consultas SQL ou gatilhos de atualização. Antes da primeira publicação retornam um relatório pré-construído sem referência de cálculo. A API entrega o snapshot completo do período e usa `Cache-Control: no-store`; transmissão de deltas não faz parte desta entrega.

## Gráficos e leitura

- Um bucket por minuto, alinhado em UTC e apresentado no fuso do navegador, em todos os períodos.
- Cards e séries usam `[referência − período, referência)`. Bordas parciais são recortadas. Um período de 48 horas pode ter 2.881 pontos.
- O minuto atual é provisório; atualizações de 10 segundos substituem esse ponto. A mudança do minuto acrescenta o próximo ponto.
- Latência inclui somente respostas. Perdas não recebem latência zero nem valor de timeout.
- Qualquer minuto com perda tem marcador vermelho na faixa abaixo da curva de latência. Perda total interrompe a curva; ausência de tentativas elegíveis tem marcador cinza.
- Erros internos são contados separadamente e não entram no denominador dos percentuais. Totais usam contagens, não a média dos percentuais de minutos.
- Clique no gráfico ou use as setas no controle de minutos para inspecionar as contagens e percentuais. A seleção de um minuto anterior é preservada nas atualizações enquanto ele estiver no período.
- A página consulta a cada 10 segundos, evita requisições sobrepostas, limita cada consulta a 9 segundos e descarta respostas de seleções antigas. Uma publicação com a mesma versão não redesenha os gráficos.
- Horário do cálculo e última medição são separados. Publicações com mais de 30 segundos recebem aviso. Falhas de consulta preservam os dados anteriores; retenção menor que o período é informada.

Uma resposta isolada não comprova cobertura integral do minuto. Esta entrega apresenta contagens e a ressalva de cobertura; não estima duração de indisponibilidade a partir de contagens de perdas nem adiciona um estado instantâneo de incidente.

## Observabilidade e capacidade

O log de ciclos mantém os campos existentes e adiciona `availability_hosts`, `availability_buckets` e `availability_ms`. O último mede a montagem dos relatórios de disponibilidade e dos relatórios vazios; ingestão e expiração continuam incluídas no tempo geral de processamento. `retained_pings` continua contando apenas o histórico do analisador existente, não as contribuições da disponibilidade.

Ensaio local sintético do acumulador, com Java e heap máximo de 768 MiB: 50 hosts, um resultado por segundo, 48 horas completas por host (8.640.000 resultados), uma perda por minuto, sete períodos e seis ciclos incrementais de 10 segundos. Resultado observado:

| Medição | Resultado |
| --- | --- |
| Ingestão inicial em memória | 663 ms |
| Primeira montagem dos relatórios | 85 ms |
| Média dos seis ciclos incrementais | 35 ms |
| Heap ocupado após coleta de lixo, mantendo históricos e última publicação | 179,5 MiB |

O ensaio não inclui banco, histórico de latência/perda, transporte HTTP, renderização nem concorrência real. Não é uma validação de produção. A retenção de contribuições para períodos móveis exatos tem custo significativo; dimensionar o heap com o inventário real e observar duração dos ciclos antes de ampliar o número de hosts. O agendador não sobrepõe ciclos: se ultrapassar 10 segundos, a publicação atrasa e o log registra `schedule_overrun`.

## Validação

- 77 testes Java aprovados, incluindo integração com H2 e testes existentes de coleta/latência/perda.
- Testes novos verificam que página, API e troca de período não acessam `SampleStore`; publicação só muda após o ciclo.
- Comparação com cálculo de referência por amostra para os sete períodos, com diferentes limites históricos, bordas exatas e avanço do relógio.
- Casos de perda isolada, perda total, erros internos, correção tardia, futuro, retenção reduzida, troca de IP, falha parcial, reconstrução, reinício e relógio retrocedendo.
- Testes do frontend cobrem cadência, não sobreposição, versão repetida, troca rápida de período, falha HTTP, timeout, recuperação, carregamento, desatualização e marcadores.
- Conferência visual local com dados simulados para curvas, perdas e lacunas. Não houve implantação ou teste contra o banco/coletor de produção.

Comandos:

```sh
mvn test -Dcollector.enabled=false
node --test src/test/js/*.test.cjs
```
