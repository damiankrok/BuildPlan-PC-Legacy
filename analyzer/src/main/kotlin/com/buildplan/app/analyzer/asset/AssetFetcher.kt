package com.buildplan.app.analyzer.asset

import com.buildplan.app.analyzer.raster.RasterCodec
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.source.FetchException
import com.buildplan.app.analyzer.source.ResourceFetcher
import java.security.MessageDigest

/**
 * Where downloaded assets go: app-private storage on the device, a temporary
 * directory in tests. Never the repository. The analyzer sees only the path
 * string it gets back, for the manifest.
 */
interface AnalysisStorage {
    /** Stores [bytes] under [relativePath] (slashes allowed) and returns a printable location. */
    fun store(relativePath: String, bytes: ByteArray): String
}

/** Limits every asset download obeys, stated so the report can print them. */
data class AssetPolicy(
    val maxBytes: Long = 6L * 1024 * 1024,
    val maxPixels: Long = 20L * 1000 * 1000,
    /** Roles worth downloading. Everything else stays DISCOVERED. */
    val wantedRoles: Set<AssetRole> = setOf(
        AssetRole.PLAN_GROUND, AssetRole.PLAN_UPPER, AssetRole.PLAN_FLOOR_N,
        AssetRole.PLAN_GROUND_WITH_AREAS, AssetRole.PLAN_UPPER_WITH_AREAS,
        AssetRole.SECTION,
        AssetRole.ELEVATION_FRONT, AssetRole.ELEVATION_REAR, AssetRole.ELEVATION_LEFT, AssetRole.ELEVATION_RIGHT,
        AssetRole.HERO_RENDER, AssetRole.SITE_PLAN,
    ),
)

/** What [AssetFetcher] hands back: the manifest with retrieval state filled in, and the decoded images by URL. */
class FetchedAssets(
    val manifest: AssetManifest,
    val images: Map<String, RasterImage>,
) {
    fun image(role: AssetRole): RasterImage? = manifest.firstWithRole(role)?.let { images[it.url] }
    fun image(record: AssetRecord): RasterImage? = images[record.url]
}

/**
 * Downloads the assets a manifest names, through the same bounded
 * [ResourceFetcher] the page came through, decodes them with the platform's
 * [RasterCodec], hashes and stores them, and records every outcome on the
 * manifest — including failures, which are a state and not an exception.
 */
class AssetFetcher(
    private val fetcher: ResourceFetcher,
    private val codec: RasterCodec,
    private val storage: AnalysisStorage,
    private val policy: AssetPolicy = AssetPolicy(),
) {

    fun fetchAll(manifest: AssetManifest, storagePrefix: String): FetchedAssets {
        val images = LinkedHashMap<String, RasterImage>()
        val records = manifest.assets.map { record ->
            if (record.role !in policy.wantedRoles) return@map record.copy(retrieval = RetrievalState.SKIPPED)
            fetchOne(record, storagePrefix, images)
        }
        return FetchedAssets(AssetManifest(records), images)
    }

    private fun fetchOne(record: AssetRecord, storagePrefix: String, images: MutableMap<String, RasterImage>): AssetRecord {
        val resource = try {
            fetcher.fetch(record.url)
        } catch (e: FetchException) {
            return record.copy(retrieval = RetrievalState.FAILED, failure = e.message)
        }
        if (!resource.isSuccess) {
            return record.copy(retrieval = RetrievalState.FAILED, failure = "HTTP ${resource.statusCode}")
        }
        if (resource.body.size > policy.maxBytes) {
            return record.copy(retrieval = RetrievalState.FAILED, failure = "${resource.body.size} bytes over the ${policy.maxBytes} limit")
        }
        val sha = sha256Hex(resource.body)
        val extension = record.url.substringAfterLast('.', "bin").substringBefore('?').take(5)
        val storagePath = storage.store("$storagePrefix/${record.role.name.lowercase()}-${sha.take(12)}.$extension", resource.body)
        val downloaded = record.copy(
            retrieval = RetrievalState.DOWNLOADED,
            byteCount = resource.body.size.toLong(),
            sha256 = sha,
            storagePath = storagePath,
            declaredMediaType = resource.contentType ?: record.declaredMediaType,
        )
        val image = codec.decode(resource.body)
            ?: return downloaded.copy(failure = "not decodable as an image")
        if (image.pixelCount.toLong() > policy.maxPixels) {
            return downloaded.copy(failure = "${image.width}x${image.height} exceeds the ${policy.maxPixels} pixel limit")
        }
        images[record.url] = image
        return downloaded.copy(retrieval = RetrievalState.DECODED, widthPx = image.width, heightPx = image.height)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
