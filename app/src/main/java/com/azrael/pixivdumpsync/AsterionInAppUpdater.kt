package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Direct in-app update path for the installed package. No Asterion Control
 * dependency, no private credentials, no silent installation and no uninstall.
 *
 * Trust: HTTPS GitHub release asset + matching published SHA-256 + exact APK
 * package identity + strictly newer versionCode + identical installed signer(s).
 * Signature mismatch is an error, never an invitation to wipe application data.
 */
object AsterionInAppUpdater {
    private const val REPO = "https://api.github.com/repos/Azrazieliz/AsterionDownloader"
    private const val STAGED = "asterion_update/verified_update.apk"
    private const val PREFS = "asterion_secure_updates"
    private const val MAX_APK = 550L * 1024 * 1024
    private val lock = AtomicBoolean(false)
    private val versionPattern = Regex(
        """^asterion-downloader-v(\d+)\.(\d+)\.(\d+)-(preview|release|production)-[a-f0-9]{8,40}$""",
        RegexOption.IGNORE_CASE
    )
    private val hashPattern = Regex("""^([A-Fa-f0-9]{64})\s+\*?(.+)$""")

    private data class Update(
        val version: String,
        val versionParts: List<Int>,
        val tag: String,
        val filename: String,
        val apkUrl: String,
        val checksumUrl: String,
        val expectedSize: Long
    )

    @Volatile private var candidate: Update? = null
    @Volatile private var status = "Updates have not been checked."

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installed(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, 0)

    private fun currentVersion(context: Context): String =
        installed(context).versionName.orEmpty()

    internal fun parts(value: String): List<Int>? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(value) ?: return null
        return match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            .takeIf { it.size == 3 }
    }

    internal fun newer(next: List<Int>, old: List<Int>): Boolean {
        for (i in 0..2) {
            if (next[i] != old[i]) return next[i] > old[i]
        }
        return false
    }

    /**
     * Redirects must remain in the GitHub release delivery infrastructure.
     * No cookies, app tokens or private application data are transmitted.
     */
    internal fun trusted(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        uri.scheme.equals("https", true) && uri.userInfo == null &&
            (host == "github.com" || host == "api.github.com" ||
                host == "release-assets.githubusercontent.com" ||
                host == "objects.githubusercontent.com")
    }.getOrDefault(false)

    private fun connect(address: String): HttpURLConnection {
        var url = address
        repeat(7) {
            require(trusted(url)) { "Untrusted update server" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 35_000
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            connection.setRequestProperty("User-Agent", "AsterionDownloader-UpdateChecker")
            val code = connection.responseCode
            if (code in 300..399) {
                val target = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Update redirect has no destination")
                url = URI(url).resolve(target).toString()
                connection.disconnect()
            } else {
                check(code in 200..299) { "Update server HTTP " + code }
                return connection
            }
        }
        error("Too many update redirects")
    }

    private fun readBounded(url: String, max: Int): String {
        val connection = connect(url)
        try {
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count == -1) break
                    check(out.size() + count <= max) { "Update metadata exceeds size limit" }
                    out.write(chunk, 0, count)
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun discover(context: Context): Update? {
        val isPreview = context.packageName.endsWith(".nocturne")
        val current = parts(currentVersion(context)) ?: listOf(0, 0, 0)
        val results = JSONArray(readBounded(REPO + "/releases?per_page=40", 450_000))
        var best: Update? = null
        for (i in 0 until results.length()) {
            val release = results.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name")
            val match = versionPattern.matchEntire(tag) ?: continue
            val channel = match.groupValues[4].lowercase(Locale.ROOT)
            if (isPreview != (channel == "preview")) continue
            val v = match.groupValues.subList(1, 4).map { it.toInt() }
            if (!newer(v, current) || (best != null && !newer(v, best.versionParts))) continue
            val version = v.joinToString(".")
            val filename = "Asterion-Downloader-v" + version +
                if (isPreview) "-Preview.apk" else "-Release.apk"
            val assets = release.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            var checksumUrl: String? = null
            var size = 0L
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://github.com/Azrazieliz/AsterionDownloader/releases/download/")) continue
                if (name == filename) {
                    apkUrl = url
                    size = asset.optLong("size")
                } else if (name == filename.removeSuffix(".apk") + ".sha256") {
                    checksumUrl = url
                }
            }
            if (apkUrl != null && checksumUrl != null && size in 1..MAX_APK) {
                best = Update(version, v, tag, filename, apkUrl, checksumUrl, size)
            }
        }
        return best
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    internal fun checksumFromRaw(raw: String, filename: String): String {
        val hashes = raw.lineSequence().mapNotNull { line ->
            val matched = hashPattern.matchEntire(line.trim()) ?: return@mapNotNull null
            if (matched.groupValues[2].trim().substringAfterLast('/') == filename)
                matched.groupValues[1].lowercase(Locale.ROOT)
            else null
        }.distinct().toList()
        check(hashes.size == 1) { "Release does not contain one matching APK checksum" }
        return hashes.single()
    }

    private fun expectedHash(update: Update): String =
        checksumFromRaw(readBounded(update.checksumUrl, 16_384), update.filename)

    @Suppress("DEPRECATION")
    private fun validateArchive(context: Context, file: File) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Invalid Android update package")
        val local = manager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) {
            "APK targets a different application; existing data were preserved"
        }
        require(archive.longVersionCode > local.longVersionCode) {
            "APK is not a newer version of the installed application"
        }
        val incoming = archive.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val present = local.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        require(incoming.isNotEmpty() && present.isNotEmpty() && incoming == present) {
            "Signing certificate differs from installed app. Update blocked to preserve your data."
        }
    }

    private fun verifiedFile(context: Context) =
        File(context.cacheDir, STAGED)

    private fun savedUpdateReady(context: Context): Boolean {
        val prefs = preferences(context)
        val file = verifiedFile(context)
        return file.isFile && file.length() > 0 &&
            prefs.getLong("ready_version_code", 0L) > installed(context).longVersionCode &&
            prefs.getString("ready_sha256", null) != null
    }

    private fun verifyStagedAgain(context: Context) {
        val file = verifiedFile(context)
        require(file.isFile && file.length() > 0) { "No staged update file" }
        val expected = preferences(context).getString("ready_sha256", null)
            ?: error("No verified update checksum")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                digest.update(chunk, 0, count)
            }
        }
        check(sha256Hex(digest.digest()) == expected) {
            "Cached update checksum changed; installation blocked"
        }
        validateArchive(context, file)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun download(context: Context, update: Update, changed: () -> Unit) {
        val checksum = expectedHash(update)
        val final = verifiedFile(context)
        check(final.parentFile?.mkdirs() == true || final.parentFile?.isDirectory == true)
        val temp = File(final.parentFile, "incoming_update.part")
        temp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val connection = connect(update.apkUrl)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(temp).buffered().use { output ->
                    val chunk = ByteArray(64 * 1024)
                    var lastProgress = -1
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        written += count
                        check(written <= MAX_APK) { "APK larger than allowed maximum" }
                        digest.update(chunk, 0, count)
                        output.write(chunk, 0, count)
                        val percent = (written * 100L / update.expectedSize).toInt().coerceIn(0, 99)
                        if (percent / 10 != lastProgress / 10) {
                            lastProgress = percent
                            status = "Downloading verified update · " + percent + "%"
                            changed()
                        }
                    }
                }
            }
            require(written == update.expectedSize) {
                "Downloaded APK size does not match the published release"
            }
            check(sha256Hex(digest.digest()) == checksum) {
                "APK SHA-256 does not match release checksum"
            }
            validateArchive(context, temp)
            if (final.exists()) check(final.delete()) { "Unable to replace old update cache" }
            check(temp.renameTo(final)) { "Unable to stage verified APK" }
            val archive = context.packageManager.getPackageArchiveInfo(final.absolutePath, 0)
                ?: error("Staged APK cannot be inspected")
            preferences(context).edit()
                .putLong("ready_version_code", archive.longVersionCode)
                .putString("ready_sha256", checksum)
                .putString("ready_version_name", archive.versionName)
                .apply()
            status = "APK verified and ready for Android installation."
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun launchInstaller(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            status = "Allow installation from Asterion Downloader, return, then tap Install."
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:" + activity.packageName)
            })
            return
        }
        val uri = Uri.parse("content://" + activity.packageName + ".update-apk/update.apk")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Verified Downloader update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        status = "Android installer opened; confirm the update to keep existing data."
        activity.startActivity(intent)
    }

    fun render(activity: Activity, parent: LinearLayout, refresh: () -> Unit) {
        val context = activity.applicationContext
        val prefs = preferences(context)
        val currentCode = installed(context).longVersionCode
        if (prefs.getLong("ready_version_code", 0) in 1..currentCode) {
            status = "Update installed. Application version verified by Android."
            prefs.edit().remove("ready_version_code").remove("ready_sha256")
                .remove("ready_version_name").apply()
            verifiedFile(context).delete()
        }
        val ui = NocturneDesign
        val panel = ui.card(activity)
        panel.addView(ui.text(activity, "APPLICATION UPDATES", 16f, ui.white, true))
        panel.addView(ui.text(activity,
            "Installed " + currentVersion(context) + " · signed in-place updates",
            12f, ui.secondary), ui.lp(activity, 5))
        panel.addView(ui.text(activity, status, 12f, ui.secondary), ui.lp(activity, 8))
        if (lock.get()) {
            panel.addView(ui.text(activity, "Update check or download in progress",
                12f, ui.primary), ui.lp(activity, 8))
        } else {
            panel.addView(ui.action(activity, "CHECK FOR UPDATE", false) {
                if (!lock.compareAndSet(false, true)) return@action
                status = "Checking official releases…"
                refresh()
                thread(name = "asterion-updater-check") {
                    runCatching { discover(context) }
                        .onSuccess { next ->
                            candidate = next
                            status = if (next == null) "No newer published signed update available."
                                else "New release available: " + next.version
                        }.onFailure { error ->
                            status = "Update check failed: " + (error.message ?: "Network error")
                        }
                    lock.set(false)
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) refresh()
                    }
                }
            }, ui.lp(activity, 9))
            val ready = savedUpdateReady(context)
            val selected = candidate
            if (ready || selected != null) {
                val label = if (ready) "INSTALL VERIFIED UPDATE"
                    else "DOWNLOAD & INSTALL " + selected!!.version
                panel.addView(ui.action(activity, label, true) {
                    if (!lock.compareAndSet(false, true)) return@action
                    status = if (ready) "Checking staged APK integrity…"
                        else "Downloading and verifying update…"
                    refresh()
                    thread(name = "asterion-updater-install") {
                        val result = runCatching {
                            if (ready) verifyStagedAgain(context)
                            else if (selected != null) download(context, selected) {
                                activity.runOnUiThread {
                                    if (!activity.isFinishing && !activity.isDestroyed) refresh()
                                }
                            }
                        }
                        lock.set(false)
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                result.onSuccess {
                                    runCatching { launchInstaller(activity) }
                                        .onFailure { error ->
                                            status = "Installer unavailable: " +
                                                (error.message ?: "Android error")
                                        }
                                }.onFailure { error ->
                                    status = "Update blocked: " +
                                        (error.message ?: "Verification failed")
                                }
                                refresh()
                            }
                        }
                    }
                }, ui.lp(activity, 8))
            }
        }
        panel.addView(ui.text(activity,
            "Only an APK with the installed application's signing certificate can update it. " +
                "Android confirms installation; no uninstallation or data clearing is performed.",
            11f, ui.secondary), ui.lp(activity, 9))
        parent.addView(panel, ui.lp(activity, 10))
    }
}
