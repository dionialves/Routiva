# Mimosa B5c — firmware 2.10.0

No cadastro local de hosts, selecione **Mimosa** na aba Autenticação,
informe a senha do painel e a porta
HTTPS, normalmente **443**. Teste e salve. No mapa, selecione o host, habilite interfaces e use
**Carregar interfaces**. Vincule a porta Ethernet a uma conexão e salve o mapa.
A coleta e o tráfego ficam exclusivamente no mapa; não existe painel Mimosa.

O adaptador envia um formulário `username`/`password` por `POST /login.php`
e consulta `GET /cgi/dashboard.php` com os cookies recebidos. Cada consulta
usa uma sessão isolada, descartada ao terminar. Não segue redirecionamentos
nem utiliza credenciais REST internas. O certificado HTTPS é reconhecido no primeiro acesso e sua impressão é guardada em
`./data/mimosa-certificates` (configurável por `mimosa.known-certificates-directory`).
A confiança é exclusiva de cada endereço/porta; mudanças posteriores são rejeitadas.
Preserve esse diretório nos backups. O usuário é sempre `configure`, definido no servidor.

Somente campos explicitamente selecionados são retornados ao navegador.
A seção `config` do dashboard pode conter senhas e não é exposta nem registrada.

Disponível: identificação, modelo reportado pelo firmware, versão, SSID,
frequência e interface Ethernet identificada pelo dashboard, incluindo MAC
e estado quando reconhecido. Esta leitura não equivale a uma descoberta de
todas as interfaces do sistema. Não cria portas de fibra a partir de campos
vazios, nem transforma estado desconhecido em porta inativa.

Tráfego Ethernet: o próprio JavaScript da B5c 2.10.0 identifica `Rx#05`
como RX Octets OK e `Tx#02` como TX Octets OK. As taxas são calculadas em bits/s
pela diferença de bytes entre leituras, na perspectiva da porta Ethernet.
Não se utiliza o histórico de throughput do enlace nem taxas PHY para essa porta.
Reinícios, redução de contador (incluindo possível wrap de largura desconhecida),
intervalo maior que 30 segundos ou dados ausentes invalidam o cálculo.
A primeira leitura estabelece a referência e não representa tráfego zero.

A descoberta e as conexões usam as APIs compartilhadas `/api/maps/connections/*`.
O mapa consulta a cada 10 segundos enquanto visível e calcula RX/TX por deltas.
Não há coleta autônoma após fechar o mapa. O cache compartilhado separa falha
de coleta de porta DOWN e invalida a referência quando o equipamento reinicia.
A descoberta inclui Ethernet e a interface lógica Wireless, com conector próprio.
O estado do enlace usa `connection.connected`; o tráfego wireless vem de
`/core/api/calls/TPutPer.php`, campo `tput1min`, TX/RX em bytes/s multiplicados
por oito. Amostras com mais de 30 segundos, futuras ou inválidas ficam indisponíveis.
A capacidade do enlace permanece desconhecida; taxas PHY não a substituem.

O formato foi baseado em uma captura fornecida do firmware 2.10.0; o login e a leitura HTTPS foram validados em equipamento real com essa versão.
