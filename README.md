<p align="center">
  <img src="src/main/resources/public/assets/icon-256.png" width="112" alt="Angatu Voice">
</p>

<h1 align="center">Angatu Voice</h1>

<p align="center">
  Voz, texto e compartilhamento de tela, sem configurar nada.<br>
  Instalou e abriu, está conectado.
</p>

<p align="center">
  <a href="https://github.com/LuanVictorGit/AngatuVoice/releases/latest/download/AngatuVoice-Setup.exe">
    <img src="https://img.shields.io/badge/Baixar%20para%20Windows-Angatu%20Voice-f2a33a?style=for-the-badge&logo=windows&logoColor=1a1206&labelColor=1a1206" alt="Baixar o instalador para Windows">
  </a>
</p>

<p align="center">
  <sub>Windows 10 ou 11 (64 bits) · o Java já vem dentro do instalador</sub>
</p>

<p align="center">
  <img src="docs/img/chat-escuro.png" alt="Conversa em grupo no Angatu Voice, tema escuro">
</p>

## Como funciona

1. **Instale e abra.** O instalador é por usuário, não pede administrador e já configura o início com o Windows (você pode desmarcar).
2. **Conexão automática.** O Angatu Voice se conecta sozinho ao servidor e mostra quem está online. Não há conta, senha nem VPN: a sua identidade é uma chave criada no seu computador.
3. **Converse.** A conversa *Geral* é de todo mundo. Para um círculo menor, crie um grupo privado e mande o convite só para quem deve entrar.
4. **Chame no voz.** Um clique em *Chamar* faz tocar para quem você chamou, com aviso de quem é mesmo com o aplicativo em segundo plano. Na chamada, você vê quem está falando, o ping de cada um e pode silenciar ou ajustar o volume de cada pessoa só para você.
5. **Mostre a sua tela.** A placa de vídeo codifica a imagem, até a resolução original e 60 quadros por segundo, e o servidor entrega a quem escolher assistir.

Tudo passa por um servidor só, com conexão cifrada e mensagens assinadas por quem escreveu. O servidor guarda mensagens para quem está offline e apaga as antigas sozinho.

## Um olhar por dentro

<table>
  <tr>
    <td width="50%"><img src="docs/img/chat-claro.png" alt="Tema claro"><br><sub><b>Tema claro e escuro.</b> Grupos, conversas privadas e participantes na mesma tela.</sub></td>
    <td width="50%"><img src="docs/img/configuracoes-audio.png" alt="Configurações de áudio"><br><sub><b>Áudio sob controle.</b> Microfone, saída, sensibilidade, cancelamento de eco, supressão de ruído e teste.</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/img/configuracoes-rede.png" alt="Configurações de rede"><br><sub><b>Rede.</b> Conexão com o servidor e participantes.</sub></td>
    <td width="50%"><img src="docs/img/atualizacoes.png" alt="Atualizações"><br><sub><b>Sempre atualizado.</b> Novas versões chegam sozinhas, verificadas e com volta automática se algo falhar.</sub></td>
  </tr>
</table>

<p align="center">
  <img src="docs/img/mobile.png" width="260" alt="Layout compacto em janela estreita"><br>
  <sub>A interface se adapta a janelas estreitas.</sub>
</p>

## O que tem dentro

- **Sem VPN e sem configuração:** o aplicativo se conecta sozinho e reconecta sozinho se a internet oscilar.
- **Tela pela placa de vídeo:** H.264 por hardware em NVIDIA, Intel e AMD, em linhas de execução próprias. Quem assiste abre quando quer, e a imagem não trava a interface.
- **Grupos privados** com convite, conversas individuais, histórico salvo no seu computador e mensagens pendentes entregues quando a pessoa volta.
- **Chamadas** individuais e em grupo, com baixa latência, cancelamento de eco e supressão de ruído.
- **Atalhos globais** para apertar para falar, silenciar microfone ou áudio, entrar e sair da chamada.
- **Bandeja do sistema**, notificações e início automático com o Windows, sempre sob o seu controle. Minimizar ou fechar a janela não derruba a conexão; encerrar é só pela opção Sair.
- **Conexão que aguenta oscilação:** quedas curtas na rede são recuperadas sozinhas, sem derrubar a chamada e sem avisos falsos de que alguém saiu.
- **Atualização automática** pelas versões publicadas neste repositório, com assinatura digital, verificação de integridade e volta para a versão anterior se a nova não abrir.

## Para quem desenvolve

```powershell
mvn -B test
mvn -q -B -DskipTests package
java --enable-native-access=ALL-UNNAMED -jar target\angatu-voice-2.0.0.jar
```

Requisitos: JDK 25 (com `jpackage`), Maven 3.9+ e o binário do [Tailwind](https://github.com/tailwindlabs/tailwindcss/releases) em `tools\bin\`. O pacote e o instalador saem de `tools\package.ps1` e `installer\angatu-voice.iss`; o GitHub Actions faz tudo isso ao publicar uma tag `vX.Y.Z`.

- [Arquitetura, servidor, protocolo e segurança](docs/ARQUITETURA.md)
- [Atualizador, manifesto e rollback](docs/ATUALIZADOR.md)
- [Versionamento e como publicar uma atualização](docs/VERSIONAMENTO.md)
- [Histórico de versões](CHANGELOG.md)

---

<p align="center"><sub>Desenvolvido por <b>Angatu Sistemas</b></sub></p>
