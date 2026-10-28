# Checkpoints dos indicadores e gráficos

A aplicação mantém os cálculos em memória e grava um ponto de recuperação no PostgreSQL já no primeiro ciclo publicado, mesmo com carga parcial, e, depois, a cada cinco minutos. O intervalo é configurável por `INDICATORS_CHECKPOINT_INTERVAL_MS` (padrão `300000`, mínimo `1000`). Não altera a cadência de dez segundos dos indicadores.

## Conteúdo e estrutura

O checkpoint contém o histórico compacto de indicadores por host/IP, a última amostra completa de cada histórico, o histórico de disponibilidade de até 48 horas e as amostras futuras já lidas. Também salva a referência temporal, o cursor confirmado e uma assinatura das regras de cálculo/retenção. As contribuições intraminuto preservam as bordas exatas dos sete períodos e os limites históricos de latência.

A implementação usa quatro tabelas compartilhadas pelos tipos de dados, em vez de tabelas separadas por tipo:

- `analytics_checkpoint`: metadados e checksum do manifesto de uma geração completa.
- `analytics_checkpoint_block`: blocos binários versionados, comprimidos com gzip e identificados por SHA-256.
- `analytics_checkpoint_part`: manifesto ordenado que associa geração, tipo, host/IP e bloco. Os tipos são `indicator`, `last`, `availability`, `pending` e `progress`. Este último preserva o limite confirmado de IDs. Partes `bootstrap` de checkpoints antigos são ignoradas.
- `analytics_checkpoint_guard`: serialização das gravações/limpezas e identidade da origem.

Os indicadores preservam seus blocos compactos. Os minutos dos gráficos são agrupados por hora na persistência; a resolução exibida continua sendo de um minuto. Relatórios dos sete períodos e percentuais são reconstruídos em memória; não são duplicados no banco.

## Consistência e custo

A captura ocorre no fim de um ciclo concluído. Ela guarda referências a blocos congelados, sem comprimir todo o histórico sob o bloqueio do ciclo. Alterações posteriores copiam somente os blocos/minutos afetados. O trabalhador `checkpoint-writer` comprime e grava em segundo plano. Há no máximo uma gravação em andamento por instância.

Blocos inalterados reutilizam seus bytes e seu registro no banco. O manifesto de cada geração é completo. Uma única transação grava os blocos novos, o manifesto e os metadados, mantém as duas gerações mais recentes e remove os blocos sem referências. Se a escrita falhar ou o processo cair antes do commit, a geração anterior permanece disponível. A falha não descarta os indicadores em memória; a próxima tentativa respeita o intervalo configurado.

Existe custo adicional de memória para bytes comprimidos, referências e cópias dos blocos alterados durante uma gravação. O tamanho do manifesto é proporcional à quantidade de blocos. Não há garantia de tempo de restauração nem de memória no volume de produção sem medição com o inventário real.

## Inicialização e recuperação

1. Captura o limite confirmado de IDs pelo protocolo existente.
2. Procura as gerações mais recentes, validando assinatura, origem, cursor e referência temporal.
3. Lê os blocos em fluxo e verifica seus checksums e o manifesto completo.
4. Restaura diretamente os históricos compactos e reconstrói contadores/relatórios na referência salva.
5. Publica o estado restaurado com sua data original, permitindo ao frontend indicar desatualização.
6. Expira as janelas, lê IDs posteriores ao cursor salvo sem consultar páginas históricas.
7. Publica indicadores e gráficos atualizados em conjunto.

Amostras futuras precisam ser persistidas: seus IDs já estão incluídos no cursor, mesmo sem terem sido incorporadas aos cálculos. O checkpoint mantém essa fila para que sejam liberadas no momento correto, sem perda ou duplicação.

Uma geração com payload/manifesto inválido é rejeitada e a anterior é tentada. Ausência de geração utilizável inicia o estado vazio no limite atual de IDs; somente novos registros serão agregados. Falha de acesso ao banco é propagada para nova tentativa no próximo ciclo; não é confundida com ausência de checkpoint.

Cada lote é aplicado atomicamente usando cópias dos índices dos hosts afetados e cópia dos blocos/minutos somente na alteração. Falha de consulta ou processamento preserva os lotes anteriores; o cursor só avança com o lote confirmado. Mudança de parâmetros incompatível, relógio anterior à referência ou cursor além do limite atual impedem a reutilização. Dados já removidos pela retenção não podem ser recuperados pela leitura incremental.

A identidade da origem protege contra reutilização de checkpoints de outra base quando a tabela de controle é reinicializada. Uma substituição manual de `ping_sample` que preserve as tabelas de checkpoint e reutilize IDs não é detectável apenas pelo cursor: nesse procedimento administrativo, também devem ser invalidados os checkpoints e renovada a identidade da origem. Backups/restaurações devem incluir as tabelas de dados e checkpoint no mesmo ponto consistente.

## Operação

Sem checkpoint válido, a aplicação inicia vazia e acompanha novos registros. Checkpoints v2 continuam compatíveis, mas sua antiga posição de leitura histórica não é retomada. Consulte [a coleta incremental](coleta-incremental-indicadores.md). Aguarde `CHECKPOINT_SAVED` antes de avaliar uma reinicialização como recuperação persistida.

```bash
docker compose exec app tail -F /var/log/routiva/indicators.log
```

Eventos principais:

- `INITIAL_LOAD_STARTED`, `INITIAL_LOAD_COMPLETED`, `CYCLE_COMPLETED`.
- `CHECKPOINT_RESTORE_STARTED`, `CHECKPOINT_RESTORE_PROGRESS`, `CHECKPOINT_RESTORED`.
- `CHECKPOINT_REJECTED`, `CHECKPOINT_UNAVAILABLE`.
- `CHECKPOINT_CAPTURE_STARTED`, `CHECKPOINT_CAPTURE_COMPLETED`.
- `CHECKPOINT_WRITE_STARTED`, `CHECKPOINT_WRITE_PROGRESS`, `CHECKPOINT_SAVED`.
- `CHECKPOINT_CAPTURE_FAILED`, `CHECKPOINT_WRITE_FAILED`.

O progresso de leitura é emitido a cada ciclo publicado. Não é um heartbeat durante uma chamada de banco bloqueada. Na escrita, há progresso por etapa: codificação, blocos e manifesto. `bytes` em `CHECKPOINT_SAVED` representa os bytes comprimidos referenciados pela geração, inclusive blocos reutilizados, não apenas os bytes novos gravados.

A captura e a restauração contam no tempo do ciclo correspondente; a escrita assíncrona tem duração própria. Os horários preservam UTC no arquivo de log.

## Validação

Os testes cobrem equivalência dos indicadores e dos sete períodos após reinício, bordas móveis, amostras futuras/atrasadas, última amostra, imutabilidade de captura durante alterações/expiração, reutilização de blocos, manutenção de duas gerações, rollback de escrita inválida e fallback para geração anterior ou inicialização vazia por incompatibilidade.

Antes de dimensionar para produção, medir duração e tamanho de gravação/restauração, pico de heap e impacto nos ciclos de dez segundos com o histórico real de até 48 horas. A carga anterior de 20 milhões de registros não representa necessariamente o novo horizonte dos gráficos.
