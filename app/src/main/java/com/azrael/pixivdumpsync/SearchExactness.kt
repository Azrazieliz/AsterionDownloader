package com.azrael.pixivdumpsync

import java.text.Normalizer
import java.util.Locale

/** Exact title/performer search: search engines may retrieve many candidates but the
 * user sees only items matching every significant query term.
 */
object SearchExactness {
    private fun terms(text:String):List<String> =
        Normalizer.normalize(text,Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter{it.isNotBlank()}

    fun matches(query:String,title:String):Boolean {
        val required=terms(query)
        val actual=terms(title)
        if(required.isEmpty() || actual.isEmpty())return false
        return required.all { word->
            actual.any{it==word} ||
                (word.length>3 && actual.any{it.startsWith(word)})
        }
    }
}
