# Access Points

A página `/ap` lista os hosts classificados localmente como AP. O botão Importar usa
somente o inventário autorizado já importado do Zabbix. Na janela, os disponíveis
ficam à esquerda e os selecionados à direita; → seleciona e ← retira. A seleção
só é persistida ao clicar em Definir como AP. Isso não altera o Zabbix.

A tabela `access_point` mantém o hostid, último nome/IP, fabricante/modelo e a
última quantidade válida de clientes. Leituras desconhecidas são nulas. Nome/IP
são conciliados com o inventário ao consultar a lista. Retirar a seleção de monitoramento não exclui o cadastro; a exclusão confirmada
no Zabbix exclui também o host e o AP no app. O status da lista indica presença no inventário, e não
conectividade em tempo real.

A análise fica em `/ap/{hostid}`. A antiga página `/hosts/{hostid}` foi removida,
sem redirecionamento. Hosts sem classificação continuam nas tabelas de indicadores,
mas não oferecem link de análise de AP. APs ausentes do inventário ainda têm sua
página de análise disponível com os últimos dados cadastrais; as consultas ao
equipamento retornam indisponibilidade.

## Organização

- `accesspoint`: cadastro, persistência, listagem e controlador das páginas/APIs.
- `equipment.EquipmentProvider`: contrato de consulta por fabricante com capacidades.
- `mikrotik.MikrotikEquipmentProvider`: primeiro adaptador; usa a integração RouterOS
  existente. O fabricante é escolhido na autenticação do host.
- `/api/ap/{id}/overview|interfaces|traffic|wireless`: consultas neutras de equipamento.
- APIs compartilhadas de disponibilidade, IXC e ping continuam em `/api/hosts/...`.

A quantidade de clientes e o modelo são atualizados quando a análise do AP faz
uma consulta válida. A lista não abre conexões com todos os equipamentos: exibe
os últimos valores persistidos e a data da última consulta válida de resumo ou
wireless. A coleta automática de Clientes Rádio é feita exclusivamente pela tarefa Avaliação de Cliente Rádio, configurada no agendador. Abrir a página não persiste clientes.

Para outro fabricante, implementar `EquipmentProvider`, normalizar as linhas ao
contrato consumido pela apresentação e informar as capacidades disponíveis.
Categorias futuras (OLT/roteador) podem reutilizar esse contrato, preservando seus
próprios cadastros, rotas e componentes de apresentação específicos.

## Avaliação de latência

Na página do AP, a engrenagem abre **Avaliação de latência**. O modal lista os
clientes wireless conectados e resolve seus IPv4 pelo IXC. Ao lado de **Start**,
selecione **5, 10, 30 ou 60 pacotes por destino** (padrão: 60). O coletor executa
essa quantidade de tentativas para o AP e para cada cliente com IP válido, com
intervalo de 1 segundo e timeout de 1,5 segundo. Não há bloqueio de tráfego.

O teste é limitado por pacotes, não por uma janela fixa de segundos. Cada destino
para ao atingir seu limite, inclusive após atualizações da configuração do
coletor. Trabalhos ignorados pelo agendador não consomem o limite; erros de envio
ou coleta são registrados como tentativas com falha, não como perda ICMP.
O progresso mostra a menor quantidade de pacotes concluídos entre os destinos.
O seletor fica bloqueado durante a execução. Clientes sem IP não recebem pings.

A conclusão é confirmada pelo backend quando todos os destinos testáveis atingem
a quantidade escolhida. Um prazo de segurança de quantidade × 1 segundo + 30
segundos encerra avaliações incompletas como erro, sem apresentá-las como sucesso.
A aba Resultados abre automaticamente com os dados finais, incluindo médias,
mínimo, máximo, perda, cobertura e Top 3 de diferenças positivas para o AP.
A média geral dá o mesmo peso a cada cliente com resposta; a perda agregada é
ponderada pelo número de pings com resultado ICMP. Diferenças negativas indicam
variação e não são, por si só, um problema. A comparação inclui tráfego, filas,
roteamento e processamento; não isola a latência do enlace wireless.

O AP permanece na primeira linha da tabela. A busca aceita nome, IP e MAC,
ignorando acentos e maiúsculas; os filtros e a ordenação não alteram as medições.
As cores usam os parâmetros iniciais de diferença acima de 2 ms para atenção,
acima de 5 ms para crítico e perda acima de 2% para ao menos atenção.
Há até 255 clientes por avaliação e limite global de 256 alvos temporários.
Resultados da avaliação manual ficam em memória por cinco minutos após a conclusão. Apenas a avaliação executada pela tarefa atualiza o histórico de Clientes Rádio.
**Executar novamente** limpa o teste anterior; fechar/cancelar encerra os alvos.

Esta versão requer atualizar **app e collector**, pois o limite `max_packets` é
aplicado no coletor. Zero mantém o monitoramento contínuo de outros recursos.
Os stubs Java são gerados normalmente pelo Maven. Para regenerar o stub Go,
instale protoc-gen-go 1.36.6 e use o perfil Maven `collector-proto`, informando
`-Dprotoc.go.executable=/caminho/absoluto/protoc-gen-go`. Não execute protoc
manualmente.

## Autenticação por host

A aba **Autenticação** em Configuração → Hosts armazena fabricante (MikroTik/Ubiquiti), usuário, senha, porta, TLS para RouterOS. Os serviços de `hosts` validam e fornecem o acesso tanto aos APs quanto às conexões mapeadas. O AP não possui modal nem ação de configuração; a autenticação é gerenciada exclusivamente no host.

Ao importar um AP, o app testa as credenciais cadastradas do host. Sem configuração ou com teste inválido, o AP é cadastrado, mas não coleta dados. Remover a classificação de AP preserva a autenticação. Excluir o host remove também seu AP e os vínculos dos mapas; a exclusão é replicada entre app e Zabbix na sincronização.

A migração transfere as senhas protegidas existentes para `host_authentication`, sem sobrescrever configurações já presentes. Preserve `data/ap-secrets.key` (ou o caminho configurado em `ap.secrets-key-file`). Mapas que usavam credenciais gerais do `.env` precisam ter a autenticação cadastrada por host.
