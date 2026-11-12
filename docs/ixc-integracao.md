# Integração IXC Provedor

Pacote independente: `br.com.scriptping.ixc`. Consulta somente leitura; não altera cadastros no IXC nem configurações do MikroTik. A vinculação é calculada pelo MAC no momento da consulta, sem gravação no banco.

## Configuração

No `.env`:

```dotenv
IXC_URL='https://seu-ixc.exemplo.com.br'
IXC_TOKEN='id:token-webservice'
```

Use o token webservice original, antes da codificação Base64. A integração monta o cabeçalho Basic. É obrigatório HTTPS com certificado válido. A URL pode incluir `/webservice/v1`. Não informe credenciais na URL.

O usuário do token precisa de permissão para listar Logins (`radusuarios`). O serviço envia POST com `ixcsoft: listar` e filtro `radusuarios.mac = MAC` para consulta individual; para vários MACs, lista `radusuarios.id > 0` e cruza os MACs no sistema, sem comandos de escrita. Timeout de conexão de 5s e de requisição de 10s; não segue redirecionamentos.

Referências consultadas:
- https://wikiapiprovedor.ixcsoft.com.br/
- https://api-provedor.ixcsoft.com.br/

## Uso

Abra um host monitorado → Wireless. A coluna **Login PPPoE** substitui o nome do rádio e consulta o IXC automaticamente pelo MAC, com pesquisa e ordenação pelo login. Se houver vários logins no mesmo MAC, os nomes aparecem separados por `/`. Sem vínculo, aparece “Sem login vinculado”; falhas na API são sinalizadas sem interromper as medições wireless. O nome do rádio permanece disponível nos detalhes do cliente.

Os nomes são consultados em lote em segundo plano pela rota `/api/hosts/{id}/ixc/login-names`, que obtém os MACs conectados em uma consulta ao MikroTik e retorna um mapa MAC → lista de logins, sem senhas. A página reutiliza os nomes por 60 segundos e aguarda 60 segundos antes de tentar novamente após falhas. MACs sem vínculo não impedem a identificação dos demais.

A documentação do IXC descreve o operador `IN` para números, sem garantir suporte a listas de MACs. Por isso, para vários MACs, o serviço faz uma listagem compartilhada de `radusuarios` com `id > 0`, em páginas de 1.000 registros, e mantém apenas os cadastros com MAC presente no host. Uma página basta quando o inventário tem até 1.000 logins; inventários maiores exigem paginação, sem multiplicar consultas por cliente wireless. Limite de 100.000 registros por consulta completa; respostas maiores são rejeitadas, sem apresentar dados parciais. Os registros são processados por página, sem armazenar o inventário em banco ou cache. Esse método transfere mais registros do IXC, mas elimina as chamadas individuais por MAC. A compatibilidade deve ser validada contra a instalação real.


Para consultar IP, senhas e porta: clique no MAC → Cadastro IXC → Consultar login pelo MAC.

O endpoint `/api/hosts/{id}/ixc/login?mac=AA:BB:CC:DD:EE:FF` confere se o host pertence ao inventário e se o MAC está conectado ao MikroTik antes de consultar o IXC. Aceita MAC separado por dois pontos, hífen, pontos ou sem separadores; envia a forma maiúscula com dois pontos. O cadastro no IXC deve estar nesse formato para o filtro remoto de igualdade encontrá-lo.

Dados apresentados:

| Informação | Campo de `radusuarios` |
| --- | --- |
| IP | `ip` |
| PPPoE | `login` |
| Senha PPPoE | `senha` |
| Senha roteador | `senha_router1` |
| Porta Roteador | `porta_http` |

Os campos `senha_router1` e `porta_http` foram confirmados pelo responsável pela integração e são fixos no código. Porta de conexão significa aqui a porta de acesso HTTP cadastrada, não a interface wireless nem uma porta física. IP é o IP cadastrado, sem inferir o IP dinâmico da sessão PPPoE. Campo ausente aparece como “Não informado no IXC”.

Vários logins com o mesmo MAC são mostrados separadamente, sem selecionar arbitrariamente um cadastro. A paginação é percorrida antes de devolver o resultado, evitando apresentar dados parciais. Erros HTTP e respostas de erro do IXC produzem mensagem genérica, sem devolver seu conteúdo.

As senhas são enviadas ao navegador apenas por ação explícita e ficam visualmente ocultas, com botão Mostrar/Ocultar. Fechar o diálogo, selecionar outro cliente ou detectar desconexão remove os dados da tela. Não há armazenamento local nem cache HTTP. A resposta contém credenciais: o endpoint segue o mesmo controle de acesso existente nas demais rotas do sistema; ocultar visualmente não substitui autorização de acesso à aplicação.

Para executar em Docker após configurar o `.env`, use `docker compose up -d` para aplicar as variáveis.

## Validação

Testes Java cobrem normalização de MAC, autenticação Basic, mapeamento dos campos, múltiplos cadastros, filtro exato da resposta, erros da API e restrição por host/cliente conectado. Teste JavaScript cobre consulta por ação, ocultação/revelação de senha e limpeza ao fechar.

A validação automatizada usa respostas simuladas. É necessário validar URL, token e permissões contra a instalação real do provedor.
