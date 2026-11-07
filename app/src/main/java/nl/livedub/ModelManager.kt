package nl.livedub

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads (once) and unpacks the offline English Vosk model. */
object ModelManager {
    // Larger, much more accurate than the "small" model (~130 MB download, ~0.5 GB RAM while running)
    const val MODEL_NAME = "vosk-model-en-us-0.22-lgraph"
    private const val OLD_MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

    fun modelDir(ctx: Context) = File(ctx.filesDir, MODEL_NAME)
    private fun marker(ctx: Context) = File(ctx.filesDir, "$MODEL_NAME.ready")

    fun isReady(ctx: Context) = marker(ctx).exists() && modelDir(ctx).isDirectory

    /** Blocking. Call from a background thread. */
    fun download(ctx: Context, onProgress: (Int) -> Unit) {
        val zip = File(ctx.cacheDir, "$MODEL_NAME.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("Model download failed: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        modelDir(ctx).deleteRecursively()
        // Free the space used by the previous small model
        File(ctx.filesDir, OLD_MODEL_NAME).deleteRecursively()
        File(ctx.filesDir, "$OLD_MODEL_NAME.ready").delete()
        unzip(zip, ctx.filesDir)
        zip.delete()
        marker(ctx).writeText("ok")
    }

    private fun unzip(zip: File, dest: File) {
        val root = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad zip entry: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                entry = zin.nextEntry
            }
        }
    }
}
