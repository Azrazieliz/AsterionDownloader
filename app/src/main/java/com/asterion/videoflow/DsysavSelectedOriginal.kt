package com.asterion.videoflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The actual owner-provided DSYSAV English site, not dsav123.wiki.
 * Public site request envelope and the exact selected /en/watch?v=<id>
 * follow the publisher's own ordinary AES-CBC signed JSON client format.
 * The resulting same-ID OSS DASH manifest is a transfer candidate; it does
 * not mean the audio/video original has been fully saved or validated.
 *
 * PC certification: /en/watch?v=MxWT7NmlUoT -> api.dsav.app/v1 response
 * -> oss.dsav.app MPD -> fully signed fMP4 video and audio.
 */
internal object DsysavSelectedOriginal {
    private const val SALT="AjPuom638LmWfWyeM5YueKuJ9PuWLdRn"
    private val KEY="OPQT123412FRANME".toByteArray(Charsets.UTF_8)
    private val IV="MRDCQP12QPM13412".toByteArray(Charsets.UTF_8)
    private const val API="https://api.dsav.app/v1"
    private const val SITE="https://dsysav.com/en"
    private val ID=Regex("""[A-Za-z0-9_-]{8,45}""")
    data class Selected(val id:String,val mpd:String,
        val durationSeconds:Double,val expire:Long)

    fun articleId(selected:String):String? {
        val uri=runCatching{URI(selected)}.getOrNull()?:return null
        if(uri.scheme!="https" || uri.host.orEmpty().lowercase() !in
            setOf("dsysav.com","www.dsysav.com") ||
            uri.port!=-1 || uri.userInfo!=null ||
            uri.rawFragment!=null || uri.path!="/en/watch")return null
        val parts=uri.rawQuery.orEmpty().split('&')
        if(parts.size!=1 || !parts.single().startsWith("v="))return null
        return parts.single().removePrefix("v=").takeIf{ID.matches(it)}
    }
    fun verifiedMpd(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        return uri.scheme=="https" && uri.host=="oss.dsav.app" &&
            uri.port==-1 && uri.userInfo==null && uri.rawFragment==null &&
            uri.path.orEmpty().startsWith("/mpd/") &&
            uri.path.orEmpty().endsWith(".mpd",ignoreCase=true) &&
            !uri.path.orEmpty().contains("..")
    }
    private fun digest(algorithm:String,bytes:ByteArray):ByteArray =
        MessageDigest.getInstance(algorithm).digest(bytes)
    private fun hex(bytes:ByteArray)=bytes.joinToString("") {
        "%02x".format(it.toInt() and 255)
    }
    private fun base(bytes:ByteArray)=
        android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
    private fun crypt(input:ByteArray,encrypt:Boolean):ByteArray {
        val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(if(encrypt)Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY,"AES"),IvParameterSpec(IV))
        return cipher.doFinal(input)
    }
    private fun post(context:Context,article:String,route:String,
                     params:JSONObject,config:JSONObject?=null):String {
        val payload=JSONObject(params.toString())
            .put("lang","en").put("route",route).toString()
        val nonce=UUID.randomUUID().toString()
        val timestamp=System.currentTimeMillis()/1000L
        val sign=hex(digest("MD5",
            (base(payload.toByteArray(Charsets.UTF_8))+
                nonce+timestamp+SALT).toByteArray(Charsets.UTF_8)))
        val data=base(crypt(payload.toByteArray(Charsets.UTF_8),true))
        val body=JSONObject().put("sign",sign).put("nonce",nonce)
            .put("timestamp",timestamp).put("data",data)
            .toString().toByteArray(Charsets.UTF_8)
        val conn=(NetworkRoute.javaProxy(context,article)?.let{
            URL(API).openConnection(it)
        }?:URL(API).openConnection()) as HttpURLConnection
        try {
            conn.requestMethod="POST"
            conn.instanceFollowRedirects=false
            conn.connectTimeout=15_000
            conn.readTimeout=26_000
            conn.doOutput=true
            conn.setRequestProperty("User-Agent",NativeMediaDownloader.USER_AGENT)
            conn.setRequestProperty("Accept","application/json")
            conn.setRequestProperty("Content-Type","application/json")
            conn.setRequestProperty("Referer",SITE)
            conn.setRequestProperty("Origin","https://dsysav.com")
            conn.setRequestProperty("Accept-Language","en-US,en;q=0.9")
            val signing=config
            if(route!="/config" &&
                signing?.optBoolean("http_sign_flag")==true){
                val signId=signing.optString("http_sign_id")
                val signKey=signing.optString("http_sign_key")
                if(signId.isBlank() || signKey.isBlank())
                    throw IOException("DSYSAV selected API signing configuration missing")
                val created=System.currentTimeMillis()/1000L
                val contentDigest="sha-256=:"+base(digest("SHA-256",body))+":"
                val sigParams="(\"@method\" \"@target-uri\" \"content-digest\" "+
                    "\"@created\");created=$created;keyid=\"$signId\";alg=\"hmac-sha256\""
                val signingText="\"@method\": POST\n\"@target-uri\": $route\n"+
                    "content-digest: $contentDigest\n\"@created\": $created\n"+
                    "\"@signature-params\": $sigParams"
                val mac=Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(signKey.toByteArray(Charsets.UTF_8),
                    "HmacSHA256"))
                conn.setRequestProperty("Content-Digest",contentDigest)
                conn.setRequestProperty("Signature-Input","v1=$sigParams")
                conn.setRequestProperty("Signature","v1=:"+base(mac.doFinal(
                    signingText.toByteArray(Charsets.UTF_8)))+":")
            }
            conn.outputStream.use{it.write(body)}
            if(conn.responseCode!=200)
                throw IOException("DSYSAV selected API HTTP "+conn.responseCode)
            val bytes=conn.inputStream.use{it.readNBytes(650_001)}
            if(bytes.isEmpty() || bytes.size>650_000)
                throw IOException("DSYSAV API response bounds exceeded")
            val wrapper=JSONObject(String(bytes,Charsets.UTF_8))
            if(wrapper.optInt("code")!=200)
                throw IOException("DSYSAV selected API refused requested item")
            val encrypted=wrapper.optString("data")
            if(encrypted.length !in 24..450_000)
                throw IOException("DSYSAV selected API did not publish data")
            val decoded=crypt(android.util.Base64.decode(encrypted,
                android.util.Base64.DEFAULT),false)
            if(decoded.isEmpty() || decoded.size>650_000)
                throw IOException("DSYSAV selected payload invalid")
            return String(decoded,Charsets.UTF_8)
        }finally{conn.disconnect()}
    }
    private fun duration(raw:String):Double {
        val parts=raw.trim().split(':')
        return if(parts.size in 2..3) {
            parts.fold(0.0){a,part->a*60+(part.toDoubleOrNull()?:return 0.0)}
        }else raw.toDoubleOrNull()?:0.0
    }
    fun resolve(context:Context,selected:String):Selected? {
        val id=articleId(selected)?:return null
        val config=JSONObject(post(context,selected,"/config",JSONObject()))
        val answer=post(context,selected,"/videos/search",
            JSONObject().put("search_type","video_detail_filter_id")
                .put("video_id",id),config)
        val records=if(answer.trimStart().startsWith("["))JSONArray(answer)
            else JSONArray().put(JSONObject(answer))
        val matches=ArrayList<JSONObject>()
        for(i in 0 until minOf(records.length(),8)){
            val item=records.optJSONObject(i)?:continue
            if(item.optString("video_id")==id &&
                verifiedMpd(item.optString("video_url")))
                matches+=item
        }
        if(matches.size!=1)
            throw IOException("DSYSAV selected watch has no unique original MPD")
        val found=matches.single()
        val runtime=duration(found.optString("video_duration"))
        if(runtime<120 || runtime>8*3600)
            throw IOException("DSYSAV published duration is not a full movie")
        val publish=config.optLong("video_sign_expire")
        val expire=if(publish>System.currentTimeMillis()/1000L+1800)
            publish else System.currentTimeMillis()/1000L+7200
        return Selected(id,found.optString("video_url"),runtime,expire)
    }
    fun signedSegment(source:String,expire:Long):String {
        val uri=runCatching{URI(source)}.getOrNull()
            ?:throw IOException("DSYSAV fragment URL malformed")
        if(uri.scheme!="https" || uri.host!="oss.dsav.app" ||
            uri.userInfo!=null || uri.port!=-1 ||
            uri.rawFragment!=null || !uri.path.orEmpty().startsWith("/mpd/") ||
            uri.path.orEmpty().contains("..") ||
            !Regex("""(?i)\.(?:m4s|mp4)$""").containsMatchIn(uri.path.orEmpty()))
            throw IOException("DSYSAV segment left exact selected OSS host")
        val raw=SALT+uri.rawPath+expire
        val signature=android.util.Base64.encodeToString(
            digest("MD5",raw.toByteArray(Charsets.UTF_8)),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING)
        return "https://oss.dsav.app"+uri.rawPath+
            "?sign="+signature+"&expire="+expire
    }
}
