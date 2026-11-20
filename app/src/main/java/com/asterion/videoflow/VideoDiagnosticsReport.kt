package com.asterion.videoflow

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.azrael.pixivdumpsync.CrashDiagnostics
import com.azrael.pixivdumpsync.ThumbnailFailureJournal
import java.net.URI
import java.util.Date

/**
 * User-visible, private crash and download receipts for on-device debugging.
 * Reads SQLite and atomic transfer journals without changing the queue.
 * Signed media URLs, cookies, tokens, and private file paths are never printed.
 */
internal object VideoDiagnosticsReport {
    private val url=Regex("""(?i)https?://[^\s'"<>]+""")
    private val credentials=Regex(
        """(?i)(?:cookie|authorization|bearer|token|password|signature|apikey|api_key)\s*[:=]\s*[^\s;,]+""")
    internal fun sanitize(raw:String):String =
        raw.replace(url,"[media URL redacted]")
            .replace(credentials,"[credential redacted]")
            .replace(Regex("""[\r\n\t]+""")," ").trim().take(320)

    fun create(context:Context):String {
        val builder=StringBuilder("ASTERION DOWNLOAD DIAGNOSTICS\n")
        builder.append("Captured: ").append(Date()).append('\n')
        builder.append("Package: ").append(context.packageName).append('\n')
        builder.append("Android: ").append(Build.VERSION.RELEASE).append('\n')
        val am=context.getSystemService(ActivityManager::class.java)
        val memory=ActivityManager.MemoryInfo()
        if(am!=null)runCatching{
            am.getMemoryInfo(memory)
            builder.append("Available RAM: ").append(memory.availMem)
                .append(" bytes; low-memory: ").append(memory.lowMemory)
                .append("; secondary native-only: ")
                .append(VideoAgentAdmission.secondaryNativeOnly(
                    memory.availMem,memory.lowMemory)).append('\n')
        }
        builder.append("Expected embedded extractor: ")
            .append(VideoExtractorVersion.VERSION)
            .append(" (actual loaded version requires runtime check)\n")
        builder.append('\n')
        builder.append(runCatching{
            AppPrivateStorageAudit.report(context)
        }.getOrElse{"App storage scan unavailable: "+
            sanitize(it.javaClass.simpleName)+"\n"})
        val tabs=listOf("Active","Queued","Held","Failed","Paused")
        val rows=runCatching{
            VideoDb(context).use {db->
                tabs.flatMap { label->
                    db.listVideosForTab(label,40).map{label to it}
                }
            }
        }.getOrElse{
            builder.append("Queue read error: ")
                .append(sanitize(it.message.orEmpty())).append('\n')
            emptyList()
        }
        for((label,video) in rows.take(140)){
            val host=runCatching{
                URI(video.url).host.orEmpty().take(90)
            }.getOrDefault("")
            val stage=VideoTransferJournal.current(context,video.id)
            builder.append(label).append(" #").append(video.id)
                .append(" host=").append(host)
                .append(" state=").append(video.status)
                .append(" progress=").append(video.progress)
                .append(" stage=").append(stage?.stage?:"unknown")
                .append(" bytes=").append(stage?.verifiedBytes?:0L)
                .append(" stageUpdatedMs=").append(stage?.updated?:0L)
                .append(" recordUpdatedMs=").append(video.updated)
                .append(" lease=").append(video.lease)
            if(!stage?.detail.isNullOrBlank())
                builder.append(" lastStageDetail=")
                    .append(sanitize(stage?.detail.orEmpty()))
            if(!video.error.isNullOrBlank()){
                builder.append(" errorKind=")
                    .append(VideoFailurePolicy.classify(video.error))
                builder.append(" error=")
                    .append(sanitize(video.error))
            }
            builder.append('\n')
            if(label=="Failed"){
                val events=runCatching{
                    DownloaderEventJournal.forVideo(context,video.id,16)
                }.getOrNull()
                if(events!=null)builder.append("  stageTimeline=")
                    .append(events.toString()).append('\n')
            }
        }
        if(rows.size>140)builder.append("Additional entries omitted: ")
            .append(rows.size-140).append('\n')
        builder.append("\nTHUMBNAIL AND POSTER LOAD FAILURES\n")
        builder.append(ThumbnailFailureJournal.report(context,30)
            .ifBlank{"No locally recorded thumbnail failures"}).append('\n')
        val crash=CrashDiagnostics.safeForClipboard(context)
        builder.append("\nANDROID PROCESS EXIT AND CRASH RECORDS\n")
        builder.append(crash?.takeIf{it.isNotBlank()}
            ?: "No recorded crash or Android process exit trace")
        return builder.toString().take(60_000)
    }
}
