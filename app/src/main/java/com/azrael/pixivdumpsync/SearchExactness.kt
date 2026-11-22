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

    /** Descriptive global-video searches need every significant word,
     * not necessarily the same word order. This is ONLY discovery metadata;
     * creator identity verification continues to require matchesPhrase.
     */
    fun mediaMatches(query:String,title:String):Boolean =
        matchesPhrase(query,title) || matches(query,title)

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
