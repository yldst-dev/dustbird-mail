package app.dustbird.mail.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

data class ImportedFile(val fileName: String, val mimeType: String, val size: Long, val path: String)

class AttachmentFiles(private val context: Context) {
    val downloads = File(context.cacheDir, "attachments")
    val outbox = File(context.filesDir, "outbox")
    private val drafts = File(context.filesDir, "draft_attachments")

    fun import(draftId: Long, uri: Uri, remainingBytes: Long): ImportedFile? {
        if (uri.scheme != "content") return null
        val owner = uri.authority?.let { context.packageManager.resolveContentProvider(it, 0)?.packageName }
        if (owner == context.packageName) return null
        val resolver = context.contentResolver
        var name: String? = null
        var declared = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                declared = if (cursor.isNull(1)) -1L else cursor.getLong(1)
            }
        }
        if (declared > remainingBytes) return null
        val target = newDraftFile(draftId)
        val copied = resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> copyLimited(input, output, remainingBytes) }
        }
        if (copied == null || copied < 0) {
            target.delete()
            return null
        }
        val fileName = safeName(name)
        return ImportedFile(fileName, resolver.getType(uri) ?: typeOf(fileName), copied, target.absolutePath)
    }

    fun importFile(draftId: Long, source: File, fileName: String?, mimeType: String, remainingBytes: Long): ImportedFile? {
        if (source.length() > remainingBytes) return null
        val target = newDraftFile(draftId)
        source.copyTo(target, overwrite = true)
        return ImportedFile(safeName(fileName), mimeType, target.length(), target.absolutePath)
    }

    fun clearDraft(draftId: Long) {
        File(drafts, draftId.toString()).deleteRecursively()
    }

    fun clearDownloads(messageRowIds: Collection<Long>) {
        val ids = messageRowIds.toSet()
        downloads.listFiles()?.filter { it.name.toLongOrNull() in ids }?.forEach { it.deleteRecursively() }
    }

    fun downloadTarget(messageRowId: Long, index: Int, fileName: String?): File =
        File(File(downloads, messageRowId.toString()).apply { mkdirs() }, "${index}_${safeName(fileName)}")

    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun viewType(fileName: String?, declared: String): String {
        val recognized = declared != GENERIC_TYPE && MimeTypeMap.getSingleton().hasMimeType(declared)
        val type = if (recognized) declared else typeOf(safeName(fileName))
        return if (type == INSTALLER_TYPE) GENERIC_TYPE else type
    }

    fun clearAll() {
        downloads.deleteRecursively()
        outbox.deleteRecursively()
        drafts.deleteRecursively()
    }

    private fun newDraftFile(draftId: Long): File =
        File(File(drafts, draftId.toString()).apply { mkdirs() }, UUID.randomUUID().toString())

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) return -1
            output.write(buffer, 0, read)
        }
    }

    private fun typeOf(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: GENERIC_TYPE

    companion object {
        const val MAX_TOTAL_BYTES = 15L * 1024 * 1024
        private const val MAX_NAME_BYTES = 180
        private const val GENERIC_TYPE = "application/octet-stream"
        private const val INSTALLER_TYPE = "application/vnd.android.package-archive"

        fun safeName(name: String?): String {
            val cleaned = name.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(120)
            return shortened(cleaned).ifBlank { "attachment" }
        }

        private fun shortened(name: String): String {
            if (name.toByteArray().size <= MAX_NAME_BYTES) return name
            val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..8 }?.let { ".$it" }.orEmpty()
            var stem = name.removeSuffix(extension)
            while (stem.isNotEmpty() && (stem + extension).toByteArray().size > MAX_NAME_BYTES) stem = stem.dropLast(1)
            return stem + extension
        }
    }
}
