---
tags: [angatu-voice, atualizador]
---
# Atualizador

O Git é a **fonte de versões e publicação**, nunca uma autorização para executar código remoto. O app só instala artefatos compilados, assinados e verificados.

## Configuração do repositório
Tudo em `src/main/resources/updater.properties`: `repository` (`owner/repo`), `api`, `publicKey` (Ed25519, Base64 X.509) e `allowedHosts`. Trocar de repositório é mudar esse arquivo. Repositório privado: defina `ANGATU_UPDATE_TOKEN` (ou a configuração `update.token`); o token só é enviado a `api.github.com`, nunca vai dentro do código distribuído.

## Preparar uma vez
1. A chave de assinatura já foi gerada: a privada em `%USERPROFILE%\.angatu\voice-update-signing.key`, a pública embutida no app. Cadastre a privada no repositório como segredo **`UPDATE_SIGNING_KEY`** (Settings, Secrets, Actions) e guarde uma cópia segura. Perder a chave obriga a lançar um instalador novo com outra chave pública.
2. Trabalhe na branch `development`; `main` só recebe o que o dono confirmar.

## Publicar uma versão
```
git tag v1.1.0 && git push origin v1.1.0                  # estável
git tag v1.2.0-beta.1 && git push origin v1.2.0-beta.1    # beta (pré-lançamento)
```
O workflow `release.yml` ajusta a versão pela tag, roda `mvn test`, empacota, executa `tools/package.ps1` (app-image com runtime e zip), gera o instalador no Inno Setup, roda `tools/Release.java` (SHA-256, `manifest.json` e `manifest.json.sig`) e publica tudo com `gh release create`. Não é preciso Git nas máquinas dos usuários.

## Manifesto
```json
{"app":"angatu-voice","version":"1.1.0","channel":"stable","minVersion":"1.0.0","os":"windows","arch":"x64",
 "mandatory":false,"notes":"...","publishedAt":"2026-11-03T00:00:00Z",
 "pkg":{"name":"AngatuVoice-1.1.0-win-x64.zip","url":"https://github.com/<repo>/releases/download/v1.1.0/AngatuVoice-1.1.0-win-x64.zip","size":232682098,"sha256":"..."}}
```
Validado antes de qualquer uso: assinatura Ed25519 sobre os bytes exatos; `app`, `os`, `arch`, canal e `minVersion`; URL `https` em host permitido; tamanho de até 700 MB; SHA-256 de 64 hex; versão igual à da tag. Se `minVersion` for maior que a instalada, o app avisa que precisa do instalador completo.

## Como o app instalado recebe a versão
1. Na abertura, a cada 2 minutos e no botão "Verificar", consulta as releases com `ETag` (resposta 304 não gasta cota) e escolhe a maior versão do canal; o estável ignora betas e rascunhos.
2. Baixa `manifest.json` e `.sig`, valida, e só aceita versão **maior** que a instalada (sem downgrade).
3. Baixa o pacote em segundo plano, com progresso e **retomada** (`Range`); confere tamanho e SHA-256 e recusa se não bater.
4. **Toda atualização é obrigatória:** a interface trava numa tela de aviso, sai da chamada se houver uma, e o app instala e reinicia sozinho, sem opção de adiar. Uma versão que não abriu duas vezes neste computador é deixada de lado até sair a próxima, para não reiniciar a pessoa sem fim.
5. Extrai com proteção contra zip-slip, confere o layout (`app\angatu-voice-<versão>.jar`: interface e backend vêm juntos no mesmo JAR, sem versões misturadas) e dispara `apply-update.ps1`, que sobrevive ao app.

## Rollback
`apply-update.ps1` espera o app sair, grava o diário (`update-journal.json`), move os itens atuais para `.rollback`, coloca os novos e inicia o app com `--post-update`, aguardando o marcador `startup-ok-<versão>` (escrito quando a interface carrega de verdade). Sem marcador em 90 s, ou se o processo morrer, encerra a nova versão, **restaura** `.rollback`, grava `update-result.json` (`rolled_back`) e reabre a versão anterior. Só apaga o backup depois da confirmação. Se faltar energia no meio, na próxima abertura o app vê o diário parado há mais de 3 min e roda o script com `-Recover`. Configurações e mensagens ficam em `%APPDATA%`, fora da pasta de instalação, e nunca são tocadas. Migrações de banco fazem backup antes e recusam esquema mais novo.

## Solução de problemas
- **"Não foi possível verificar atualizações"**: sem internet, limite de requisições do GitHub ou repositório privado sem token. O app continua funcionando.
- **"Recusada porque não pôde ser autenticada"**: assinatura inválida (segredo `UPDATE_SIGNING_KEY` diferente da chave pública embutida).
- **Atualização revertida**: veja `%APPDATA%\AngatuVoice\logs\app.log` e o histórico em Configurações, Atualizações.
- **Sem conexão com o servidor**: o banner avisa e o aplicativo tenta de novo sozinho. Confira a internet e se um firewall bloqueia `voice.angatusistemas.com.br`.
