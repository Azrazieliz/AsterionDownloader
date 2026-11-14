package com.azrael.pixivdumpsync

/** Treat explicit account, entitlement and interactive verification failures
 * as requiring a user action instead of repeatedly retrying in the background.
 * No credential is bundled into the APK.
 */
internal object NovelAccountPolicy {
    fun needsBrowserConnection(message:String):Boolean{
        val normalized=message.lowercase()
        return listOf("http 401","authenticated site session",
            "sign in","login required","account required").any{it in normalized}
    }
}
