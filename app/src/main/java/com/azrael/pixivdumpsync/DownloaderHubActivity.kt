package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Retains navigation compatibility for notifications created by older builds. */
class DownloaderHubActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this,AsterionHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
