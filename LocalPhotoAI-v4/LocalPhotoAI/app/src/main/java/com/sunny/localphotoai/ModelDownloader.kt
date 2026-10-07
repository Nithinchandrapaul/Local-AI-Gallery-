package com.sunny.localphotoai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {
    const val MODEL_NAME = "embeddinggemma-2-text-vision-440m.litertlm"
    private const val MODEL_URL = "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm?download=true"

    fun modelFile(context: Context): File = File(File(context.filesDir, "models"), MODEL_NAME)
    fun isInstalled(context: Context): Boolean = modelFile(context).exists() && modelFile(context).length() > 100_000_000L

    fun download(context: Context, onProgress: (Int) -> Unit): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = modelFile(context)
        val partial = File(dir, "$MODEL_NAME.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 120_000; instanceFollowRedirects = true
            requestMethod = "GET"
        }
        connection.connect()
        if (connection.responseCode !in 200..299) error("Model download failed: HTTP ${connection.responseCode}")
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var readTotal = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); readTotal += n
                    if (total > 0) onProgress(((readTotal * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        connection.disconnect()
        if (partial.length() < 100_000_000L) error("Downloaded model is unexpectedly small")
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Unable to finalize model" }
        return target
    }
}
