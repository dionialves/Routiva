# Clientes Rádio

Acesse **Clientes Rádio** no menu ou `/clientes/radio`.
A criação e a atualização automática de clientes, observações, coletas e resultados de
latência são feitas exclusivamente pela tarefa **Avaliação de Cliente Rádio**.
Abrir um AP, consultar seus clientes, executar uma avaliação manual ou fechar a página
não grava dados no histórico de Clientes Rádio. A consulta ao vivo e os diagnósticos
manuais continuam disponíveis; os resultados manuais expiram após cinco minutos.

## Tarefa de avaliação

Em **Configuração → Agendador de tarefas**, crie um agendamento e escolha
**Avaliação de Cliente Rádio** (`radio-client-assessment`). A tarefa tem parâmetros fixos:
todos os APs cadastrados, processados em sequência, e 30 pacotes por destino.
Nenhum agendamento é criado/ativado automaticamente na instalação.

O coletor deve estar habilitado e saudável. Cada execução fixa a lista de APs no início.
APs que não estão no inventário autorizado são registrados como falha, sem exclusão de dados.
Para cada AP:

1. Reserva o AP contra avaliações concorrentes do navegador ou de outra tarefa.
2. Lê os clientes wireless por API e relaciona MACs com os logins/IPs do IXC.
3. Inicia a mesma avaliação de latência usada pelo navegador, com 30 pacotes por destino,
   incluindo o AP como referência. Máximo atual de 255 clientes por AP e IPv4 para ICMP.
4. Faz novas leituras wireless a cada dez segundos durante a janela de avaliação.
5. Consolida o lote e grava observações, identidade, coleta elegível e latência numa transação.
6. Remove os probes temporários, libera a reserva e segue ao próximo AP.

Cada chamada da API do equipamento fecha sua conexão antes de retornar; o processamento
usa os dados capturados em memória. O processo não fabrica sessões HTTP. A consulta ao
IXC é reaproveitada para a identificação e a persistência, sem uma segunda busca no fechamento.
Os resultados por AP são persistidos à medida que terminam: uma falha posterior não desfaz
APs já concluídos. Não há repetição automática de medições ou gravações que falharam.

A janela é de pelo menos trinta segundos, estendida quando necessário para atender
`RADIO_HISTORY_MINIMUM_READINGS` e `RADIO_HISTORY_MINIMUM_DURATION_MS`. Uma leitura
repetida com o mesmo horário não conta como uma nova amostra. Por padrão são necessárias
duas leituras distintas cobrindo dez segundos por cliente para gerar uma coleta histórica.
Clientes que entraram tarde ou saíram cedo podem ter apenas observações, sem substituir
sua última coleta elegível. O diagnóstico original de conexão/sinal é preservado.
`RADIO_HISTORY_INACTIVITY_MS` não é mais utilizado; não existem visitas do navegador.

## Falhas e acompanhamento

A aba **Histórico de execuções** do agendamento mostra o resultado geral e os resultados
por AP, incluindo aguardando, em andamento, concluído, com ressalvas, falha e interrupção.
Detalhes por AP informam clientes observados, cobertura histórica, medições ICMP e ausência de IP.

- Falha em um AP: registra e segue para o próximo. Todos os APs falhando resulta em falha geral.
- IXC indisponível: mantém observações, preserva vínculos existentes e marca ressalvas;
  clientes sem IP resolvido não são pingados.
- Cliente sem IP válido/ambíguo: observação preservada, sem latência inventada.
- Erros de envio/coleta: não entram no denominador de perda e geram ressalvas.
- Perda ICMP total: perda de 100%, latência indisponível; não significa erro de envio.
- Falha numa leitura posterior: nunca interpreta erro como AP vazio nem apaga clientes.
- AP ocupado por avaliação manual: registra falha daquele AP e continua, sem cancelar a medição manual.
- Interrupção: solicita encerramento dos probes; o AP incompleto não é persistido.
- Reinício: marca execução e itens pendentes/em andamento como interrompidos; sem retomada automática.
- Outro agendamento da mesma tarefa em execução: nova ocorrência ignorada, com motivo no histórico.

Dados ainda em memória do AP atual podem ser perdidos num encerramento abrupto.
Uma falha de gravação deixa o lote daquele AP sem persistência e é registrada, sem sucesso indevido.

## Identidade e histórico

O login IXC identifica o cliente e o MAC identifica o equipamento. Sem login inequívoco,
preserva-se o vínculo vigente; sem vínculo, cria-se um provisório. Consultas ambíguas
permanecem pendentes. Amostras originais e períodos de vínculo são preservados.

As médias históricas utilizam os valores originais, com contagens individuais, e não os
valores `avg-*` do diagnóstico. TX/RX e P Throughput são apresentados em Mbps.

A ação explícita **Vincular login** permanece disponível para corrigir identidades: mostra
destino, períodos e quantidade de coletas elegíveis antes da confirmação. Registra o
operador e a data de início, sem iniciar coleta ou pings. Períodos que cruzam vínculos
identificados encerrados exigem revisão da data. Segmentos sem cobertura suficiente
permanecem apenas como observações.

Latência/perda históricas são fornecidas apenas pela avaliação da tarefa. Cada resultado
pertence ao lote e ao MAC testado, associado à observação existente no início da medição.
A avaliação válida mais recente é selecionada dentro do período do cliente.

Listagem e histórico consideram apenas APs do inventário autorizado; vinculação manual
exige acesso a todos os APs das observações afetadas. Unicidade e bloqueio transacional
de identidade protegem alterações concorrentes.

## Implementação

- `LatencyAssessmentService`: resolução dos destinos, execução dos pings, reserva por AP
  e cálculo dos resultados. Usado pelo navegador e pela tarefa.
- `LatencyAssessmentController`: adaptador HTTP, sem persistência de Clientes Rádio.
- `RadioClientAssessmentTask`: orquestração sequencial e relatório por AP.
- `RadioCollection`: lote de amostras/resultados sem dependência de sessão.
- `RadioClientService.persistCollection`: único processamento/persistência do lote;
  não consulta o IXC. Seu único produtor automático é a tarefa.

O antigo `RadioVisitService`, o cabeçalho `X-Radio-Visit`, os identificadores de visita
na página e o endpoint de fechamento da visita foram removidos.
