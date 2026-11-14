package com.azrael.pixivdumpsync

import java.util.Locale

/** Pure, Android-independent multilingual tokenizer for creator name ranking. */
internal object CreatorNameTokens {
    fun words(value:String):Set<String> =
        value.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter{word->
                word.length>=3 ||
                    (word.length>=2 && word.any{ch->
                        when(Character.UnicodeScript.of(ch.code)){
                            Character.UnicodeScript.HAN,
                            Character.UnicodeScript.HIRAGANA,
                            Character.UnicodeScript.KATAKANA,
                            Character.UnicodeScript.HANGUL->true
                            else->false
                        }
                    })
            }.toSet()
}
