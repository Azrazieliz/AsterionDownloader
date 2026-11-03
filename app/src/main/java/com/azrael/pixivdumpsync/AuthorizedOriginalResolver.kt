package com.azrael.pixivdumpsync

import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** Extracts only ordinary download links exposed to the current authenticated session.
 * Never fabricates subscriber access, unwraps DRM, or impersonates authorization.
 */
data class AccessibleOriginal(val url:String,val filename:String,val kind:String,
    val referer:String)

enum class OriginalAccessCause { SIGN_IN, ENTITLEMENT, RATE_LIMIT, NOT_FOUND, NO_ORIGINAL }

class OriginalAccessException(val reason:OriginalAccessCause,message:String):
    IllegalStateException(message)

object AuthorizedOriginalResolver {
    private val extensions=mapOf(
        "epub" to setOf("epub"),
        "video" to setOf("mp4","webm","m4v","mov"),
        "image" to setOf("jpg","jpeg","png","webp","gif","avif"),
        "model" to setOf("blend","fbx","obj","glb","gltf","stl","unitypackage",
            "gguf","safetensors","onnx","qnn","tflite","pte","ckpt","pt","pth"),
        "archive" to setOf("zip","7z","rar"),
        "game" to setOf("apk","torrent","zip","7z","rar")
    )
    internal fun mediaType(url:String):String {
        val path=runCatching{URI(url).path.orEmpty().lowercase(Locale.ROOT)}.getOrDefault("")
        val ext=path.substringAfterLast('.',missingDelimiterValue="")
        return extensions.entries.firstOrNull{ext in it.value}?.key.orEmpty()
    }
    private fun kindAccepts(hint:String,kind:String):Boolean{
        val type=hint.lowercase(Locale.ROOT)
        return when(type){
            "books","epub"->kind=="epub"
            "video","videos"->kind=="video"
            "adult","hentai"->kind in setOf("video","image","archive")
            "cosplay","artwork","images"->kind=="image"
            "assets","model"->kind in setOf("model","archive")
            "games","torrent"->kind in setOf("game","archive")
            "comics"->kind=="archive"
            else->kind.isNotBlank()
        }
    }
    internal fun parse(html:String,page:String,hint:String):AccessibleOriginal?{
        val doc=Jsoup.parse(html,page)
        val candidates=LinkedHashMap<String,Pair<Int,String>>()
        val filenames=HashMap<String,String>()
        fun add(raw:String,weight:Int,nameHint:String=""){
            val normalized=runCatching{URI(page).resolve(raw.trim()).toString()}.getOrNull()
                ?:return
            if(!SearchRanking.validUrl(normalized))return
            val type=mediaType(normalized).ifBlank{
                // A signed download URL may omit the extension; trust only a
                // download filename explicitly supplied by the page.
                mediaType(nameHint)
            }
            if(!kindAccepts(hint,type))return
            val path=URI(normalized).path.lowercase(Locale.ROOT)
            if(listOf("trailer","sample","preview","thumbnail","sprite","avatar","poster")
                    .any{it in path})return
            candidates[normalized]=maxOf(candidates[normalized]?.first?:0,weight) to type
            if(nameHint.isNotBlank())filenames[normalized]=nameHint
        }
        for(tag in doc.select("a[download][href],.download a[href],.attachment a[href],"+
            "a[href*=download],a[href$=.epub],a[href$=.zip],a[href$=.apk],"+
            "a[href$=.blend],a[href$=.fbx],a[href$=.mp4],a[href$=.webm]"))
            add(tag.attr("href"),if(tag.hasAttr("download"))100 else 75,
                tag.attr("download").ifBlank{tag.attr("data-filename")})
        for(tag in doc.select("a[data-download-url],button[data-download-url],"+
            "a[data-file-url],button[data-file-url]")){
            val url=tag.attr("data-download-url").ifBlank{tag.attr("data-file-url")}
            val name=tag.attr("download").ifBlank{tag.attr("data-filename")}
            add(url,85,name)
        }
        for(tag in doc.select("video[src],video source[src],source[type^=video][src]"))
            add(tag.attr("src"),90)
        for(tag in doc.select("meta[property=og:video],meta[property=og:video:url],"+
            "meta[itemprop=contentUrl]"))add(tag.attr("content"),70)
        for(tag in doc.select("a[href]")){
            val link=tag.attr("href")
            if(mediaType(link).isNotBlank())add(link,40)
        }
        val best=candidates.maxByOrNull{it.value.first}?:return null
        val path=filenames[best.key].orEmpty().ifBlank{
            URI(best.key).path.substringAfterLast('/')
        }
        val name=path.replace(Regex("[^A-Za-z0-9._-]"),"_").take(100)
            .ifBlank{"original.bin"}
        return AccessibleOriginal(best.key,name,best.value.second,page)
    }
    fun resolve(pageOrFile:String,hint:String):AccessibleOriginal {
        require(SearchRanking.validUrl(pageOrFile)){"Invalid download source URL"}
        val kind=mediaType(pageOrFile)
        if(kindAccepts(hint,kind)){
            val name=URI(pageOrFile).path.substringAfterLast('/').take(100)
            return AccessibleOriginal(pageOrFile,name,kind,pageOrFile)
        }
        // Session-authorized downloads may be signed URLs without an extension.
        // Inspect only response headers before treating them as HTML listings.
        runCatching{AuthorizedEndpointInspector.inspect(pageOrFile,hint)}
            .getOrNull()?.let{return it}
        val request=Jsoup.connect(pageOrFile).timeout(17000).maxBodySize(3_000_000)
            .userAgent("Mozilla/5.0 (Linux; Android 17) AsterionDownloader/1.3")
            .header("Accept","text/html,application/xhtml+xml")
            .ignoreHttpErrors(true).followRedirects(true)
        SiteSession.cookie(pageOrFile)?.let{request.header("Cookie",it)}
        val response=request.execute()
        val destination=response.url().toString()
        check(SearchRanking.validUrl(destination) && destination.startsWith("https://")){
            "Untrusted HTTP redirect"
        }
        when(response.statusCode()){
            401->throw OriginalAccessException(OriginalAccessCause.SIGN_IN,
                "Sign in with an account authorized to access this original")
            402->throw OriginalAccessException(OriginalAccessCause.ENTITLEMENT,
                "This file requires an active entitlement or purchase")
            403->throw OriginalAccessException(OriginalAccessCause.ENTITLEMENT,
                "The connected account is not permitted to download this file")
            404,410->throw OriginalAccessException(OriginalAccessCause.NOT_FOUND,
                "This original file is no longer available")
            429->throw OriginalAccessException(OriginalAccessCause.RATE_LIMIT,
                "Provider rate limit reached; try again later")
        }
        require(response.statusCode() in 200..299) {
            "Provider HTTP "+response.statusCode()
        }
        val target=java.net.URI(destination)
        val original=java.net.URI(pageOrFile)
        if(!target.host.equals(original.host,true) &&
            (target.path.orEmpty().contains("/login",true) ||
             target.path.orEmpty().contains("/signin",true) ||
             target.path.orEmpty().contains("/auth",true)))
            throw OriginalAccessException(OriginalAccessCause.SIGN_IN,
                "Provider redirected to account sign-in")
        if(target.path.orEmpty().matches(Regex("(?i).*/(?:login|signin|sign-in|auth|account/login)/?")))
            throw OriginalAccessException(OriginalAccessCause.SIGN_IN,
                "Session expired or account sign-in required")
        val doc=response.parse()
        val content=doc.outerHtml()
        return parse(content,destination,hint)
            ?:throw OriginalAccessException(OriginalAccessCause.NO_ORIGINAL,
                "This session exposes no downloadable original; check the account's access rights")
    }
}
