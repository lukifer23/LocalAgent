package com.localagent.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class EditorViewModel : ViewModel() {

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private var webView: WebView? = null

    fun setWebView(wv: WebView?) {
        webView = wv
    }

    fun openFile(file: File) {
        viewModelScope.launch {
            if (file.isFile && file.canRead()) {
                val text = file.readText()
                _currentFile.value = file
                _content.value = text
                _isModified.value = false
                webView?.evaluateJavascript("setContent(`${text.replace("`", "\\`").replace("$", "\\$")}`)", null)
            }
        }
    }

    fun saveFile() {
        viewModelScope.launch {
            val file = _currentFile.value ?: return@launch
            if (file.canWrite()) {
                file.writeText(_content.value)
                _isModified.value = false
            }
        }
    }

    fun onContentChanged(newContent: String) {
        _content.value = newContent
        _isModified.value = true
    }

    inner class EditorInterface {
        @JavascriptInterface
        fun onContentChanged(newContent: String) {
            this@EditorViewModel.onContentChanged(newContent)
        }
    }
}
