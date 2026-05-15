package com.localagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.localagent.ui.AppNav
import com.localagent.ui.LocalAgentOnboarding
import com.localagent.ui.LocalAppContainer
import com.localagent.ui.theme.LocalAgentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LocalAgentApp
        app.container.deliverOAuthRedirect(intent)
        setContent {
            val onboardingDone by app.container.onboardingDismissed.collectAsStateWithLifecycle()
            val windowSizeClass = calculateWindowSizeClass(this)
            LocalAgentTheme {
                CompositionLocalProvider(
                    LocalAppContainer provides app.container
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (!onboardingDone) {
                            LocalAgentOnboarding(
                                onComplete = {
                                    lifecycleScope.launch { app.container.completeOnboarding() }
                                },
                            )
                        } else {
                            AppNav(
                                windowSizeClass = windowSizeClass,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as LocalAgentApp).container.deliverOAuthRedirect(intent)
    }
}
