# Hosts ativos e históricos

O inventário ativo controla a coleta e as linhas de hosts no dashboard. O catálogo
`imported` preserva os hosts que já participaram da rede. Os indicadores gerais e
por grupo incluem as medições desse catálogo dentro do período consultado, mesmo
quando o host já não está ativo. A quantidade de hosts dos grupos conta apenas os
ativos. Ausência de novas medições não é convertida em perda de pacotes.

Uma consulta bem-sucedida que não retorna um host selecionado remove esse host do
inventário ativo e da configuração do coletor. Erros de consulta preservam o último
inventário. A seleção é mantida para permitir retomar a coleta se o mesmo ID voltar
à origem. A consulta de inventário na tela continua sendo somente leitura.

## Atualização de instalações existentes

Não é necessário apagar registros nem executar SQL. Após instalar a nova versão,
a reconciliação consulta o Zabbix a cada 60 segundos (primeira tentativa após 60
segundos) e corrige também os hosts que ficaram ativos por importações antigas.
A ação de sincronizar permite antecipar essa consulta. A publicação seguinte dos
indicadores, normalmente em até mais 60 segundos, atualiza a lista no dashboard.

Hosts já retirados do inventário ativo voltam a contribuir para os indicadores a
partir do catálogo persistido de importados e das medições ainda armazenadas.
Snapshots antigos são aceitos e reconstruídos na publicação seguinte. O catálogo
e o estado ativo são preservados no novo snapshot para restauração após reinício.

Essa recuperação depende dos registros e das medições existentes: dados que já
foram apagados não são recriados. Não há alteração na política de retenção. Não
há reconstrução de versões anteriores de IP ou de associações antigas a grupos;
os cálculos continuam usando o endereço e os grupos preservados no catálogo.

Configurações opcionais: `zabbix.sync-interval-ms` e
`zabbix.sync-initial-delay-ms`, ambas com padrão de 60000.
