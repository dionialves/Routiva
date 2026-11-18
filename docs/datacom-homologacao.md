# Datacom DmOS via SSH

Implementação para switches DmOS e OLTs como DM4612. Não consulta ONUs, não coleta
wireless e não classifica Datacom como Access Point.

## Uso

1. Em Configuração → Hosts, abra a aba Autenticação.
2. Selecione Datacom, informe usuário, senha e porta SSH (padrão 22).
3. Teste e salve a autenticação. Use um usuário com acesso às consultas operacionais.
4. No menu do host nas telas de monitoramento, abra **Abrir painel Datacom**.
5. Nos mapas, use **Carregar interfaces** e selecione as interfaces Ethernet para
   as conexões. Uma ligação pode combinar Datacom e MikroTik.

O painel também está em `/equipamentos/{hostId}`. Ele funciona com o cadastro local,
sem importar o equipamento na lista de Access Points. PON é uma interface física;
ONUs e seus clientes não são consultados. Conexões mapeadas aceitam somente Ethernet.

## Coleta

A conexão SSH usa shell interativo com `| nomore`, sem entrar no modo de configuração.
A descoberta no mapa executa `show interface link` e uma consulta opcional a
`show platform`: não depende de consultas individuais por porta. Para o chassi
DM4250 com placa 24XS+2QX em 1/1, o perfil físico define 24 SFP+ de 10G e
2 QSFP+ de 40G, inclusive em portas desligadas. A velocidade atual do link é
preservada separadamente. Outros chassis/placas não recebem esse perfil. Detalhes não informados nessa lista permanecem desconhecidos
para revisão manual. O mapa apresenta a mensagem específica quando o comando é
recusado ou o formato não é reconhecido. O leitor aceita tanto nomes completos
quanto tabelas agrupadas por família (por exemplo, Ten Gigabit Ethernet Interfaces
e Forty Gigabit Ethernet Interfaces), com IDs locais chassis/slot/porta e cabeçalhos
em várias linhas. A família faz parte do nome persistido, evitando colisões.

Os únicos comandos permitidos são:

- `show platform`, `show firmware`
- `show system cpu`, `show system memory`, `show system uptime`
- `show interface link`
- `show interface <tipo> <chassis/slot/porta>`
- `show interface <tipo> <chassis/slot/porta> statistics`

Nomes de interface passam por uma lista de tipos permitidos e validação numérica.
Comandos de ONUs e comandos de alteração são recusados. As sessões são encerradas
após a consulta; no máximo oito consultas simultâneas, dez segundos por comando,
sessenta segundos por sessão e 4 MiB por resposta. O painel consulta a cada dez
segundos, sem sobreposição e somente enquanto visível. Os mapas mantêm seu cache
e ritmo de consulta existentes.

Senhas reutilizam a criptografia do cadastro de hosts. As chaves SSH compartilham
`AP_SSH_KNOWN_HOSTS_FILE` (padrão `./data/ap-known-hosts`) com a integração SSH existente:
a primeira chave é registrada e mudanças posteriores são recusadas. Preserve esse
arquivo e a chave de criptografia junto aos backups.

Tráfego é calculado por diferenças de contadores de octetos, respeitando números
sem sinal de 64 bits. Reinício de contador, intervalo antigo e primeira amostra
não produzem um zero artificial. Campos ausentes permanecem indisponíveis. Erro
SSH não representa porta DOWN. A velocidade atual do link não define a capacidade
física máxima do conector: quando não há identificação, a capacidade importada
permanece desconhecida e pode ser configurada no elemento do mapa.

## Validação e limites

Testes cobrem saídas representativas de DmOS, estados ausentes, contadores, a lista
de comandos, prompt SSH, autenticação por host, painel sem wireless e mapas mistos.
Ainda é necessário validar o formato das saídas e os privilégios no DM4612 e em um
switch reais: não foram fornecidos acesso nem capturas de uma versão instalada.
Variações de firmware não reconhecidas aparecem como leitura parcial ou indisponível,
sem inventar métricas. Capacidade física não é deduzida da velocidade negociada:
quando a saída não informa conector ou velocidades suportadas, revise esses campos
na configuração dos slots do mapa. Memória só é convertida quando a saída informa a unidade.

Referências de comandos do fabricante:

- [DmOS Command Reference 5.12.0](https://stn2.pronet.net.br/FIRMWARE-OLT-DATACOM/DmOS-5.12.0-DM4610-HW2/Manual/204.4284.31%20-%20DmOS%20-%20Command%20Reference.pdf)
- [DmOS Quick Configuration Guide](https://stn2.pronet.net.br/DM4170/Manual/204.4292.38%20-%20DmOS%20-%20Quick%20Configuration%20Guide.pdf)

- [Especificação DM4250 24XS+2QX — Datacom](https://datacom.com.br/uploads/product/file/73993eb304f71d39099aeaf05fb62f44.pdf)

## Mapas acessados por HTTP

A geração dos IDs de slots e portas usa `crypto.randomUUID` quando disponível e
UUID v4 com `crypto.getRandomValues` em acessos HTTP por IP. Essa diferença do
navegador não afeta a autenticação SSH. IDs já existentes são preservados ao
recarregar interfaces. Erros na montagem das portas, respostas HTTP inválidas e
redirecionamentos de sessão apresentam mensagens distintas de falhas do equipamento.
