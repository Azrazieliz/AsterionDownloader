package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class GalleryMaskedForbiddenAccessTest {
    private val nginxPage="""<html>
        <head><title>403 Forbidden</title></head>
        <body><center><h1>403 Forbidden</h1></center>
        <hr><center>nginx</center></body></html>"""

    @Test fun live4kGirl200TransportDenialIsNotAnEmptyGallery(){
        assertTrue(GalleryAccessGate.isMaskedForbidden(
            "https://www.4kgirl.com/",200,nginxPage))
        assertTrue(GalleryAccessGate.isMaskedForbidden(
            "https://4kgirl.com/feed/",200,nginxPage))
    }

    @Test fun ordinaryPageMentioning403IsNotMistakenForDeniedPublisher(){
        assertFalse(GalleryAccessGate.isMaskedForbidden(
            "https://www.4kgirl.com/",200,
            """<html><head><title>Recent galleries</title></head>
            <body><article>Guide to fixing 403 Forbidden errors</article>
            <a href="/actual-post/">Real album</a></body></html>"""))
    }

    @Test fun gateDetectorNeverChangesOtherPublisherPaths(){
        assertFalse(GalleryAccessGate.isMaskedForbidden(
            "https://cosplaytele.com/",200,nginxPage))
        assertFalse(GalleryAccessGate.isMaskedForbidden(
            "https://www.4khd.com/",200,nginxPage))
        assertFalse(GalleryAccessGate.isMaskedForbidden(
            "https://www.4kgirl.com/",403,nginxPage))
        assertFalse(GalleryAccessGate.isMaskedForbidden(
            "https://www.4kgirl.com/",200,"<html>Gallery</html>"))
    }
}
