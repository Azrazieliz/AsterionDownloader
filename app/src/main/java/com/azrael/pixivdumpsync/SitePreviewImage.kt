package com.azrael.pixivdumpsync

import org.jsoup.nodes.Element
import java.net.URI

/** Extracts a real image URL from provider markup, preferring source-advertised
 * larger variants. Never manufactures image addresses or substitutes site logos.
 */
internal object SitePreviewImage {
    private fun resolve(image:Element,raw:String):String? {
        val candidate=raw.trim().substringBefore(' ').trim()
        if(candidate.isBlank() || candidate.startsWith("data:",true))return null
        val uri=runCatching{
            URI(image.baseUri()).resolve(candidate).toString()
        }.getOrNull()?:return null
        // Never present a provider's lazy-load placeholder as an authentic
        // thumbnail. JAVIDEO currently serves /images/default-cover.* on
        // some list responses even though the movie page is real.
        val path=runCatching{URI(uri).path.orEmpty().lowercase()}
            .getOrDefault("")
        if(listOf("default-cover","placeholder","no-image","noimage",
                "blank.gif","spacer.gif","loading.gif")
                .any{it in path})return null
        return uri.takeIf{SourceIndexParser.safe(it)}
    }

    private fun srcset(image:Element,text:String):String? {
        val variants=text.split(',').mapNotNull { part ->
            val pieces=part.trim().split(Regex("\\s+"))
            val uri=resolve(image,pieces.firstOrNull().orEmpty())?:return@mapNotNull null
            val size=pieces.getOrNull(1).orEmpty().trim().let {
                it.removeSuffix("w").removeSuffix("x").toFloatOrNull()?:0f
            }
            uri to size
        }
        return variants.maxByOrNull{it.second}?.first
    }

    fun best(image:Element):String? {
        for(attribute in listOf("data-original","data-full","data-large",
                "data-lazy","data-lazyload","data-thumb","data-thumbnail",
                "data-preview","data-preview-src","data-poster",
                "data-thumb-url","data-thumbnail-url","data-image",
                "data-cover","data-echo","data-cfsrc")){
            resolve(image,image.attr(attribute))?.let{return it}
        }
        val picture=image.closest("picture")
        for(node in listOfNotNull(image)+picture?.select("source[srcset]").orEmpty()){
            for(attribute in listOf("data-srcset","srcset")){
                srcset(image,node.attr(attribute))?.let{return it}
            }
        }
        for(attribute in listOf("data-src","data-lazy-src",
                "data-original-src","data-flickity-lazyload",
                "data-background-image","src")){
            resolve(image,image.attr(attribute))?.let{return it}
        }
        return null
    }
}
