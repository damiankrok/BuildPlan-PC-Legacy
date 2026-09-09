package com.buildplan.app.analyzer.cache

import com.buildplan.app.analyzer.asset.AnalysisStorage
import com.buildplan.app.analyzer.snapshot.Json
import com.buildplan.app.analyzer.snapshot.JsonValue
import com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.analyzer.snapshot.json
import com.buildplan.app.analyzer.source.FetchException
import com.buildplan.app.analyzer.source.FetchedResource
import com.buildplan.app.analyzer.source.ResourceFetcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Limits the cache keeps itself inside. Stated so the report can print them. */
data class AnalysisCachePolicy(
    /** Total bytes the cache may hold across all projects before the least recently used are dropped. */
    val maxBytes: Long = 96L * 1024 * 1024,
    /** How many projects may be kept at once, whatever their size. */
    val maxProjects: Int = 8,
)

/** A finished analysis the cache is holding for a project. */
data class CachedAnalysis(
    val projectKey: String,
    val snapshot: ProjectAnalysisSnapshot,
    val storedAtEpochMillis: Long,
)

/**
 * The app-private cache of everything one analysis touched: the pages and
 * drawings it fetched, and the snapshot it produced.
 *
 * Four properties it is built around, each of which is a way the naive version
 * goes wrong:
 *
 * 1. **Versioned by everything that changes the answer.** The top directory is
 *    named after the schema, analyzer and adapter versions together. A build
 *    that changes any of them cannot see the old directory at all, and deletes
 *    it on the way past. Replaying an old parser's output as if it were
 *    current is the one cache bug that produces confident wrong numbers.
 * 2. **Nothing is written where it will be read until it is complete.** Every
 *    file is written to a `.part` sibling and renamed; every run writes into a
 *    private session directory that is renamed into place only once the run
 *    has finished. A cancelled or crashed run leaves a session directory,
 *    which the next open sweeps away, and never a half-downloaded drawing
 *    inside a project the next run would trust.
 * 3. **Keyed by the project, reached by the URL.** A project is stored under
 *    the site's own key; the URLs that resolved to it are recorded beside it,
 *    because the user's link and the canonical link are rarely the same string
 *    and both must find the same cache entry.
 * 4. **Bounded.** Old projects are dropped by least recent use, whole projects
 *    at a time, so a partly-pruned project can never exist.
 *
 * The root is supplied by the host and is expected to be app-private storage.
 * Nothing here asks for external storage or a permission.
 */
class AnalysisCache(
    root: File,
    /** The version token that must match for anything stored here to be replayable. */
    val cacheTag: String,
    private val policy: AnalysisCachePolicy = AnalysisCachePolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = ReentrantLock()
    private val versionRoot = File(root, cacheTag)
    private val projectsDir = File(versionRoot, PROJECTS)
    private val sessionsDir = File(versionRoot, SESSIONS)

    init {
        lock.withLock {
            root.mkdirs()
            // Anything under a different version token was produced by a build whose answers we
            // can no longer vouch for. Deleting it here, rather than ignoring it, is what keeps
            // the budget honest as well: an orphan directory is not free.
            root.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name != cacheTag) child.deleteRecursively()
            }
            projectsDir.mkdirs()
            sessionsDir.mkdirs()
            sweepSessions()
        }
    }

    /** Total bytes currently held. */
    fun sizeBytes(): Long = lock.withLock { versionRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Project keys currently held, most recently used first. */
    fun projects(): List<String> = lock.withLock {
        projectDirs().sortedByDescending { lastUsed(it) }.map { it.name }
    }

    /** Deletes everything this cache holds under the current version. */
    fun clear() = lock.withLock {
        projectsDir.deleteRecursively()
        sessionsDir.deleteRecursively()
        projectsDir.mkdirs()
        sessionsDir.mkdirs()
    }

    /**
     * The finished analysis stored for the project that [requestedUrl] last
     * resolved to, or null when nothing under the current version matches.
     */
    fun lookupByUrl(requestedUrl: String): CachedAnalysis? = lock.withLock {
        val normalized = normalizeUrl(requestedUrl)
        val dir = projectDirs().firstOrNull { dir -> aliasesOf(dir).contains(normalized) } ?: return null
        readAnalysis(dir)
    }

    /** The finished analysis stored under [projectKey], or null. */
    fun lookupByKey(projectKey: String): CachedAnalysis? = lock.withLock {
        val dir = File(projectsDir, safe(projectKey))
        if (!dir.isDirectory) return null
        readAnalysis(dir)
    }

    /**
     * Opens a private working area for one run.
     *
     * Everything the run fetches or stores lands here and nowhere else, so two
     * runs cannot write over each other's half-finished files, and a run that
     * never finishes leaves nothing a later run can mistake for a result.
     */
    fun beginSession(requestedUrl: String): Session = lock.withLock {
        val dir = File(sessionsDir, "${hash(normalizeUrl(requestedUrl)).take(16)}-${clock()}-${sessionCounter++}")
        dir.mkdirs()
        File(dir, RESPONSES).mkdirs()
        Session(dir, requestedUrl)
    }

    /**
     * One run's working area, and the two seams it hands the pipeline: a
     * fetcher that reads and writes cached responses, and storage for decoded
     * drawings.
     */
    inner class Session internal constructor(
        internal val dir: File,
        private val requestedUrl: String,
    ) {
        private var committed = false

        /**
         * Bytes the pipeline may read: this session's own downloads first, then
         * whatever the project's existing cache entry already holds.
         *
         * The project key is not known when the session opens, so the replay
         * source is resolved through the URL aliases instead — which is exactly
         * how a returning user's typed link finds last week's download.
         */
        fun fetcher(network: ResourceFetcher?, allowReplay: Boolean): ResourceFetcher =
            CachingFetcher(
                network = network,
                writeDir = File(dir, RESPONSES),
                readDirs = buildList {
                    add(File(dir, RESPONSES))
                    if (allowReplay) {
                        val existing = lock.withLock { projectDirs().firstOrNull { d -> aliasesOf(d).contains(normalizeUrl(requestedUrl)) } }
                        existing?.let { add(File(it, RESPONSES)) }
                    }
                },
            )

        /** Where decoded drawings go. Returns paths relative to the cache root: never a device path. */
        fun storage(): AnalysisStorage = AnalysisStorage { relativePath, bytes ->
            val file = File(dir, "$ASSETS/$relativePath")
            file.parentFile?.mkdirs()
            writeAtomically(file, bytes)
            "$ASSETS/$relativePath"
        }

        /**
         * Publishes this session as the cache entry for [projectKey], replacing
         * whatever was there.
         *
         * The swap is a rename of one directory over another, so a reader
         * either sees the whole old entry or the whole new one. The old entry
         * is moved aside first and deleted afterwards, because deleting it
         * before the rename would leave a window with no entry at all.
         */
        fun commit(projectKey: String, snapshot: ProjectAnalysisSnapshot): CachedAnalysis = lock.withLock {
            val target = File(projectsDir, safe(projectKey))
            val aliases = (if (target.isDirectory) aliasesOf(target) else emptySet()) + normalizeUrl(requestedUrl)
            val stored = clock()
            writeAtomically(File(dir, SNAPSHOT), SnapshotCodec.write(snapshot).toByteArray(Charsets.UTF_8))
            writeAtomically(File(dir, MANIFEST), manifestJson(projectKey, aliases, stored).toByteArray(Charsets.UTF_8))

            val retired = if (target.isDirectory) File(sessionsDir, "retired-${clock()}-${sessionCounter++}") else null
            if (retired != null && !target.renameTo(retired)) target.deleteRecursively()
            if (!dir.renameTo(target)) {
                // Same-filesystem renames do not normally fail; when one does, a copy is still
                // correct because the destination does not exist yet.
                dir.copyRecursively(target, overwrite = true)
                dir.deleteRecursively()
            }
            retired?.deleteRecursively()
            committed = true
            prune()
            CachedAnalysis(projectKey, snapshot, stored)
        }

        /** Throws the session away. Safe to call twice, and safe after [commit]. */
        fun discard() = lock.withLock {
            if (!committed) dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- internals

    private var sessionCounter = 0L

    private fun projectDirs(): List<File> = projectsDir.listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun readAnalysis(dir: File): CachedAnalysis? {
        val snapshotFile = File(dir, SNAPSHOT)
        if (!snapshotFile.isFile) return null
        val snapshot = try {
            SnapshotCodec.read(snapshotFile.readText(Charsets.UTF_8))
        } catch (e: RuntimeException) {
            // A snapshot this build cannot parse is not a cache hit; drop it rather than
            // reason about a half-understood one.
            dir.deleteRecursively()
            return null
        }
        if (snapshot.schemaVersion != ProjectAnalysisSnapshot.SCHEMA_VERSION) {
            dir.deleteRecursively()
            return null
        }
        touch(dir)
        return CachedAnalysis(dir.name, snapshot, File(dir, MANIFEST).takeIf { it.isFile }?.lastModified() ?: dir.lastModified())
    }

    private fun aliasesOf(dir: File): Set<String> {
        val manifest = File(dir, MANIFEST).takeIf { it.isFile } ?: return emptySet()
        val parsed = try {
            Json.parse(manifest.readText(Charsets.UTF_8)) as? JsonValue.Obj
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptySet()
        return parsed.arr("aliases")?.items?.filterIsInstance<JsonValue.Str>()?.map { it.value }?.toSet().orEmpty()
    }

    private fun lastUsed(dir: File): Long = File(dir, USED).takeIf { it.isFile }?.lastModified() ?: dir.lastModified()

    private fun touch(dir: File) {
        val marker = File(dir, USED)
        runCatching {
            if (!marker.isFile) marker.writeBytes(ByteArray(0))
            marker.setLastModified(clock())
        }
    }

    /**
     * Brings the cache back inside its budget by dropping whole projects,
     * least recently used first.
     *
     * Whole projects, because a project missing half its drawings would be
     * replayed as a project whose drawings failed to download, which is a
     * different and much worse thing than a cache miss.
     */
    private fun prune() {
        val dirs = projectDirs().sortedByDescending { lastUsed(it) }.toMutableList()
        while (dirs.size > policy.maxProjects) {
            dirs.removeAt(dirs.size - 1).deleteRecursively()
        }
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        while (total > policy.maxBytes && dirs.size > 1) {
            val victim = dirs.removeAt(dirs.size - 1)
            total -= victim.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            victim.deleteRecursively()
        }
    }

    /** Removes every working area left behind by a run that was cancelled or died. */
    private fun sweepSessions() {
        sessionsDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun manifestJson(projectKey: String, aliases: Set<String>, storedAt: Long): String = Json.write(
        json {
            "projectKey" to projectKey
            "cacheTag" to cacheTag
            "storedAtEpochMillis" to storedAt
            "aliases" toStrings aliases.toList()
        },
    )

    companion object {
        private const val PROJECTS = "projects"
        private const val SESSIONS = "sessions"
        private const val RESPONSES = "responses"
        private const val ASSETS = "assets"
        private const val SNAPSHOT = "snapshot.json"
        private const val MANIFEST = "manifest.json"
        private const val USED = ".used"


        /**
         * The comparable form of a URL for cache lookup: scheme and host
         * lower-cased, a trailing slash and an empty query dropped.
         *
         * Nothing more is normalised. A query string that differs is a
         * different page as far as this cache is concerned, because guessing
         * which parameters are decorative is how a cache starts answering the
         * wrong question.
         */
        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            val scheme = trimmed.substringBefore("://", "").lowercase()
            if (scheme.isEmpty()) return trimmed
            val rest = trimmed.substringAfter("://")
            val authority = rest.substringBefore('/', rest).lowercase()
            val path = if (rest.contains('/')) "/" + rest.substringAfter('/') else ""
            return "$scheme://$authority" + path.removeSuffix("/").removeSuffix("?")
        }

        internal fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Writes through a `.part` sibling so a reader never sees a half-written file. */
        internal fun writeAtomically(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, "${file.name}.part")
            part.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!part.renameTo(file)) {
                part.copyTo(file, overwrite = true)
                part.delete()
            }
        }

        private fun safe(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unnamed" }
    }
}

/**
 * Joins the redirect chain in a cached response head.
 *
 * A control character rather than a comma or a space, because both of those
 * are legal in a URL, and a separator that can appear inside the values it
 * separates is a parser waiting to lie.
 */
private const val REDIRECT_SEPARATOR = "\u001f"

/**
 * A fetcher that answers from cached bytes when it can and records what it had
 * to go and get.
 *
 * [network] is null when the caller asked for a cache-only run; a miss is then
 * a failure rather than a fetch, which is what makes "do not open a socket"
 * enforceable instead of merely intended.
 */
internal class CachingFetcher(
    private val network: ResourceFetcher?,
    private val writeDir: File,
    private val readDirs: List<File>,
) : ResourceFetcher {

    override fun fetch(url: String): FetchedResource {
        val name = AnalysisCache.hash(AnalysisCache.normalizeUrl(url))
        readDirs.forEach { dir ->
            val head = File(dir, "$name.head")
            val body = File(dir, "$name.body")
            if (head.isFile && body.isFile) {
                runCatching { return read(url, head, body) }
            }
        }
        val fetcher = network
            ?: throw FetchException("Cache-only run and $url is not in the cache")
        val resource = fetcher.fetch(url)
        runCatching { write(name, resource) }
        return resource
    }

    private fun read(requestedUrl: String, head: File, body: File): FetchedResource {
        val fields = head.readText(Charsets.UTF_8).lineSequence()
            .mapNotNull { line -> line.substringBefore('=', "").takeIf { it.isNotEmpty() }?.let { it to line.substringAfter('=') } }
            .toMap()
        return FetchedResource(
            requestedUrl = requestedUrl,
            finalUrl = fields["finalUrl"] ?: requestedUrl,
            redirects = fields["redirects"]?.split(REDIRECT_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty(),
            statusCode = fields["statusCode"]?.toIntOrNull() ?: 200,
            contentType = fields["contentType"]?.takeIf { it.isNotBlank() },
            body = body.readBytes(),
            retrievedAtEpochMillis = fields["retrievedAt"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun write(name: String, resource: FetchedResource) {
        // The body lands first: a head with no body would be read as an empty response,
        // while a body with no head is simply not a hit.
        AnalysisCache.writeAtomically(File(writeDir, "$name.body"), resource.body)
        val head = buildString {
            appendLine("finalUrl=${resource.finalUrl}")
            appendLine("statusCode=${resource.statusCode}")
            appendLine("contentType=${resource.contentType ?: ""}")
            appendLine("retrievedAt=${resource.retrievedAtEpochMillis}")
            appendLine("redirects=${resource.redirects.joinToString(REDIRECT_SEPARATOR)}")
        }
        AnalysisCache.writeAtomically(File(writeDir, "$name.head"), head.toByteArray(Charsets.UTF_8))
    }
}
