package com.bdfiles.core

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

/**
 * Acceso a un almacenamiento al que Android solo deja entrar con permiso
 * (memorias USB y algunas tarjetas), usando el sistema de documentos.
 *
 * Por fuera se comporta como cualquier otra carpeta: las rutas son "/carpeta/archivo".
 * Por dentro, cada ruta se traduce al identificador que usa Android.
 */
class SafSource(
    context: Context,
    private val treeUri: Uri,
    override val title: String,
    override val id: String,
) : FileSource {

    private val resolver: ContentResolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val rootPath: String = "/"

    /** Traducción de ruta a identificador de documento, para no preguntar cada vez. */
    private val docIds = HashMap<String, String>().apply { put("/", rootDocId) }

    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
    )

    private fun normalize(path: String): String {
        val p = "/" + path.trim('/').replace("//", "/")
        return if (p == "/") "/" else p.trimEnd('/')
    }

    /** Devuelve el identificador de documento de una ruta, recorriendo lo que haga falta. */
    private fun docIdOf(path: String): String {
        val clean = normalize(path)
        docIds[clean]?.let { return it }
        val parent = parentOf(clean) ?: return rootDocId
        val name = nameOf(clean)
        // Al listar el padre se rellena la traducción de todos sus hijos.
        listRaw(parent)
        return docIds[clean]
            ?: throw UserError("No se encontró “$name” en este almacenamiento")
    }

    private fun childrenUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun listRaw(path: String): List<FileItem> {
        val clean = normalize(path)
        val parentId = docIds[clean] ?: run {
            // Si todavía no se conoce, se resuelve subiendo hasta la raíz.
            val parent = parentOf(clean) ?: return emptyList()
            listRaw(parent)
            docIds[clean] ?: throw UserError("No se pudo abrir “${nameOf(clean)}”")
        }
        val out = ArrayList<FileItem>()
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(childrenUri(parentId), columns, null, null, null)
                ?: throw UserError("Android no dejó leer este almacenamiento")
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                val flags = if (cursor.isNull(5)) 0 else cursor.getInt(5)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val childPath = if (clean == "/") "/$name" else "$clean/$name"
                docIds[childPath] = docId
                val writable = (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0 ||
                    (isDir && (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0)
                out.add(
                    FileItem(
                        name = name,
                        path = childPath,
                        isDir = isDir,
                        size = if (isDir) 0 else size,
                        lastModified = modified,
                        readOnly = !writable,
                        sourceId = id,
                    ),
                )
            }
        } catch (e: UserError) {
            throw e
        } catch (e: Exception) {
            throw UserError(
                "No se pudo leer el almacenamiento.\n\n" +
                    "Puede que lo hayas desconectado o que el permiso ya no valga. " +
                    "Vuelve a darle acceso desde la pantalla de inicio.\n\n${e.message ?: ""}",
            )
        } finally {
            runCatching { cursor?.close() }
        }
        return out
    }

    override fun list(path: String): List<FileItem> = listRaw(path)

    override fun stat(path: String): FileItem? {
        val clean = normalize(path)
        if (clean == "/") return FileItem(title, "/", true, sourceId = id)
        val parent = parentOf(clean) ?: return null
        return runCatching { listRaw(parent).firstOrNull { it.path == clean } }.getOrNull()
    }

    override fun openRead(path: String): InputStream =
        resolver.openInputStream(docUri(docIdOf(path)))
            ?: throw UserError("No se pudo abrir “${nameOf(path)}”")

    override fun openWrite(path: String, size: Long): OutputStream {
        val clean = normalize(path)
        val existing = runCatching { docIdOf(clean) }.getOrNull()
        val uri = if (existing != null) {
            docUri(existing)
        } else {
            val parent = parentOf(clean) ?: throw UserError("Ruta no válida")
            val parentId = docIdOf(parent)
            val created = DocumentsContract.createDocument(
                resolver, docUri(parentId), mimeFor(nameOf(clean)), nameOf(clean),
            ) ?: throw UserError("No se pudo crear “${nameOf(clean)}”")
            docIds[clean] = DocumentsContract.getDocumentId(created)
            created
        }
        // "wt" vacía el archivo antes de escribir.
        return resolver.openOutputStream(uri, "wt")
            ?: throw UserError("No se pudo escribir en “${nameOf(clean)}”")
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "ini", "cfg", "meta", "dat" -> "text/plain"
        "xml" -> "text/xml"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "zip", "rpf" -> "application/zip"
        else -> "application/octet-stream"
    }

    override fun mkdirs(path: String) {
        val clean = normalize(path)
        if (clean == "/") return
        if (runCatching { docIdOf(clean) }.getOrNull() != null) return
        val parent = parentOf(clean) ?: return
        mkdirs(parent)
        val parentId = docIdOf(parent)
        val created = DocumentsContract.createDocument(
            resolver, docUri(parentId), DocumentsContract.Document.MIME_TYPE_DIR, nameOf(clean),
        ) ?: throw UserError("No se pudo crear la carpeta “${nameOf(clean)}”")
        docIds[clean] = DocumentsContract.getDocumentId(created)
    }

    override fun createFile(path: String) {
        val clean = normalize(path)
        if (runCatching { docIdOf(clean) }.getOrNull() != null) {
            throw UserError("Ya existe “${nameOf(clean)}”")
        }
        val parent = parentOf(clean) ?: throw UserError("Ruta no válida")
        val parentId = docIdOf(parent)
        val created = DocumentsContract.createDocument(
            resolver, docUri(parentId), mimeFor(nameOf(clean)), nameOf(clean),
        ) ?: throw UserError("No se pudo crear “${nameOf(clean)}”")
        docIds[clean] = DocumentsContract.getDocumentId(created)
    }

    override fun delete(path: String) {
        val clean = normalize(path)
        val docId = runCatching { docIdOf(clean) }.getOrNull() ?: return
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, docUri(docId)) }
            .getOrDefault(false)
        if (!ok) throw UserError("No se pudo borrar “${nameOf(clean)}”")
        // Se olvida lo que colgaba de ahí.
        docIds.keys.filter { it == clean || it.startsWith("$clean/") }.forEach { docIds.remove(it) }
    }

    override fun rename(path: String, newName: String) {
        val clean = normalize(path)
        val docId = docIdOf(clean)
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, docUri(docId), newName) }
            .getOrNull() ?: throw UserError("No se pudo renombrar “${nameOf(clean)}”")
        docIds.keys.filter { it == clean || it.startsWith("$clean/") }.forEach { docIds.remove(it) }
        val parent = parentOf(clean) ?: "/"
        docIds[if (parent == "/") "/$newName" else "$parent/$newName"] =
            DocumentsContract.getDocumentId(renamed)
    }

    override fun freeSpace(): Long = -1

    override fun totalSpace(): Long = -1
}
