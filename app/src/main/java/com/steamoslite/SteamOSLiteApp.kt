package com.steamoslite

import android.app.Application
import timber.log.Timber

class SteamOSLiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
