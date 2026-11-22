# Minecraft Skin Studio 3D

Aplicación Android para crear y editar skins de Minecraft, desarrollada en Java.

## Funciones incluidas en esta base

- Vista 3D rotatoria del personaje.
- Modelos Classic y Slim.
- Selector de partes del cuerpo.
- Paleta de cientos de tonos y rueda universal HSV.
- Tema oscuro azul.

## Compilación automática

El workflow `.github/workflows/skin-studio-build.yml` compila el APK de depuración con Java 17, Gradle 8.7 y Android SDK 34.

- Se ejecuta en cada push a `skin-studio/setup-ci`.
- También admite ejecución manual desde GitHub Actions.
- El APK se publica como artifact `Minecraft-Skin-Studio-debug-apk`.

## Compilación local

Con Gradle 8.7 instalado:

```sh
gradle --no-daemon :app:assembleDebug
```

## Identificador

- Application ID: `com.forjastudios.skinstudio`
- Android mínimo: API 24
- Target: API 34
