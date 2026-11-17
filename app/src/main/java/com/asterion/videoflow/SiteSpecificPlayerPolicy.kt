package com.asterion.videoflow

import org.jsoup.nodes.Document

/** Recognize actual site access gates without mistaking a selected player's
 * ordinary controls for a challenge. Never mislabel a verification page as
 * a playable original or attempt to defeat the verification step.
 */
internal object SiteSpecificPlayerPolicy {
    fun accessGate(doc:Document):Boolean {
        if(doc.select("form[action*=captcha],.cf-turnstile,.h-captcha,"+
                ".g-recaptcha,#challenge-form,[data-sitekey],"+
                "form#file-captcha").isNotEmpty())return true
        val title=doc.title().trim().lowercase()
        val text=doc.body()?.text()?.take(900)?.lowercase().orEmpty()
        val phrases=listOf("just a moment","verify you are human",
            "checking your browser","access denied","captcha verification",
            "cloudflare security verification")
        return phrases.any{it in title} ||
            (doc.select("video[src],video source[src],iframe[src]").isEmpty() &&
                phrases.any{it in text})
    }
}
