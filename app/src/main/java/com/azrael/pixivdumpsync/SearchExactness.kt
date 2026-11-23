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

    /** Two-word performer identities are ordered and must use complete
     * tokens; descriptive queries remain broad (a documentary needn't
     * repeat the user's keyword order in its title). This specifically
     * prevents "Bella Alice" matching unrelated Bella + Alice performers.
     */
    private val descriptiveWords=setOf(
        "documentary","documentaries","movie","movies","film","films",
        "video","videos","scene","scenes","episode","episodes",
        "trailer","trailers","tutorial","tutorials","review","reviews",
        "game","games","music","song","songs","full","complete",
        "official","funny","best","hentai","porn","gallery","photos"
    )
    private fun likelyPersonName(query:String,requested:List<String>):Boolean {
        if(requested.size !in 2..4 ||
            requested.any{it.length<2 || !it.all(Char::isLetter)} ||
            requested.any{it in descriptiveWords})return false
        return requested.size==2 || query.any(Char::isUpperCase)
    }
    fun mediaMatches(query:String,title:String):Boolean {
        val requested=terms(query)
        if(requested.isEmpty())return false
        if(likelyPersonName(query,requested)){
            val actual=terms(title)
            return actual.windowed(requested.size).any{it==requested}
        }
        return matches(query,title)
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
