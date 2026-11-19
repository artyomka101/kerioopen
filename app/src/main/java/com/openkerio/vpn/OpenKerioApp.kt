package com.openkerio.vpn

import android.app.Application
import com.openkerio.vpn.data.AppSettings

class OpenKerioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Theme only — do not wrap locale here; applicationContext is unsafe in attachBaseContext.
        runCatching { AppSettings(this).applyTheme() }
    }
}
