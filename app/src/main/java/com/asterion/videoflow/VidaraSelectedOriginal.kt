package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Resolves ONLY the unique first-party iframe#iframe-video on the actual
 * user-selected LiveCamRips /video/<id> article. The publisher's Vidara
 * filecode and Vidara's ordinary public /api/stream response are the entire
 * identity chain. The response is a signed HLS source, not a file receipt.
 *
 * PC publisher proof: /video/16892369 -> vidara.to/a806a19ec33a ->
 * POST vidara.to/api/stream {device:web,filecode:a806a19ec33a}.
 * The exact video transfer and playback validation remains TransferService's
 * responsibility, with the already existing HLS engine, queue and MediaStore.
 */
internal object VidaraSelectedOriginal {
    private val articlePath=Regex("""^/video/[0-9]{5,12}/?$""")
    private val filecode=Regex("""^/(?:e/|v/)?([A-Za-z0-9_-]{8,50})/?$""")
    private val exclude=Regex("""(?i)(?:preview|teaser|trailer|sample)""")
    private fun uri(raw:String):URI?=runCatching{URI(raw)}.getOrNull()
    fun selectedIframe(article:String,iframe:String):String? {
        val a=uri(article)?:return null
        val b=uri(iframe)?:return null
        if(a.scheme!="https" || a.host?.lowercase() !in
            setOf("livecamrips.to","www.livecamrips.to") ||
            a.port!=-1 || a.userInfo!=null || a.rawQuery!=null ||
            !articlePath.matches(a.path.orEmpty()))return null
        if(b.scheme!="https" || b.host?.lowercase()!="vidara.to" ||
            b.port!=-1 || b.userInfo!=null || b.rawQuery!=null ||
            b.rawFragment!=null)return null
        return filecode.matchEntire(b.path.orEmpty())?.groupValues?.get(1)
    }
    fun verifiedHls(url:String):Boolean {
        val parsed=uri(url)?:return false
        if(parsed.scheme!="https" || parsed.host.isNullOrBlank() ||
            parsed.port!=-1 || parsed.userInfo!=null ||
            parsed.rawFragment!=null || exclude.containsMatchIn(parsed.path.orEmpty()))
            return false
        return parsed.path.orEmpty().endsWith(".m3u8",ignoreCase=true) &&
            parsed.host!="localhost" &&
            !parsed.host.endsWith(".local",ignoreCase=true)
    }
    fun resolve(context:Context,article:String,iframe:String):String? {
        val id=selectedIframe(article,iframe)?:return null
        val address="https://vidara.to/api/stream"
        val conn=(NetworkRoute.javaProxyForSelected(context,article,iframe)?.let{
            URL(address).openConnection(it)
        }?:URL(address).openConnection()) as HttpURLConnection
        try {
            conn.instanceFollowRedirects=false
            conn.connectTimeout=14_000
            conn.readTimeout=22_000
            conn.requestMethod="POST"
            conn.doOutput=true
            conn.setRequestProperty("User-Agent",NativeMediaDownloader.USER_AGENT)
            conn.setRequestProperty("Content-Type","application/json")
            conn.setRequestProperty("Accept","application/json")
            conn.setRequestProperty("Origin","https://vidara.to")
            conn.setRequestProperty("Referer",iframe)
            // A session is forwarded ONLY to its own explicitly connected
            // Vidara host, never the unrelated LiveCamRips publisher.
            SiteSession.cookie(context,iframe)?.takeIf{it.isNotBlank()}
                ?.let{conn.setRequestProperty("Cookie",it)}
            val body=JSONObject().put("device","web").put("filecode",id)
                .toString().toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use{it.write(body)}
            val status=conn.responseCode
            if(status!=200)throw IOException(
                "Selected Vidara original API HTTP $status")
            if(!conn.contentType.orEmpty().contains("json",ignoreCase=true))
                throw IOException("Selected Vidara API did not return JSON")
            val text=conn.inputStream.bufferedReader().use{it.readText()}
            if(text.length !in 30..350_000)
                throw IOException("Invalid Vidara selected response length")
            val response=JSONObject(text)
            val selected=response.optString("streaming_url")
            if(!verifiedHls(selected))
                throw IOException("Selected Vidara response has no full HLS source")
            return selected
        } finally {
            conn.disconnect()
        }
    }
}
