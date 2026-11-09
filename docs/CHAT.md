---
tags: [arquitetura, chat, protocolo]
---
# Chat — protocolo e regras do servidor

O servidor (`relay/`) é a **única fonte de verdade** das mensagens. O app desktop não guarda
mensagem nenhuma: lê páginas do servidor, envia pedidos e reage aos eventos que o servidor
espalha. A interface mostra só o que o servidor confirmou; o único estado transitório na página é
a mensagem "enviando" (identificada pelo `nonce`) até a confirmação chegar.

> [!note] Versão do protocolo
> Clientes a partir da 2.2.0 mandam `proto: 2` no `hello` e falam o protocolo abaixo. Clientes
> antigos (`msg`, `sync`, `stored`) continuam aceitos enquanto a atualização obrigatória não chega:
> o que eles enviam é gravado no formato novo e espalhado para os clientes novos.

## Conexão

- `hello` ganha `proto: 2` e `device` (16 hex, aleatório por instalação, fixo).
- Mesmo `id` com o mesmo `device` substitui a sessão anterior (4409). Com `device` diferente, as
  duas sessões convivem (**vários dispositivos**): eventos de chat vão para todas; quadros `route`
  (chamadas, sinalização, perfil) vão só para a sessão mais recente. Presença: online enquanto
  houver uma sessão.
- `welcome` ganha `proto: 2`, `http` (token aleatório de 32 bytes em hex, vale enquanto a sessão
  existir, usado no cabeçalho `X-AV-Token` das rotas HTTP do servidor), `admin: {admin, active}` e
  `mutedUntil` (ms, 0 se não houver).
- Banido: o servidor manda `{t:"banned"}` e fecha com 4403. Expulso: `{t:"kicked", reason}` e fecha
  com 4410; o app espera 60 s para reconectar.
- Avisos do servidor à pessoa, em todas as suas sessões: `{t:"admin", admin, active}` quando ela
  entra como administradora ou liga/desliga o modo; `{t:"muted", until}` quando é silenciada
  (`until` em ms) ou deixa de ser (`until: 0`); `{t:"chat.wiped"}` quando um administrador apaga
  todas as mensagens.
- Os quadros `chat.*` só vão para sessões `proto: 2`; clientes antigos continuam recebendo o envelope
  `route` de sempre.

## Pedido e resposta

Todo pedido do cliente leva `r` (texto curto). A resposta é `{t:"res", r, ok:true, ...}` ou
`{t:"res", r, ok:false, error:"<texto em PT-BR para a tela>"}`.

| Pedido | Campos | Resposta | Evento espalhado |
|---|---|---|---|
| `chat.send` | `nonce`, `chat`, `text`, `replyTo?`, `attachments?` (ids) | `message` | `chat.new {message}` |
| `chat.edit` | `id`, `text` | `message` | `chat.update {message}` |
| `chat.delete` | `id` | — | `chat.delete {id, chat}` |
| `chat.react` | `id`, `emoji`, `on` | `message` | `chat.update {message}` |
| `chat.pin` | `id`, `on` | `message` (com `pinned`, `pinnedBy`) | `chat.update {message}` |
| `chat.pins` | `chat` | `messages` (fixadas, da mais recente para a mais antiga; até 50 por conversa) | — |
| `chat.history` | `chat`, `before?`/`after?`/`around?` (seq), `limit` (≤100) | `messages` (seq crescente), `chat` | — |
| `chat.read` | `chat`, `seq` | — | `chat.read {chat, seq}` às outras sessões da pessoa |
| `chat.typing` | `chat` | (sem resposta) | `chat.typing {chat, from, name}` aos outros membros |
| `chat.unread` | — | `chats: {chat: {count, mentions, lastSeq, readSeq}}` | — |
| `admin.login` | `password` | `admin` | — |
| `admin.mode` | `active` | `admin` | — |
| `admin.kick` / `admin.ban` / `admin.unban` | `id`, `reason?` | — | — |
| `admin.mute` | `id`, `minutes` (0 tira) | — | — |
| `admin.clear` | `chat` | `removed` | `chat.cleared {chat}` |
| `admin.bans` | — | `bans` | — |
| `admin.stats` | — | `messageBytes`, `messageCount`, `attachmentBytes`, `attachmentLimit` | — |
| `admin.wipe` | `confirm` (exatamente `"APAGAR TUDO"`) | `removed`, `attachments` | `chat.wiped` a todas as sessões |

`chat` do lado do cliente: `publico`, `g_…` ou `dm:<id da outra pessoa>`. O servidor guarda conversa
privada como `dm:<menor>+<maior>` e devolve sempre essa forma canônica; o app traduz.

### Mensagem

```json
{"id":"…","seq":123,"chat":"publico","from":"<20 hex>","name":"Ana","ts":0,"edited":0,
 "text":"oi <@0123456789abcdef0123>","replyTo":"…","ref":{"id":"…","seq":120,"from":"…","name":"…","text":"…","attachments":0,"missing":false},
 "mentions":["…"],"reactions":[{"emoji":"👍","count":2,"users":["…"]}],
 "attachments":[{"id":"…","name":"foto.webp","type":"image/webp","size":1234,"width":800,"height":600,"expired":false}],
 "embeds":[{"url":"…","title":"…","description":"…","site":"…","color":"#…","image":true,"imageWidth":0,"imageHeight":0}],
 "nonce":"…"}
```

- `id` e `seq` são atribuídos pelo servidor; `seq` é global e crescente e serve de cursor.
- Hora (`ts`, `edited`) é a do servidor.
- `ref` resume a mensagem respondida: `seq` (para abrir o histórico com `around`), texto cortado em
  300 caracteres e `attachments` (quantos anexos ela tinha). Se ela foi apagada, vem só
  `{id, missing:true}`.
- Menção: `<@id>` no texto; o servidor extrai `mentions` (até 20) ao enviar e ao editar.
- Só o autor edita. O autor apaga a própria; administrador com o modo ativo apaga qualquer uma.
- Texto até 4.000 caracteres; vazio só com anexo. Até 20 emojis diferentes por mensagem.
- Silenciado não envia, não edita, não reage e não envia anexo.
- Limite de envio: 10 mensagens a cada 10 s por sessão.

## Pré-visualização de links (Open Graph)

Depois de gravar (ou editar), o servidor busca até 3 links `http(s)` do texto — link entre `< >` não
gera prévia — e grava `embeds`, espalhando `chat.update`. A busca nunca atrasa o envio. Proteções:
só `http`/`https` nas portas 80 e 443, endereço resolvido e recusado se for loopback, privado,
link-local, CGNAT, multicast ou ULA, conferido de novo a cada redirecionamento (no máximo 3), 5 s de
limite, no máximo 1 MB de HTML lido, só `text/html`. Campos: `og:title`, `og:description`,
`og:image` (+ `width`/`height`), `og:site_name`, `theme-color`; sem `og:title`, usa `<title>` e
`meta description`. A imagem da prévia passa pelo servidor (`GET /api/embed/{mensagem}/{n}`), para
o site não ver o endereço de quem lê.

## Anexos

- Envio: `POST /api/upload` no servidor, corpo cru, `X-AV-Token`, `X-AV-Name` (nome codificado em
  URL), `Content-Type`. Até 25 MB por arquivo, até 10 por mensagem, até 20 esperando envio por
  pessoa. Devolve o anexo (`201`, o mesmo objeto de `attachments`); ele só vale depois de entrar numa
  mensagem (o que não entrar em 1 hora é apagado). Recusa: status HTTP com o texto em PT-BR no corpo
  (`413` grande demais, `415` formato recusado, `401` token inválido).
- O tipo gravado vem dos primeiros bytes do arquivo, não do que o app declara: WebP, GIF, WebM
  (`audio/webm` quando o app declara `audio/*`, senão `video/webm`), Ogg (`audio/ogg`), MP3
  (`audio/mpeg`), WAV (`audio/wav`), FLAC, MP4/M4A (`audio/mp4` quando declarado áudio, senão
  `video/mp4`) e PDF. Outro arquivo fica com o tipo declarado se for seguro, senão
  `application/octet-stream`. Mensagem de voz: `audio/webm` (Opus), nome "Mensagem de voz.webm".
- Imagem (G2): o app converte para WebP qualidade 0,85 com o lado maior em no máximo 2.560 px antes
  de enviar. O servidor recusa PNG/JPEG/BMP e WebP maior que 2.560 px; GIF fica original.
  Dimensões vêm do cabeçalho do arquivo, lidas no servidor.
- Leitura: `GET /api/files/{id}` com `X-AV-Token`, só para quem é da conversa. `nosniff`; o que não
  for imagem, vídeo ou áudio sai como `attachment`.
- Os arquivos ficam em `/data/attachments`, fora da conta de bytes das mensagens. Têm teto próprio de
  10 GiB (`ATTACHMENT_STORAGE_LIMIT_BYTES`): passou, os mais antigos são apagados até 90% do teto e a
  mensagem mostra o anexo com `expired: true` (espalhando `chat.update`).

## Armazenamento

**Nenhuma mensagem é apagada automaticamente** — nem por idade, nem por tamanho. Mensagem só sai
quando o autor ou um administrador a apaga, quando um administrador limpa uma conversa
(`admin.clear`) ou apaga tudo (`admin.wipe`).

**O que se mede:** a soma, em bytes, das linhas de mensagem gravadas no SQLite — o JSON de cada
mensagem como está no banco: texto, autor, horários, menções, reações, metadados de prévia de link
e as referências aos anexos. Não entram os arquivos anexados, índices, páginas livres do SQLite e as
outras tabelas. Medição exata (`SUM(length(CAST(data AS BLOB)))`) ao iniciar e depois de cada
limpeza em massa; entre elas, o total anda pelo tamanho de cada linha gravada, editada ou apagada.
O número aparece em `admin.stats`.

## Administração

Senha em `ADMIN_PASSWORD` no servidor, nunca no app nem no repositório. Comparação em tempo
constante, no máximo 5 tentativas a cada 10 minutos por pessoa e por endereço. Quem acerta vira
administrador (guardado no servidor) e liga ou desliga o modo administrador; os poderes só valem com
o modo ligado. Banimento vale para a identidade e para o endereço de IP de onde ela conectou.
Administrador não expulsa, não silencia e não bane outro administrador. Banimento por endereço só
vale para endereço público (loopback e rede privada são compartilhados) e só quando a pessoa está
conectada no momento do banimento; offline, o banimento vale só para a identidade.

**Apagar tudo** (`admin.wipe`): exige o modo ativo e `confirm: "APAGAR TUDO"` exatamente. Apaga todas
as mensagens, os anexos delas (arquivo e registro) e o estado de leitura de todo mundo; anexos ainda
não enviados ficam. Roda uma vez por vez (um segundo pedido durante a limpeza é recusado), recalcula
os bytes, registra no log e espalha `{t:"chat.wiped"}` a todas as sessões `proto: 2`.
