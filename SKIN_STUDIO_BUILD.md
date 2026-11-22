# Minecraft Skin Studio 3D

Android application project from the supplied Java ZIP.

## Automatic test build

GitHub Actions workflow: `.github/workflows/skin-studio-build.yml`

It runs on pushes to `skin-studio/setup-ci` and can also be started manually from the Actions tab. The workflow uses Java 17, Gradle 8.7 and Android SDK, then runs:

```sh
gradle --no-daemon :app:assembleDebug
```

The debug APK is uploaded as the workflow artifact `Minecraft-Skin-Studio-debug-apk`.

Package: `com.forjastudios.skinstudio`
Minimum Android SDK: 24
Target SDK: 34
