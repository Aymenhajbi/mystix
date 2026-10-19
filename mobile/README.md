# Atelys Mobile (ADR-0018, ADR-0019)

`device-hub-core` : détection du terminal, contrat des adaptateurs de scan et résolution de l'intégration, en
Kotlin/JVM pur. Il se construit sans le SDK Android.

```powershell
cd mobile
.\gradlew.bat test
```

Sur un poste Windows dont le dossier temporaire contient un espace, le JDK ne peut pas ouvrir ses sockets locales
(`Unable to establish loopback connection`). Contournement, pour la session :

```powershell
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:/dev/mystix/mobile/.tmp"
New-Item -ItemType Directory -Force mobile\.tmp | Out-Null
```

Les tests utilisent des adaptateurs factices : ils ne valent pas recette sur un terminal réel.
