package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/** Public 4KHD official TeraBox bridge. No account-mutating APIs are used. */
class TeraBoxShareRequestCompatibilityTest {
    private val resolver=TeraBoxResolver(HttpClient())

    private fun pairs(encoded:String):Map<String,String> =
        encoded.split('&').associate { field->
            val parts=field.split('=',limit=2)
            URLDecoder.decode(parts[0],"UTF-8") to
                URLDecoder.decode(parts.getOrElse(1){""},"UTF-8")
        }

    @Test fun publicShareUsesMinimalAndKnownFirstPartyQueryShapes(){
        val values=resolver.shareListQueries(
            "publicTokenAbc123","1shareCodeXYZ",null).map(::pairs)
        assertEquals(3,values.size)
        assertEquals("1",values[0]["root"])
        assertEquals("250528",values[0]["app_id"])
        assertEquals("publicTokenAbc123",values[0]["jsToken"])
        assertEquals("1shareCodeXYZ",values[0]["shorturl"])
        assertFalse(values[0].containsKey("channel"))
        assertEquals("0",values[1]["channel"])
        assertEquals("dubox",values[2]["channel"])
        assertTrue(values.all{it["shorturl"]=="1shareCodeXYZ"})
    }

    @Test fun selectedSubfolderUsesDirWithoutRootAndEscapesPath(){
        val values=resolver.shareListQueries(
            "actualToken","shareX","/Pictures/Anime & Cosplay/").map(::pairs)
        assertTrue(values.all{
            it["dir"]=="/Pictures/Anime & Cosplay/" && !it.containsKey("root")
        })
    }

    @Test fun publisherLogIdIsForwardedOnlyWhenItIsRealAndWellFormed(){
        val html="""<script>window.config={"dp-logid":"7392083182045647"}</script>"""
        val observed=resolver.publishedLogId(html)
        assertEquals("7392083182045647",observed)
        val values=resolver.shareListQueries(
            "publicTokenAbc123","1shareCodeXYZ",null,observed).map(::pairs)
        assertEquals(3,values.size)
        assertTrue(values.all{it["dp-logid"]==observed})
        val absent=resolver.shareListQueries(
            "publicTokenAbc123","1shareCodeXYZ",null).map(::pairs)
        assertTrue(absent.all{!it.containsKey("dp-logid")})
        assertNull(resolver.publishedLogId("<script>window.config={}</script>"))
        assertTrue(resolver.shareListQueries(
            "publicTokenAbc123","1shareCodeXYZ",null,"not a token!").map(::pairs)
            .all{!it.containsKey("dp-logid")})
    }

    @Test fun cannotCreatePublicRequestWithoutActualShareIdentifiers(){
        assertThrows(IllegalArgumentException::class.java){
            resolver.shareListQueries("","shareX",null)
        }
        assertThrows(IllegalArgumentException::class.java){
            resolver.shareListQueries("realToken","",null)
        }
    }
}
