package com.localagent.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.localagent.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
