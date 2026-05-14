package com.localagent

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localagent.ui.AppNav
import com.localagent.ui.LocalAppContainer
import com.localagent.ui.theme.LocalAgentTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersChatTab() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as LocalAgentApp
        composeRule.setContent {
            LocalAgentTheme {
                CompositionLocalProvider(LocalAppContainer provides app.container) {
                    AppNav(modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeRule.onNodeWithText("Agent stream").assertExists()
    }
}
