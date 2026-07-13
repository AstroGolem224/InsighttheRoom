package itr.export.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

// MIME is DERIVED from the (validated) extension so a .svg can't be shared as image/png
private val MIME_BY_EXT = mapOf("png" to "image/png", "svg" to "image/svg+xml")
private const val KEEP_MS = 24 * 60 * 60 * 1000L

/**
 * Write [bytes] under cache/exports and return a read-only share Intent with a FileProvider URI.
 * [fileName] must be a bare basename with an allowed extension (no path traversal). MIME is derived
 * from the extension. Files older than 24 h (vs [nowMs]) are swept. The URI is attached as ClipData
 * so the chooser propagates the read grant. Caller starts the chooser.
 */
fun shareExport(context: Context, fileName: String, bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Intent {
    require(!fileName.contains('/') && !fileName.contains('\\') && fileName == File(fileName).name) { "fileName must be a bare basename" }
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val mime = MIME_BY_EXT[ext] ?: throw IllegalArgumentException("extension not allowed: $ext")
    val dir = File(context.cacheDir, "exports").apply { require(mkdirs() || isDirectory) { "cannot create exports dir" } }
    dir.listFiles()?.filter { it.isFile }?.forEach { if (nowMs - it.lastModified() > KEEP_MS) it.delete() }
    val target = File(dir, fileName)
    require(target.canonicalFile.parentFile == dir.canonicalFile) { "resolved path escapes the exports dir" }
    target.writeBytes(bytes)
    val uri = Uri.Builder()
        .scheme("content")
        .authority("${context.packageName}.fileprovider")
        .appendPath("exports")
        .appendPath(fileName)
        .build()
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
