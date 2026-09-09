package com.buildplan.app.analyzer.lab

import android.content.Context
import android.graphics.BitmapFactory
import com.buildplan.app.analyzer.asset.AnalysisStorage
import com.buildplan.app.analyzer.raster.RasterCodec
import com.buildplan.app.analyzer.raster.RasterImage
import java.io.File

/**
 * The Android side of the analyzer's two platform seams: decoding an asset
 * into pixels, and keeping downloaded assets and snapshots in app-private
 * storage. Nothing here interprets anything.
 */
internal object AndroidRasterCodec : RasterCodec {
    override fun decode(bytes: ByteArray): RasterImage? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = RasterImage(bitmap.width, bitmap.height, argb)
        bitmap.recycle()
        return image
    }
}

/** App-private analysis storage under `filesDir/analyzer`. Never external, never the repository. */
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
