# Agendador de tarefas

Acesso administrativo em **Configuração → Agendador de tarefas** (`/configuracao/agendador`).
O catálogo inclui **Avaliação de Cliente Rádio**, que processa todos os APs em sequência
e avalia 30 pacotes por destino. Veja [Clientes Rádio](radio-clients.md). Nenhum agendamento
é criado automaticamente; escolha a frequência e salve para ativar a tarefa.
A página usa a tabela compartilhada com uma linha por agendamento. O nome da tarefa
é um link no padrão das tabelas do sistema e abre o modal `analysisDialog`, com as abas
**Dados do agendamento** e **Histórico de execuções**. As ações de pausar, ativar e excluir
ficam no rodapé do modal. A consulta do histórico filtra por agendamento no banco antes
de paginar, sem misturar execuções de outras tarefas.

## Configuração guiada

- Identificação pela própria tarefa, sem campo de nome. Uma atividade pode ter apenas um agendamento não excluído, ativo ou pausado.
- Tarefa do catálogo, ativo/pausado e fuso IANA.
- Diário: horário local.
- Semanal: um ou mais dias e horário local.
- Mensal: dia 1–31 e horário. Meses sem o dia escolhido são pulados.
- Intervalo: 5, 10, 15, 30 ou 60 minutos. Os disparos são alinhados ao relógio
  (30 minutos significa HH:00 e HH:30, não 30 minutos após salvar).
- Fuso inicial `America/Sao_Paulo`; aceita outros fusos IANA e UTC.
- Prévia calculada no servidor: descrição e próximas cinco ocorrências.
- Não há campo de cron, comandos de sistema ou código livre. `ScheduleRule` gera
  internamente uma expressão Spring somente a partir dos campos validados.
- Horários locais inexistentes na entrada do horário de verão são pulados.
  Na saída do horário de verão, um horário repetido pode ocorrer duas vezes, com instantes UTC distintos.

## Executar agora e unicidade

O rodapé do modal oferece **Executar agora**. A solicitação retorna imediatamente e
usa o mesmo pool, proteção contra sobreposição, resultados por AP e histórico da execução automática.

- Agendamento existente: executa os dados salvos, mesmo se pausado, sem alterar a próxima ocorrência.
  Alterações no formulário precisam ser salvas antes para afetar essa execução.
- Novo agendamento: valida os campos, salva como **pausado** e executa uma vez. Não ativa
  recorrência implicitamente. O modal informa esse comportamento.
- Tarefa em andamento ou executor ocupado: recusa com mensagem, sem criar outra execução concorrente.
- A interface impede duplo clique enquanto envia a solicitação; o servidor também valida concorrência.
- Acesso restrito a administrador e proteção CSRF, como nas demais alterações.

O botão **Novo agendamento** fica indisponível quando todas as atividades do catálogo
já possuem agendamento. A seleção também desabilita atividades já utilizadas em outras linhas.
O índice único `task_schedule_activity_unique` protege gravações concorrentes por `task_slot`.
Excluir libera a atividade para novo cadastro; pausar mantém sua reserva.

Na atualização de bancos antigos, `schema.sql` consolida duplicados existentes:
mantém a linha não excluída com maior `updated_at` (desempate por `created_at` e ID),
move suas execuções para ela e exclui logicamente as demais. Resultados por AP permanecem
ligados aos mesmos identificadores de execução. A migração é idempotente.

## Execução e persistência

`task_schedule` armazena os dados e a próxima ocorrência; `task_execution` mantém o histórico.
Os instantes são salvos como milissegundos UTC. A tela usa o fuso salvo no agendamento/execução,
independentemente do fuso do navegador ou do servidor. Alterações futuras do fuso preservam o histórico.

`ScheduleEngine` verifica vencimentos a cada segundo em um scheduler próprio e
executa no máximo duas tarefas simultaneamente em um pool separado, sem fila ilimitada.
Não exige sessão HTTP nem navegador aberto. O horário é o instante de disparo pretendido;
a inicialização pode atrasar alguns segundos devido ao processamento e à carga.

Cada reserva de execução e atualização da próxima ocorrência ocorre na mesma transação,
com bloqueio da linha do agendamento (`FOR UPDATE`). Chamadas concorrentes ao despachante
não podem reservar a mesma ocorrência duas vezes.

**Esta versão suporta uma instância da aplicação por banco**, como no Compose atual.
Não executar múltiplas réplicas: a recuperação de início considera execuções pendentes
como pertencentes à instância anterior. Coordenação entre réplicas exige liderança/lease
com recuperação por proprietário antes de escalar horizontalmente.

Regras:

- Mesma tarefa ainda em execução, mesmo em outro agendamento: nova ocorrência registrada como ignorada.
- Os dois workers ocupados: ocorrência ignorada e registrada com o motivo; sem fila atrasada.
- Ocorrência vencida há mais de 60 segundos: horário perdido. Registra-se a primeira
  ocorrência pendente e avança-se diretamente para a próxima futura, sem gerar uma linha por
  cada intervalo perdido durante uma longa indisponibilidade.
- Falha: registra-se o erro, sem repetição automática. Detalhes técnicos ficam nos logs,
  correlacionados pelo identificador da execução.
- Reinício: execuções em andamento são marcadas como interrompidas, sem repetição automática.
- Pausa: bloqueia novos disparos, preservando execução já reservada/iniciada.
- Reativação/edição: recalcula próxima ocorrência a partir do horário atual.
- Exclusão lógica: interrompe novos disparos e preserva histórico e execução em andamento.
- Desligamento solicita interrupção aos workers. Implementações de tarefas devem respeitar
  interrupções e configurar limites de tempo para chamadas externas.
- Histórico sem expiração automática nesta versão, paginado em blocos de 25 registros.

A tela atualiza a lista a cada 15 segundos quando visível e os modais estão fechados.
Com o modal aberto na aba Histórico, atualiza apenas as execuções do agendamento selecionado.
Há atualização manual, estados vazios, mensagens de falha e formulários com CSRF.
Todas as rotas, inclusive prévia e API, ficam sob `/configuracao/**`, restrito a ADMIN.

## Inclusão de novas tarefas

Criar um bean Spring que implemente `ScheduledTask`:

1. `key()`: identificador estável e único (letras minúsculas, números e hífen).
2. `label()`: nome exibido na seleção e copiado para o histórico.
3. `validate(parameters)`: rejeitar parâmetros desconhecidos/inválidos antes de salvar e executar.
4. `execute(parameters)`: executar no servidor e retornar `Outcome` com mensagem/resumo e
   indicação de sucesso parcial. Falhas devem lançar exceção; interrupções devem ser respeitadas.

`TaskCatalog` descobre os beans automaticamente. Não registrar tarefas fictícias em produção.
O contrato e a persistência já aceitam parâmetros por tarefa.
O progresso por AP e seus resultados são persistidos em `task_execution_item` e apresentados
na aba Histórico de execuções. Os campos específicos da interface serão adicionados conforme cada tarefa.
Não reutilizar controllers nem fabricar sessões HTTP para operações de background.
A avaliação de Clientes Rádio já usa `LatencyAssessmentService`, compartilhado com o navegador,
sem criar sessões HTTP. Apenas a tarefa entrega lotes ao serviço de persistência.

## Validação

- `ScheduleRuleTests`: fuso, horário de verão, semanal, mensal, intervalos e validação.
- `ScheduleStoreTests`: DDL idempotente em H2, persistência, reservas concorrentes,
  sobreposição, limite de concorrência, atraso, recuperação, pausa e histórico após exclusão.
- `SchedulePageTests`: renderização dos fragmentos sem duplicação, catálogo vazio, CSRF,
  prévia e rejeição de tarefa desconhecida.
- `SecurityFlowTests`: usuário normal sem acesso às telas e operações do agendador.
- `task-scheduler.test.cjs`: resumos e formatação no fuso selecionado.
