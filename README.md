# English Ai — PWA + Android TWA

Este pacote mantém os arquivos da aplicação web e a interface fornecida, sem redesenhar o app. A estratégia recomendada é publicar o PWA em um **repositório público separado**, para não expor o repositório privado `wendeloopes/english-ai` nem o projeto Android antigo.

## 1. Criar o repositório público separado

No GitHub, crie um repositório chamado **`english-ai-web`**, marque-o como **Public** e não inicialize com README, licença ou `.gitignore`. Isso mantém o código antigo no repositório privado.

Abra o ZIP e envie **todo o conteúdo da pasta do projeto para a raiz** desse novo repositório (não envie a pasta externa `EnglishAi_PWA_Project` como uma subpasta). A raiz deve conter `index.html`, `sw.js`, `manifest.json`, os ícones, `.github/` e `android-twa/`.

## 2. Publicar o PWA

1. No repositório `english-ai-web`, abra **Settings → Pages**.
2. Selecione **GitHub Actions** como fonte de publicação.
3. Na aba **Actions**, execute `Deploy English Ai PWA` (ou faça um commit na branch `main`).
4. A URL esperada é **https://wendeloopes.github.io/english-ai-web/**.
5. Abra a URL no Chrome Android com internet e instale pelo menu do Chrome quando a opção estiver disponível. HTTPS é necessário para recursos como microfone, service worker e OPFS.

## 3. Gerar o APK Android (TWA)

O workflow `Build English Ai Android TWA` compila o projeto em `android-twa/` usando Android Browser Helper. Depois de publicar o PWA, confira se a URL no `android-twa/app/src/main/AndroidManifest.xml` abre corretamente.

- Nome: **English Ai**
- Application ID: `com.englishai.app`
- O APK de debug será disponibilizado como artefato do workflow do GitHub Actions.
- **O APK TWA ainda precisa ser compilado no GitHub Actions e testado em um aparelho real.** Sem configuração de Digital Asset Links para o certificado de assinatura, o Chrome pode mostrar uma barra de Custom Tab.

## Limites funcionais importantes

- O modelo de IA não está incluído no pacote; o usuário seleciona o modelo compatível de aproximadamente 3 GB nas configurações.
- O aparelho precisa ter WebGPU e memória suficiente para o modelo escolhido.
- O reconhecimento de voz do Chrome pode exigir internet; não se garante reconhecimento de fala totalmente offline.
- A checagem de sintaxe e dos arquivos não prova que o modelo carregará em todo aparelho.

## Verificações já feitas

- Arquivos e ícones originais preservados.
- Sintaxe JavaScript e JSON do manifesto verificados localmente.
- Os workflows estão preparados, mas ainda dependem de serem enviados ao novo repositório para executar no GitHub.
