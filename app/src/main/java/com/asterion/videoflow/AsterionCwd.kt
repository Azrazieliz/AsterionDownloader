package com.asterion.videoflow

import java.io.File

/** Native process working directory for embedded yt-dlp temp-file creation.
 * The library is packaged for each supported Android ABI.
 */
internal object AsterionCwd {
    init { System.loadLibrary("asterioncwd") }
    private external fun cd(path:String):Int

    @Synchronized fun enter(directory:File) {
        require(directory.isDirectory && directory.canWrite()) {
            "Download staging is not writable"
        }
        val errno=cd(directory.absolutePath)
        check(errno==0) {"Cannot change download working directory (errno="+errno+")"}
    }
}
