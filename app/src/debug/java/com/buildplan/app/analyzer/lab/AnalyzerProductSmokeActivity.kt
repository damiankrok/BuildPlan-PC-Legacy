package com.buildplan.app.analyzer.lab

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.ui.screens.ProjectImportScreen
import com.buildplan.app.ui.screens.ProjectImportViewModel
import com.buildplan.app.ui.theme.BuildPlanTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Runs the real product screen/service from a URL, without any architecture answers. */
class AnalyzerProductSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[ProjectImportViewModel::class.java]
        setContent { BuildPlanTheme { ProjectImportScreen(onOpenDrawer = {}, viewModel = model) } }
        if (savedInstanceState == null) {
            val start = SystemClock.elapsedRealtime()
            model.onUrlChanged(requireNotNull(intent.getStringExtra("url")))
            model.analyze()
            lifecycleScope.launch {
                val state = model.state.first { !it.running && it.outcome != null }
                val report = state.outcome?.reportOrNull
                val runtime = Runtime.getRuntime()
                val metrics = "outcome=${state.outcome?.javaClass?.simpleName}\nelapsedMs=${SystemClock.elapsedRealtime()-start}\nheapUsed=${runtime.totalMemory()-runtime.freeMemory()}\nheapMax=${runtime.maxMemory()}\nverificationOpened=${state.verification != null}\nrooms=${report?.candidate?.rooms?.size}\n"
                File(filesDir,"product-smoke.txt").writeText(metrics)
                report?.let { File(filesDir,"product-snapshot.json").writeText(SnapshotCodec.write(it.snapshot)) }
                Log.i("ProductSmoke",metrics)
                Log.i("ProductSmoke","COMPLETE")
            }
        }
    }
}
