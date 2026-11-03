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

**Redes e membros não ficam em nenhum lugar local** (ficam nos servidores do Radmin), então não há como listá-los oficialmente. Alternativa adotada: o "grupo" **Rede Radmin** corresponde a quem está no adaptador e roda o Angatu Voice; participantes são descobertos por beacons UDP assinados em broadcast (`26.255.255.255`, portas 47600 a 47609), por troca de pares e pelo botão "Conectar por IP". Grupos privados adicionais usam convite com segredo.

## Protocolo entre usuários
- Escuta TCP **só no IP do Radmin** (portas 47600 a 47609); UDP em curinga (necessário para receber broadcast no Windows), mas origens fora da faixa do adaptador são descartadas sem leitura.
- **Identidade**: chave Ed25519 gerada na primeira execução; o id é o hash da chave pública (20 hex). O nome do Radmin nunca prova identidade: é só um rótulo, e nomes repetidos são sinalizados na tela.
- **Handshake**: cada lado envia `hello` assinado (chave, X25519 efêmera, nome, porta, hora). As chaves de sessão derivam do segredo X25519 e da transcrição, uma por direção; AES-GCM com nonce de contador estrito (replay ou adulteração derrubam o link).
- **Pertencimento a grupo privado**: tag HMAC(segredo, "member", transcrição, id) enviada no `auth`; quem não tem o segredo não prova e não recebe nada.
- **Mensagens**: id `<autor>:<uuid>`, assinadas pelo autor; quem retransmite não consegue forjar. Limite de 4000 caracteres, controles removidos, texto exibido sempre como texto. Histórico persistente; ao reconectar, `sync` pede o que faltou e DMs pendentes são reenviadas até a confirmação (`ack`).
- **Chamadas**: a mídia é WebRTC ponto a ponto no adaptador Radmin (candidatos filtrados para a faixa 26.x); o Java só mantém presença (`call`) e relaya sinalização (`sig`, até 32 KiB, tipos `offer`, `answer`, `candidate`, `bye`) entre pares autenticados. Negociação perfeita, reconexão por `restartIce`, malha completa em grupo.
- Limites: 64 links, 8 handshakes simultâneos, 120 quadros por segundo por link, quadro de até 256 KiB.

## Voz e tela
Microfone com cancelamento de eco, supressão de ruído, ganho de entrada, detecção de voz (limiar pela sensibilidade; apertar para falar por atalho global), volume por pessoa e geral, saída selecionável (`setSinkId`). Tela: `getDisplayMedia` com 720p ou 1080p e 30 ou 60 FPS; a cada 3 s o controle adaptativo observa perda, RTT e limitação por banda e desce ou sobe uma escada (resolução, FPS e taxa de bits). Atalhos globais: gancho de teclado `WH_KEYBOARD_LL` via JNA (só observa, nada é engolido).

**Limitação conhecida:** o JCEF não oferece seletor nativo de fonte; a captura usa a flag `--auto-select-desktop-capture-source` (tela inteira). Escolher monitor ou janela específica exigiria um seletor próprio.

## Persistência e migrações
`Saveable` (SQLite, nada em RAM). `Migrations` roda passos ordenados em transação, faz backup (`VACUUM INTO`, os 3 últimos) antes de migrar e **recusa abrir dados de esquema mais novo** (sem downgrade).

## Configurações
Aplicativo (nome, iniciar com o Windows, minimizado, bandeja, tema, notificações, privacidade), Áudio, Vídeo, Rede (diagnóstico, conexão por IP), Atualizações (canal, intervalo, histórico) e Sobre. Tudo salvo localmente; a chave `update.token` nunca é devolvida à interface.

## Segurança, resumo
Loopback único para a interface; sessão por token de uso único; Host, Origin e cookie em toda rota; CSP e `no-store`; rede só no adaptador Radmin com autenticação mútua Ed25519 e sessão cifrada; mensagens assinadas e saneadas; limites por link; atualizador com assinatura Ed25519, SHA-256, hosts permitidos e sem downgrade; nenhum script ou comando vem do repositório, e o script de atualização é do próprio app e recebe parâmetros do próprio app.
