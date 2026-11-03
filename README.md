---
tags: [angatu-voice, readme]
---
# Angatu Voice

Aplicativo desktop da Angatu Sistemas para conversar por voz, texto e compartilhamento de tela dentro da rede privada do **Radmin VPN**. Java 25 hospeda a janela (JFrame + Chromium embutido via JCEF) e serve a interface HTML/CSS/JS por um servidor local ligado só a `127.0.0.1`. O padrão de engenharia é o da **AngatuLibrariesSkill**.

## Requisitos
- Windows 10/11 x64 e **Radmin VPN** instalado e conectado (requisito externo obrigatório).
- Para desenvolver: JDK 25 (com `jpackage`) e Maven 3.9+. O binário do Tailwind fica em `tools\bin\` (ignorado pelo git): https://github.com/tailwindlabs/tailwindcss/releases (`tailwindcss-windows-x64.exe`).
- Para gerar o instalador: Inno Setup 6 (o GitHub Actions instala sozinho).

## Executar localmente
```powershell
mvn -B test
mvn -q -B -DskipTests package
java --enable-native-access=ALL-UNNAMED -jar target\angatu-voice-1.0.0.jar
```
Na primeira execução o Chromium é extraído para `%LOCALAPPDATA%\AngatuVoice\jcef-152.0.6` (alguns segundos). Dados do usuário: `%APPDATA%\AngatuVoice` (banco `database.db`, logs, backups de migração).

## Build e instalador
```powershell
mvn -B -DskipTests package
.\tools\package.ps1                 # dist\image\AngatuVoice + dist\AngatuVoice-<versão>-win-x64.zip
ISCC installer\angatu-voice.iss /DAppVersion=1.0.0   # dist\AngatuVoice-Setup-1.0.0.exe
```
O instalador é por usuário (sem administrador), cria atalhos, marca "iniciar com o Windows" por padrão (desmarcável) e, ao desinstalar, remove a entrada de inicialização e pergunta se deve apagar mensagens e configurações. O runtime Java vai dentro do pacote; ninguém precisa instalar Java.

## Documentação
- [[docs/ARQUITETURA]]: arquitetura, ponte Java e interface, integração com o Radmin, protocolo entre usuários, segurança, configurações.
- [[docs/ATUALIZADOR]]: repositório Git, publicação de versões, manifesto, rollback, solução de problemas.

## Testes
`mvn -B test` roda o protocolo de rede (handshake, quadros cifrados, adulteração), a segurança do atualizador (assinatura, hash, host, zip-slip) e a escolha de releases por canal. O script de troca de arquivos (`apply-update.ps1`) foi exercitado em sandbox para sucesso, rollback e recuperação após interrupção.

## Desenvolvido por Angatu Sistemas
