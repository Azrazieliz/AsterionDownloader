package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class FreedlSelectedOriginalTest {
    private val ownerPage="https://frdl.hk/realSelectedFile12/"
    private val html="""
        <html><body>
        <form method="POST" id="DownloadForm" action="">
           <input type="hidden" name="op" value="download1">
           <input type="hidden" name="id" value="opaque_server_selected_id">
           <input type="hidden" name="hash" value="">
           <input type="hidden" name="rand" value="server_nonce">
           <input type="hidden" name="referer" value="">
           <input type="hidden" name="download_free" value="1">
           <input type="hidden" name="adblock_detected" value="0">
           <input type="hidden" name="hls_direct" value="0">
           <button type="submit">Normal Free Download</button>
        </form>
        <form id="captcha-step">
           <input name="captcha">
           <div class="h-captcha" data-sitekey="not-a-real-key"></div>
        </form>
        </body></html>
    """.trimIndent()

    @Test fun onlyExactSelectedFreeFormPostsPublishedHiddenFields(){
        val fields=FreedlSelectedOriginal.publicFreeForm(
            Jsoup.parse(html,ownerPage),ownerPage)
        assertNotNull(fields)
        assertEquals(ownerPage,fields!!.action)
        assertEquals(8,fields.fields.size)
        assertEquals("",fields.fields["hash"])
        assertEquals("opaque_server_selected_id",fields.fields["id"])
    }

    @Test fun captchaInsideSameFreeFormRequiresUserAction(){
        val challenge=html.replace(
            "<button type=\"submit\">Normal Free Download</button>",
            "<input type=\"hidden\" name=\"h-captcha-response\" value=\"\">"+
            "<button type=\"submit\">Normal Free Download</button>")
        assertNull(FreedlSelectedOriginal.publicFreeForm(
            Jsoup.parse(challenge,ownerPage),ownerPage))
    }

    @Test fun failClosedOnAdActionsAndForgedHost(){
        for(candidate in listOf(
            "https://ads.example/realSelectedFile12",
            "http://frdl.hk/realSelectedFile12",
            "https://frdl.hk.evil.example/realSelectedFile12",
            "https://frdl.hk/otherSelectedFile22",
            "https://frdl.hk/realSelectedFile12?next=somewhere"
        ))assertNull(candidate,FreedlSelectedOriginal.publicFreeForm(
            Jsoup.parse(html,candidate),candidate))
        val bad=html.replace("action=\"\"",
            "action=\"https://ads.example/elsewhere\"")
        assertNull(FreedlSelectedOriginal.publicFreeForm(
            Jsoup.parse(bad,ownerPage),ownerPage))
        val alternate=html.replace("method=\"POST\"","method=\"GET\"")
        assertNull(FreedlSelectedOriginal.publicFreeForm(
            Jsoup.parse(alternate,ownerPage),ownerPage))
    }

    @Test fun onlyGenuineSelectedDirectFilesAreEligibleRedirects(){
        assertTrue(FreedlSelectedOriginal.selectedMediaRedirect(ownerPage,
            "https://cdn.example.com/owner-original.mp4?signature=abc"))
        assertFalse(FreedlSelectedOriginal.selectedMediaRedirect(ownerPage,
            "https://cdn.example.com/owner-trailer.html"))
        assertFalse(FreedlSelectedOriginal.selectedMediaRedirect(
            "https://ads.example/wrong", "https://cdn.example.com/owner-original.mp4"))
    }
}
