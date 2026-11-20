package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PublicCreatorEvidenceTest {
    @Test fun establishedPublisherConfirmsCreatorButNotVideoOwnership() {
        val article="https://wpb.shueisha.co.jp/gravure/20260906-132709/"
        val doc=Jsoup.parse("""
            <html><head><title>ティナ キツネ - 週プレNEWS</title></head>
            <body><main><h1>ティナ キツネ『オセアニアの純情』</h1>
            <p>■ティナ キツネ（Tina Kitsune）</p>
            <p>モデル、インフルエンサー</p></main></body></html>
        """.trimIndent(),article)
        assertTrue(PublicCreatorEvidence.publishedPerson(
            "Tina Kitsune",article,doc))
        assertFalse(PerformerIdentity.isProfileUrl("Tina Kitsune",article))
        assertEquals(article,PublicCreatorEvidence.verifiedReference("Tina Kitsune"))
    }

    @Test fun untrustedSitesAndWrongNamesCannotProvideIdentityProof() {
        val article="https://wpb.shueisha.co.jp/gravure/20260906-132709/"
        val doc=Jsoup.parse("<h1>Other Creator</h1><p>Different person</p>",article)
        assertFalse(PublicCreatorEvidence.publishedPerson("Tina Kitsune",article,doc))
        assertFalse(PublicCreatorEvidence.isPublisherPage(
            "https://wpb.shueisha.co.jp.evil.test/gravure/20260906-132709/"))
        assertFalse(PublicCreatorEvidence.isPublisherPage(
            "https://wpb.shueisha.co.jp/search?q=Tina+Kitsune"))
    }

    @Test fun magazineProofCannotAuthorizeAutomaticVideoAttribution() {
        val page="https://wpb.shueisha.co.jp/gravure/20260906-132709/"
        assertFalse(PerformerIdentity.isProfileUrl("Tina Kitsune",page))
        assertFalse(PublicCreatorEvidence.isPublisherPage(
            "https://watchporn.to/models/tina-kitsune/"))
    }
}
