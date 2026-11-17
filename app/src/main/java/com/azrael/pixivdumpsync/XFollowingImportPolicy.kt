package com.azrael.pixivdumpsync

/** A suggested X profile is NOT a followed account, even when its avatar,
 * posts and username are visible on a /following page. Fail closed.
 */
internal object XFollowingImportPolicy {
    private val handlePattern=Regex("[A-Za-z0-9_]{1,15}")
    private val followingPath=Regex("^/([A-Za-z0-9_]{1,15})/following/?$",
        RegexOption.IGNORE_CASE)
    private val reserved=setOf("home","explore","search","following",
        "followers","notifications","messages","settings","premium")

    fun isFollowedAccount(handle:String,followingControlObserved:Boolean):Boolean =
        followingControlObserved && handlePattern.matches(handle) &&
            handle.lowercase() !in reserved

    /** Import only the owner's actual account list, never another user's
     * following page or a Home/For You/recommendations timeline.
     */
    /** The URL /alice/following is not proof that Alice is the signed-in
     * account: anybody can browse another user's list. Before importing,
     * cross-check X's own authenticated Profile navigation target. Failing
     * closed here prevents another account's "Following" buttons or suggested
     * cells from polluting the owner's subscriptions.
     */
    fun isAuthenticatedOwnFollowingPage(
        path:String,ownHandle:String,profileHref:String
    ):Boolean {
        if(!isOwnFollowingPage(path,ownHandle))return false
        val owner=profileHref.trim().substringBefore('?').trimEnd('/')
        if(!owner.matches(Regex("^/[A-Za-z0-9_]{1,15}$")))return false
        return owner.substring(1).equals(ownHandle,ignoreCase=true)
    }

    fun isOwnFollowingPage(path:String,ownHandle:String):Boolean {
        if(!handlePattern.matches(ownHandle))return false
        return followingPath.matchEntire(path)?.groupValues?.get(1)
            ?.equals(ownHandle,ignoreCase=true)==true
    }
}
