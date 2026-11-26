# Agendador de tarefas

Acesso administrativo em **Configuração → Agendador de tarefas** (`/configuracao/agendador`).
O catálogo inclui **Backup de hosts** e **Avaliação de Cliente Rádio**.
O backup percorre os hosts habilitados em sequência e usa as credenciais e a retenção de cada host.
A avaliação percorre os APs e avalia 30 pacotes por destino. Veja [Clientes Rádio](radio-clients.md).
Escolha a tarefa e salve a programação para ativar a recorrência.
A página usa a tabela compartilhada com uma linha por agendamento. O nome da tarefa
é um link no padrão das tabelas do sistema e abre o modal `analysisDialog`, com as abas
**Dados do agendamento** e **Histórico de execuções**. O rodapé oferece **Excluir**, **Cancelar**, **Executar agora** e **Salvar**. A consulta do histórico filtra por agendamento no banco antes
de paginar, sem misturar execuções de outras tarefas.

## Configuração guiada

- Cada tarefa pode ter um agendamento não excluído.
- Os cinco campos CRON são minuto (0–59), hora (0–23), dia do mês (1–31), mês (1–12)
  e dia da semana (0–6, começando pelo domingo).
- **Todos** gera `*` e **Valor** aceita um único inteiro dentro do intervalo do campo.
- `0 1 * * *` executa diariamente às 01:00. O Spring recebe `0 0 1 * * *`, com segundos no início.
- Se dia do mês e dia da semana tiverem valores fixos, os dois precisam coincidir.
  Datas impossíveis, como 31 de fevereiro, são rejeitadas.
- Novas programações usam `spring.jackson.time-zone`, com padrão `America/Sao_Paulo`.
  Regras antigas mantêm sua recorrência e seu fuso até serem substituídas no formulário.
  Agendamentos já pausados mantêm esse estado ao editar.
- Horários locais inexistentes na entrada do horário de verão são pulados.
  Na saída do horário de verão, um horário repetido pode ocorrer duas vezes, com instantes UTC distintos.

## Executar agora e unicidade

O rodapé do modal oferece **Executar agora**. A solicitação retorna imediatamente e
usa o mesmo pool, proteção contra sobreposição, resultados por equipamento e histórico da execução automática.

- Agendamento existente: executa os dados salvos, mesmo se pausado, sem alterar a próxima ocorrência.
  Alterações no formulário precisam ser salvas antes para afetar essa execução.
- Novo agendamento: valida os campos, cria um registro para guardar o histórico e executa uma vez.
  O registro aparece como **Somente manual** até salvar a programação recorrente.
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
- Histórico sem expiração automática, paginado na interface em blocos de 10 registros.

A tela atualiza a lista a cada 15 segundos quando visível e os modais estão fechados.
O histórico consulta as execuções ao abrir a aba, mudar de página ou iniciar uma execução manual.
O horário previsto abre os logs da execução, com resumo e resultados por equipamento. A exportação em texto inclui todos os resultados. A interface apresenta estados vazios, mensagens de falha e formulários com CSRF.
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

- `ScheduleRuleTests`: campos CRON, combinação dos dias, fuso, horário de verão e regras legadas.
- `ScheduleStoreTests`: DDL idempotente em H2, persistência, reservas concorrentes,
  sobreposição, limite de concorrência, atraso, recuperação, pausa e histórico após exclusão.
- `SchedulePageTests`: renderização dos fragmentos sem duplicação, catálogo vazio, CSRF,
  persistência do CRON no fuso padrão, prévia legada e rejeição de tarefa desconhecida.
- `SecurityFlowTests`: usuário normal sem acesso às telas e operações do agendador.
- `task-scheduler.test.cjs`: campos CRON, edição legada, histórico, execução manual e formatação no fuso da regra.
