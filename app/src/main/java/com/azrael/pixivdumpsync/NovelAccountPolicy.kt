package com.azrael.pixivdumpsync

/** Treat explicit account, entitlement and interactive verification failures
 * as requiring a user action instead of repeatedly retrying in the background.
 * No credential is bundled into the APK.
 */
internal object NovelAccountPolicy {
    fun needsBrowserConnection(message:String):Boolean{
        val normalized=message.lowercase()
        return listOf("http 401","http 403","authenticated site session",
            "interactive browser verification","sign in","login required",
            "account required","access denied").any{it in normalized}
    }
}
