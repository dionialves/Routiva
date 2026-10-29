> Documento histórico da arquitetura anterior. A implementação atual usa [resumos persistidos por minuto](agregacao-por-minuto.md), sem históricos brutos em memória ou checkpoints.

# Coleta incremental dos indicadores

O serviço agrega novos registros em memória e salva checkpoints. Não consulta páginas históricas nem reconstrói o estado a partir de medições antigas de `ping_sample`.

## Inicialização

Com checkpoint válido, restaura os agregados, as amostras futuras e o cursor salvo. Lê os IDs posteriores ao cursor, incluindo registros recebidos durante a parada, respeitando a retenção.

Sem checkpoint válido, captura o limite confirmado de IDs, inicializa o cursor nesse limite e publica o estado vazio. Somente IDs confirmados depois desse ponto entram nos indicadores. Registros antigos permanecem no banco conforme a retenção, mas não são carregados. As janelas ganham dados conforme chegam novas medições.

Checkpoints v2 existentes continuam compatíveis. Se contiverem uma posição de carga histórica incompleta (`bootstrap`), ela é ignorada. Os agregados já persistidos são preservados, sem tentar completar o passado que faltava.

## Ciclo e consistência

- O ciclo regular ocorre a cada dez segundos e expira as janelas mesmo sem novos dados.
- A leitura incremental usa páginas de IDs crescentes. O cursor só avança após aplicação bem-sucedida da página.
- Enquanto houver IDs pendentes, o mesmo agendador executa etapas adicionais sem sobreposição.
- Medições atrasadas com IDs novos corrigem os agregados dentro da retenção. Medições futuras aguardam seu horário e são incluídas no checkpoint.
- Falhas de leitura preservam a publicação anterior e permitem repetir a página sem duplicação.
- Recuo do relógio ou do limite de IDs reinicia o estado no limite atual, sem recuperação histórica.
- As requisições HTTP leem somente a publicação imutável em memória.

`COMPLETE` significa que o cursor alcançou o limite confirmado; não significa cobertura de 48 horas. `CATCHING_UP` indica novos IDs pendentes. A interface informa “Indicadores atualizados” quando alcança o limite.

## Configuração

| Variável | Padrão | Função |
| --- | --- | --- |
| `INDICATORS_INCREMENTAL_PAGE_SIZE` | `10000` | Máximo de IDs por etapa (1 a 100000). |
| `INDICATORS_INCREMENTAL_DELAY_MS` | `1000` | Intervalo entre etapas adicionais (mínimo 1 ms). |
| `INDICATORS_CHECKPOINT_INTERVAL_MS` | `300000` | Intervalo entre tentativas de checkpoint (mínimo 1000 ms). |

As variáveis antigas `INDICATORS_BOOTSTRAP_*` foram removidas. Migre tamanho e intervalo para os nomes acima. O timeout específico da consulta histórica deixa de existir.

O checkpoint é capturado após um ciclo publicado e gravado em segundo plano, mantendo duas gerações. Consulte [checkpoints](checkpoints-indicadores.md) para formato, integridade e operação.
