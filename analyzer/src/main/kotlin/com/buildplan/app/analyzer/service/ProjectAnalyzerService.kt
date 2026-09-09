package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.cache.AnalysisCache
import com.buildplan.app.analyzer.cache.AnalysisCachePolicy
import com.buildplan.app.analyzer.candidate.AnalysisIssue
import com.buildplan.app.analyzer.candidate.IssueSeverity
import com.buildplan.app.analyzer.pipeline.AnalysisCancelledException
import com.buildplan.app.analyzer.pipeline.AnalysisListener
import com.buildplan.app.analyzer.pipeline.AnalysisRun
import com.buildplan.app.analyzer.pipeline.CancellationSignal
import com.buildplan.app.analyzer.pipeline.ProjectAnalyzer
import com.buildplan.app.analyzer.raster.RasterCodec
import com.buildplan.app.analyzer.site.SiteAdapter
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.ResolutionStep
import com.buildplan.app.analyzer.source.ResourceFetcher
import com.buildplan.app.analyzer.source.SupportedSite
import com.buildplan.app.analyzer.source.UrlSafety
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the application calls to turn a project URL into a candidate.
 *
 * This, and the value types it exposes, is the whole supported surface.
 * Everything under `plan/`, `roof/`, `raster/`, `text/` and `site/archon/` is
 * the analyzer's own business and will be rewritten without notice; a caller
 * that reaches past this interface has taken a dependency on a prototype's
 * internals. `AnalyzerApiSurfaceTest` in the app module checks that none does.
 */
interface ProjectAnalyzerService {

    /**
     * Runs one analysis, reporting progress and finishing with exactly one
     * outcome.
     *
     * Cold: nothing happens until the flow is collected, cancelling the
     * collection cancels the run, and the work never runs on the collector's
     * thread. A caller that leaves — a screen that is closed, a scope that is
     * cancelled — takes the analysis with it and leaves no half-written cache
     * entry behind.
     */
    fun analyze(request: AnalyzeProjectRequest): Flow<AnalysisEvent>

    /** The outcome alone, for a caller with nothing to show while it waits. */
    suspend fun analyzeOnce(request: AnalyzeProjectRequest): AnalysisOutcome {
        val last = analyze(request).last()
        return (last as? AnalysisEvent.Completed)?.outcome
            ?: AnalysisOutcome.AnalysisFailed(null, "analysis produced no outcome")
    }
}

/**
 * The platform seams the analyzer cannot supply itself: pixels, the network
 * and name resolution.
 *
 * On Android these are `BitmapFactory` and `HttpURLConnection`; in tests they
 * are ImageIO and a fake. Keeping them behind an interface is what lets the
 * whole service — cancellation, cache and URL safety included — be tested on a
 * plain JVM with no device and no socket.
 */
interface AnalyzerPlatform {

    val codec: RasterCodec

    /** The network fetcher. Wrapped by the cache; the pipeline never sees it directly. */
    fun networkFetcher(): ResourceFetcher

    /** Resolves a host to addresses, so the private-address rule can be exercised without DNS. */
    fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

/**
 * The service.
 *
 * The shape of a run:
 *
 * 1. **Check the URL before opening anything.** Scheme, credentials, port,
 *    host allowlist and address class are decided here, so an unsupported or
 *    unsafe link is a typed answer rather than a network error with a
 *    confusing message.
 * 2. **Take the lock for this project.** Two runs of the same link are
 *    serialised, so the second finds the first one's cache entry instead of
 *    racing it into the same directory. Different projects run concurrently.
 * 3. **Answer from the cache when the caller allows it.** A stored snapshot
 *    under the current version token is replayed without touching the network
 *    or the pipeline, and says so on the report.
 * 4. **Otherwise run the pipeline into a private session**, and publish that
 *    session as the project's cache entry only once it has finished.
 *
 * Cancellation is checked at every pipeline stage and between drawings. A
 * cancelled run publishes nothing and deletes its session: a half-finished
 * download that looked like a cache entry would be replayed by the next run as
 * though it were complete, which is the one cache failure that produces
 * confident wrong numbers rather than an error.
 */
class DefaultProjectAnalyzerService(
    private val platform: AnalyzerPlatform,
    private val cache: AnalysisCache,
    private val adapterFactory: () -> SiteAdapter = { ArchonSiteAdapter() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProjectAnalyzerService {

    /**
     * One lock per project, so two taps on the same link do not both download
     * it and then both rename a session over the same directory.
     *
     * Keyed by the normalised requested URL rather than by the project key,
     * because the key is not known until the page has been fetched — which is
     * already inside the part that must not happen twice.
     *
     * Never pruned, on purpose. An entry is a mutex and a URL, and one process
     * sees a handful of project links. Evicting one would mean knowing that
     * nobody is waiting on it, and every cheap way of asking leaves a window
     * where two runs of the same project each take a different mutex — trading
     * a few dozen bytes for the exact race this map exists to prevent.
     */
    private val locks = HashMap<String, Mutex>()
    private val locksGuard = Mutex()

    override fun analyze(request: AnalyzeProjectRequest): Flow<AnalysisEvent> = channelFlow {
        val emit: (AnalysisPhase, String?) -> Unit = { phase, detail ->
            trySend(AnalysisEvent.Progress(AnalysisProgress(phase, detail)))
        }
        emit(AnalysisPhase.VALIDATING_URL, null)

        val outcome = try {
            runGuarded(request, emit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AnalysisCancelledException) {
            AnalysisOutcome.Cancelled
        } catch (e: Exception) {
            // A pipeline that throws is a bug or a page shaped in a way no rule anticipated.
            // Either way the caller gets a typed failure rather than an exception through a
            // Flow it cannot catch meaningfully.
            AnalysisOutcome.AnalysisFailed(null, e.toString())
        }
        send(AnalysisEvent.Completed(outcome))
    }
        // Progress is offered from the analysing coroutine and must never block it: a stage
        // report that had to wait for a collector would make the analysis run at the speed of
        // the UI.
        .buffer(Channel.UNLIMITED)
        .flowOn(dispatcher)

    private suspend fun runGuarded(
        request: AnalyzeProjectRequest,
        emit: (AnalysisPhase, String?) -> Unit,
    ): AnalysisOutcome {
        val verdict = UrlSafety.check(request.url) { host -> platform.resolve(host) }
        if (verdict is UrlSafety.Verdict.Rejected) {
            return when (verdict.reason) {
                UrlSafety.Rejection.UNSUPPORTED_HOST -> AnalysisOutcome.UnsupportedSource(
                    url = request.url,
                    host = verdict.detail,
                    reason = "host is not one of the supported sites",
                    supportedHosts = SUPPORTED_HOSTS,
                )
                else -> AnalysisOutcome.UnsafeUrl(request.url, verdict.reason, verdict.detail)
            }
        }

        val mutex = locksGuard.withLock { locks.getOrPut(AnalysisCache.normalizeUrl(request.url)) { Mutex() } }
        return mutex.withLock { runOnce(request, emit) }
    }

    private suspend fun runOnce(
        request: AnalyzeProjectRequest,
        emit: (AnalysisPhase, String?) -> Unit,
    ): AnalysisOutcome {
        if (request.cachePolicy != CachePolicy.REFRESH) {
            val cached = cache.lookupByUrl(request.url)
            if (cached != null) {
                val report = ProjectAnalysisReport.of(cached.snapshot, request.url, servedFromCache = true, generatedAtEpochMillis = clock())
                if (report != null) {
                    emit(AnalysisPhase.FINALIZING, "cache")
                    return classify(report)
                }
            }
            if (request.cachePolicy == CachePolicy.CACHE_ONLY) {
                return AnalysisOutcome.FetchFailed(request.url, "cache-only run and this project is not in the cache", emptyList())
            }
        }

        val session = cache.beginSession(request.url)
        var committed = false
        try {
            val run = pipeline(request, session, emit)
            currentCoroutineContext().ensureActive()

            val identity = run.resolution.identity
            val source = run.source
            if (identity == null || source == null) {
                val reachedThePage = run.resolution.steps.any { it.kind == ResolutionStep.Kind.FETCH }
                return if (reachedThePage) {
                    AnalysisOutcome.UnsupportedSource(
                        url = request.url,
                        host = null,
                        reason = run.resolution.failure ?: "the page is not a project page of a supported site",
                        supportedHosts = SUPPORTED_HOSTS,
                    )
                } else {
                    AnalysisOutcome.FetchFailed(request.url, run.resolution.failure ?: "the page could not be fetched", run.resolution.steps)
                }
            }

            val snapshot = run.snapshot(clock())
            val report = ProjectAnalysisReport.of(snapshot, request.url, servedFromCache = false, generatedAtEpochMillis = clock())
                ?: return AnalysisOutcome.AnalysisFailed(AnalysisPhase.FINALIZING, "the run resolved a page but produced no source package")

            val outcome = classify(report)
            // Published only here, and only for a run that read what it set out to read.
            //
            // A run whose drawings would not download, or whose page no longer has the shape the
            // adapter reads, is not stored: the next thing a person does after "the drawings did
            // not arrive" is tap again, and answering that instantly with the same failure from
            // disk would be worse than going back to the network. A complete run — including a
            // Partial one, which is a usable answer — is what a later run may replay.
            if (outcome is AnalysisOutcome.Success || outcome is AnalysisOutcome.Partial) {
                session.commit(identity.projectKey, snapshot)
                committed = true
            }
            return outcome
        } catch (e: AnalysisCancelledException) {
            return AnalysisOutcome.Cancelled
        } finally {
            if (!committed) session.discard()
        }
    }

    private suspend fun pipeline(
        request: AnalyzeProjectRequest,
        session: AnalysisCache.Session,
        emit: (AnalysisPhase, String?) -> Unit,
    ): AnalysisRun {
        val context = currentCoroutineContext()
        val network = if (request.cachePolicy == CachePolicy.CACHE_ONLY) null else platform.networkFetcher()
        val analyzer = ProjectAnalyzer(
            fetcher = session.fetcher(network, allowReplay = request.cachePolicy != CachePolicy.REFRESH),
            codec = platform.codec,
            storage = session.storage(),
            adapter = adapterFactory(),
            listener = AnalysisListener { stage, message -> emit(AnalysisPhase.of(stage), message) },
            cancellation = CancellationSignal { !context.isActive },
        )
        return analyzer.analyze(ProjectInput(request.url))
    }

    /**
     * Decides which outcome family a finished report belongs to.
     *
     * One rule, stated here rather than scattered: a run is degraded when
     * something it set out to *read* did not arrive, and not merely because
     * the answer is uncertain. Ambiguity and absent opening heights are the
     * normal, honest state of these sources — if they made a run partial, then
     * every run over a real house would be partial and the word would stop
     * carrying information.
     */
    private fun classify(report: ProjectAnalysisReport): AnalysisOutcome {
        val health = report.adapterHealth
        if (health.severity == AdapterSeverity.BROKEN) return AnalysisOutcome.SourceChanged(health, report)

        val plans = report.source?.assets?.plans.orEmpty()
        val failedPlans = plans.filter { it.retrieval != RetrievalState.DECODED }
        if (plans.isNotEmpty() && failedPlans.size == plans.size) {
            return AnalysisOutcome.AssetFailure(
                report,
                failedPlans.map { AssetFailureDetail(it.url, it.role.name, it.failure ?: it.retrieval.name) },
            )
        }

        val degraded = mutableListOf<AnalysisIssue>()
        degraded += report.issues.filter { it.severity == IssueSeverity.BLOCKING }
        health.degraded.forEach { degraded += AnalysisIssue(IssueSeverity.WARNING, "source", it.signal.name, "${it.signal.what}: ${it.evidence}") }
        health.absent.forEach { degraded += AnalysisIssue(IssueSeverity.WARNING, "source", it.signal.name, "${it.signal.what} not found: ${it.evidence}") }
        failedPlans.forEach { degraded += AnalysisIssue(IssueSeverity.WARNING, "assets", it.role.name, it.failure ?: "not decoded") }
        if (report.candidate == null) degraded += AnalysisIssue(IssueSeverity.BLOCKING, "candidate", null, "no building candidate was produced")
        else if (report.candidate?.roof == null) degraded += AnalysisIssue(IssueSeverity.WARNING, "roof", null, "the roof could not be solved")

        return if (degraded.isEmpty()) AnalysisOutcome.Success(report) else AnalysisOutcome.Partial(report, degraded)
    }

    private companion object {
        /** Every host any supported site may serve from, sorted, for a caller to show. */
        val SUPPORTED_HOSTS: List<String> = SupportedSite.entries.flatMap { it.allHosts }.sorted()
    }
}

/**
 * How the application gets a service without naming a site adapter.
 *
 * The cache directory has to be named after the versions of the things that
 * decide what a run answers, and one of those is the adapter — so the two are
 * built together here rather than leaving a caller to pair them correctly.
 */
object AnalyzerServices {

    fun create(
        platform: AnalyzerPlatform,
        /** App-private directory. Never external storage; nothing here asks for a permission. */
        cacheRoot: File,
        cachePolicy: AnalysisCachePolicy = AnalysisCachePolicy(),
        adapterFactory: () -> SiteAdapter = { ArchonSiteAdapter() },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = System::currentTimeMillis,
    ): ProjectAnalyzerService = DefaultProjectAnalyzerService(
        platform = platform,
        cache = AnalysisCache(cacheRoot, cacheTag(adapterFactory()), cachePolicy, clock),
        adapterFactory = adapterFactory,
        dispatcher = dispatcher,
        clock = clock,
    )

    /**
     * The version token a cache directory is named after: schema, analyzer and
     * adapter together.
     *
     * Anything that changes what a run would answer belongs in here, and
     * anything in here orphans the previous directory. That is the whole
     * defence against replaying an old parser's output as though it were
     * current.
     */
    fun cacheTag(adapter: SiteAdapter): String =
        ("s${ProjectAnalysisSnapshot.SCHEMA_VERSION}" +
            "-a${ProjectAnalysisSnapshot.ANALYZER_VERSION}" +
            "-${adapter.adapterId}${adapter.adapterVersion}")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
}
