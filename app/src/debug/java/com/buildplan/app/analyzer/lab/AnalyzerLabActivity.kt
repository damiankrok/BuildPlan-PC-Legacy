package com.buildplan.app.analyzer.lab

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.buildplan.app.R
import com.buildplan.app.analyzer.AndroidRasterCodec
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.pipeline.AnalysisListener
import com.buildplan.app.analyzer.pipeline.AnalysisRun
import com.buildplan.app.analyzer.pipeline.ProjectAnalyzer
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.analyzer.source.HttpResourceFetcher
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.validate.ValidationStatus
import com.buildplan.app.render.filament.FilamentCanvas
import com.buildplan.app.render.filament.SpikeVisibility
import com.buildplan.app.render.filament.rememberModelScene
import com.buildplan.app.ui.theme.BuildPlanTheme
import com.buildplan.app.ui.workspace.LocalMotionPolicy
import com.buildplan.app.ui.workspace.rememberSystemMotionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Analyzer Lab: a debug-only harness that runs the project analyzer on a
 * URL and shows what it found — facts, assets, rooms, quantities, the
 * reconciliation against the page, the gaps and questions, the log, and a
 * candidate preview on the renderer.
 *
 * Its own launcher entry, declared only in the debug manifest, so nothing
 * of it reaches the owner's workspace or a release build. Evidence tooling,
 * not product UX: lists and text, no chrome.
 */
class AnalyzerLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            BuildPlanTheme {
                CompositionLocalProvider(LocalMotionPolicy provides rememberSystemMotionPolicy()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AnalyzerLabScreen() }
                }
            }
        }
    }
}

private enum class LabTab(val labelRes: Int) {
    SUMMARY(R.string.lab_tab_summary),
    ROOMS(R.string.lab_tab_rooms),
    QUANTITIES(R.string.lab_tab_quantities),
    VALIDATION(R.string.lab_tab_validation),
    QUESTIONS(R.string.lab_tab_questions),
    ASSETS(R.string.lab_tab_assets),
    LOG(R.string.lab_tab_log),
    PREVIEW(R.string.lab_tab_preview),
}

/** The two evaluation inputs of this stage, as quick buttons. Lab convenience only; the analyzer core never sees them. */
private val quickInputs = listOf(
    "A" to "https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca",
    "B" to "https://www.archon.pl/projekty-domow/projekt-dom-pod-wiazowcem-n-ver-2-md6a1fa1493ad8to",
)

@Composable
private fun AnalyzerLabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val stages = remember { mutableStateListOf<String>() }
    var run by remember { mutableStateOf<AnalysisRun?>(null) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(LabTab.SUMMARY) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        if (running || url.isBlank()) return
        running = true
        stages.clear()
        run = null
        exportPath = null
        error = null
        val input = url
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val storage = LabStorage(context)
                    val analyzer = ProjectAnalyzer(
                        fetcher = HttpResourceFetcher(),
                        codec = AndroidRasterCodec,
                        storage = storage,
                        listener = AnalysisListener { stage, message -> scope.launch { stages += "${stage.name}: $message" } },
                    )
                    val r = analyzer.analyze(ProjectInput(input))
                    val file = storage.writeText("${r.resolution.identity?.projectKey ?: "unresolved"}/snapshot.json", SnapshotCodec.write(r.snapshot()))
                    r to file.absolutePath
                }
            }
            result.onSuccess { (r, path) -> run = r; exportPath = path }
                .onFailure { error = it.toString() }
            running = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.lab_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.lab_url_label)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            quickInputs.forEach { (label, quick) -> OutlinedButton(onClick = { url = quick }) { Text(stringResource(R.string.lab_quick_project, label)) } }
            Button(onClick = ::start, enabled = !running && url.isNotBlank()) { Text(stringResource(R.string.lab_run)) }
        }
        if (running) {
            Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Text(stages.lastOrNull() ?: stringResource(R.string.lab_starting), style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        exportPath?.let { Text(stringResource(R.string.lab_exported, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val current = run
        if (current != null) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabTab.entries.forEach { t -> FilterChip(selected = t == tab, onClick = { tab = t }, label = { Text(stringResource(t.labelRes)) }) }
            }
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    LabTab.PREVIEW -> PreviewTab(current)
                    else -> LinesTab(linesFor(tab, current, stages))
                }
            }
        } else if (stages.isNotEmpty()) {
            LinesTab(stages.toList())
        }
    }
}

@Composable
private fun LinesTab(lines: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(lines) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun PreviewTab(run: AnalysisRun) {
    val candidate = run.candidate
    val preview = remember(run) { candidate?.let { CandidatePreview.of(it) } }
    if (preview == null) {
        Text(stringResource(R.string.lab_preview_unavailable), style = MaterialTheme.typography.bodyMedium)
        return
    }
    key(preview) {
        val scene = rememberModelScene(preview)
        if (scene == null) {
            Text(stringResource(R.string.lab_preview_unavailable), style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpikeVisibility.entries.forEach { option ->
                        FilterChip(selected = option == scene.visibility, onClick = { scene.showVisibility(option) }, label = { Text(stringResource(option.labelRes)) })
                    }
                }
                Text(
                    text = scene.selectedElement?.name ?: stringResource(R.string.lab_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                FilamentCanvas(scene = scene, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun linesFor(tab: LabTab, run: AnalysisRun, stages: List<String>): List<String> = when (tab) {
    LabTab.SUMMARY -> buildList {
        add("Wejście: ${run.input.rawUrl}")
        run.resolution.steps.forEach { add("${it.kind}: ${it.detail}") }
        run.source?.let { s ->
            add("")
            add("Projekt: ${s.title} (${s.identity.canonicalUrl})")
            add("Tagi: ${s.siteTags.filterKeys { it.startsWith("project") }}")
            add("")
            add("Fakty ze strony (${s.scalars.count { it.measured.value != null }}):")
            s.scalars.filter { it.measured.value != null }.forEach { add("  ${it.rawLabel}: ${it.rawValue}  [${it.key}, ${it.measured.fidelity}]") }
            add("")
            s.construction.forEach { add("  ${it.label}: ${it.text}") }
        }
        run.candidate?.let { c ->
            add("")
            add("Kompletność: ${run.gaps?.completenessScore?.let { "%.0f %%".format(it * 100) } ?: "—"}")
            run.gaps?.statuses?.forEach { add("  ${it.state} ${it.requirement}: ${it.evidence}") }
            add("")
            add("Rzędne:")
            listOf("teren" to c.levels.terrain, "parter" to c.levels.groundFloor, "poddasze" to c.levels.upperFloor, "okap" to c.levels.eave, "kalenica" to c.levels.ridge, "parter w świetle" to c.levels.groundClearHeight, "ścianka kolankowa" to c.levels.kneeWall)
                .forEach { (n, m) -> add("  $n: ${m.value?.let { "%.2f m".format(it) } ?: "brak"} [${m.fidelity}]") }
            c.roof?.let { r -> add("Dach: ${r.family}, ${r.facets.size} połaci, ${"%.1f".format(java.util.Locale.ROOT, r.totalArea.value)} m², ${r.pitchDegrees.value}°, ${r.note}") }
            add("Ściany: ${c.walls.size}, otwory: ${c.openings.size}, schody: ${c.stairs.size}")
            // The correctness work of STAGE-023B, where it can be seen at a glance: how many rooms
            // have a proved outline, what told us a stair is there, and how many opening heights
            // the drawings actually carry.
            val unresolved = c.rooms.filter { it.geometryState == RoomGeometryState.UNRESOLVED_REGION }
            add("Obrysy pomieszczeń: ${c.rooms.size - unresolved.size}/${c.rooms.size} to poprawne pierścienie proste")
            unresolved.forEach { add("  BEZ OBRYSU ${it.id} ${it.name}: ${it.geometryNote}") }
            c.stairs.forEach { s ->
                add("  Schody ${s.id} na ${s.floorId}${s.toFloorId?.let { t -> " → $t" } ?: ""}: ${s.evidence.joinToString("+")} [${s.fidelity}], stopni ${s.treadCount.value?.let { n -> "%.0f".format(n) } ?: "nieodczytane"}")
                s.unresolved.forEach { u -> add("      ? $u") }
            }
            val heights = c.openings.count { it.height.value != null }
            add("Wysokości otworów odczytane z rysunku: $heights/${c.openings.size}")
        }
        add("")
        add("Czas [ms]: ${run.timingsMillis}")
    }
    LabTab.ROOMS -> buildList {
        run.candidate?.floors?.forEach { f ->
            add("${f.name} (${f.id}) — skala ${f.calibration?.pixelsPerMeter?.value?.let { "%.2f px/m".format(it) } ?: "brak"} ${f.calibration?.confidence ?: ""}, rzędna ${f.floorElevation.value?.let { "%.2f".format(java.util.Locale.ROOT, it) }}")
            f.footprint?.let { add("  obrys: ${"%.2f".format(java.util.Locale.ROOT, it.bounds.width)} × ${"%.2f".format(java.util.Locale.ROOT, it.bounds.depth)} m, ${it.vertices.size} narożników, ${"%.1f".format(java.util.Locale.ROOT, it.area)} m²") }
            f.rooms.forEach { r ->
                val q = run.quantities?.rooms?.firstOrNull { it.roomId == r.id }
                add("  ${r.sourceOrdinal ?: "?"}. ${r.name} [${r.id}] ${"%.2f".format(java.util.Locale.ROOT, r.plannedArea.value)} m² (źródło ${r.sourceFloorArea.value ?: r.sourceUsableArea.value}) ${r.matchConfidence} ${r.geometryState}")
                if (r.geometryState == RoomGeometryState.UNRESOLVED_REGION) add("      obrys nierozstrzygnięty: ${r.geometryNote}")
                r.matchAlternatives.forEach { alt -> add("      mogłoby też być: wiersz ${alt.sourceRowIndex + 1} „${alt.roomName}” (${"%.2f".format(java.util.Locale.ROOT, alt.publishedAreaM2)} m2, ${"%.1f".format(java.util.Locale.ROOT, alt.relativeError * 100)} %): ${alt.why}") }
                add("      obwód ${"%.2f".format(java.util.Locale.ROOT, r.perimeter.value)} m, ściany brutto ${"%.1f".format(java.util.Locale.ROOT, q?.wallGross?.value)} / netto ${"%.1f".format(java.util.Locale.ROOT, q?.wallNet?.value)} m², sufit płaski ${"%.1f".format(java.util.Locale.ROOT, q?.ceilingFlat?.value)} + skosy ${"%.1f".format(java.util.Locale.ROOT, q?.ceilingSloped?.value)} m², kubatura ${"%.1f".format(java.util.Locale.ROOT, q?.volume?.value)} m³")
                r.boundary.forEachIndexed { i, b -> add("      ściana ${i + 1}: ${"%.2f".format(java.util.Locale.ROOT, b.segment.length)} m → ${b.neighbourRoomId ?: if (b.faceOutside) "zewnątrz" else "?"} (${b.wallId ?: "brak ściany"})") }
            }
            f.unmatchedRegions.forEach { add("  region bez pomieszczenia ${it.id}: ${"%.2f".format(java.util.Locale.ROOT, it.areaM2)} m²") }
        }
    }
    LabTab.QUANTITIES -> buildList {
        run.quantities?.let { q ->
            q.floors.forEach { f -> add("${f.floorId}: podłogi ${"%.1f".format(java.util.Locale.ROOT, f.roomFloorAreaSum.value)} m², ściany zewn. ${"%.1f".format(java.util.Locale.ROOT, f.exteriorWallsStructural.value)} m², nośne wewn. ${"%.1f".format(java.util.Locale.ROOT, f.loadBearingWallsStructural.value)} m², działowe ${"%.1f".format(java.util.Locale.ROOT, f.partitionsStructural.value)} m², otwory ${f.openingAreasByType.mapValues { "%.1f".format(java.util.Locale.ROOT, it.value) }}") }
            add("Dach: ${"%.1f".format(java.util.Locale.ROOT, q.roofTotal.value)} m², kalenice ${"%.1f".format(java.util.Locale.ROOT, q.ridgeLength.value)} m, naroża/kosze ${"%.1f".format(java.util.Locale.ROOT, q.hipLength.value)} m, okapy ${"%.1f".format(java.util.Locale.ROOT, q.eaveLength.value)} m")
            q.roofFacetAreas.forEach { (id, a) -> add("  $id: ${"%.1f".format(java.util.Locale.ROOT, a)} m²") }
            add("Stolarka zewn. (założone wysokości): ${"%.1f".format(java.util.Locale.ROOT, q.exteriorJoinery.value)} m²; elewacja brutto ${"%.1f".format(java.util.Locale.ROOT, q.facadeGross.value)} / netto ${"%.1f".format(java.util.Locale.ROOT, q.facadeNet.value)} m²; podłogi+schody ${"%.1f".format(java.util.Locale.ROOT, q.floorsAndStairsArea.value)} m²")
            add("")
            add("Powierzchnie (${q.surfaces.size}):")
            q.surfaces.forEach { s -> add("  ${s.id} ${s.type} brutto ${"%.2f".format(java.util.Locale.ROOT, s.grossArea.value)} − ${"%.2f".format(java.util.Locale.ROOT, s.deductions.value)} = ${"%.2f".format(java.util.Locale.ROOT, s.netArea.value)} m² [${s.netArea.fidelity}] ${s.neighbourRoomId?.let { "↔ $it" } ?: if (s.facesOutside) "↔ zewnątrz" else ""}") }
            q.notes.forEach { add("Uwaga: $it") }
        }
    }
    LabTab.VALIDATION -> run.validations.map { v -> "${v.status} ${v.subject}: ${v.candidateValue?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "—"} vs ${v.sourceValue ?: "—"} ${v.unit} ${v.relativeDifference?.let { "(%.1f %%)".format(it * 100) } ?: ""} — ${v.semantics}" } +
        listOf("", "Podsumowanie: ${run.validations.groupingBy { it.status }.eachCount()}", "Progi: silne ≤ 5 %, akceptowalne ≤ 15 %; ${ValidationStatus.NOT_COMPARABLE} = różne pojęcia; ${ValidationStatus.INSUFFICIENT_SOURCE} = brak danych albo założenie po stronie kandydata.")
    LabTab.QUESTIONS -> run.questions.map { "[${it.id}] ${it.text}${it.currentAssumption?.let { a -> "  (założenie: $a)" } ?: ""}" } +
        listOf("") + (run.candidate?.issues?.map { "${it.severity} ${it.stage}: ${it.message}" } ?: emptyList())
    LabTab.ASSETS -> run.assets?.manifest?.assets?.map { a -> "${a.role} ${a.retrieval} ${a.widthPx ?: "-"}×${a.heightPx ?: "-"} ${a.byteCount ?: "-"} B sha=${a.sha256?.take(12) ?: "-"} ${a.url}${a.failure?.let { " ! $it" } ?: ""}" } ?: emptyList()
    LabTab.LOG -> stages + listOf("") + run.log
    LabTab.PREVIEW -> emptyList()
}
