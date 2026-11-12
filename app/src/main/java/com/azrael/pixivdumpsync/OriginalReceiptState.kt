package com.azrael.pixivdumpsync

import android.content.Context

/** Untrusted HTTP success never appears as a verified saved original. */
internal object OriginalReceiptState {
    private const val PREF="asterion_direct_receipt_state_v1"
    private fun key(id:Long)="download:"+id
    fun get(context:Context,id:Long):String =
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(key(id),"UNVERIFIED").orEmpty()
    fun set(context:Context,id:Long,state:String){
        require(state in setOf("VERIFIED","INVALID","UNVERIFIED","STALLED","MISSING"))
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(key(id),state).apply()
    }
    fun clear(context:Context,id:Long){
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().remove(key(id)).apply()
    }
}
