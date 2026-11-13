package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Real publisher integration, intentionally opt-in for a separately reported
 * live CI job. Does not add automatic video downloads or store adult originals.
 */
class ActualProviderIntegrationTest {
    private fun enabled() {
        assumeTrue("Live HTTP checks run only in the dedicated provider job",
            System.getenv("ASTERION_LIVE_PROVIDERS")=="1")
    }
    private fun page(address:String,referer:String?=null):org.jsoup.nodes.Document {
        return try{
            Jsoup.connect(address).timeout(20_000).maxBodySize(3_000_000)
                .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile) "+
                    "AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .apply{if(referer!=null)referrer(referer)}
                .get()
        }catch(blocked:org.jsoup.HttpStatusException){
            if(blocked.statusCode!=403 ||
                !address.startsWith("https://freewebnovel.com/"))throw blocked
            // The actual Java HTTP client gets 403 for public novel pages
            // which ordinary browser-compatible HTTP retrieves with 200.
            // Feed those REAL remote HTML bytes into the APP's Kotlin EPUB
            // parser/writer; production uses BrowserPageFetch instead.
            println("LIVE_EPUB_JAVA_403_BROWSER_CLIENT_FALLBACK")
            val code="""
import sys,urllib.request
headers={'User-Agent':'Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36',
         'Accept-Language':'en-US,en;q=0.9','Accept-Encoding':'identity','Accept':'*/*'}
if len(sys.argv)>2 and sys.argv[2]:headers['Referer']=sys.argv[2]
response=urllib.request.urlopen(urllib.request.Request(sys.argv[1],headers=headers),timeout=18)
if response.status!=200:raise RuntimeError('real provider HTTP '+str(response.status))
sys.stdout.buffer.write(response.read(3000000))
""".trimIndent()
            val process=ProcessBuilder(
                "python3","-c",code,address,referer.orEmpty()).start()
            val downloaded=process.inputStream.readNBytes(3_000_000)
            val exited=process.waitFor(25,java.util.concurrent.TimeUnit.SECONDS)
            if(!exited)process.destroyForcibly()
            val stderr=process.errorStream.bufferedReader()
                .use{it.readText().takeLast(900)}
            if(!exited || process.exitValue()!=0 || downloaded.size<=1500){
                // A failed remote request must remain FAILED. Report the
                // publisher's actual HTTP exception rather than obscuring it
                // under an arbitrary assertion or reporting a fake EPUB.
                throw IllegalStateException(
                    "Live novel reader failure: "+address.substringBefore('?').take(180)+
                    " · python exit="+(if(exited)process.exitValue() else -1)+
                    " · bytes="+downloaded.size+" · "+stderr.take(600))
            }
            Jsoup.parse(String(downloaded,Charsets.UTF_8),address)
        }
    }

    @Test fun actualFreeWebNovelChaptersBeyondTwelveProduceCompleteEpub(){
        enabled()
        val root="https://freewebnovel.com/novel/dual-cultivation-novel"
        val landing=page(root)
        val listed=NovelEpubParser.chapters(landing,root)
        val chosen=(1..14).map{number->
            listed.firstOrNull{it.number==number.toDouble()}
                ?:error("Publisher omitted chapter "+number+" from its real catalog")
        }
        val temp=Files.createTempFile("asterion-real-provider",".epub").toFile()
        try {
            temp.outputStream().use { output ->
                NovelEpubParser.writeEpubStreaming(output,
                    "Dual Cultivation live integration",root,chosen.map{it.title}){i->
                    val chapter=chosen[i]
                    // Real 2026-11-13 source rejected a 250ms/chap burst with
                    // HTTP 429 on chapter eight. Match the production slow
                    // publisher cadence; do not hammer the live website.
                    Thread.sleep(10_000L)
                    val document=page(chapter.url,root)
                    val actual=NovelEpubParser.readerChapter(document,chapter.url,chapter)
                    assertEquals(chapter.number,actual.number,0.0)
                    val text=NovelEpubParser.chapterText(document)
                    assertTrue(text.paragraphs.joinToString("").length>=80)
                    text
                }
            }
            ZipFile(temp).use{zip->
                val chapters=(1..14).map{"OEBPS/chapter-"+it+".xhtml"}
                for(name in chapters)assertNotNull("missing "+name,zip.getEntry(name))
                assertNotNull(zip.getEntry("OEBPS/content.opf"))
                assertNotNull(zip.getEntry("OEBPS/nav.xhtml"))
                println("LIVE_EPUB_14_COMPLETE bytes="+temp.length())
            }
        }finally{temp.delete()}
    }

    @Test fun actualJavGuruPublicListingIsParsedIntoRealWatchPages(){
        enabled()
        val url="https://jav.guru/"
        val doc=page(url)
        val entries=AdultProviderListings.entries(doc,url,"ADULT",System.currentTimeMillis())
        assertTrue("Publisher served HTML but the site's video parser found no watch cards",
            entries.isNotEmpty())
        assertTrue(entries.all{
            com.asterion.videoflow.VideoSiteProfiles.isVideoPage(it.url)})
        println("LIVE_JAVGURU_CARDS="+entries.size)
    }

    @Test fun actualKiutakuDetailsExposePublisherPhotoUrls(){
        enabled()
        val pageUrl="https://kiutaku.com/7440"
        val doc=page(pageUrl)
        val listing=IndexedItem(pageUrl,"Machi – Evanescia",null,
            "https://kiutaku.com/","COSPLAY","cosplay",System.currentTimeMillis(),false)
        val found=SiteBackfillProfiles.galleryOriginals(doc,listing,System.currentTimeMillis())
        assertTrue("Real Kiutaku detail has photos but parser found none",found.isNotEmpty())
        assertTrue("must be actual publisher original",found.any{
            URI(it.url).host.orEmpty().endsWith("mitaku.net")})
        println("LIVE_KIUTAKU_ORIGINAL_LINKS="+found.size)
        // Actual photo request, using exactly the Referer Android uses.
        val first=found.first{URI(it.url).host.orEmpty().endsWith("mitaku.net")}
        val u=URI(first.url).toASCIIString()
        val conn=URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout=15_000;conn.readTimeout=15_000
        conn.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 17; Mobile)")
        conn.setRequestProperty("Referer","https://mitaku.net/")
        conn.setRequestProperty("Accept","image/avif,image/webp,image/*,*/*;q=0.8")
        try{
            val http=conn.responseCode
            assertEquals("mitaku.net real original must return HTTP 200",200,http)
            val magic=ByteArray(12)
            val n=conn.inputStream.use{it.read(magic)}
            assertTrue(n>7)
            val image=(magic[0]==0xff.toByte()&&magic[1]==0xd8.toByte()) ||
                (magic[0]==0x89.toByte()&&magic[1]==0x50.toByte()) ||
                String(magic,0,4)=="RIFF"
            assertTrue("response not a valid publisher photo",image)
            println("LIVE_KIUTAKU_IMAGE_HTTP="+http)
        }finally{conn.disconnect()}
    }

    @Test fun actualRule34VideoCardsExposeThumbnailMetadata(){
        enabled()
        val url="https://rule34video.com/"
        val doc=page(url)
        val thumbs=doc.select("img").mapNotNull{SitePreviewImage.best(it)}
            .filter{it.startsWith("https://rule34video.com/")}
        assertTrue("Real video portal returned no provider-image candidates",thumbs.isNotEmpty())
        println("LIVE_RULE34_IMAGE_CANDIDATES="+thumbs.size)
    }
}
