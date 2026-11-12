package com.azrael.pixivdumpsync

import java.text.Normalizer
import java.util.Locale

/** Prevent a two-letter/generic query from masquerading as a unique model.
 * Multi-word, deliberately selected creator names can still be tracked even
 * when their profiles are outside the supported provider network.
 */
internal object PerformerFollowPolicy {
    private val generic=setOf(
        "model","models","girl","girls","woman","women","porn","video",
        "videos","unknown","test","sample","artist","performer"
    )
    fun permittedName(raw:String):Boolean {
        val name=Normalizer.normalize(raw.trim(),Normalizer.Form.NFKC)
            .replace(Regex("\\s+")," ")
        if(name.length !in 3..100 || name.contains("://") ||
            name.contains('@') || name.contains('/') ||
            name.any{it.isISOControl()})return false
        val terms=name.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter{it.isNotBlank()}
        if(terms.isEmpty() || terms.all{it in generic})return false
        if(terms.size==1 && terms.first().length<3)return false
        // A two-character abbreviation like "JJ" is not sufficient proof of
        // identity. Multiple meaningful words, e.g. Tina Kitsune, are allowed.
        return terms.size>=2 || terms.first().length>=3
    }
}
