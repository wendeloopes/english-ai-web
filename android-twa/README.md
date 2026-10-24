# English Ai — Android Trusted Web Activity

This Android wrapper opens the published English Ai PWA at:
https://wendeloopes.github.io/english-ai-web/

The GitHub Actions workflow builds a debug-signed APK suitable for sideloading. If Digital Asset Links cannot verify the APK signing certificate, Chrome may show its Custom Tab toolbar. The app requires internet for first load and for browser/AI engine resources; the model is selected by the user in the app settings.
