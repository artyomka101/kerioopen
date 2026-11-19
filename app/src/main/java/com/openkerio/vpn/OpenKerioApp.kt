package com.openkerio.vpn

import android.app.Application
import android.content.Context
import com.openkerio.vpn.data.AppSettings

class OpenKerioApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppSettings.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppSettings(this).applyTheme()
    }
}
