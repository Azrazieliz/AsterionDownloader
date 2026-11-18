package com.asterion.videoflow

import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/**
 * Public, normal-download form on the publisher-selected MP4Hentai ->
 * Mirrored.to -> Freedl.ink -> frdl.io -> frdl.hk file chain.
 *
 * The four forms on the observed frdl.hk HTML include a public DownloadForm
 * whose server-issued "hash" field can legitimately be empty. The public
 * POST can still return a separate CAPTCHA; that result is AUTH, never a
 * successful file and never permission to solve the challenge automatically.
 *
 * These checks only accept a form on the already verified selected frdl.hk
 * file landing page. This class cannot discover/guess file IDs or bypass a
 * human gate.
 */
internal object FreedlSelectedOriginal {
    internal data class PublicFreeForm(val action:String,val fields:Map<String,String>)
    private val expected=setOf("op","id","hash","rand","referer",
        "download_free","adblock_detected","hls_direct")
    private val idPattern=Regex("""^/[A-Za-z0-9_-]{6,100}/?$""")

    internal fun isSelectedFilePage(current:String):Boolean {
        val u=runCatching{URI(current)}.getOrNull()?:return false
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.fragment==null && u.rawQuery.isNullOrBlank() &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="frdl.hk" &&
            idPattern.matches(u.path.orEmpty())
    }

    internal fun publicFreeForm(document:Document,current:String):PublicFreeForm? {
        if(!isSelectedFilePage(current))return null
        val forms=document.select("form#DownloadForm")
        if(forms.size!=1)return null
        val form=forms.single()
        if(!form.attr("method").equals("post",ignoreCase=true))return null
        // A challenge *inside* the one public free form means user action
        // is mandatory. Other forms on the page may be for the later step.
        if(form.select("input[name*=captcha],.cf-turnstile,.h-captcha,"+
                ".g-recaptcha,[data-sitekey]").isNotEmpty())return null
        val target=runCatching{
            URI(current).resolve(form.attr("action").trim()).toString()
        }.getOrNull()?:return null
        if(!isSelectedFilePage(target) ||
            URI(target).path.trimEnd('/')!=URI(current).path.trimEnd('/'))
            return null
        val inputs=form.select("input[name]")
        if(inputs.size!=expected.size)return null
        if(inputs.any{
                !it.attr("type").equals("hidden",ignoreCase=true)
            })return null
        val fields=LinkedHashMap<String,String>()
        for(node in inputs){
            val name=node.attr("name")
            if(name !in expected || fields.put(name,node.attr("value"))!=null)
                return null
            if(node.attr("value").length>3000)return null
        }
        if(fields.keys!=expected || fields["id"].isNullOrBlank())return null
        // The server's POST id is opaque and need not equal the public route.
        // It is accepted ONLY from this already verified same-file DOM and
        // same-page form action; never reconstruct or substitute an ID.
        if(form.select("button").none{
                Regex("""(?i)download|free""").containsMatchIn(it.text())
            })return null
        return PublicFreeForm(target,fields)
    }

    internal fun selectedMediaRedirect(from:String,to:String):Boolean {
        if(!isSelectedFilePage(from))return false
        val u=runCatching{URI(to)}.getOrNull()?:return false
        return u.scheme=="https" && u.host!=null && u.userInfo==null &&
            u.port==-1 && u.fragment==null &&
            NativeMediaDownloader.isDirectMediaUrl(to) &&
            !FullLengthVideoPolicy.isExplicitPreview("",to)
    }
}
