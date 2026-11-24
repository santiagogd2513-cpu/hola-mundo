# BD Files

Gestor de archivos para Android. Autor: **Santiago**.

Tema oscuro con rosa, violeta, púrpura, morado claro y cian.

## Qué hace

**Explorador**
- Vista en lista, con miniaturas de fotos, vídeos y PDF, icono de los APK y el icono
  de dentro de los `.jar` (los mods de Minecraft suelen llevar `pack.png`).
- Pestañas (hasta 14, se pueden apagar en los ajustes).
- Búsqueda en todo el dispositivo, no solo en la carpeta actual.
- Barra de ruta con migas para saltar a cualquier carpeta de arriba.
- Anclar carpetas (salen las primeras de la lista) y favoritos.
- Atajos a carpetas, con los habituales ya puestos.

**Operaciones**
- Copiar, mover, renombrar, borrar, crear carpetas y archivos.
- **Verificación**: al copiar o mover se calcula la huella (CRC32) de lo leído y se
  vuelve a leer el destino para comprobar que coincide. Al mover, el original solo
  se borra si la comprobación sale bien.
- Barra de progreso en el centro de la pantalla, con el fondo oscurecido: qué se está
  haciendo, velocidad, tiempo transcurrido y tiempo que falta.
- Al borrar: cancelar / papelera / borrar definitivamente.

**Comprimidos**
- Crear ZIP, extraer todo o solo algunas cosas, añadir y quitar dentro de un ZIP.
- Leer ZIP, JAR, APK, 7z, RAR, TAR, TAR.GZ, TAR.BZ2 y TAR.XZ.
- Protección contra el truco de «../» que escribiría fuera de la carpeta destino.

**Red**
- **Cliente FTP escrito desde cero** (RFC 959), sin librerías. Con perfiles de aparato:
  Xbox 360, PlayStation 3, PC y router/NAS. Entiende listados estilo UNIX y MS-DOS,
  modo pasivo y activo.
- Descarga desde enlaces directos.
- MediaFire y Mega: ver las notas dentro de la app.

**Permisos**
- Con **Shizuku**: dar y quitar permisos de almacenamiento a otras apps (emuladores y
  demás), igual que haría `adb shell pm grant`, y entrar en carpetas del sistema.
- Sin Shizuku: ver el estado de cada app y abrir sus ajustes.

## Cómo está hecho

- Kotlin + Jetpack Compose, `minSdk` 24.
- `core/`: modelo, orígenes de archivos, operaciones y comprimidos.
- `net/`: cliente FTP propio y descargas.
- `ui/`: pantallas y componentes.

## Pruebas

20 pruebas automáticas, que se ejecutan en cada compilación:

- Cliente FTP contra un **servidor FTP de prueba** levantado en la propia prueba:
  listar, descargar, subir, renombrar, borrar, carpetas, contraseña incorrecta,
  servidor apagado y listados reales de consolas.
- Copiar y mover con verificación, conflictos de nombre, borrado recursivo.
- Comprimir, extraer, extraer solo lo marcado, editar un ZIP y el caso del «../».
- Ordenación con carpetas ancladas y nombres sin repetir.

## Compilar

GitHub Actions (`.github/workflows/bdfiles.yml`) compila el APK en cada push que toque
`bd-files/`. Se descarga desde **Actions** → la ejecución → **Artifacts** → `BD-Files`.

El número de compilación que se ve en Ajustes → Acerca de es el de la ejecución de Actions.

`app/debug.keystore` es una clave de firma de depuración (contraseña `android`), para que
cada APK nuevo se instale encima del anterior. No es un secreto.
