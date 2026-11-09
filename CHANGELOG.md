---
tags: [angatu-voice, changelog]
---
# Histórico de versões

Todas as mudanças relevantes do Angatu Voice ficam aqui, da mais nova para a mais antiga. O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o [Versionamento Semântico](https://semver.org/lang/pt-BR/). As regras do projeto estão em [[docs/VERSIONAMENTO]].

O texto de cada seção vira as notas da versão no GitHub e na tela de Atualizações do aplicativo, então ele é escrito para quem usa o app.

## [2.2.1] - 2026-11-09

### Adicionado
- Tiques nas suas mensagens, como no WhatsApp: relógio enquanto envia, um tique quando o servidor guardou, dois quando chegou ao aparelho da outra pessoa e dois azuis quando ela leu. Quem estava offline recebe os dois tiques assim que volta. Nos grupos, os tiques ficam azuis quando alguém lê, e passar o mouse mostra quem viu.
- "Digitando…" com animação também no topo da conversa e na lista lateral.

### Corrigido
- Arquivos .md e .txt enviados davam "Não foi possível abrir o texto" e "Arquivo indisponível".
- Na tela cheia da transmissão, os controles e o cursor somem quando o mouse para e voltam ao mexer.
- Ao sair da tela cheia, a barra de baixo (microfone, fone, compartilhar) sumia atrás da barra de tarefas.
- O menu da mensagem (botão direito) saía pela parte de baixo da tela e escondia "Apagar mensagem": agora ele abre para cima quando não cabe e fica sempre por cima de tudo.
- A lixeira também aparece na barra de ações ao passar o mouse, para quem escreveu e para o administrador com o modo ligado.

## [2.2.0] - 2026-11-08

### Adicionado
- Chat novo, com as mensagens guardadas no servidor: a conversa é a mesma em qualquer computador com a sua identidade, e o que um aparelho lê, edita ou apaga aparece nos outros na hora.
- Responder a uma mensagem, com a citação acima da resposta; clicar nela leva até a original.
- Editar (seta para cima edita a última) e apagar as próprias mensagens; a edição mostra "(editada)".
- Reações com emoji, menções com @ (com sugestões enquanto você digita) e emojis por :nome:.
- Anexos de até 25 MB: imagens aparecem na conversa e abrem em tela grande, vídeos e áudios tocam ali mesmo, e qualquer arquivo pode ser salvo em Downloads. Arraste, cole ou use o clipe.
- Mensagens de voz: segure o microfone para gravar e solte para enviar, arraste para a esquerda para cancelar ou para cima para continuar sem segurar (um clique também grava sem segurar, com pausa e lixeira). Na conversa, o áudio mostra a onda do som, dá para pular para qualquer ponto e ouvir em 1×, 1,5× ou 2×.
- Texto longo (mais de 2.000 caracteres ou 40 linhas) vira um arquivo .md com o nome que você escolher, mostrado já formatado na conversa, com botões de copiar e baixar.
- Arquivos .md e .txt já enviados podem ser editados no próprio cartão, sem apagar e mandar de novo, por quem enviou ou pelo administrador.
- Fixar mensagens: qualquer pessoa da conversa fixa pelo menu da mensagem; a última fixada fica numa faixa no topo, e um clique leva até ela.
- Aviso na tela quando alguém entra ou sai da chamada em que você está.
- Barra "Ir para a mais recente" quando você sobe para ler mensagens antigas.
- Prévia de links com título, descrição e imagem do site.
- Formatação: negrito, itálico, sublinhado, riscado, código, blocos de código, citações, títulos, listas, spoiler e links com texto.
- Indicador de quem está digitando, linha de "Novas" nas mensagens não lidas, aviso de mensagens novas quando você está lendo mais acima, e contador vermelho de menções na lista de conversas.
- Menu de ações no botão direito da mensagem, barra de ações ao passar o mouse e Ctrl + K para trocar de conversa.
- Botão de tela cheia no topo da conversa; Esc volta ao normal no mesmo ponto da leitura.
- Dez avatares de monstrinhos para quem ainda não tem foto, sempre o mesmo para cada pessoa.
- Modo administrador, liberado por senha nas configurações: editar e apagar qualquer mensagem, limpar uma conversa, apagar todas as mensagens do servidor, silenciar, expulsar e banir.

### Mudou
- As mensagens não são mais apagadas depois de 30 dias: nada sai do servidor sozinho. O administrador tem a opção de apagar tudo nas configurações.
- O layout se ajusta melhor em janelas estreitas, e a conversa não rouba mais espaço da tela compartilhada quando está escondida.
- "Limpar mensagens" saiu da conversa privada; agora cada um apaga as próprias mensagens, e o administrador pode limpar uma conversa inteira.

### Corrigido
- A bolinha de online ao lado da foto não aparece mais cortada.

## [2.1.0] - 2026-11-06

### Mudou
- Toda atualização agora é obrigatória. Em até 2 minutos depois de sair, o aplicativo trava numa tela de aviso, sai da chamada se você estiver em uma, atualiza e reinicia sozinho, sem opção de adiar.
- Saíram das configurações as opções "Atualizar automaticamente" e "Verificar a cada", e o botão de cancelar o download.
- Se uma versão não abrir duas vezes no seu computador, o aplicativo volta para a anterior e espera a próxima, em vez de reiniciar sem parar.

### Corrigido
- Transmissão presa em "Carregando transmissão…" para quem entra depois de ela começar: algumas placas só enviam os parâmetros da imagem no primeiro quadro, e agora eles acompanham todo quadro-chave.
- Sem nenhuma imagem em 15 segundos, a transmissão agora passa de verdade para o outro caminho. Antes, o aplicativo tentava de novo para sempre.
- Placas que ignoram o pedido de quadro-chave têm o codificador reiniciado.
- O aplicativo limpa o cache da interface a cada nova versão, e os sons sempre vêm da versão instalada.

## [2.0.2] - 2026-11-06

### Corrigido
- Transmissão presa em "Carregando transmissão…" quando a placa de vídeo de quem transmite é Intel ou AMD: o aplicativo parava de entregar quadros ao codificador antes de receber o primeiro de volta. Se uma placa não devolver imagem em 4 segundos, a transmissão continua pelo processador.

### Adicionado
- Erros de chamada e de transmissão agora ficam gravados no log do aplicativo.

## [2.0.1] - 2026-11-06

### Corrigido
- Ao abrir, o aplicativo podia ficar em "Sem conexão com o servidor" e demorar a entrar, ou não entrar. Agora ele se identifica no primeiro contato.

## [2.0.0] - 2026-11-06

### Adicionado
- Agora um servidor próprio cuida de tudo: quem está online, mensagens, voz e tela. Não é mais preciso instalar nenhuma VPN nem entrar em rede nenhuma: abriu o aplicativo, está conectado.
- Mensagens para quem está offline ficam guardadas no servidor e chegam quando a pessoa volta. Ele apaga sozinho as com mais de 30 dias e, em cada conversa, as que passam das 1.000 mais recentes. O histórico no seu computador continua.
- Quem transmite envia a tela uma vez só para o servidor, que entrega a todos que assistem.
- Um aviso rápido mostra quando alguém começa ou para de assistir à sua tela.

### Mudou
- Qualquer pessoa com o aplicativo pode entrar e aparece para todos na conversa Geral. Para um círculo menor, continuam os grupos privados com convite.
- As mensagens passam pelo servidor de forma legível para ele. A assinatura impede que alguém escreva em nome de outra pessoa, mas não esconde o conteúdo de quem administra o servidor.
- A voz passa pelo servidor, então ninguém na chamada vê o endereço de ninguém.

### Corrigido
- A tela de quem transmite podia ficar preta com o som funcionando. Sem imagem por 4 segundos, o aplicativo refaz a inscrição sozinho.

## [1.7.3] - 2026-11-05

### Corrigido
- O VPN não abre mais junto com o Angatu Voice, nem na primeira vez. Quem ainda não está na rede AngatuVoice entra pelo botão Reconectar, em Configurações > Rede.

## [1.7.2] - 2026-11-05

### Corrigido
- Os pacotes de atualização antigos, que podiam ocupar centenas de MB, agora são apagados sozinhos assim que a versão instalada abre. Downloads incompletos de versões que ficaram para trás também.

## [1.7.1] - 2026-11-05

### Corrigido
- A versão 1.7.0 ficava presa na tela "Iniciando…" e nunca abria. O aplicativo volta a abrir normalmente.
- Se a interface não conseguir iniciar por qualquer motivo, agora aparece uma mensagem, em vez de a tela ficar parada para sempre.

## [1.7.0] - 2026-11-05

### Adicionado
- O compartilhamento de tela agora usa a placa de vídeo: H.264 por hardware (NVIDIA, Intel e AMD) para codificar e para exibir. Em 1080p, numa GTX 1660 SUPER, a imagem sai a 56 FPS, contra 24 FPS do VP9 e 9 FPS do AV1 por processador. Quem transmite usa um codificador só, não importa quantas pessoas assistem.
- Codificação e decodificação rodam em linhas de execução próprias, fora da interface: a imagem não trava quando a tela do aplicativo está ocupada, e o contrário também.
- Atualizações pequenas: quando só o aplicativo muda, o download é de poucos MB, não mais de 236 MB, e a instalação reaproveita o que já está no computador. Vale a partir da próxima versão, porque esta ainda precisa chegar por inteiro.

### Corrigido
- O VPN não abre mais sozinho. O aplicativo lê o estado dele sem tocar na janela. Só o botão Reconectar, as ações de rede e a primeira entrada automática na rede AngatuVoice abrem a VPN, que depois é fechado pelo X e continua rodando em segundo plano.
- Computadores fracos: o aplicativo rodava um script do PowerShell e dois comandos do Windows a cada 5 segundos. Agora não roda nenhum. A Java também passou a usar menos memória e processador, e sem placa de vídeo compatível a transmissão começa em 720p a 30 FPS em computadores com até 4 núcleos lógicos.
- O overlay de quem está falando deixa passar os cliques para o que está embaixo dele.
- O cartão "Você está transmitindo" não fica mais cortado nem com a foto por cima dos botões na coluna lateral.
- A marca da Angatu Sistemas segue a especificação: "Desenvolvido por" ao lado do logotipo oficial, com link para o site.
- Novas versões são procuradas a cada 15 minutos, em vez de 6 horas.

## [1.6.0] - 2026-11-05

### Adicionado
- Todos os avisos sonoros foram refeitos com uma regra só: subir é ligar, entrar ou começar, e descer é desligar, sair ou terminar. O timbre mostra de quem é o assunto, então seu microfone, outra pessoa, uma transmissão e uma mensagem não se confundem mais.
- Em Configurações > Áudio, "Testar os sons" toca qualquer som do aplicativo, com o nome do que ele significa.
- Novos sons para transmissão encerrada, para quem começa ou para de assistir à sua tela, para chamada recusada e para chamada perdida.

### Corrigido
- O som de desligar o microfone era mais agudo que o de ligar, e os de entrada e saída de outra pessoa eram quase iguais.
- O aviso de entrada automática na rede AngatuVoice não tocava som nenhum.
- Silenciar outra pessoa não usa mais o som do seu microfone, que dava a entender que você mesmo tinha sido silenciado.
- Alguém assistir à sua tela não soa mais como alguém entrando na chamada.
- Enquanto você espera ser atendido, toca o tom de chamada brasileiro, sem um aviso por cima no início.
- Chamada recebida durante uma conversa agora toca dois bipes discretos, em vez do tom de chamada por cima das vozes. Quando ela some, o tom de chamada de quem ainda espera ser atendido volta.
- Ensurdecido, você não ouve mais os avisos sobre outras pessoas nem as mensagens.
- Várias mensagens seguidas tocam um aviso só, e os sons não saem mais tão altos sobre as vozes.

## [1.5.3] - 2026-11-05

### Adicionado
- Mensagem visual e aviso sonoro ao entrar automaticamente na rede AngatuVoice via automação do VPN, orientando o usuário que pode fechar a janela da VPN e utilizar o Voice.
- Minimização automática da janela do VPN para a bandeja do sistema após conclusão do ingresso ou criação de rede por automação.
- Notificação em tempo real via WebSocket (`net.autojoined`) e leitura no estado inicial do aplicativo.

## [1.5.2] - 2026-11-05

### Adicionado
- Substituição e padronização completa de todos os efeitos sonoros por arquivos MP3 livres sob licença CC0 1.0 Universal / Domínio Público, catalogados e documentados em `assets/audio/LICENSES.md`, preservando intacta a música de chamada principal.
- Exibição da versão do sistema no título da janela principal (`Angatu Voice vX.Y.Z`).
- Bloqueio estrito de interface e encerramento imediato de chamadas ativas em versões desatualizadas com atualização automática em segundo plano.
- Monitor em segundo plano (próprio) para fechar automaticamente notificações travadas do VPN (`CToastContainer` / popups Qt).

### Corrigido
- Ajuste do volume padrão dos sons e toque de chamada para iniciar em 100%.
- Operações de criar, entrar, sair e excluir redes do VPN aprimoradas com automação nativa via menu de contexto do Windows e confirmações de diálogo.
- Desativação proativa de notificações nativas de chat do VPN no registro do Windows para evitar popups indesejados.

## [1.5.1] - 2026-11-05

### Adicionado
- Criação e ingresso universais e automáticos na rede padrão AngatuVoice para todos os participantes, sem necessidade de configuração de nós administradores.
- Detecção nativa de avisos e confirmações do VPN na automação da interface do Windows.

### Corrigido
- Falha de servidor (Server Error) ao abrir as configurações de rede ou clicar no botão "Reconectar".
- Execução assíncrona da reconexão de rede, evitando bloqueios na interface e respostas lentas de requisições.
- Descoberta e restauração do VPN quando a janela do programa estiver minimizada na bandeja do sistema (system tray).

## [1.5.0] - 2026-11-05

### Adicionado
- Gerenciador automático de redes VPN integrado diretamente na tela de Configurações > Rede.
- Conexão automática e permanente à rede padrão "AngatuVoice" (senha123) ao abrir o aplicativo, sem exigir configuração manual do usuário na VPN.
- Painel visual de redes: cartão em destaque da rede padrão AngatuVoice com selo de proteção, status de conexão, IP virtual, contagem de participantes e botão de reconexão.
- Ações para criar novas redes secundárias ou entrar em redes de terceiros diretamente pela interface do aplicativo.
- Inicialização controlada da rede (modo mestre de inicialização) e recuperação contínua com intervalo exponencial e aleatoriedade contra oscilações de conexão.
- Transmissão de tela em resolução real 1080p (Full HD) a 60 FPS com alta fluidez e nitidez para textos, códigos e janelas.
- Seletor de prioridade de transmissão: "Nitidez" prioriza a resolução 1080p para leitura, reduzindo a taxa de quadros antes de diminuir a resolução; "Movimento" prioriza a fluidez a 60 FPS contínuos.
- Exibição da taxa de bits real (Mbps/kbps) na barra inferior e no cabeçalho da transmissão.
- Aceleração gráfica por GPU na renderização de tela e cópia direta de memória de vídeo no navegador integrado.

### Alterado
- O codec padrão de transmissão passa a ser o VP9, reduzindo o consumo de banda em cerca de 40% em relação ao VP8 com tempo de codificação de apenas ~4 ms por quadro.
- A taxa de bits inicial sobe imediatamente para até 6 Mbps na partida, eliminando o período de baixa resolução ou borrado ao iniciar a transmissão.
- Latência de reprodução de vídeo otimizada com buffer dinâmico de 60 ms para sincronia e estabilidade.

### Corrigido
- Quedas de taxa de quadros e lentidão causadas pelo limitador de largura de banda inicial do WebRTC.
- Oscilações de conexão com recuperação automática por reinício de candidatos de rede (ICE restart).
- Proteção nativa estrita impedindo qualquer tentativa de exclusão ou saída da rede padrão AngatuVoice.

## [1.4.7] - 2026-11-04

### Adicionado
- Transmissões como no Discord: quando alguém compartilha a tela, aparece um cartão "Ao vivo" e você escolhe se quer assistir. Dá para fechar a transmissão a qualquer momento e abrir de novo depois.
- Lista de quem está assistindo em cada transmissão, com a contagem e as fotos de perfil; um clique mostra todos os nomes. Quem transmite vê o número de espectadores na barra inferior e ouve um aviso quando alguém entra ou sai.
- Opção de ver a sua própria transmissão. A prévia começa desligada para poupar o computador.
- Selo "Ao vivo" ao lado de quem está transmitindo na chamada.

### Alterado
- A tela só é codificada para quem está assistindo, o que alivia bastante o processador de quem transmite.
- O volume da transmissão agora abre num cartão próprio, ao passar o mouse sobre o ícone de som, em vez de ficar semitransparente por cima da imagem.

### Corrigido
- FPS da transmissão oscilando muito. A captura agora entrega no mínimo 30 quadros por segundo mesmo com a tela parada, a qualidade automática reduz a resolução sem nunca baixar de 30 FPS, e o vídeo recebido passa por um pequeno buffer que deixa a reprodução constante. O FPS exibido passou a ser a média real do intervalo, sem saltos.

## [1.4.6] - 2026-11-03

### Adicionado
- Ao compartilhar com mais de um monitor, uma janela mostra a prévia de cada tela para você escolher qual transmitir, ou todas juntas.
- Volume próprio para cada transmissão que você assiste, separado do volume da voz da pessoa.
- Aviso "Transmitindo" na barra inferior, com a resolução e os FPS que estão saindo.

### Alterado
- Sua própria transmissão não aparece mais para você.

### Corrigido
- Compartilhamento de tela travado e borrado: a imagem começava a 2–5 FPS e nunca ganhava banda. Agora fica fluida desde o início e a nitidez sobe em poucos segundos, com o codec mais leve para o processador.

## [1.4.5] - 2026-11-03

### Corrigido
- As vozes da chamada e os sons do próprio aplicativo não vão mais junto com o áudio do compartilhamento de tela: quem assiste ouve só o som do computador, sem eco. Precisa do Windows 10 versão 2004 ou mais novo; nos mais antigos, continua o comportamento anterior.

## [1.4.4] - 2026-11-03

### Corrigido
- Chamadas que ficavam em "Conectando…" para sempre: a voz agora usa o endereço da VPN, que antes ficava de fora da conexão.

## [1.4.3] - 2026-11-03

### Corrigido
- Chamadas que continuavam em "Conectando…" para quem estava na mesma rede: as mensagens de conexão agora são enviadas e tratadas uma de cada vez, na ordem certa.

## [1.4.2] - 2026-11-03

### Corrigido
- Chamadas que ainda podiam ficar em "Conectando…" quando as mensagens de conexão chegavam fora de ordem.

## [1.4.1] - 2026-11-03

### Corrigido
- Chamadas que ficavam em "Conectando…" para sempre depois que alguém reabria o aplicativo ou a conexão era refeita.

## [1.4.0] - 2026-11-03

### Adicionado
- Música e animação de chamada recebida, mostrando quem está chamando, com os botões Atender e Recusar. A música para na hora em que a chamada é atendida, recusada ou cancelada, e quem liga ouve um som de espera.
- Aviso de chamada no canto da tela, com Atender e Recusar, quando o aplicativo está minimizado ou em segundo plano.
- Aviso de chamada perdida.
- Botão para silenciar cada pessoa só para você, com indicação na tela. O microfone dela e o que os outros ouvem não mudam, e a escolha fica salva.
- Indicador de ping em milissegundos, com barras de qualidade, na barra inferior e ao lado de cada pessoa. Clique nele para ver a latência de todo mundo.
- Quem está na chamada vê quando alguém desligou o microfone ou o áudio.
- Faixa avisando quando há uma chamada em andamento em outra conversa, com botão para entrar.
- Resoluções 1440p e original da tela, opção de prioridade entre nitidez e fluidez e escolha do monitor no compartilhamento.
- Opção para ligar ou desligar a música de chamada, com botão para testar.

### Alterado
- Minimizar agora só minimiza: a janela continua na barra de tarefas e o aplicativo segue conectado. O botão de fechar esconde a janela na bandeja, e encerrar de verdade é só pela opção Sair.
- Um clique no ícone da bandeja já abre a janela.
- A janela volta do tamanho em que estava, em vez de sempre maximizada.
- Ao iniciar com o Windows, o aplicativo abre com a janela principal e já se conecta à rede. A opção de iniciar escondido na bandeja continua nas configurações.
- A transmissão de tela ocupa a área da conversa para aparecer maior, com um botão para mostrar as mensagens.
- O indicador de quem está falando fica aceso por mais de meio segundo depois da fala e não pisca mais, e todos na chamada veem o mesmo.

### Corrigido
- Com o áudio do sistema ligado no compartilhamento, o microfone continuava aberto mesmo silenciado, porque o áudio da tela vinha do microfone. Agora é o som do computador de verdade, e o microfone obedece sempre ao botão de silenciar.
- A voz saía abafada durante o compartilhamento de tela, porque chegava duas vezes aos outros, pelo microfone e pelo áudio da tela. Agora vai uma vez só, com a mesma clareza de quando não há compartilhamento.
- Imagem borrada e pixelada no compartilhamento de tela. A transmissão agora mantém a resolução da tela, com a proporção original, e textos legíveis desde os primeiros segundos. Se a rede de alguém não acompanhar, a qualidade cai só para essa pessoa e volta sozinha.
- A imagem da transmissão piscava a cada mudança na chamada.
- Pessoas saindo e entrando da rede sem parar, ou aparecendo como desconectadas para só um dos lados. Oscilações curtas não derrubam mais nada, e a conexão volta sozinha sem avisos falsos.
- Uma oscilação na rede não derruba mais a chamada.
- Demora para aparecer a chamada e os participantes para quem acabou de entrar na rede.
- Redes com mais de oito pessoas deixavam de conectar alguns participantes.
- Aberto escondido na bandeja, o aplicativo só começava a receber chamadas depois de a janela ser aberta.
- O aplicativo em segundo plano deixava de acompanhar a fala e os avisos no tempo certo.

## [1.3.0] - 2026-11-03

### Adicionado
- Opção para limpar o histórico de mensagens ou excluir conversas privadas diretamente no cabeçalho da conversa.
- Opção para excluir grupos e remover todas as mensagens salvas associadas a eles.
- Transmissão de tela em tela cheia com atalho Esc e duplo clique para entrar ou sair, e botão para silenciar ou reativar o áudio da transmissão.
- Opção nas configurações de áudio para escutar a própria voz nos fones de ouvido durante o teste de microfone.
- Opção nas configurações para ativar ou desativar o fundo transparente nos avisos de quem está falando no canto da tela.

## [1.2.0] - 2026-11-03

### Adicionado
- Nova aba **Atalhos** nas configurações, com todas as teclas num só lugar: silenciar microfone, silenciar áudio, apertar para falar, silenciar todos, entrar ou sair da chamada, compartilhar a tela e abrir o Angatu Voice.
- Aviso quando você escolhe teclas que já são usadas por outro atalho, e botão para restaurar os atalhos padrão.

### Alterado
- Os atalhos que ficavam nas abas Aplicativo e Áudio foram para a aba Atalhos.
- Só é possível ter um Angatu Voice aberto por vez. Abrir de novo traz a janela que já estava aberta para a frente, mesmo que as duas aberturas aconteçam juntas.

## [1.1.2] - 2026-11-03

### Corrigido
- Não era possível clicar nem digitar no aplicativo: a tela aparecia, mas ignorava o mouse e o teclado. A janela do navegador interno nunca recebia os eventos do Windows, e agora recebe.
- A janela não vinha para a frente ao abrir o aplicativo; agora o Windows a traz para o primeiro plano e o foco já cai no navegador interno.

## [1.1.1] - 2026-11-03

### Corrigido
- Acentos quebrados nas telas do instalador (por exemplo, "Opções" e "área de trabalho"). Os textos agora vêm das traduções oficiais do instalador e não dependem mais da codificação do arquivo.

## [1.1.0] - 2026-11-03

### Adicionado
- Foto de perfil, recortada e reduzida no próprio computador antes de ir para os outros participantes.
- Aviso no canto da tela mostrando quem está falando durante a chamada, com o aplicativo em segundo plano. Pode ser desligado e mudado de canto.
- Sons ao silenciar e reativar o microfone e o áudio, entrar e sair de chamadas, alguém entrar ou sair e chegar mensagem, com volume próprio.
- Tela de carregamento logo ao abrir, enquanto a interface é preparada.

### Alterado
- O aplicativo abre sempre maximizado e em primeiro plano.
- A cada abertura o aplicativo verifica se há versão nova e, com a atualização automática ligada, já baixa e instala.
- Marca da Angatu Sistemas no rodapé da barra lateral, com o logotipo oficial.

### Corrigido
- O aplicativo parecia não abrir na primeira vez: a janela só aparecia depois de preparar a interface e ficava cinza.
- Acentos errados no instalador.

## [1.0.0] - 2026-11-03

### Adicionado
- Primeira versão: mensagens em grupo e privadas, grupos com convite, chamadas de voz, compartilhamento de tela, integração com o VPN, bandeja, atalhos globais, início com o Windows e atualização automática.
