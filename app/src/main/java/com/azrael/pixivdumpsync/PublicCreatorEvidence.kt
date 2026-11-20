package com.azrael.pixivdumpsync

import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/**
 * Independent creator identity evidence from established publishers.
 * This proves the person exists, NOT that any unrelated publisher video
 * featuring their name belongs to them. Automatic video attribution still
 * requires PerformerIdentity.isProfileUrl + owner-linked video entries.
 */
internal object PublicCreatorEvidence {
    private val trustedHosts=setOf("wpb.shueisha.co.jp","yanmaga.jp")
    private val shueisha=Regex("^/gravure/[0-9]{8}-[0-9]{4,9}/?$")
    private val yanmaga=Regex("^/(?:columns/articles/[0-9]+|gravures/books/[^?#]+)$")

    fun isPublisherPage(address:String):Boolean {
        val uri=runCatching{URI(address)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
            uri.rawFragment!=null || uri.host.orEmpty().lowercase(Locale.ROOT)
                !in trustedHosts)return false
        val path=uri.path.orEmpty()
        return when(uri.host!!.lowercase(Locale.ROOT)){
            "wpb.shueisha.co.jp"->shueisha.matches(path)
            "yanmaga.jp"->yanmaga.matches(path)
            else->false
        }
    }

    private fun normalized(value:String)=Normalizer.normalize(
        value,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()

    fun publishedPerson(name:String,address:String,doc:Document):Boolean {
        if(!isPublisherPage(address))return false
        val wanted=normalized(name)
        if(wanted.length<5 || wanted.split(' ').size<2)return false
        val lead=buildString{
            append(doc.title()).append(' ')
            append(doc.selectFirst("h1")?.text().orEmpty()).append(' ')
            append(doc.select("meta[property=og:title],meta[name=description]")
                .eachAttr("content").joinToString(" ")).append(' ')
            // Magazine articles may have Japanese headline text and a
            // romanized name in the immediately following byline.
            append(doc.body()?.text()?.take(4200).orEmpty())
        }
        val actual=" "+normalized(lead)+" "
        return actual.contains(" "+wanted+" ")
    }

    /** Public sources directly checked against the publisher on 2026-11-20.
     * Stored identity evidence never establishes rights to third-party
     * videos or triggers automatic downloads.
     */
    fun verifiedReference(name:String):String?=when(normalized(name)){
        "tina kitsune"->"https://wpb.shueisha.co.jp/gravure/20260906-132709/"
        else->null
    }

    fun trustedProfileCandidate(name:String,address:String):Boolean =
        isPublisherPage(address) && normalized(name).isNotBlank()
}
