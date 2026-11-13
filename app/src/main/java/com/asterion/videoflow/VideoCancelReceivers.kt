package com.asterion.videoflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cancellation is delivered explicitly to BOTH isolated transfer processes.
 * Static VideoEngine fields in the UI process cannot cancel futures owned
 * by :videoflow_primary or :videoflow_secondary.
 * Receivers are not exported; only our own application can invoke them.
 */
class VideoCancelPrimaryReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=VideoEngine.ACTION_CANCEL_LOCAL)return
        val id=intent.getLongExtra(VideoEngine.EXTRA_VIDEO_ID,-1L)
        if(id>0L)VideoEngine.cancelInThisProcess(id)
    }
}

class VideoCancelSecondaryReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=VideoEngine.ACTION_CANCEL_LOCAL)return
        val id=intent.getLongExtra(VideoEngine.EXTRA_VIDEO_ID,-1L)
        if(id>0L)VideoEngine.cancelInThisProcess(id)
    }
}
