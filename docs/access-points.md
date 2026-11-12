# Access Points

A página `/ap` lista os hosts classificados localmente como AP. O botão Importar usa
somente o inventário autorizado já importado do Zabbix. Na janela, os disponíveis
ficam à esquerda e os selecionados à direita; → seleciona e ← retira. A seleção
só é persistida ao clicar em Definir como AP. Isso não altera o Zabbix.

A tabela `access_point` mantém o hostid, último nome/IP, fabricante/modelo e a
última quantidade válida de clientes. Leituras desconhecidas são nulas. Nome/IP
são conciliados com o inventário ao consultar a lista. A ausência no inventário
não exclui o cadastro. O status da lista indica presença no inventário, e não
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
  existente. Os APs importados usam inicialmente este fabricante.
- `/api/ap/{id}/overview|interfaces|traffic|wireless`: consultas neutras de equipamento.
- APIs compartilhadas de disponibilidade, IXC e ping continuam em `/api/hosts/...`.

A quantidade de clientes e o modelo são atualizados quando a análise do AP faz
uma consulta válida. A lista não abre conexões com todos os equipamentos: exibe
os últimos valores persistidos e a data da última consulta válida de resumo ou
wireless. Não há coleta automática em segundo plano de clientes nesta entrega.

Para outro fabricante, implementar `EquipmentProvider`, normalizar as linhas ao
contrato consumido pela apresentação e informar as capacidades disponíveis.
Categorias futuras (OLT/roteador) podem reutilizar esse contrato, preservando seus
próprios cadastros, rotas e componentes de apresentação específicos.
