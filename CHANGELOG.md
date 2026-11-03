---
tags: [angatu-voice, changelog]
---
# Histórico de versões

Todas as mudanças relevantes do Angatu Voice ficam aqui, da mais nova para a mais antiga. O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o [Versionamento Semântico](https://semver.org/lang/pt-BR/). As regras do projeto estão em [[docs/VERSIONAMENTO]].

O texto de cada seção vira as notas da versão no GitHub e na tela de Atualizações do aplicativo, então ele é escrito para quem usa o app.

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
