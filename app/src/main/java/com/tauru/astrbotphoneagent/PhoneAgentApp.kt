package com.tauru.astrbotphoneagent

import android.app.Application
import com.tauru.astrbotphoneagent.data.RealAppState

class PhoneAgentApp : Application() {

    lateinit var appState: RealAppState
        private set

    override fun onCreate() {
        super.onCreate()
        appState = RealAppState(this)
    }
}
