package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VerifiedXChinaProfilesTest {
    private val address="https://en.xchina.co/model/id-5ff0cb8a616f1.html"
    @Test fun opaquePublishedModelIdIsOnlyCandidateNotAutomaticProof(){
        assertTrue(PerformerIdentity.isProfileUrl("Wu Mengmeng",address))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Wu Mengmeng","https://en.xchina.co/models/type-7.html"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Wu Mengmeng","https://xchina.co.attacker.test/model/id-5ff0cb8a616f1.html"))
        assertFalse(PerformerIdentity.isProfileUrl(
            "Wu Mengmeng","https://en.xchina.co/video/5ff0cb8a616f1"))
    }
    @Test fun wrongPersonCannotBeVerifiedByAnOpaqueProfileUrl(){
        val wrong=Jsoup.parse("""<html><head>
            <link rel="canonical" href="$address"/></head>
            <body><h1>Different Performer</h1></body></html>""",address)
        assertFalse(PerformerIdentity.matchesEvidence("Wu Mengmeng",address,wrong))
        val real=Jsoup.parse("""<html><head>
            <link rel="canonical" href="$address"/></head>
            <body><h1>Wu Mengmeng</h1></body></html>""",address)
        assertTrue(PerformerIdentity.matchesEvidence("Wu Mengmeng",address,real))
    }
}
