---
tags: [angatu-voice, arquitetura]
---
# Arquitetura

## Visão geral
```
JFrame - JCEF (Chromium) --http--> 127.0.0.1:<porta livre> (AngatuLib/Javalin)
                                     rotas /api/*  +  WS /ws  (cookie de sessão)
                                     |
                      services (regras) - Saveable/SQLite
                                     |
                      PeerNetwork - ServerLink - WSS /ws/net - servidor (relay/, voice.angatusistemas.com.br)
                                     |
                      voz: WebRTC por TURN (3479)    tela: WSS /ws/stream
```
A interface é uma **SPA**: `index.html` é o shell, `app.html` o único fragmento (`/app`), e as telas (chat, configurações, modais, chamada) são montadas por `public/scripts/app.js` e `settings.js`. O JCEF só aceita navegar para o próprio servidor; popups e menus de contexto são bloqueados.

## Servidor local e ponte Java e interface
- A biblioteca não tem opção de host; `BootRoute` fixa `jetty.host = 127.0.0.1` antes de o servidor subir. A porta livre é escolhida a cada execução.
- O JFrame abre `/boot?t=<token>`: token aleatório de uso único e 60 s. `/boot` confere o `Host`, troca o token por um cookie `HttpOnly; SameSite=Strict` e redireciona para `/app`.
- **Toda** rota `/api/*` passa por `Guard.requireSession`: par em loopback, `Host` igual ao nosso (barra DNS rebinding), cookie válido (comparação em tempo constante) e `Origin` do mesmo site nas escritas. O WebSocket confere o cookie dentro do próprio `LiveHub`, porque a requisição de upgrade não passa pelos filtros.
- A "ponte" é essa lista fechada de rotas: JavaScript não chama métodos Java arbitrários. Corpos são records que listam os campos aceitos; configurações só entram pela lista de `SettingsService` (cada chave com validação); ações do sistema operacional são só abrir link http(s) (com confirmação na tela), sair e as do atualizador.
- Eventos em tempo real (WS) apenas avisam; quem os recebe relê pelo REST. CSP restritiva e `no-store` em tudo.

## Servidor (`relay/`)
Um só processo Java com AngatuLib na Coolify, em `voice.angatusistemas.com.br`, mais um contêiner `coturn` para a voz. O acesso é aberto a qualquer um, por decisão do dono: não há conta nem senha.

| Canal | O que leva |
|---|---|
| `WS /ws/net` | presença, mensagens, chamadas, sinalização, fotos |
| `WS /ws/stream` | a tela: quem transmite publica, quem assiste se inscreve com o id do fluxo |
| TURN, porta 3479 (UDP e TCP) | a voz e o som da tela, em WebRTC cifrado de ponta a ponta |
| `GET /health` | o que a Coolify consulta |

- **Identidade:** chave Ed25519 gerada na primeira execução; o id é o hash da chave pública (20 hex). O servidor manda um desafio e confere a assinatura, então ninguém fala em nome de outro. Nomes são só rótulos, e nomes repetidos são sinalizados.
- **Mensagens:** id `<autor>:<uuid>`, assinadas pelo autor; o app confere a assinatura de cada uma, então o servidor não consegue forjar. Ele consegue ler: o conteúdo não é cifrado de ponta a ponta.
- **Entrega:** o servidor grava (`StoredMessage`, com número de sequência) e repassa. Ao conectar, o app pede tudo depois da última sequência que recebeu (`sync`). Numa conversa privada, a mensagem fica pendente até quem recebe confirmar (`ack`); num grupo, até o servidor guardar (`stored`). O que não foi confirmado é reenviado, e o servidor não duplica.
- **Limpeza:** a cada hora o servidor apaga mensagens com mais de 30 dias e, em cada conversa, o que passa das 1.000 mais recentes (`MESSAGE_RETENTION_DAYS`, `MESSAGE_MAX_PER_CHAT`). O histórico local não é afetado.
- **Grupos privados:** o app prova que é membro com `SHA-256("AV3|group|" + segredo)`. O servidor guarda só esse hash: não sabe o nome do grupo nem o segredo, e o convite continua sendo passado entre as pessoas.
- **Presença:** quem cai fica 12 s como online antes de os outros saberem, porque a reconexão costuma vir antes disso. A conexão é checada a cada 3 s, e 20 s sem resposta derrubam e refazem.
- **Fotos:** só quem ainda não tem pede (`profile?`), e a foto vai direto da pessoa, sem ficar guardada no servidor.
- **Limites:** 512 conexões, 32 por endereço, 200 quadros por segundo, 128 KB por quadro; na tela, 32 fluxos, 16 espectadores por fluxo, 5 MB/s por fluxo, 4 MB por quadro. Redes da lista DROP da Spamhaus são recusadas.
- **TURN:** a porta 3478 da VPS é de outro projeto. Como todos são retransmitidos pelo mesmo processo, as portas de retransmissão ficam dentro do contêiner e só a 3479 é publicada. A credencial é curta e calculada do segredo `TURN_SECRET`, que está nas variáveis da Coolify.
- **Memória:** `mem_limit` de 256 MB no servidor e 64 MB no TURN.

### Chamada recebida
Quem entra numa sala vazia é quem chama, e o quadro leva `ring`. Quem recebe guarda a chamada em `CallService` até ela acabar: atendida, recusada (`decline`, e fica quieta até a sala esvaziar), cancelada ou sem resposta em 45 s, que vira chamada perdida. Quem chega no meio toca se a chamada ainda é nova (no mínimo por 15 s) e vê a faixa de chamada em andamento se não. A interface lê a lista em `/api/state` e no evento `call.incoming`; com a janela em segundo plano, `CallPopup` mostra Atender e Recusar sobre os outros programas sem roubar o foco, e a escolha volta pelo evento `call.action`. O toque é a música `assets/ringtone.mp3`, tocada em laço por um único elemento de áudio, que para na hora e usa a saída escolhida. Quem liga ouve o tom de chamada brasileiro em laço, e quem já está em outra chamada ouve dois bipes discretos. O significado de cada som está em `assets/audio/LICENSES.md`.

## Voz e tela
Microfone com cancelamento de eco, supressão de ruído, ganho de entrada, detecção de voz (limiar pela sensibilidade, 50 ms de pré-rolagem para não cortar a primeira sílaba; apertar para falar por atalho global), volume e silêncio por pessoa e geral, saída selecionável (`setSinkId`). Atalhos globais: gancho de teclado `WH_KEYBOARD_LL` via JNA (só observa, nada é engolido).

**Quem está falando.** Cada par tem um canal de dados `av-state` que leva fala, microfone e áudio de quem envia, então todos veem o mesmo que o próprio falante. O indicador acende com dois ciclos de 50 ms acima do limiar, apaga com 60% dele (histerese) e fica 600 ms aceso depois da fala. Sem mensagem no canal por 5 s, cai para a medição local do áudio recebido.

**Tela.** A captura vem de `getUserMedia` com `chromeMediaSource: desktop`, porque é o único caminho que aceita escolher o monitor (`screen:<índice>:0`, o mesmo índice de `EnumDisplayDevices`) e que entrega o áudio do sistema de verdade.

- **Nitidez:** `contentHint = detail` (ou `motion`) e ordem de preferência VP8, VP9, AV1 no caminho do WebRTC. Medido com movimento real, VP9 e AV1 por processador ficam em 24 e 9 FPS. Cada oferta e resposta SDP leva `b=AS:16000`, `x-google-start-bitrate=6000` e `x-google-min-bitrate=1500` (para 60 FPS), evitando a restrição inicial do limitador WebRTC. A captura solicita `minFrameRate: 60` para manter cadência estável sem throttling em telas estáticas.
- **Proporção e adaptação:** o tamanho real vem dos quadros (`videoHeight` de um `<video>` fora da tela), porque `getSettings()` de uma captura informa o limite pedido, não o que chega. A tela usa `object-fit: contain`. No WebRTC, o modo "Nitidez" usa `balanced`, porque `maintain-resolution` prende a imagem em 2 a 5 FPS; o modo "Movimento" (`maintain-framerate`) prioriza o ritmo.
- **Adaptação por pessoa:** a cada 2 s, cada conexão compara a banda disponível com o mínimo do degrau, a perda e a limitação por CPU. Três leituras ruins descem um degrau, e a volta espera 20 s estáveis, dobrando a cada queda até 2 minutos. Um espectador com rede ruim não afeta os outros.
- **Áudio do sistema:** só entra faixa cujo `deviceId` começa com `loopback`, pois o microfone tem de passar sempre pelo silenciar. A captura também ouve a saída do próprio aplicativo (`restrictOwnAudio` e o cancelamento de eco não a removem no CEF), então, enquanto alguém fala, o som compartilhado baixa para 6% para a pessoa não ouvir a si mesma de volta.

**Placa de vídeo.** Quando quem transmite tem codificador H.264 por hardware e quem assiste tem decodificador, a imagem não passa pelo vídeo do WebRTC. Um `VideoEncoder` num Worker recebe os quadros de `MediaStreamTrackProcessor` e os envia pelo `/ws/stream`. O servidor repassa cada quadro a uma fila curta por espectador; quem fica para trás perde quadros até o próximo quadro-chave e pede outro. Do outro lado, outro Worker decodifica e escreve em `MediaStreamTrackGenerator`, cuja faixa vai para o mesmo `<video>`. O som da transmissão continua no WebRTC. A taxa é uma só e segue o caminho mais estreito: o aviso `slow` do servidor ou o `bufferedAmount` acima de 1 MB cortam 30%, e seis segundos calmos sobem 15%. Sem imagem por 4 s, quem assiste refaz a inscrição; sem o primeiro quadro em 8 s, ou sem placa compatível, volta ao WebRTC com VP8. Nesse caso, com até 4 núcleos lógicos, começa em 720p a 30 FPS.

**Limitação conhecida:** só o monitor inteiro é compartilhado; escolher uma janela específica exigiria um seletor próprio. A captura entrega de 42 a 54 FPS em 1080p, não 60. A placa precisa de H.264; quando ela não tem, a mídia é codificada em software, e cada espectador custa um codificador.

## Janela e segundo plano
Minimizar é o minimizar do Windows, o botão de fechar esconde a janela na bandeja (avisando na primeira vez) e `quit` só vem do Sair da bandeja ou de Configurações, depois de enviar `bye`. `Tray` restaura com um clique, e a janela volta no estado em que estava. Com "Iniciar escondido na bandeja", `addNotify` e `createImmediately` criam o Chromium sem mostrar a janela, porque chamadas e avisos vivem na página. `--disable-background-timer-throttling`, `--disable-backgrounding-occluded-windows`, `--disable-renderer-backgrounding` e `IntensiveWakeUpThrottling` desligado mantêm a detecção de voz em tempo real com a janela escondida.

## Persistência e migrações
`Saveable` (SQLite, nada em RAM). `Migrations` roda passos ordenados em transação, faz backup (`VACUUM INTO`, os 3 últimos) antes de migrar e **recusa abrir dados de esquema mais novo** (sem downgrade).

## Configurações
Aplicativo (nome, iniciar com o Windows, iniciar escondido, bandeja, tema, notificações, privacidade), Áudio, Vídeo, Rede (estado do servidor, participantes, diagnóstico), Atualizações (canal, intervalo, histórico) e Sobre. Tudo salvo localmente; a chave `update.token` nunca é devolvida à interface. O instalador pergunta sobre o início com o Windows: até a pessoa mudar nas configurações, o registro é a resposta.

## Segurança, resumo
Loopback único para a interface; sessão por token de uso único; Host, Origin e cookie em toda rota; CSP e `no-store`; conexão ao servidor por TLS com identidade provada por Ed25519; mensagens assinadas e saneadas; limites no servidor; atualizador com assinatura Ed25519, SHA-256, hosts permitidos e sem downgrade; nenhum script ou comando vem do repositório, e o script de atualização é do próprio app e recebe parâmetros do próprio app.
