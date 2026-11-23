# LibertyUV Android

Adaptación Android independiente del explorador RPF7 de LibertyV, enfocada en archivos RPF7 de Xbox 360 y PS3.

## Implementado en esta base
- Proyecto Android nativo con Java 17.
- Selección de clave local de 32 bytes, mantenida solo en memoria.
- Lectura y validación de cabecera RPF7.
- Descifrado AES-256-ECB de la tabla TOC en modo de una pasada.
- Navegación de carpetas y validación de rangos/entradas.
- Exportación RAW de los datos almacenados.
- GitHub Actions: pruebas, APK y publicación directa en Releases.

## Limitaciones que siguen pendientes
Esta no es todavía una sustitución completa del programa de escritorio. El códec Xbox XMem/LZX, el manejo de audio AWC y la reconstrucción/recompresión RPF aún no están implementados ni validados. Los archivos comprimidos o cifrados se exportan como RAW, no como contenido listo para el juego. No los vuelvas a insertar en la consola.

No se incluyen claves ni DLL de Windows. Usa únicamente claves que tengas derecho a utilizar. Prueba siempre con copias recuperables.
