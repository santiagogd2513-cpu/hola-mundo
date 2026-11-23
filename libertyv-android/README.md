# LibertyV para Android

Versión para Android de **LibertyV r47**, el explorador de archivos RPF de Grand Theft Auto V
para **Xbox 360** y **PlayStation 3**. Es una reescritura en Kotlin, porque el original era un
programa de Windows (.NET con ventanas WinForms y DLL nativas) que no puede funcionar en Android.

## Fase 1 (esta versión): ver y extraer

- Cargar la clave (`key_xbox360.dat` / `key_ps3.dat`). Se comprueba por MD5 igual que el original
  y se guarda dentro de la app. **La app no incluye ninguna clave.**
- Abrir un `.rpf`. La consola se detecta sola por la cabecera.
- Navegar por las carpetas, buscar en todo el RPF y abrir RPF que estén dentro de otro RPF.
- Exportar un archivo o una carpeta entera (descifrado y descomprimido).
- Recursos (`.xft`, `.xtd`, `.xsc`, `.cft`…) en tres modos, como el original: RSC7, SYS + GFX o tal cual.
- Audio `.awc`: escuchar las pistas y exportarlas a `.wav`.
- Ver el contenido de un archivo (texto o hexadecimal).

## Fase 2 (pendiente): editar y guardar

Importar, reemplazar, borrar y guardar el RPF. En Xbox 360 los archivos se tendrán que guardar
**comprimidos** (el juego no carga archivos sin comprimir). El compresor LZX ya está hecho y probado
(`core/.../LzxEncoder.kt`).

## Cómo está hecho

| Parte | Original (Windows) | Android |
|---|---|---|
| Interfaz | WinForms | Jetpack Compose |
| Cifrado | AES-256 ECB (.NET) | AES-256 ECB (javax.crypto) |
| Compresión PS3 | DeflateStream | `java.util.zip.Inflater` |
| Compresión Xbox 360 | `lzx.dll` / `xcompress.dll` | Decodificador LZX propio en Kotlin |
| Audio XMA2 / MP3 | `libav_wrapper.dll` | FFmpeg mínimo compilado con el NDK |

- `core/`: librería en Kotlin puro (RPF7, AES, LZX, AWC). Se prueba en el ordenador:
  `gradle -PcoreOnly=true :core:test`
- `app/`: la aplicación Android. El código nativo de audio está en `app/src/main/cpp/`.
- `scripts/build-ffmpeg.sh`: compila FFmpeg solo con los decodificadores XMA1/XMA2/MP3.

El LZX se comprobó contra libmspack, una implementación de referencia independiente.

## Compilar

GitHub Actions (`.github/workflows/android.yml`) compila el APK en cada push que toque
`libertyv-android/`. El APK se descarga desde la pestaña **Actions** → la ejecución →
**Artifacts** → `LibertyV-android`.

En local hace falta el SDK de Android con NDK 27, y ejecutar antes `scripts/build-ffmpeg.sh`.

`app/debug.keystore` es una clave de firma de depuración (contraseña `android`). Está en el
repositorio para que cada APK nuevo se instale encima del anterior sin tener que desinstalar.
No es un secreto.

FFmpeg se distribuye bajo licencia LGPL 2.1.
