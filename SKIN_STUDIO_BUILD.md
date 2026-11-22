# Skin Studio 3D — Android Build

## Features in the current update

- Reorganized dark-blue editor interface.
- Classic and Slim model selector.
- 3D preview with drag-to-rotate.
- 2D 64x64 skin texture editor.
- Brush with adjustable size.
- Contiguous-color bucket fill.
- Eraser.
- Whole-part fill for head, hair, face, torso, arms, hands, legs and shoes, using the corresponding skin UV regions.
- Tool descriptions displayed when a tool is selected.
- Import existing Minecraft skin PNG files through Android's document picker (64x64 modern or 64x32 legacy texture).

## Automatic build

Workflow: `.github/workflows/skin-studio-build.yml`

- Java 17
- Gradle 8.7
- Android SDK 34
- Task: `:app:assembleDebug`
- Output artifact: `Minecraft-Skin-Studio-debug-apk`

## Branch

`skin-studio/setup-ci`
