package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Owner-selected provider URLs enter their existing true video transfer lane,
 * not gallery backfill, irrespective of whether their route contains /video.
 */
class SourceRouterVideoSelectionTest {
    @Test fun actualSelectedNativeVideoRoutesDoNotFallIntoGallerySync(){
        listOf(
            "https://jav.guru/123456/mxgs-1452-example-title/",
            "https://javideo.net/en/fc2ppv-4994603",
            "https://mp4hentai.com/2026/11/selected-video/",
            "https://helloporn.com/video-74763-selected-clip",
            "https://hentaihaven.xxx/watch/selected-series/episode-2/",
            "https://uncensoredhentai.xxx/watch/selected-video/"
        ).forEach{url->
            assertEquals("Owner-selected $url must start VideoFlow",
                DownloadKind.VIDEO,SourceRouter.classify(url))
        }
    }

    @Test fun listingAndUnrelatedGalleryStillNotVideo(){
        listOf(
            "https://jav.guru/category/uncensored/",
            "https://javideo.net/search/",
            "https://mp4hentai.com/2026/11/",
            "https://example.com/creator/gallery/123"
        ).forEach{url->
            assertEquals(DownloadKind.GALLERY,SourceRouter.classify(url))
        }
    }
}
