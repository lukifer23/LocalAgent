package com.localagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import android.widget.Toast
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localagent.R
import com.localagent.auth.OAuthCoordinator
import com.localagent.auth.CredentialVault
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private object Routes {
    const val CHAT = "chat"
    const val HERMES = "hermes"
    const val TERMINAL = "terminal"
    const val PROVIDERS = "providers"
    const val LOCAL_MODEL = "local_model"
    const val SETTINGS = "settings"
    const val WORKSTATION = "workstation" // Dual-pane view
}

private data class TabSpec(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val tabs =
    listOf(
        TabSpec(Routes.CHAT, R.string.tab_chat, Icons.Filled.Chat),
        TabSpec(Routes.HERMES, R.string.tab_hermes, Icons.Filled.CloudDownload),
        TabSpec(Routes.TERMINAL, R.string.tab_terminal, Icons.Filled.Code),
        TabSpec(Routes.PROVIDERS, R.string.tab_keys, Icons.Filled.VpnKey),
        TabSpec(Routes.LOCAL_MODEL, R.string.tab_local, Icons.Filled.Memory),
        TabSpec(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
    )

@Composable
fun AppNav(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    LaunchedEffect(activity, container) {
        val act = activity ?: return@LaunchedEffect
        container.oauthRedirectIntents.collect { redir ->
            suspendCoroutine { cont ->
                OAuthCoordinator(container.credentialVault).completeAuthorization(
                    act,
                    redir,
                    container.credentialVault.get(CredentialVault.OAUTH_PENDING_CLIENT_SECRET),
                ) { res ->
                    if (res.isSuccess) {
                        container.envWriter.syncFromVault(container.credentialVault.snapshot())
                        Toast
                            .makeText(act, context.getString(R.string.providers_oauth_connected), Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        Toast
                            .makeText(
                                act,
                                res.exceptionOrNull()?.message
                                    ?: context.getString(R.string.providers_oauth_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    cont.resume(Unit)
                }
            }
        }
    }

    NavigationSuiteScaffold(
        modifier = modifier,
        layoutType = if (isExpanded) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        navigationSuiteItems = {
            tabs.forEach { tab ->
                item(
                    selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                    onClick = {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(tab.icon, contentDescription = stringResource(tab.labelRes))
                    },
                    label = { Text(stringResource(tab.labelRes)) },
                )
            }
            if (isExpanded) {
                // Add a "Workstation" toggle only in expanded mode
                item(
                    selected = destination?.hierarchy?.any { it.route == Routes.WORKSTATION } == true,
                    onClick = {
                        navController.navigate(Routes.WORKSTATION) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(Icons.Filled.Code, contentDescription = "Workstation")
                    },
                    label = { Text("Workstation") }
                )
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.CHAT) { ChatRoute() }
            composable(Routes.HERMES) { HermesSetupRoute() }
            composable(Routes.TERMINAL) { TerminalRoute() }
            composable(Routes.PROVIDERS) { ProvidersRoute() }
            composable(Routes.LOCAL_MODEL) { LocalModelRoute() }
            composable(Routes.SETTINGS) { SettingsRoute() }
            composable(Routes.WORKSTATION) { WorkstationView() }
        }
    }
}

@Composable
fun WorkstationView() {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            ChatRoute()
        }
        VerticalDivider(Modifier.width(1.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            TerminalRoute()
        }
    }
}
