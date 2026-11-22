package com.asterion.videoflow

import android.content.Context
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Selected DSYSAV first-party DASH timeline -> locally served original HLS
 * audio/video playlists with independently signed original OSS fragments.
 * Only the three chosen playlists are served from 127.0.0.1. Signed media
 * bytes travel directly from the publisher to the existing yt-dlp engine.
 * This is NOT an independent downloader or a fabricated playable original.
 */
internal class DsysavDashHlsBridge private constructor(
    private val socket:ServerSocket,
    private val master:String,private val video:String,private val audio:String,
    val expectedDuration:Double
):AutoCloseable {
    @Volatile private var running=true
    val url="http://127.0.0.1:"+socket.localPort+"/master.m3u8"
    private val serverThread=Thread({
        while(running){
            val client=try{socket.accept()}catch(_:SocketException){break}
                catch(_:IOException){break}
            client.use{connection->
                try {
                    connection.soTimeout=4000
                    val reader=connection.getInputStream().bufferedReader()
                    val first=reader.readLine().orEmpty()
                    val page=Regex("""^GET /(master|video|audio)\.m3u8 HTTP/1\.[01]$""")
                        .matchEntire(first)?.groupValues?.get(1)
                    var host=""
                    var count=0
                    while(count++<40){
                        val header=reader.readLine()?:break
                        if(header.isEmpty())break
                        if(header.length>4096)break
                        if(header.startsWith("Host:",true))
                            host=header.substringAfter(':').trim()
                    }
                    val allowed=page!=null && count<40 &&
                        host in setOf("127.0.0.1:"+socket.localPort,
                            "localhost:"+socket.localPort)
                    val body=(when {
                        !allowed->"Forbidden"
                        page=="master"->master
                        page=="video"->video
                        else->audio
                    }).toByteArray(Charsets.UTF_8)
                    val response=(if(allowed)"HTTP/1.1 200 OK" else
                        "HTTP/1.1 403 Forbidden")+"\r\n"+
                        "Content-Type: "+(if(allowed)
                            "application/vnd.apple.mpegurl" else "text/plain")+
                        "\r\nContent-Length: "+body.size+
                        "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().apply{
                        write(response.toByteArray(Charsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }catch(_:Exception){}
            }
        }
    },"dsysav-exact-selected-manifest").apply{isDaemon=true;start()}
    override fun close() {
        running=false
        socket.close()
        runCatching{serverThread.join(2000)}
    }
    internal data class Track(val kind:String,val bandwidth:Int,
        val height:Int,val codecs:String,val init:String,
        val segments:List<String>,val durations:List<Double>) {
        val seconds get()=durations.sum()
    }
    companion object {
        private fun child(parent:Element,name:String):Element? {
            val n=parent.childNodes
            for(i in 0 until n.length){
                val e=n.item(i) as? Element?:continue
                if(e.tagName.substringAfter(':')==name)return e
            }
            return null
        }
        private fun all(parent:Element,name:String):List<Element>{
            val found=ArrayList<Element>()
            val nodes=parent.getElementsByTagName(name)
            for(i in 0 until nodes.length)(nodes.item(i) as? Element)
                ?.let{found.add(it)}
            return found
        }
        private fun signed(base:URI,template:String,id:String,
                           number:Int,expire:Long):String {
            var name=template.replace("\u0024RepresentationID\u0024",id)
            name=Regex("""\u0024Number(?:%0([1-9])d)?\u0024""")
                .replace(name){m->
                    number.toString().padStart(m.groupValues[1]
                        .toIntOrNull()?:0,'0')
                }
            if(name.contains('\u0024') || name.length !in 3..500)
                throw IOException("Unknown selected DASH fragment template")
            return DsysavSelectedOriginal.signedSegment(
                base.resolve(name).toString(),expire)
        }
        private fun mpd(context:Context,article:String,url:String):ByteArray {
            if(!DsysavSelectedOriginal.verifiedMpd(url))
                throw IOException("Not selected DSYSAV MPD")
            val conn=(NetworkRoute.javaProxyForSelected(context,article,url)
                ?.let{URL(url).openConnection(it)}
                ?:URL(url).openConnection()) as HttpURLConnection
            try{
                conn.instanceFollowRedirects=false
                conn.connectTimeout=16000
                conn.readTimeout=26000
                conn.setRequestProperty("User-Agent",NativeMediaDownloader.USER_AGENT)
                conn.setRequestProperty("Referer",article)
                if(conn.responseCode!=200)
                    throw IOException("Selected DASH MPD HTTP "+conn.responseCode)
                val out=ByteArrayOutputStream()
                val buf=ByteArray(32768)
                conn.inputStream.use{input->
                    while(true){
                        val n=input.read(buf)
                        if(n<0)break
                        if(out.size()+n>1600000)
                            throw IOException("MPD exceeds safe bound")
                        out.write(buf,0,n)
                    }
                }
                val bytes=out.toByteArray()
                if(bytes.size !in 100..1600000 ||
                    !String(bytes,Charsets.UTF_8).contains("<MPD"))
                    throw IOException("Selected source is not a DASH manifest")
                return bytes
            }finally{conn.disconnect()}
        }
        internal fun tracks(bytes:ByteArray,manifest:String,
            selection:DsysavSelectedOriginal.Selected):Pair<Track,Track>{
            val factory=DocumentBuilderFactory.newInstance().apply{
                isNamespaceAware=false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
                setFeature("http://xml.org/sax/features/external-general-entities",false)
                setFeature("http://xml.org/sax/features/external-parameter-entities",false)
            }
            val doc=factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            val root=doc.documentElement
            if(root.tagName.substringAfter(':')!="MPD")
                throw IOException("DASH root is not MPD")
            val periods=all(root,"Period")
            if(periods.size!=1)
                throw IOException("Selected DASH must have one period")
            val base=URI(manifest).let{original->
                child(root,"BaseURL")?.textContent?.trim()
                    ?.takeIf{it.isNotEmpty()}?.let(original::resolve)
                    ?:original
            }
            val candidates=ArrayList<Track>()
            for(adapt in all(periods.single(),"AdaptationSet").take(20)){
                val common=child(adapt,"SegmentTemplate")
                for(rep in all(adapt,"Representation").take(16)){
                    val contentType=adapt.getAttribute("contentType")
                    val kind=contentType.ifBlank{
                        if(rep.getAttribute("mimeType").ifBlank{
                            adapt.getAttribute("mimeType")
                        }.startsWith("audio"))"audio" else "video"
                    }
                    if(kind !in setOf("video","audio"))continue
                    val template=child(rep,"SegmentTemplate")?:common?:continue
                    val media=template.getAttribute("media")
                    val initialization=template.getAttribute("initialization")
                    val id=rep.getAttribute("id").ifBlank{"0"}
                    if(media.isBlank() || initialization.isBlank() ||
                        !id.matches(Regex("[A-Za-z0-9_-]{1,50}")))continue
                    val timescale=template.getAttribute("timescale")
                        .toLongOrNull()?.takeIf{it>0}?:1000L
                    val start=template.getAttribute("startNumber")
                        .toIntOrNull()?.takeIf{it>=0}?:1
                    val durations=ArrayList<Double>()
                    val timeline=child(template,"SegmentTimeline")
                    if(timeline!=null){
                        for(entry in all(timeline,"S").take(6000)){
                            val d=entry.getAttribute("d").toLongOrNull()?:0
                            val r=entry.getAttribute("r").toIntOrNull()?:0
                            if(d<=0 || r !in 0..6000)
                                throw IOException("Invalid selected DASH timeline")
                            repeat(r+1){
                                if(durations.size>=6000)
                                    throw IOException("Too many selected fragments")
                                durations+=d.toDouble()/timescale
                            }
                        }
                    }else{
                        val per=template.getAttribute("duration")
                            .toLongOrNull()?:0L
                        if(per<=0)continue
                        val seconds=per.toDouble()/timescale
                        if(seconds<=0)continue
                        repeat(ceil(selection.durationSeconds/seconds)
                            .toInt().coerceIn(1,6000)){durations+=seconds}
                    }
                    if(durations.size !in 1..6000 ||
                        abs(durations.sum()-selection.durationSeconds)>
                        maxOf(20.0,selection.durationSeconds*.10))continue
                    val trackBase=child(rep,"BaseURL")?.textContent?.trim()
                        ?.takeIf{it.isNotEmpty()}?.let(base::resolve)?:base
                    val init=signed(trackBase,initialization,id,start,selection.expire)
                    val signedFragments=durations.indices.map{i->
                        signed(trackBase,media,id,start+i,selection.expire)
                    }
                    candidates+=Track(kind,rep.getAttribute("bandwidth")
                        .toIntOrNull()?:0,rep.getAttribute("height")
                        .toIntOrNull()?:0,rep.getAttribute("codecs"),
                        init,signedFragments,durations)
                }
            }
            val video=candidates.filter{it.kind=="video"}
                .maxWithOrNull(compareBy<Track>{it.height}.thenBy{it.bandwidth})
                ?:throw IOException("No complete selected DASH video")
            val audio=candidates.filter{it.kind=="audio"}
                .maxByOrNull{it.bandwidth}
                ?:throw IOException("No complete selected DASH audio")
            return video to audio
        }
        private fun playlist(track:Track):String {
            val ceiling=ceil(track.durations.maxOrNull()?:0.0).toInt()
            if(ceiling !in 1..120 || track.segments.isEmpty() ||
                track.segments.size!=track.durations.size)
                throw IOException("Unfinalized selected DASH playlist")
            return buildString {
                append("#EXTM3U\n#EXT-X-VERSION:7\n")
                append("#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:")
                append(ceiling)
                append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-MAP:URI=\"")
                append(track.init)
                append("\"\n")
                for(i in track.segments.indices){
                    append("#EXTINF:")
                    append("%.3f".format(java.util.Locale.US,
                        track.durations[i]))
                    append(",\n")
                    append(track.segments[i])
                    append('\n')
                }
                append("#EXT-X-ENDLIST\n")
            }
        }
        fun open(context:Context,article:String,
            selected:DsysavSelectedOriginal.Selected):DsysavDashHlsBridge {
            if(DsysavSelectedOriginal.articleId(article)!=selected.id ||
                !DsysavSelectedOriginal.verifiedMpd(selected.mpd))
                throw IOException("DASH selected item changed")
            val (v,a)=tracks(mpd(context,article,selected.mpd),
                selected.mpd,selected)
            val socket=ServerSocket(0,12,InetAddress.getByName("127.0.0.1"))
            val port=socket.localPort
            val master=buildString{
                append("#EXTM3U\n#EXT-X-VERSION:7\n")
                append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",")
                append("NAME=\"Source audio\",DEFAULT=YES,AUTOSELECT=YES,")
                append("URI=\"http://127.0.0.1:")
                append(port)
                append("/audio.m3u8\"\n")
                append("#EXT-X-STREAM-INF:BANDWIDTH=")
                append(maxOf(1000,v.bandwidth+a.bandwidth))
                append(",AUDIO=\"aud\"\nhttp://127.0.0.1:")
                append(port)
                append("/video.m3u8\n")
            }
            return DsysavDashHlsBridge(socket,master,
                playlist(v),playlist(a),selected.durationSeconds)
        }
    }
}
