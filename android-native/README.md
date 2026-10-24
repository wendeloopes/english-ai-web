# English Ai — Android nativo

Esta pasta contém a versão Android nativa, mantendo a interface aprovada e conectando o chat às APIs locais de modelo, microfone, fala e importação de arquivos.

## Modelo Android

O runtime nativo usa LiteRT-LM e exige um modelo **compatível com o formato `.litertlm`**. O arquivo `.task` usado pela versão web/MediaPipe não é automaticamente compatível; renomeá-lo não converte o modelo.

O motor usa CPU como opção conservadora, ajusta o limite de tokens ao total de RAM reportado pelo Android e reduz o histórico de conversa em aparelhos com menos memória. Ao ficar em segundo plano por alguns minutos, o app libera o motor e tenta carregá-lo novamente ao voltar para o primeiro plano.

## Build e validação

O workflow `android-native.yml` compila o APK de depuração e verifica os contratos entre a interface JavaScript e a ponte Kotlin. Build bem-sucedido comprova compilação, mas não substitui teste no aparelho real: desempenho, consumo de memória, compatibilidade do modelo e reconhecimento de voz podem variar conforme o dispositivo e os serviços instalados.
