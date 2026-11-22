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

    /**
     * Model/person queries are identities, not an unordered bag of words.
     * "Bella Alice" must not match "Barbara Bella and Alice Marshall".
     * Keep the general unordered matcher for descriptive and torrent queries.
     */
    fun matchesPhrase(query:String,title:String):Boolean {
        val requested=terms(query)
        val candidate=terms(title)
        if(requested.isEmpty() || candidate.size<requested.size)return false
        return candidate.windowed(requested.size).any{slice->
            requested.zip(slice).all{(q,t)->
                t==q || (q.length>3 && t.startsWith(q))
            }
        }
    }

    /** In the unified video search, two or more name/title words are one
     * ordered identity. The prior unordered OR fallback turned "Bella Alice"
     * into "Barbara Bella and Alice Marshall", an unrelated recording.
     * Broad unordered keyword matching remains available for other explicit
     * metadata uses via matches(), but not to claim an exact video hit.
     */
    fun mediaMatches(query:String,title:String):Boolean {
        val requested=terms(query)
        if(requested.isEmpty())return false
        // A model name must match full tokens, not a prefix such as
        // "Alice" matching "Alicez". Loose prefix matching still exists
        // for explicit autocomplete via matches()/matchesPhrase().
        return if(requested.size>=2){
            val actual=terms(title)
            actual.windowed(requested.size).any{it==requested}
        }else matches(query,title)
    }

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
