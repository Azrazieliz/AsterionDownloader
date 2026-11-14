package com.azrael.pixivdumpsync

import java.util.Locale

/** A caution signal, NOT an antivirus engine. Content hashes from BitTorrent
 * prove piece integrity, never absence of viruses or unwanted executables.
 * Flag risky filenames without restricting manual selection.
 */
internal object TorrentContentRisk {
    private val executable=setOf("exe","com","scr","msi","msp","bat","cmd",
        "ps1","psm1","vbs","vbe","js","jse","wsf","wsh","hta","lnk","url",
        "reg","apk","jar","dex","sh","desktop","appref-ms")
    private val macro=setOf("docm","xlsm","pptm","xlam","xll")
    private val fakeMedia=Regex(
        """(?i)\.(?:jpe?g|png|webp|gif|mp4|mkv|mov|pdf|epub|zip)\.(?:exe|scr|com|bat|cmd|apk|js)$""")
    fun reviewReason(path:String):String? {
        val normalized=path.replace('\\','/')
            .lowercase(Locale.ROOT).trim()
        val file=normalized.substringAfterLast('/')
        if(normalized.startsWith("/") || normalized.split('/').any{it==".."})return "Unsafe path"
        if(normalized.contains('\u202e') || normalized.contains('\u202d') ||
            normalized.contains('\u2066') || normalized.contains('\u2067'))return "Deceptive Unicode filename"
        if(fakeMedia.containsMatchIn(file))return "Disguised executable"
        val ext=file.substringAfterLast('.', "")
        return when {
            ext in executable->"Executable or script; scan before opening"
            ext in macro->"Macro-capable document; scan before opening"
            else->null
        }
    }
    /** Risky entries remain selectable deliberately, but never prechecked.
     * Games used to auto-select arbitrary .exe/.js scripts, exposing users
     * to stealth payloads before the private-quarantine gate.
     */
    fun selectByDefault(folder:String,path:String):Boolean =
        reviewReason(path)==null &&
        (folder!="Books" || path.substringAfterLast('.').equals("epub",true))
}
