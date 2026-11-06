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
                      PeerNetwork - TCP cifrado + beacons UDP - adaptador "Radmin VPN" (26.x/8)
```
A interface é uma **SPA**: `index.html` é o shell, `app.html` o único fragmento (`/app`), e as telas (chat, configurações, modais, chamada) são montadas por `public/scripts/app.js` e `settings.js`. O JCEF só aceita navegar para o próprio servidor; popups e menus de contexto são bloqueados.

## Servidor local e ponte Java e interface
- A biblioteca não tem opção de host; `BootRoute` fixa `jetty.host = 127.0.0.1` antes de o servidor subir. A porta livre é escolhida a cada execução.
- O JFrame abre `/boot?t=<token>`: token aleatório de uso único e 60 s. `/boot` confere o `Host`, troca o token por um cookie `HttpOnly; SameSite=Strict` e redireciona para `/app`.
- **Toda** rota `/api/*` passa por `Guard.requireSession`: par em loopback, `Host` igual ao nosso (barra DNS rebinding), cookie válido (comparação em tempo constante) e `Origin` do mesmo site nas escritas. O WebSocket confere o cookie dentro do próprio `LiveHub`, porque a requisição de upgrade não passa pelos filtros.
- A "ponte" é essa lista fechada de rotas: JavaScript não chama métodos Java arbitrários. Corpos são records que listam os campos aceitos; configurações só entram pela lista de `SettingsService` (cada chave com validação); ações do sistema operacional são só abrir link http(s) (com confirmação na tela), sair e as do atualizador.
- Eventos em tempo real (WS) apenas avisam; quem os recebe relê pelo REST. CSP restritiva e `no-store` em tudo.

## Integração com o Radmin VPN (validada nesta máquina)
O Radmin VPN **não tem API pública**. O que ele expõe localmente e o app usa:

| Informação | Fonte |
|---|---|
| Instalado | `Radmin VPN\RvRvpnGui.exe` em Program Files |
| Em execução | serviço `RvControlSvc` (`sc query`) |
| IP privado, máscara, broadcast | adaptador "Famatech Radmin VPN Ethernet Adapter" (IPv4 `26.x.x.x/8`) |
| Apelido do usuário | `HKLM\SOFTWARE\WOW6432Node\Famatech\RadminVPN\1.0\Alias` |

### Automação de interface e gerenciamento de redes
Para permitir a entrada e saída em redes sem intervenção manual do usuário:
- **Automação via Windows UI Automation:** o executável Qt (`RvRvpnGui.exe`) é controlado através de script PowerShell dedicado (`radmin-automation.ps1`), executado com comutação explícita de desktop (`WinSta0\Default`) em thread STA dedicada. Os diálogos e ações de menu (`actionJoin_Existant`, `actionCreate_New`, `DlgJoinNetwork`, `DlgCreateNetwork`) são acionados diretamente pelos identificadores de automação.
- **Rede padrão obrigatória:** a rede **AngatuVoice** (senha `senha123`) é permanente, protegida contra exclusão, saída ou renomeação, e conectada automaticamente ao inicializar o aplicativo.
- **Mestre de inicialização (bootstrap):** caso a rede ainda não tenha sido criada no Radmin, instâncias iniciadas com a flag `-Dangatu.bootstrap.master=true` ou `ANGATU_BOOTSTRAP_MASTER=true` criam a rede na partida. Usuários normais aguardam a existência da rede e realizam tentativas de conexão com recuo exponencial e jitter.
- **Persistência local:** a entidade `RadminNetwork` armazena as redes conhecidas no SQLite via Saveable, sincronizando seu status com o cliente gráfico do Radmin.
- **Rotas REST protegidas:** `/api/networks` lista o estado da rede padrão e redes adicionais; `/api/networks/join` e `/api/networks/create` gerenciam redes adicionais; operações de saída (`/leave`) ou exclusão (`/delete`) contra a rede `AngatuVoice` são rejeitadas estritamente com código 403 Forbidden.

**Redes e membros não ficam em nenhum lugar local** (ficam nos servidores do Radmin), então não há como listá-los oficialmente. Alternativa adotada: o "grupo" **Rede Radmin** corresponde a quem está no adaptador e roda o Angatu Voice; participantes são descobertos por beacons UDP assinados em broadcast (`26.255.255.255`, portas 47600 a 47609), por troca de pares e pelo botão "Conectar por IP". Grupos privados adicionais usam convite com segredo.

## Protocolo entre usuários
- Escuta TCP **só no IP do Radmin** (portas 47600 a 47609); UDP em curinga (necessário para receber broadcast no Windows), mas origens fora da faixa do adaptador são descartadas sem leitura.
- **Identidade**: chave Ed25519 gerada na primeira execução; o id é o hash da chave pública (20 hex). O nome do Radmin nunca prova identidade: é só um rótulo, e nomes repetidos são sinalizados na tela.
- **Handshake**: cada lado envia `hello` assinado (chave, X25519 efêmera, nome, porta, hora). As chaves de sessão derivam do segredo X25519 e da transcrição, uma por direção; AES-GCM com nonce de contador estrito (replay ou adulteração derrubam o link).
- **Pertencimento a grupo privado**: tag HMAC(segredo, "member", transcrição, id) enviada no `auth`; quem não tem o segredo não prova e não recebe nada.
- **Mensagens**: id `<autor>:<uuid>`, assinadas pelo autor; quem retransmite não consegue forjar. Limite de 4000 caracteres, controles removidos, texto exibido sempre como texto. Histórico persistente; ao reconectar, `sync` pede o que faltou e DMs pendentes são reenviadas até a confirmação (`ack`).
- **Chamadas**: a mídia é WebRTC ponto a ponto no adaptador Radmin (candidatos filtrados para a faixa 26.x); o Java só mantém presença (`call`, `rooms`, `decline`) e relaya sinalização (`sig`, até 64 KiB, tipos `offer`, `answer`, `candidate`, `bye`) entre pares autenticados. Negociação perfeita, reconexão por `restartIce`, malha completa em grupo.
- Limites: 64 links, 8 handshakes simultâneos, rajada de 4000 quadros com reposição de 400 por segundo por link, quadro de até 256 KiB, 16 MiB de fila de saída por link.

### Conexão e presença
Cada link tem uma fila de saída e uma thread própria de escrita: um par travado nunca segura os outros, o relógio da rede nem as rotas. Um quadro que este lado não entende é descartado sem derrubar o link.

| Situação | O que acontece |
|---|---|
| A cada 3 s | `ping`/`pong` em cada link: mede a latência e prova que o outro lado responde |
| 10 s sem receber nada | o par é suspeito: disca de novo sem mexer na presença, e o socket novo substitui o antigo |
| 20 s sem receber nada | o socket é fechado |
| Socket fechado sem `bye` | a pessoa fica "reconectando" por até 20 s, continua nas salas e a mídia WebRTC segue; o redial é imediato, e o lado de id maior espera 400 ms para os dois não discarem juntos |
| Volta dentro da carência | evento `peer.relinked`, sem aviso; a interface termina a negociação que estava pendente |
| Porta recusa duas vezes | o programa do outro lado fechou: acaba a carência na hora |
| `bye` recebido | saída imediata, sem carência |

**Qual socket fica.** Dois dials que se cruzam nos primeiros 5 s são decididos pela ordem dos ids, então os dois lados escolhem o mesmo. Depois disso o socket novo sempre vence: o outro lado só discaria se a ponta dele tivesse morrido, e recusá-lo deixaria a pessoa offline para um dos lados até o tempo limite.

**Quem chega depois.** O `auth` traz os endereços conhecidos e quem chega é apresentado aos demais por um quadro `peers` na hora, repetido a cada 30 s, sem depender de broadcast. O redial usa espera de 1 a 30 s por endereço, zerada quando chega um beacon, e o adaptador do Radmin só é dado como perdido depois de duas leituras seguidas e 30 s de carência.

**Salas.** Na volta do link, `rooms` lista as salas em que a pessoa está naquele momento, com a idade da chamada, e substitui o que este lado sabia: o que mudou durante a queda é corrigido na hora. Versões até 1.3.0 não têm `proto` no `auth`; para elas valem os tempos antigos (ping a cada 20 s, queda em 70 s) e o primeiro `call` de uma sala vazia conta como toque.

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

**Placa de vídeo.** Quando quem transmite tem codificador H.264 por hardware e quem assiste tem decodificador, a imagem não passa pelo vídeo do WebRTC. Um `VideoEncoder` num Worker recebe os quadros de `MediaStreamTrackProcessor`, e cada quadro é dividido em pedaços de 16 KB no canal de dados `av-video`, sem ordem e com 150 ms de vida. Do outro lado, outro Worker remonta, decodifica e escreve em `MediaStreamTrackGenerator`, cuja faixa vai para o mesmo `<video>`. O áudio da transmissão continua no WebRTC. Perdeu um quadro, quem assiste pede um quadro-chave; há um a cada 5 s de qualquer forma. A taxa é uma só para todos e segue o caminho mais estreito: `bufferedAmount` acima de 1 MB corta 30%, e seis segundos calmos sobem 15%. Sem placa compatível, em versões antigas ou sem imagem em 6 s, volta ao WebRTC com VP8; nesse caso, com até 4 núcleos lógicos, começa em 720p a 30 FPS.

**Limitação conhecida:** só o monitor inteiro é compartilhado; escolher uma janela específica exigiria um seletor próprio. A captura entrega de 42 a 54 FPS em 1080p, não 60. A placa precisa de H.264; quando ela não tem, a mídia é codificada em software, e cada espectador custa um codificador.

## Janela e segundo plano
Minimizar é o minimizar do Windows, o botão de fechar esconde a janela na bandeja (avisando na primeira vez) e `quit` só vem do Sair da bandeja ou de Configurações, depois de enviar `bye`. `Tray` restaura com um clique, e a janela volta no estado em que estava. Com "Iniciar escondido na bandeja", `addNotify` e `createImmediately` criam o Chromium sem mostrar a janela, porque chamadas e avisos vivem na página. `--disable-background-timer-throttling`, `--disable-backgrounding-occluded-windows`, `--disable-renderer-backgrounding` e `IntensiveWakeUpThrottling` desligado mantêm a detecção de voz em tempo real com a janela escondida.

## Persistência e migrações
`Saveable` (SQLite, nada em RAM). `Migrations` roda passos ordenados em transação, faz backup (`VACUUM INTO`, os 3 últimos) antes de migrar e **recusa abrir dados de esquema mais novo** (sem downgrade).

## Configurações
Aplicativo (nome, iniciar com o Windows, iniciar escondido, bandeja, tema, notificações, privacidade), Áudio, Vídeo, Rede (diagnóstico, conexão por IP), Atualizações (canal, intervalo, histórico) e Sobre. Tudo salvo localmente; a chave `update.token` nunca é devolvida à interface. O instalador pergunta sobre o início com o Windows: até a pessoa mudar nas configurações, o registro é a resposta.

## Segurança, resumo
Loopback único para a interface; sessão por token de uso único; Host, Origin e cookie em toda rota; CSP e `no-store`; rede só no adaptador Radmin com autenticação mútua Ed25519 e sessão cifrada; mensagens assinadas e saneadas; limites por link; atualizador com assinatura Ed25519, SHA-256, hosts permitidos e sem downgrade; nenhum script ou comando vem do repositório, e o script de atualização é do próprio app e recebe parâmetros do próprio app.
