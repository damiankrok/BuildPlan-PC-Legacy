package com.buildplan.app.analyzer.lab

import android.content.Context
import com.buildplan.app.analyzer.asset.AnalysisStorage
import java.io.File

/**
 * The Lab's own storage.
 *
 * Decoding is the product's [com.buildplan.app.analyzer.AndroidRasterCodec] —
 * the Lab must exercise the same code the app ships, or it stops being
 * evidence about the product.
 *
 * What is different here is where things land: the Lab writes under `filesDir`
 * with absolute paths, because its whole job is to leave evidence a person can
 * pull off the device with `adb`. The product writes into `cacheDir` and hands
 * back relative paths, so a device path never travels in a report.
 */
internal class LabStorage(context: Context) : AnalysisStorage {
    val root: File = File(context.filesDir, "analyzer").apply { mkdirs() }

    override fun store(relativePath: String, bytes: ByteArray): String {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun writeText(relativePath: String, text: String): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }
}
