package com.azrael.pixivdumpsync

/** Strict recovery of already owner-selected, explicitly deferred torrents.
 * No source discovery, no manifest re-download and no selection guessing.
 */
internal object TorrentDeferredSelection {
    private val jobId=Regex("[a-f0-9]{24}")
    private val allowed=setOf("Games","Books","Videos","Assets")
    fun recover(values:Map<String,*>):List<String> {
        return values.keys.asSequence()
            .filter{it.startsWith("job_deferred_")}
            .map{it.removePrefix("job_deferred_")}
            .filter{jobId.matches(it)}
            .filter{values["job_deferred_$it"]==true &&
                values["job_cancelled_$it"]!=true &&
                values["job_paused_$it"]!=true}
            .filter{(values["job_folder_$it"] as? String)?.let(allowed::contains)==true}
            .filter{id->
                val raw=values["job_indices_$id"] as? String ?:return@filter false
                raw.isNotBlank() && raw.length<=130_000 &&
                    raw.split(',').all{index->
                        index.isNotBlank() && index.length<=6 &&
                            index.all(Char::isDigit) &&
                            (index.toIntOrNull()?:-1) in 0..19999
                    }
            }
            .sorted().toList()
    }
}
