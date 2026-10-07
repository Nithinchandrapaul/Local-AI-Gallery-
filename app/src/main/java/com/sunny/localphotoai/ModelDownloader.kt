package com.sunny.localphotoai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {
    private const val FILE_NAME = "embeddinggemma-2-text-vision-440m.litertlm"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm"

    fun modelFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun ensureModel(context: Context): File? {
        val target = modelFile(context)
        if (target.exists() && target.length() > 50_000_000L) return target

        val temp = File(context.filesDir, "$FILE_NAME.download")
        return runCatching {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) error("Model download HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (!temp.renameTo(target)) error("Unable to finalize model")
            target
        }.getOrElse {
            temp.delete()
            null
        }
    }
}
