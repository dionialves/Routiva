# Clientes Rádio

Acesse **Clientes Rádio** no menu ou `/clientes/radio`. Cada abertura de `/ap/{hostid}` cria uma visita por aba. A coleta reutiliza as leituras wireless da página, inclusive na aba Interfaces. Ela não inicia pings.

## Coleta ao vivo e encerramento

A abertura da visita e o recebimento de amostras usam somente memória no servidor. A resposta wireless não aguarda identificação de clientes no IXC, análise histórica ou gravação no banco. As consultas IXC já existentes para exibir logins na tela continuam independentes e seus nomes podem ser reaproveitados nas próximas amostras, sem chamadas adicionais do histórico durante a visita.

Ao sair da página, o servidor marca a visita como encerrada e responde com HTTP 202 assim que a encaminha ao processamento separado. O mesmo ocorre após dois minutos sem leituras válidas, verificados a cada quinze segundos. Um trabalhador dedicado valida o lote, resolve identidades no IXC e persiste observações e coletas em transação. Se o banco falhar, o lote continua em memória e é tentado novamente depois de trinta segundos. Reenvios e fechamentos concorrentes não duplicam coletas.

As sessões em andamento e os lotes ainda não gravados dependem da memória do processo: uma interrupção abrupta do servidor pode perdê-los. O encerramento normal tenta encaminhar as visitas abertas e aguarda até quinze segundos pelo trabalhador. A listagem Clientes Rádio reflete o lote após seu processamento, não durante a visita.

Por padrão, cada equipamento precisa de duas leituras distintas cobrindo pelo menos dez segundos para gerar histórico. O fim da observação é o horário da última amostra daquele equipamento, mesmo que tenha desconectado antes do fechamento. Visitas curtas ainda permitem cadastrar o equipamento, sem substituir a última coleta elegível.

As variáveis `RADIO_HISTORY_MINIMUM_READINGS`, `RADIO_HISTORY_MINIMUM_DURATION_MS` e `RADIO_HISTORY_INACTIVITY_MS` ajustam os limites. O mínimo de leituras não pode ser menor que dois; a inatividade mínima é trinta segundos. Se uma aba voltar depois da expiração, a página será reaberta para criar uma visita nova.

## Identidade e histórico

O login IXC identifica o cliente e o MAC identifica o equipamento. Sem login inequívoco, preserva-se o vínculo vigente; sem vínculo, cria-se um provisório. Consultas ambíguas permanecem pendentes. Identificações feitas apenas após o encerramento vinculam o cadastro retrospectivamente, sem preencher o login observado nas amostras como se já fosse conhecido naquela data. Um proprietário identificado anteriormente mantém suas observações.

As médias históricas utilizam os valores originais, com contagens individuais, e não os valores `avg-*` do diagnóstico. TX/RX e P Throughput são apresentados em Mbps.

A ação **Vincular login** mostra o destino, os períodos existentes e a quantidade de coletas elegíveis antes da confirmação. A associação registra o operador e sua data de início. Um período que cruza vínculos identificados já encerrados precisa de revisão da data. Limites que dividem uma visita recalculam as coletas a partir das amostras originais; segmentos sem cobertura suficiente permanecem apenas como observações.

Somente **Avaliação de latência** preenche latência/perda. A conclusão é capturada no servidor em memória, mesmo sem consultas do navegador. Se terminar logo após a saída da página, atualiza a mesma visita por meio do trabalhador. Erros de envio/coleta não entram no denominador da perda; perda total mantém a latência indisponível. Cada resultado pertence à visita e ao MAC testado e considera a observação existente no início do teste. A avaliação válida mais recente é selecionada dentro do período daquele cliente.

A listagem e o histórico consideram apenas APs do inventário autorizado existente; a vinculação manual exige acesso a todos os APs das observações que seriam afetadas. As restrições de unicidade e o bloqueio transacional de identidade protegem as alterações concorrentes.
