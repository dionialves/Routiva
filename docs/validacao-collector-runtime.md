# Validação operacional — 22/10/2026

Inspeção dos três serviços ativos no Docker local, aproximadamente às 17h05–17h07 (America/Sao_Paulo). Esta evidência complementa o ensaio simulado em coletor-icmp.md; não é teste de longa duração nem certificação de 10.000 hosts.

## Causa e correção

O ambiente efetivo do Java tinha PING_INTERVAL_MS=5000, vindo do .env existente. A consulta dos horários originais no PostgreSQL confirmou intervalo médio de 5.000 ms para 734 hosts. O valor externo prevalecia sobre o padrão de 1.000 ms do código.

Alterado somente PING_INTERVAL_MS para 1000 no .env e recriado o serviço app, sem rebuild. Preservado PING_TIMEOUT_MS=2000. A variável antiga PING_CONCURRENCY=64 permanece no ambiente, mas não é utilizada pelo motor Go.

O coletor e PostgreSQL permaneceram ativos. A identidade do coletor não mudou; connections_total passou de 1 para 2 devido à reconexão do Java.

## Evidências depois da correção

- 734 hosts ativos.
- 14.760 envios em aproximadamente 20,134 segundos: 733,09 envios/s, compatível com 734/s considerando a borda de medição.
- PostgreSQL: 14.681 amostras numa janela de 20 segundos, encerrada cinco segundos antes da consulta para incluir timeouts concluídos.
- Intervalo planejado médio: 1.000,00 ms; intervalo entre envios reais: 1.000,05 ms.
- Atraso de envio p95: 3,72 ms.
- Variação dos contadores: zero descartes, skips, erros de envio, erros de recepção, erros internos e reconexões durante a observação.
- Último tempo de ACK observado: 7,53 ms, incluindo persistência/publicação e comunicação.
- Buffer observado: 49 resultados; 60 pendências; 17 goroutines; heap Go aproximadamente 4 MB.
- Health do Java: UP. Os três contêineres estavam healthy.

Houve 635 timeouts no intervalo dos contadores (aproximadamente 4,3% dos envios), separados das falhas internas, que permaneceram zeradas. A janela não permite atribuir causa aos destinos sem resposta; esse diagnóstico requer análise por destino/rede. Consultas SQL e snapshots dos contadores são realizados em instantes diferentes, portanto seus totais não devem ser comparados como uma única transação.

Uma amostra de docker stats depois da mudança indicou Java ~300 MiB, Go ~15 MiB e PostgreSQL ~118 MiB. São leituras pontuais, não dimensionamento de memória para a retenção completa.

## Observações

O refresh do painel é independente da cadência ICMP: a tela pode atualizar a cada cinco segundos enquanto há uma tentativa por segundo por host.

Os logs de inicialização mostram aviso do Netty sobre SO_KEEPALIVE em Unix Domain Socket, sem interromper o stream. Também houve aviso de inicialização de beans durante a publicação de amostras recuperadas na reconexão. Não foram observadas falhas de persistência no período consultado; esses avisos não explicavam o intervalo de cinco segundos.

A configuração foi aplicada no ambiente ativo. Não foram alterados código, timeout, banco, frontend ou imagem do coletor nesta validação. A alteração do .env não é versionada pelo Git.
