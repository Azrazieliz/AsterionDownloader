package com.azrael.pixivdumpsync

import android.app.Application

/**
 * Install crash capture before any Activity, Service or background worker runs.
 * This also covers the isolated VideoFlow primary and secondary processes,
 * unlike installing it only when the Home screen is opened.
 */
class AsterionApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
    }
}
