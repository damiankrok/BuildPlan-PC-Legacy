package com.buildplan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.buildplan.app.ui.BuildPlanApp
import com.buildplan.app.ui.theme.BuildPlanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BuildPlanTheme {
                BuildPlanApp()
            }
        }
    }
}
