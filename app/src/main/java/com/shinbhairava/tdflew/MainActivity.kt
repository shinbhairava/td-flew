package com.shinbhairava.tdflew

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinbhairava.tdflew.engine.AuthStep
import com.shinbhairava.tdflew.ui.MainShell
import com.shinbhairava.tdflew.ui.auth.AuthScreen
import com.shinbhairava.tdflew.ui.theme.TdFlewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TdFlewApplication).container
        setContent {
            TdFlewTheme {
                val authStep by container.telegram.authStep.collectAsStateWithLifecycle()
                if (authStep is AuthStep.Ready) {
                    MainShell(container)
                } else {
                    AuthScreen(
                        step = authStep,
                        manager = container.telegram,
                    )
                }
            }
        }
    }
}
