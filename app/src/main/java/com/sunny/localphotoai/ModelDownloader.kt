package com.sunny.localphotoai

import android.content.Context
import java.io.File

/** Bundled model provider. No network/model download is used on-device. */
object ModelDownloader {
    private const val FILE_NAME = "embeddinggemma-2-text-vision-440m.litertlm"
    private const val ASSET_PATH = "models/$FILE_NAME"

    fun modelFile(context: Context): File {
        val target = File(context.filesDir, FILE_NAME)
        if (target.exists() && target.length() > 50_000_000L) return target
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        return runCatching {
            context.assets.open(ASSET_PATH).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) error("Unable to finalize bundled AI model")
            target
        }.getOrElse {
            temp.delete()
            throw IllegalStateException("Bundled AI model is missing: $ASSET_PATH", it)
        }
    }
}