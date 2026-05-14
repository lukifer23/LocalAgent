package com.localagent.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.localagent.R
import com.localagent.runtime.HermesTerminalService
import com.localagent.runtime.TerminalSessionHandle
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalRoute(rows: Int = 28, cols: Int = 90) {
    val context = LocalContext.current
    var handle by remember { mutableStateOf<TerminalSessionHandle?>(null) }

    val scheme = MaterialTheme.colorScheme
    val bg = scheme.surfaceContainerHighest
    val fg = scheme.onSurface

    val emulator =
        remember(bg, fg) {
            TerminalEmulatorFactory.create(
                Looper.getMainLooper(),
                initialRows = rows,
                initialCols = cols,
                defaultForeground = fg,
                defaultBackground = bg,
                onKeyboardInput = { bytes -> handle?.send(bytes) },
                onResize = { dims -> handle?.resize(dims.rows, dims.columns) },
            )
        }

    DisposableEffect(context) {
        HermesTerminalService.start(context, rows, cols)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    handle = (binder as HermesTerminalService.LocalBinder).handle()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    handle = null
                }
            }
        context.bindService(Intent(context, HermesTerminalService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(connection)
        }
    }

    LaunchedEffect(handle, emulator) {
        val h = handle ?: return@LaunchedEffect
        h.output.collect { chunk ->
            emulator.writeInput(chunk, 0, chunk.size)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.terminal_title))
                        Text(
                            stringResource(R.string.terminal_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Terminal(
            terminalEmulator = emulator,
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            backgroundColor = bg,
            foregroundColor = fg,
            keyboardEnabled = true,
            showSoftKeyboard = true,
        )
    }
}
