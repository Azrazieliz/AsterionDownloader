package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Android 17 crash-proof source contract. A background JobScheduler callback
 * cannot launch an FGS. Runtime device validation remains necessary.
 */
class SyncJobServiceAndroid17RegressionTest {
    @Test fun scheduledGallerySyncNeverLaunchesForbiddenForegroundService(){
        val cwd=File(System.getProperty("user.dir") ?: ".")
        val module=if(File(cwd,"src/main/java").isDirectory)cwd
            else File(cwd,"app")
        val file=File(module,
            "src/main/java/com/azrael/galleryflow/SyncJobService.kt")
        assertTrue("Gallery JobService source missing",file.isFile)
        // Inspect executable source, not prose: the JobService's KDoc
        // describes the old startForegroundService() crash. That historical
        // mention must not fail a source regression test.
        val source=file.readText()
            .replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""(?m)//[^\r\n]*$"""), "")
        assertFalse("Android background JobService must never start an FGS",
            source.contains("startForegroundService("))
        assertFalse("Scheduled job must not call startService either",
            source.contains("startService("))
        assertTrue("Must execute asynchronously under JobScheduler",
            source.contains("executor.submit"))
        assertTrue("Must finish the job only after the worker returns",
            source.contains("jobFinished(params,false)"))
        assertTrue("Must cancel when JobScheduler stops the job",
            source.contains("onStopJob(") && source.contains("worker?.cancel(true)"))
    }
}
