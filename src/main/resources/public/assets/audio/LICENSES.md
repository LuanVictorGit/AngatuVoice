---
tags: [angatu-voice, audio]
---
# Sons do Angatu Voice

Os sons de `assets/audio/` (fora de `soundboard/`) são obra original da Angatu Sistemas, sintetizados por `tools/SoundGen.java`, sem amostra de terceiros. Para mudar um som, altere o gerador e rode de novo: `java tools/SoundGen.java src/main/resources/public/assets/audio`.

A música de chamada (`assets/ringtone.mp3`) é outro arquivo, escolhido pelo dono do projeto, e não vem do gerador.

## As regras

- **Direção:** subir é ligar, entrar ou começar. Descer é desligar, sair ou terminar. Todo par é espelhado.
- **Timbre:** diz de quem é o assunto. Seus controles, sua chamada, outras pessoas, transmissão, ou um sino que pede atenção.
- **Nível:** o que soa fica entre -19 e -26 dB RMS, com pico abaixo de -3 dBFS, para nada estourar sobre as vozes.
- **Silêncio:** ensurdecido, você não ouve os avisos sobre outras pessoas nem as mensagens. Seus próprios controles continuam soando.

## O que cada som significa

| Arquivo | Quando toca | Forma |
|---|---|---|
| `interface/mute.mp3` | Você desliga o microfone | Dois tons, descendo |
| `interface/unmute.mp3` | Você liga o microfone | Dois tons, subindo |
| `interface/deafen.mp3` | Você desliga o fone | Três tons graves, descendo |
| `interface/undeafen.mp3` | Você liga o fone | Três tons graves, subindo |
| `interface/peer-muted.mp3` | Você silencia alguém, ou todos | Um toque curto, grave |
| `interface/peer-unmuted.mp3` | Você volta a ouvir alguém, ou todos | Um toque curto, agudo |
| `interface/error.mp3` | Algo deu errado | Duas batidas graves e dissonantes |
| `calls/call-joined.mp3` | Você entra numa chamada em que já há gente | Arpejo maior, subindo |
| `calls/call-ended.mp3` | A sua chamada termina | O mesmo arpejo, descendo |
| `calls/call-busy.mp3` | Recusaram a sua chamada | Tom de ocupado: 425 Hz, 250 ms ligado e 250 ms desligado, três vezes |
| `calls/call-ringback.mp3` | Você espera ser atendido, em laço | Tom de chamada: 425 Hz, 1 s ligado e 4 s desligado |
| `calls/call-waiting.mp3` | Chega outra chamada durante a conversa, em laço | Dois bipes baixos a cada 5 s |
| `calls/peer-joined.mp3` | Outra pessoa entra na sua chamada | Dois tons de madeira, subindo |
| `calls/peer-left.mp3` | Outra pessoa sai da sua chamada | Dois tons de madeira, descendo |
| `notifications/stream-started.mp3` | Começa uma transmissão de tela | Quintas subindo |
| `notifications/stream-stopped.mp3` | Termina uma transmissão de tela | Quintas descendo |
| `notifications/viewer-joined.mp3` | Alguém começa a assistir à sua tela | Dois tons de palheta, subindo |
| `notifications/viewer-left.mp3` | Alguém para de assistir à sua tela | Dois tons de palheta, descendo |
| `notifications/message-received.mp3` | Chega mensagem fora da conversa aberta | Sino de dois tons, subindo |
| `notifications/call-missed.mp3` | Você perdeu uma chamada | Sino de três tons, descendo |
| `notifications/network-connected.mp3` | O app entrou sozinho na rede AngatuVoice | Sino de três tons, subindo até a oitava |

Os tons de telefone seguem a Resolução nº 473/2007 da Anatel. Quando alguém chama você, toca `assets/ringtone.mp3`.

## Fontes

- Tons de telefone no Brasil: [Resolução CD/ANATEL nº 473/2007](https://www.legisweb.com.br/legislacao/?id=106413).
- Grave e dissonante soam como maior gravidade: [The perceived hazard of earcons](https://experts.nau.edu/en/publications/the-perceived-hazard-of-earcons-in-information-technology-excepti/).
- Subir para começar e descer para terminar é costume de projeto, não resultado de estudo: [Earcons as sonification bookends](https://accessibleoceans.whoi.edu/earcons-as-sonification-bookends).
- Silêncio ao ensurdecer, pedido antigo de quem usa chamadas de voz: [Discord Support](https://support.discord.com/hc/es/community/posts/360037371652--Option-to-mute-sound-notification-of-other-users-leaving-joining-your-voice-channel-when-you-are-deafened).

## Painel de sons (`soundboard/`)

Gravações de terceiros em domínio público ou com licença aberta, usadas no painel do Shift direito. Os arquivos WAV foram cortados no começo e no fim, convertidos para mono e reamostrados para 22.050 Hz; os OGG estão como vieram. O crédito também aparece em Configurações › Sobre.

| Arquivo | Origem | Autor | Licença |
|---|---|---|---|
| `soundboard/pato.wav` | [Domestic duck sound 01](https://commons.wikimedia.org/wiki/File:Domestic_duck_sound_01.wav) | Ganesh Mohan T | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0) (o arquivo adaptado segue a mesma licença) |
| `soundboard/fracasso.wav` | [Sad Trombone](https://commons.wikimedia.org/wiki/File:Sad_Trombone.wav) | Sad Tromboner | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0) |
| `soundboard/ba-dum-tss.wav` | [Kevin MacLeod assorted rimshots - Bah-dum](https://commons.wikimedia.org/wiki/File:Kevin_MacLeod_assorted_rimshots_-_Bah-dum.wav) | Kevin MacLeod | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0) |
| `soundboard/aplausos.wav` | [277021 sandermotions applause-2](https://commons.wikimedia.org/wiki/File:277021_sandermotions_applause-2.wav) | Sandermotions | [CC0](https://creativecommons.org/publicdomain/zero/1.0/) |
| `soundboard/berinjela.wav` | [LL-Q5146 (por)-MedK1-berinjela](https://commons.wikimedia.org/wiki/File:LL-Q5146_(por)-MedK1-berinjela.wav) (Lingua Libre) | MedK1 | [CC0](https://creativecommons.org/publicdomain/zero/1.0/) |
| `soundboard/game-over.ogg`, `perdedor.ogg`, `vitoria.ogg` | [Voiceover Pack: Fighter](https://kenney.nl/assets/voiceover-pack-fighter) (`game_over`, `loser`, `flawless_victory`) | Kenney Vleugels (Kenney.nl) | [CC0](https://creativecommons.org/publicdomain/zero/1.0/) |

