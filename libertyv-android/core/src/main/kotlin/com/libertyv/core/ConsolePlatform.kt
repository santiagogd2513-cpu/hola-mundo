package com.libertyv.core

import java.security.MessageDigest

/** Consola para la que está hecho el archivo RPF. Cambia compresión, audio y tamaños de recursos. */
enum class ConsolePlatform(
    val displayName: String,
    /** Valor del bit de plataforma en la cabecera RPF7. */
    val platformBit: Int,
    /** MD5 de la clave AES correcta (para comprobar el .dat sin incluir la clave en la app). */
    val keyMd5: String,
    /** Tamaño base de páginas "system" de los recursos. */
    val systemPageBase: Int,
    /** Tamaño base de páginas "graphics" de los recursos. */
    val graphicsPageBase: Int,
    val keyFileName: String,
) {
    XBOX360("Xbox 360", 1, "ead1ea1a3870557b424bc8cf73f51018", 8192, 8192, "key_xbox360.dat"),
    PS3("PlayStation 3", 0, "1df41d237d8056ec87a5bc71925c4cde", 4096, 5504, "key_ps3.dat");

    /** true si [key] es la clave AES-256 correcta para esta plataforma. */
    fun isValidKey(key: ByteArray): Boolean = key.size == 32 && md5Hex(key) == keyMd5

    companion object {
        fun md5Hex(data: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

        /** Detecta la plataforma de una clave por su MD5, o null si no coincide con ninguna. */
        fun forKey(key: ByteArray): ConsolePlatform? = entries.firstOrNull { it.isValidKey(key) }
    }
}
