package com.localagent

import android.app.Application
import com.localagent.di.AppContainer

class LocalAgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
