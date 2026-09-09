package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.asset.AnalysisStorage
import com.buildplan.app.analyzer.raster.RasterCodec
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.source.FetchException
import com.buildplan.app.analyzer.source.FetchedResource
import com.buildplan.app.analyzer.source.HttpResourceFetcher
import com.buildplan.app.analyzer.source.ResourceFetcher
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.junit.Assume

/**
 * The opt-in harness for tests that touch the live source or its cached copy.
 *
 * Nothing here runs unless `BUILDPLAN_ANALYZER_EVIDENCE_DIR` names a directory
 * outside the repository. The first run fetches live and caches every
 * response (bytes + metadata) under `<dir>/cache`; later runs replay the
 * cache, so evaluation is repeatable while third-party drawings never enter
 * the worktree. Set `BUILDPLAN_ANALYZER_LIVE=1` to bypass the cache.
 */
object EvidenceHarness {

    const val ENV_DIR = "BUILDPLAN_ANALYZER_EVIDENCE_DIR"
    const val ENV_LIVE = "BUILDPLAN_ANALYZER_LIVE"

    fun directoryOrSkip(): File {
        val path = System.getenv(ENV_DIR) ?: System.getProperty("buildplan.analyzer.evidenceDir")
        Assume.assumeTrue("$ENV_DIR not set: live/cached source evaluation skipped", !path.isNullOrBlank())
        val dir = File(path!!)
        Assume.assumeTrue("$path is not a directory", dir.isDirectory)
        val repoRoot = File(".").absoluteFile.parentFile.parentFile
        Assume.assumeTrue("evidence dir must be outside the repository", !dir.canonicalPath.startsWith(repoRoot.canonicalPath))
        return dir
    }

    fun fetcher(dir: File): ResourceFetcher {
        val live = System.getenv(ENV_LIVE) == "1"
        return CachingFetcher(HttpResourceFetcher(), File(dir, "cache"), bypass = live)
    }

    fun storage(dir: File, run: String): AnalysisStorage = DirectoryStorage(File(dir, run))

    val codec: RasterCodec = ImageIoRasterCodec

    fun write(dir: File, name: String, text: String) {
        File(dir, name).apply { parentFile.mkdirs() }.writeText(text)
    }
}

/** ImageIO for the JVM tests; the debug Lab uses BitmapFactory on the device. */
object ImageIoRasterCodec : RasterCodec {
    override fun decode(bytes: ByteArray): RasterImage? {
        val image: BufferedImage = try {
            ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        } catch (e: Exception) {
            return null
        }
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return RasterImage(image.width, image.height, argb)
    }

    fun toBufferedImage(image: RasterImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        return out
    }
}

class DirectoryStorage(private val root: File) : AnalysisStorage {
    override fun store(relativePath: String, bytes: ByteArray): String {
        val file = File(root, relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return file.absolutePath
    }
}

class CachingFetcher(
    private val delegate: ResourceFetcher,
    private val cacheDir: File,
    private val bypass: Boolean,
) : ResourceFetcher {

    override fun fetch(url: String): FetchedResource {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        val body = File(cacheDir, "$key.bin")
        val meta = File(cacheDir, "$key.meta")
        if (!bypass && body.isFile && meta.isFile) {
            val lines = meta.readLines()
            val fields = lines.associate { it.substringBefore('=') to it.substringAfter('=') }
            return FetchedResource(
                requestedUrl = url,
                finalUrl = fields["finalUrl"] ?: url,
                redirects = fields["redirects"]?.split('|')?.filter { it.isNotBlank() } ?: emptyList(),
                statusCode = fields["status"]?.toInt() ?: 200,
                contentType = fields["contentType"]?.takeIf { it != "null" },
                body = body.readBytes(),
                retrievedAtEpochMillis = fields["retrievedAt"]?.toLong() ?: 0L,
            )
        }
        val resource = try {
            delegate.fetch(url)
        } catch (e: FetchException) {
            throw e
        }
        cacheDir.mkdirs()
        body.writeBytes(resource.body)
        meta.writeText(
            listOf(
                "url=$url",
                "finalUrl=${resource.finalUrl}",
                "redirects=${resource.redirects.joinToString("|")}",
                "status=${resource.statusCode}",
                "contentType=${resource.contentType}",
                "retrievedAt=${resource.retrievedAtEpochMillis}",
            ).joinToString("\n"),
        )
        return resource
    }
}
