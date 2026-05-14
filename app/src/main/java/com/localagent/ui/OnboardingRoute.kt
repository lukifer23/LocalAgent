package com.localagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localagent.R

/**
 * Guided first-run: Termux linkage, Hermes tab, Keys — non-technical path with clear steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAgentOnboarding(onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val total = 3
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_step_counter, step + 1, total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            AnimatedVisibility(
                visible = step == 0,
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut(),
            ) {
                OnboardingStepCard(
                    title = stringResource(R.string.onboarding_step1_title),
                    body = stringResource(R.string.onboarding_step1_body),
                )
            }
            AnimatedVisibility(
                visible = step == 1,
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut(),
            ) {
                OnboardingStepCard(
                    title = stringResource(R.string.onboarding_step2_title),
                    body = stringResource(R.string.onboarding_step2_body),
                )
            }
            AnimatedVisibility(
                visible = step == 2,
                enter = fadeIn() + slideInVertically { it / 4 },
                exit = fadeOut(),
            ) {
                OnboardingStepCard(
                    title = stringResource(R.string.onboarding_step3_title),
                    body = stringResource(R.string.onboarding_step3_body),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (step < total - 1) {
                    Button(
                        onClick = { step++ },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.onboarding_next))
                    }
                } else {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Text(
                            stringResource(R.string.onboarding_enter_app),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (step > 0) {
                    Button(
                        onClick = { step-- },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
