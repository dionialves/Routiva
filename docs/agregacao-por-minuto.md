# Indicadores a partir de resumos persistidos por minuto

## Fluxo

O coletor grava os pings em `ping_sample`. A aplicação não mantém histórico bruto em memória. Um processo agendado agrega novos IDs no PostgreSQL a cada 60 segundos e grava `ping_minute`. As requisições calculam somente o período e grupo solicitados a partir dessa tabela. Não existe cache de indicadores nem recuperação por checkpoint.

Cada resumo é identificado por `(host_id, target, minute_at)`. O minuto é UTC, definido pelo horário da medição, em intervalo semiaberto `[início, início + 60000)`. Contém `total_pings`, `healthy`, `degraded`, `lost`, `errors`, `latency_sum` e `last_sample_at`. Respostas acima do `latency_limit` da própria amostra são degradadas. Timeout e erro ICMP contam como perda; erros internos ficam separados. Total bruto = saudáveis + degradados + perdas + erros. O denominador dos percentuais exclui erros internos.

Média = soma das latências / quantidade de respostas. Nunca se faz média simples das médias de minutos ou de hosts. Disponibilidade técnica = 100 × respostas / tentativas válidas; operacional = 100 × saudáveis / tentativas válidas. Grupos somam as contagens e somas antes de calcular as médias e percentuais.

## Retomada e consistência

`ping_aggregation_progress` mantém o último ID consumido, o limite confirmado da execução, o horário de processamento e o limite de minutos completos consolidados. Não contém pings nem objetos serializados.

1. Capturar o limite de IDs confirmados sob `ping_ingestion_guard`, usando uma transação curta. Todos os escritores devem respeitar esse bloqueio antes de alocar IDs; gravações externas e reutilização/alteração de IDs não são suportadas.
2. Selecionar até `INDICATORS_AGGREGATION_PAGE_SIZE` novos IDs até esse limite. A seleção não filtra por horário: seis horas pendentes continuam elegíveis.
3. Agrupar o intervalo em SQL, sem trazer pings individuais ao Java.
4. Somar as contribuições nos resumos existentes ou criar os ausentes.
5. Atualizar o cursor na mesma transação. `FOR UPDATE` na linha de progresso serializa consumidores e limpeza. Rollback desfaz resumos e cursor juntos; repetir um lote não duplica valores.
6. Continuar imediatamente com o próximo lote até alcançar o limite capturado. Depois aguardar o intervalo configurado. Novos IDs posteriores ao limite ficam para a próxima execução.

Um minuto pode receber contribuições de muitos lotes. Pings atrasados corrigem o resumo do seu minuto original. O resumo do minuto atual também pode estar persistido, mas as consultas excluem minutos ainda abertos. Não há fila de pings futuros em memória.

As consultas usam uma transação `REPEATABLE_READ`, incluindo progresso, totais e série, para não misturar versões de lotes. O agrupamento de totais é por host/IP e o da série por minuto diretamente em SQL. O backend não cria uma série de 48 horas por host apenas para somar o gráfico do grupo.

## Atualização e precisão

Períodos suportados: 5 min, 1 h, 8 h, 24 h e 48 h. O período termina no limite consolidado de minutos completos, ancorado na captura do limite de IDs da execução concluída. Durante recuperação, o limite completo anterior é preservado e `load.phase=CATCHING_UP`. Antes da primeira execução concluída, os resultados são explicitamente parciais. A interface mostra o horário consolidado e o processamento pendente/desatualizado.

As bordas são alinhadas ao minuto, não a uma janela móvel exata ao segundo. A atualização pode levar aproximadamente dois minutos com o intervalo padrão. Resultados atrasados podem corrigir minutos antigos.

O status de coleta considera a última medição nos resumos recentes, com tolerância de intervalo de agregação + dois minutos. A cor continua combinando perda nas janelas de 5 min, 1 h e 8 h e limites de latência de 15/20 ms. Os antigos diagnósticos de sequências individuais e cobertura entre pings não são reconstruídos: não são necessários aos campos atuais do painel.

## Retenção e migração

O esquema cria `ping_minute` e `ping_aggregation_progress` automaticamente. Na primeira execução o cursor começa em zero e agrega todos os pings ainda disponíveis no banco, em lotes. Não precisa de backfill manual. Dados brutos já excluídos não podem ser recuperados de checkpoints antigos por esta implementação.

A limpeza respeita a retenção configurada, limitada a 48 horas, mas só apaga pings cujo ID já foi processado. Isso protege atrasos de seis horas ou mais. Uma parada prolongada da agregação pode aumentar o uso de disco; monitore os eventos de falha e o cursor. Resumos vencidos também são removidos. Os IDs permanecem monotônicos mesmo quando todos os pings já processados foram limpos.

As tabelas `analytics_checkpoint*` existentes são ignoradas e preservadas para possibilitar retorno à versão anterior. Não são criadas em bancos novos. Não execute exclusão dessas tabelas durante a primeira implantação. O código, tarefas e configurações de checkpoint foram removidos; sua exclusão física pode ser feita separadamente após validar a migração e o plano de retorno.

Na implantação, execute apenas uma versão da aplicação por banco. Não mantenha simultaneamente uma versão antiga cuja limpeza não protege os pings pendentes.

## Configuração

```dotenv
INDICATORS_AGGREGATION_INTERVAL_MS=60000
INDICATORS_AGGREGATION_PAGE_SIZE=10000
```

O tamanho deve estar entre 1 e 100000. O intervalo deve ser positivo. As antigas variáveis `INDICATORS_INCREMENTAL_*` e `INDICATORS_CHECKPOINT_INTERVAL_MS` não são usadas.

## Operação

```sh
docker compose logs --tail=100 app
docker compose exec -T app tail -n 30 /var/log/routiva/indicators.log
```

Eventos: `MINUTE_BATCH_COMMITTED` (cursor, limite, quantidade de pings e resumos), `MINUTE_AGGREGATION_COMPLETED` (duração do ciclo) e `MINUTE_AGGREGATION_FAILED` (retentativa no próximo ciclo). O cursor é durável por lote, mesmo se um ciclo longo for interrompido.

Para consultar a posição, no PostgreSQL:

```sql
SELECT * FROM ping_aggregation_progress;
SELECT count(*) AS summaries, min(minute_at), max(minute_at) FROM ping_minute;
```

Consultas não utilizam `ping_sample` nem checkpoints. Cache permanece desabilitado para medir primeiro o custo real das consultas agregadas.

## Validação

```sh
mvn test -Dcollector.enabled=false
node --test src/test/js/*.test.cjs
```

As suítes `MinuteAggregationTests`, `MinuteReportTests` e `AvailabilityDashboardTests` usam H2 por padrão. Para repetir no PostgreSQL, use um banco **exclusivo de testes**, usuário `postgres` e senha `routiva-test-only`:

```sh
ROUTIVA_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:PORTA/postgres mvn -Dtest=MinuteAggregationTests,MinuteReportTests,AvailabilityDashboardTests test
```

Cada teste cria e remove seu próprio esquema. Cobertura: lotes no mesmo minuto, seis horas pendentes, reinício, rollback, concorrência, IDs com lacunas, atraso, troca de IP, retenção protegida, limites históricos, média ponderada, períodos alinhados, paginação e consultas sem dados brutos.
