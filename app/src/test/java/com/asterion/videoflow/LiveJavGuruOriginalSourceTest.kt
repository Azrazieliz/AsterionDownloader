package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in REAL source check against the exact publicly published selected
 * Jav Guru article. Tests the production Kotlin decoder, rather than only
 * the separate Python probe. No login, challenge bypass, or ad iframes.
 * An HLS playlist success is not a complete file or Android receipt.
 */
class LiveJavGuruOriginalSourceTest {
    private val selected =
        "https://jav.guru/1077002/doks-695-little-devil-jk-gives-thick-tongue-kisses-while-repeatedly-teasing-and-toying-with-you-in-a-mean-slow-handjob-until-you-cum/"

    private fun html(url:String,referer:String):Pair<String,String> {
        val result=Jsoup.connect(url)
            .userAgent(NativeMediaDownloader.USER_AGENT)
            .header("Referer",referer)
            .ignoreHttpErrors(true)
            .followRedirects(true)
            .timeout(28_000)
            .maxBodySize(1_200_000)
            .execute()
        assertEquals("Selected publisher/player must be accessible",200,result.statusCode())
        return result.url().toString() to result.body()
    }

    @Test fun productionKotlinFindsSelectedLuluManifestBytes(){
        assumeTrue("Run live only on explicit opt-in",
            System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
        val (_,watchHtml)=html(selected,selected)
        val streams=JavGuruOriginalSource.published(selected,watchHtml)
        assertTrue("No selected first-party STREAM LU",
            streams.any{it.server=="LU"})
        val stream=streams.single{it.server=="LU"}
        val (_,relay)=html(stream.url,selected)
        val follow=JavGuruOriginalSource.nextIframe(stream.url,relay)
        assertNotNull("Selected first-party relay did not decode",follow)
        val (embed,playerHtml)=html(follow!!,stream.url)
        assertTrue("Not the selected external player",
            JavGuruOriginalSource.selectedEmbed("LU",embed))
        val manifest=JavGuruOriginalSource.selectedHls(embed,playerHtml)
        assertNotNull("Production Kotlin did not unpack exact selected HLS",manifest)
        val conn=URL(manifest!!).openConnection() as HttpURLConnection
        conn.connectTimeout=20_000
        conn.readTimeout=23_000
        conn.setRequestProperty("User-Agent",NativeMediaDownloader.USER_AGENT)
        conn.setRequestProperty("Referer",embed)
        conn.setRequestProperty("Accept-Encoding","identity")
        try {
            assertEquals("Selected manifest denied",200,conn.responseCode)
            val data=conn.inputStream.use{it.readNBytes(3000)}
            assertTrue("Manifest is not actual HLS bytes",
                String(data,Charsets.UTF_8).trimStart().startsWith("#EXTM3U"))
            println("JAV_GURU_SELECTED_KOTLIN_HLS_SOURCE=true")
            println("JAV_GURU_SELECTED_PLAYLIST_BYTES="+data.size)
        } finally {
            conn.disconnect()
        }
    }
}
