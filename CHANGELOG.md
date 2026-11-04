---
tags: [angatu-voice, changelog]
---
# Histórico de versões

Todas as mudanças relevantes do Angatu Voice ficam aqui, da mais nova para a mais antiga. O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o [Versionamento Semântico](https://semver.org/lang/pt-BR/). As regras do projeto estão em [[docs/VERSIONAMENTO]].

O texto de cada seção vira as notas da versão no GitHub e na tela de Atualizações do aplicativo, então ele é escrito para quem usa o app.

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
- Chamadas que ficavam em "Conectando…" para sempre: a voz agora usa o endereço do Radmin, que antes ficava de fora da conexão.

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
- Primeira versão: mensagens em grupo e privadas, grupos com convite, chamadas de voz, compartilhamento de tela, integração com o Radmin VPN, bandeja, atalhos globais, início com o Windows e atualização automática.
