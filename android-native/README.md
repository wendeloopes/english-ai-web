# English Ai — Android nativo

Esta pasta separa a versão Android nativa da versão web/TWA. O objetivo é manter a interface aprovada e ligar as funções de modelo, microfone, fala e importação de arquivo a APIs Android locais.

## Modelo

O runtime Android usa LiteRT-LM e requer um arquivo .litertlm compatível. O modelo .task usado pela versão web MediaPipe não é automaticamente intercambiável. Não basta renomear o arquivo.

## Estado

O scaffold e o workflow de compilação serão verificados antes de considerar o APK pronto. Um build CI, sozinho, não comprova o desempenho no aparelho real; reconhecimento offline e vozes dependem dos recursos instalados no Android.
