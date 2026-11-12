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

A consulta existente da Registration Table atualiza uma janela móvel de 30 segundos
por AP, interface e MAC, desde a primeira amostra. A categoria corresponde à pior
condição válida entre sinal médio, SNR médio e CCQ sob tráfego em cada direção.
Sem indicadores avaliáveis, a categoria é Indisponível.

A coluna TX CCQ mostra a média geral. O diagnóstico da linha e os detalhes mostram
também a média das amostras com tráfego significativo. No AP, TX corresponde ao
download do cliente e RX ao upload. Cada direção usa sua taxa nativa ou o delta
de seus próprios contadores em bytes e o intervalo entre leituras. Um contador
que diminui inicia uma nova referência. TX/RX Rate são taxas de associação.

Por padrão, menos de 100 kbps indica ociosidade e de 100 kbps até menos de 1 Mbps
indica atividade baixa. Nesses casos, a média geral continua disponível e a última
avaliação válida sob tráfego aparece como histórica por até 60 segundos. A partir
de 1 Mbps, a amostra participa da média sob tráfego. Uma leitura crítica gera
um alerta provisório imediato. Para consolidar uma piora de CCQ, a média deve
alcançar a categoria e duas leituras representativas consecutivas devem atingir
essa gravidade ou pior. Uma leitura ociosa, ausente ou uma interrupção superior a
3 segundos reinicia a confirmação. A recuperação usa uma margem de histerese.

O desaparecimento de um cliente numa consulta bem-sucedida preserva os últimos
valores por até 120 segundos, identificados como desconectados. Retorno do cliente
ou regressão de uptime reinicia as médias. Falha de API preserva a tabela no
navegador e informa dados desatualizados. Pacote indisponível não é interpretado
como desconexão. Janelas sem consultas expiram após o período de retenção;
a limpeza ocorre na próxima atualização wireless.

Parâmetros opcionais (application.properties / variáveis de ambiente):

- `WIRELESS_QUALITY_WINDOW=30`: duração da janela móvel, em segundos.
- `WIRELESS_QUALITY_IDLE_BPS=100000` e `WIRELESS_QUALITY_SIGNIFICANT_BPS=1000000` delimitam os estados de atividade.
- `WIRELESS_QUALITY_HISTORY_MS=60000` define a validade da referência histórica.
- `WIRELESS_QUALITY_CONFIRMATIONS=2` define quantas leituras consecutivas confirmam uma piora de CCQ.
- `WIRELESS_QUALITY_HYSTERESIS=1,1,2` define as margens de recuperação para sinal (dBm), SNR (dB) e CCQ (pontos percentuais).
- `WIRELESS_QUALITY_STRONG_SIGNAL=-50` define o limiar do aviso de possível excesso de potência.
- `WIRELESS_QUALITY_RETENTION_MS=120000`: retenção sem atualização.
- `WIRELESS_QUALITY_SIGNAL=-71,-75,-83`: Normal acima de −71 dBm; Atenção de −75 a −71 dBm (inclusive); Ruim acima de −83 e abaixo de −75 dBm; Crítico até −83 dBm. A recuperação mantém a margem de histerese configurada.
- `WIRELESS_QUALITY_SNR=30,25,15`: limites inferiores Normal/Atenção/Ruim.
- `WIRELESS_QUALITY_CCQ=90,80,60`: limites inferiores Normal/Atenção/Ruim.

Comparações contínuas: valor >= cada limite pertence à respectiva categoria;
abaixo do último limite é Crítico para SNR/CCQ. Para sinal, o último limite
é inclusivo em Crítico (≤ -83 dBm por padrão). São parâmetros operacionais, não limites
universais do fabricante. A ordenação inicial prioriza criticidade e conserva a
posição durante as atualizações; os cabeçalhos permitem ordenar manualmente.
