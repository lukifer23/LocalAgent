package com.localagent.ui

import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localagent.runtime.HermesPaths
import java.io.File

@Composable
fun EditorRoute() {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel()
    val currentFile by vm.currentFile.collectAsStateWithLifecycle()
    val isModified by vm.isModified.collectAsStateWithLifecycle()

    var showExplorer by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showExplorer = !showExplorer }) {
                Icon(Icons.Default.Folder, contentDescription = "Explorer")
            }
            Text(
                text = currentFile?.name ?: "No file open",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall
            )
            if (isModified) {
                Text("*", color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = { vm.saveFile() }, enabled = currentFile != null) {
                Icon(Icons.Default.Save, contentDescription = "Save")
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxSize()) {
            if (showExplorer) {
                FileExplorer(
                    modifier = Modifier.weight(0.3f),
                    onFileSelected = {
                        vm.openFile(it)
                        showExplorer = false
                    }
                )
                Divider(Modifier.padding(vertical = 16.dp))
            }
            EditorWebView(
                modifier = Modifier.weight(0.7f),
                vm = vm
            )
        }
    }
}

@Composable
fun FileExplorer(modifier: Modifier, onFileSelected: (File) -> Unit) {
    val context = LocalContext.current
    val root = remember { HermesPaths.syntheticHome(context) }
    var currentDir by remember { mutableStateOf(root) }
    val files = remember(currentDir) { currentDir.listFiles()?.toList()?.sortedBy { it.name } ?: emptyList() }

    LazyColumn(modifier) {
        if (currentDir != root) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { currentDir = currentDir.parentFile ?: root }
                        .padding(8.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Text("..", Modifier.padding(start = 8.dp))
                }
            }
        }
        items(files) { file ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (file.isDirectory) {
                            currentDir = file
                        } else {
                            onFileSelected(file)
                        }
                    }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.FileOpen,
                    contentDescription = null
                )
                Text(file.name, Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun EditorWebView(modifier: Modifier, vm: EditorViewModel) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                }
                webViewClient = WebViewClient()
                addJavascriptInterface(vm.EditorInterface(), "AndroidEditor")
                loadUrl("file:///android_asset/editor/index.html")
                vm.setWebView(this)
            }
        },
        modifier = modifier
    )
}
