package com.buildplan.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.buildplan.app.ui.BuildPlanApp
import com.buildplan.app.ui.theme.BuildPlanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark-only, so the system bars are told so rather than
        // left to follow the device's light theme: light icons over the
        // workspace's dark top edge, whatever the device is set to.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            BuildPlanTheme {
                BuildPlanApp()
            }
        }
    }
}
