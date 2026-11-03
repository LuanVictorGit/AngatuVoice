---
tags: [angatu-voice, versionamento, atualizacoes]
---
# Versionamento e publicação de atualizações

Este documento é a regra para lançar uma versão nova do Angatu Voice sem confundir ninguém: nem quem desenvolve, nem o atualizador, nem quem usa. O funcionamento interno do atualizador está em [[docs/ATUALIZADOR]].

## A regra de ouro

> **Toda versão publicada tem um número novo, maior que o anterior, e nunca é republicada com o mesmo número.**

O atualizador só instala uma versão **maior** que a instalada. Se um número for reaproveitado, quem já tinha aquele número nunca recebe a correção. Por isso:

- Não apague e recrie uma tag que já foi para usuários. Errou? Lance a próxima versão de correção.
- Não pule a atualização do `pom.xml` e do `CHANGELOG.md`: o workflow recusa a publicação se a tag, o `pom.xml` e o `CHANGELOG.md` não disserem a mesma versão.

## Formato: MAJOR.MINOR.PATCH

| Parte | Quando sobe | Exemplo |
|---|---|---|
| **PATCH** | só correções, nada novo para o usuário aprender | `1.1.0` para `1.1.1` |
| **MINOR** | recurso novo que não quebra nada | `1.1.1` para `1.2.0` |
| **MAJOR** | mudança incompatível: protocolo entre usuários, dados que não voltam, exige reinstalar | `1.4.2` para `2.0.0` |

Ao subir uma parte, as da direita voltam a zero (`1.2.7` para `1.3.0`).

### Exemplos práticos

| Mudança | Nova versão |
|---|---|
| Corrigi um texto, um travamento ou a cor de um botão | PATCH |
| Adicionei uma opção nas configurações | MINOR |
| Mudei o formato do banco, com migração automática | MINOR (a migração preserva os dados) |
| Mudei o protocolo e versões antigas não conversam com a nova | MAJOR |
| Troquei a chave de assinatura das atualizações | MAJOR, com instalador novo (veja abaixo) |

### Versões beta

Para testar com poucas pessoas, use o sufixo `-beta.N` sobre a **próxima** versão: `1.2.0-beta.1`, `1.2.0-beta.2`, e por fim `1.2.0`. Betas só chegam a quem escolheu o canal Beta nas configurações. A versão final é sempre maior que as betas dela.

## Passo a passo para lançar

1. Trabalhe na branch `development`. Rode `mvn -B test` e teste o app.
2. Escolha o número pela tabela acima.
3. Altere a versão em **três lugares**, todos com o mesmo número:
   - `pom.xml`, em `<version>`;
   - `installer/angatu-voice.iss`, em `#define AppVersion` (é só o padrão local; o workflow passa o número certo);
   - `CHANGELOG.md`, com uma seção nova no topo: `## [1.2.0] - AAAA-MM-DD`, dividida em *Adicionado*, *Alterado* e *Corrigido*, escrita para quem usa o app.
4. Faça o commit em `development` (`chore: versão 1.2.0`) e leve para a `main` quando o dono aprovar.
5. Crie e envie a tag a partir do commit aprovado:
   ```
   git tag v1.2.0
   git push origin v1.2.0
   ```
6. Acompanhe a aba **Actions** do GitHub. O workflow `release`:
   - confere se a tag, o `pom.xml` e o `CHANGELOG.md` batem e para se não baterem;
   - roda os testes, empacota, gera o instalador `AngatuVoice-Setup.exe`, o pacote `.zip`, o `manifest.json` e a assinatura;
   - publica a Release com as notas tiradas do `CHANGELOG.md`.
7. Confira a Release publicada e o botão de download do README.

## O que acontece no computador de quem usa

1. Ao abrir o Angatu Voice, ele verifica na hora se existe versão nova no canal escolhido (e repete a cada poucas horas enquanto fica aberto).
2. Com a atualização automática ligada (padrão), baixa em segundo plano, confere a assinatura e o SHA-256, e instala na hora se não houver chamada em andamento. Se houver, instala assim que a chamada terminar.
3. O aplicativo fecha, troca os arquivos e abre a versão nova. Se ela não abrir em até 90 segundos, a anterior volta sozinha.
4. Mensagens, configurações e a identidade ficam em `%APPDATA%\AngatuVoice` e nunca são tocadas pela atualização.

Com a atualização automática desligada, aparece um aviso "Reiniciar para atualizar" e a pessoa escolhe quando.

## Atualização obrigatória

Para uma correção de segurança, publique a release com a variável `MANDATORY=true` no passo *Signed manifest* do workflow. Ela é instalada mesmo para quem desligou a atualização automática.

## Quando é preciso instalador novo

- Mudar a chave de assinatura: versões antigas não aceitam pacotes assinados com a chave nova.
- Subir a `minVersion` do manifesto (padrão `1.0.0`): versões abaixo dela recebem o aviso para baixar o instalador completo.

Nesses casos, lance uma MAJOR e avise os usuários para baixar o instalador pelo botão do README.

## Erros comuns

| Sintoma | Causa | O que fazer |
|---|---|---|
| Workflow falha em "Version from the tag" | tag diferente do `pom.xml` ou sem seção no `CHANGELOG.md` | corrija os arquivos, apague a tag **que ainda não publicou nada** e crie de novo |
| Ninguém recebe a versão | número igual ou menor que o instalado, ou publicada como beta | lance o próximo número |
| "A atualização foi recusada porque não pôde ser autenticada" | segredo `UPDATE_SIGNING_KEY` diferente da chave pública do app | restaure o segredo correto |
| A versão nova abre e volta para a anterior | ela não terminou de carregar a interface em 90 s | veja `%APPDATA%\AngatuVoice\logs\app.log` e lance uma correção |
