# Página do host MikroTik

Clique no nome de um host em **Disponibilidade** para acessar `/hosts/{id}`.
A página consulta o IP atual do inventário monitorado; não aceita endereços de
conexão arbitrários pelo navegador. Todos os comandos RouterOS são fixos e de
leitura.

## Configuração

Defina no `.env` (não versionar credenciais):

```dotenv
MIKROTIK_USERNAME='usuario-de-consulta'
MIKROTIK_PASSWORD='senha'
MIKROTIK_API_TLS=true
MIKROTIK_API_PORT=8729
```

API nativa com login moderno: RouterOS 6.43+ e 7. O serviço API-SSL deve estar
acessível pelo servidor Routiva. TLS valida a cadeia do certificado e o IP do
host no certificado; certificados privados exigem uma CA confiável na JVM.
Não há opção de ignorar a validação. Para API simples em rede protegida,
use `MIKROTIK_API_TLS=false` e `MIKROTIK_API_PORT=8728`.
O usuário precisa de permissões de leitura/API. O Routiva não envia comandos de escrita.

Em Docker, recrie o serviço com `docker compose up -d --build app` após configurar.
O `.env` já é passado ao app pelo Compose. O servidor deve alcançar diretamente
os equipamentos; esta integração não passa pelo coletor Go.

## Dados e funcionamento

- Visão geral: identificação, modelo, RouterOS, uptime, CPU e memória.
- Histórico: exclusivamente o endpoint existente de disponibilidade do host,
  com disponibilidade técnica/operacional, latência e perda, com todos os períodos compartilhados pelo app, de 5 minutos a 2 anos.
- Interfaces: tabela com uma interface por linha, estado e velocidades de
  download/upload calculadas pela diferença de contadores entre consultas.
  Até 2 Mbps (inclusive) usa kbps; acima, Mbps. A primeira consulta aguarda a
  próxima medição. Contadores reiniciados e pausas longas exigem nova amostra. Clique
  na linha (ou Enter/Espaço) para abrir detalhes e tráfego ao vivo em um modal.
  Fechar o modal interrompe as consultas de tráfego; Escape também fecha a janela.
- Wireless: tabelas de registro dos pacotes wireless, wifi e wifiwave2.
  Recursos ausentes ou sem permissão são indicados; campos ausentes não são
  apresentados como zero. Pesquisa e seleção abrem um modal de detalhes do cliente, com identificação,
  sinal/qualidade e tráfego. Os dados acompanham as atualizações
  da lista e indicam quando o cliente deixa de estar conectado.

Cada consulta abre e fecha uma conexão com prazo limitado. Não há sessão remota
persistente nem coleta em segundo plano. Há limite de oito consultas API
simultâneas. A visão geral permanece acima das abas e é atualizada junto com a aba ativa,
um segundo após a consulta anterior. O histórico é atualizado a cada minuto.
Ao ocultar a página, consultas futuras são suspensas; uma
consulta já recebida pelo servidor pode terminar dentro de seu prazo limitado.

Gráficos ao vivo mantêm no máximo 120 amostras na memória da página. Trocar a
interface limpa seu gráfico. Nada é
salvo no banco, localStorage ou sessionStorage. Falhas de gerenciamento não
impedem a consulta independente ao histórico de ICMP.

As credenciais compartilhadas ficam isoladas em `MikrotikService` para futura
resolução por cliente autenticado. A vinculação a clientes/login ainda não
faz parte desta entrega.

Referência do protocolo: [API RouterOS](https://manual.mikrotik.com/docs/developer-guides/api/).

## Banda efetiva do cliente wireless

O modal mostra Download (AP → cliente) e Upload (cliente → AP), além de gráfico
com até 120 amostras da sessão. Usa as taxas de tráfego nativas quando presentes;
caso contrário, calcula diferenças do campo `bytes` (TX,RX) entre consultas.
As taxas do enlace (`tx-rate`/`rx-rate`) não são usadas como consumo. Contadores
ausentes, reiniciados ou consultas interrompidas não são tratados como zero.

Referências: [Wireless](https://help.mikrotik.com/docs/spaces/ROS/pages/8978446/Wireless+Interface),
[WiFi](https://help.mikrotik.com/docs/spaces/ROS/pages/224559120/WiFi).

## Qualidade wireless em tempo real

A consulta existente da Registration Table alimenta janelas em memória de até 30
amostras válidas por AP, interface, MAC e indicador. A primeira amostra já produz
classificação provisória; cada resposta atualiza as médias sem tarefa adicional
ou persistência de amostras. TX/RX Rate são auxiliares, convertidos para Mbps.

A qualidade usa a pior condição entre sinal, SNR e TX/RX CCQ disponíveis. Valores
ausentes aparecem como `—`. O tooltip detalha os indicadores e a formação da
janela. Uma leitura crítica instantânea gera um alerta separado da classificação
pela média, sem limpar seu histórico. Sinal acima de -50 dBm é informado no
tooltip e avaliado junto com os demais indicadores.

O desaparecimento de um cliente numa consulta bem-sucedida preserva os últimos
valores por até 120 segundos, identificados como desconectados. Retorno do cliente
ou regressão de uptime reinicia as médias. Falha de API preserva a tabela no
navegador e informa dados desatualizados. Pacote indisponível não é interpretado
como desconexão. Janelas sem consultas expiram após o período de retenção;
a limpeza ocorre na próxima atualização wireless.

Parâmetros opcionais (application.properties / variáveis de ambiente):

- `WIRELESS_QUALITY_WINDOW=30`: quantidade de amostras válidas por indicador.
- `WIRELESS_QUALITY_RETENTION_MS=120000`: retenção sem atualização.
- `WIRELESS_QUALITY_SIGNAL=-65,-75,-83`: limites Normal/Atenção e início de Crítico.
- `WIRELESS_QUALITY_SNR=30,25,15`: limites inferiores Normal/Atenção/Ruim.
- `WIRELESS_QUALITY_CCQ=90,80,60`: limites inferiores Normal/Atenção/Ruim.

Comparações contínuas: valor >= cada limite pertence à respectiva categoria;
abaixo do último limite é Crítico para SNR/CCQ. Para sinal, o último limite
é inclusivo em Crítico (≤ -83 dBm por padrão). São parâmetros operacionais, não limites
universais do fabricante. A ordenação inicial prioriza criticidade e conserva a
posição durante as atualizações; os cabeçalhos permitem ordenar manualmente.
