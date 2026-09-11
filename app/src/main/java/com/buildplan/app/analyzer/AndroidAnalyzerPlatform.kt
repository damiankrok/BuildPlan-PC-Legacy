package com.buildplan.app.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.buildplan.app.analyzer.cache.AnalysisCachePolicy
import com.buildplan.app.analyzer.raster.RasterCodec
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.service.AnalyzerPlatform
import com.buildplan.app.analyzer.service.AnalyzerServices
import com.buildplan.app.analyzer.service.ProjectAnalyzerService
import com.buildplan.app.analyzer.source.HttpResourceFetcher
import com.buildplan.app.analyzer.source.ResourceFetcher
import java.io.File

/**
 * Turning downloaded bytes into pixels, on the device.
 *
 * The one Android type the analyzer's world touches. Everything past this
 * function is a plain `IntArray`, which is what lets every pixel algorithm in
 * `:analyzer` be written and tested on a JVM and still run here unchanged.
 */
object AndroidRasterCodec : RasterCodec {
    override fun decode(bytes: ByteArray): RasterImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 20_000_000) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        // Bound allocation before decoding. Calibration is measured on the decoded raster.
        while (bounds.outWidth.toLong() * bounds.outHeight / options.inSampleSize.coerceAtLeast(1).let { it.toLong() * it } > 4_000_000) {
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = RasterImage(bitmap.width, bitmap.height, argb)
        // A plan raster is several megabytes of bitmap and the analyzer has its own copy of the
        // pixels by now. Holding both is how a 2 GB device runs out of memory on the second plan.
        bitmap.recycle()
        return image
    }
}

/** The device's side of the analyzer's platform seams. */
class AndroidAnalyzerPlatform(
    override val codec: RasterCodec = AndroidRasterCodec,
) : AnalyzerPlatform {
    override fun networkFetcher(): ResourceFetcher = HttpResourceFetcher()
}

/**
 * How the app gets the analyzer.
 *
 * One service per process, because the cache it owns has to be the only writer
 * of its directory: two services over one directory would each sweep the
 * other's working areas at construction. It is a plain object rather than a
 * singleton with an Activity in it — nothing here holds a Context beyond the
 * application one, so a rotated screen does not leak a service or a run.
 */
object BuildPlanAnalyzer {

    /**
     * Downloaded pages and drawings live in `cacheDir`, not `filesDir`.
     *
     * They are reproducible from the network and are the largest thing the app
     * writes, so the system may reclaim them under storage pressure — which is
     * exactly the right trade for a cache, and it keeps the app off the backup
     * quota. Nothing here uses external storage, so no permission is involved.
     */
    private const val DIRECTORY = "project-analyzer"

    @Volatile
    private var instance: ProjectAnalyzerService? = null

    fun service(context: Context): ProjectAnalyzerService {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: AnalyzerServices.create(
                platform = AndroidAnalyzerPlatform(),
                cacheRoot = cacheRoot(context),
                cachePolicy = AnalysisCachePolicy(),
            ).also { instance = it }
        }
    }

    fun cacheRoot(context: Context): File = File(context.applicationContext.cacheDir, DIRECTORY)
}
