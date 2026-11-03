package com.azrael.galleryflow

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import com.azrael.pixivdumpsync.ResourceDownloads
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URI
import java.util.Locale

class ProviderWebActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") }
            ?: run {
                finish()
                return
            }

        if (shouldUseExternalBrowser(url)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("External browser required")
                .setMessage("This account provider requires a separate browser. "+
                    "Open it explicitly, or remain in Asterion.")
                .setPositiveButton("OPEN EXTERNAL BROWSER"){_,_->
                    runCatching{
                        startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                    }.onFailure{
                        Toast.makeText(this,it.message?:"Browser unavailable",
                            Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
                .setNegativeButton("CANCEL"){_,_->finish()}
                .setOnCancelListener{finish()}
                .show()
            return
        }

        window.statusBarColor = Color.rgb(17, 18, 22)
        window.navigationBarColor = Color.rgb(17, 18, 22)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "Provider page"
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = HttpClient.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val link=request.url.toString()
                    val folder=intent.getStringExtra(EXTRA_FOLDER).orEmpty()
                    val torrentFolder=when(folder){
                        "Games"->"Games"
                        "Adult"->"Adult"
                        "Books"->"Books"
                        else->null
                    }
                    if(torrentFolder!=null && (link.startsWith("magnet:?",true) ||
                        (link.startsWith("https://") &&
                            URI(link).path.orEmpty().endsWith(".torrent",true)))){
                        ResourceDownloads.route(this@ProviderWebActivity,link,torrentFolder)
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    CookieManager.getInstance().flush()
                    status.text = pageUrl ?: "Provider page"
                }
            }
            webChromeClient = WebChromeClient()
            setDownloadListener(providerDownloadListener())
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 18, 22))
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                webView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun shouldUseExternalBrowser(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return host.contains("terabox") ||
            host.endsWith("1024tera.com") ||
            host.endsWith("4funbox.com") ||
            host.endsWith("nephobox.com") ||
            host.endsWith("mirrobox.com") ||
            host.endsWith("momerybox.com") ||
            host == "accounts.google.com"
    }

    private fun providerDownloadListener() = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        val folder=intent.getStringExtra(EXTRA_FOLDER).orEmpty()
        if(folder in setOf("Games","Adult","Books") &&
            (url.startsWith("magnet:?",true) ||
                url.startsWith("https://") && runCatching{URI(url).path.endsWith(".torrent",true)}.getOrDefault(false))){
            ResourceDownloads.route(this,url,folder)
            return@DownloadListener
        }
        runCatching {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                "An accessible HTTPS original file URL is required"
            }
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val route = intent.getStringExtra(EXTRA_FOLDER)
                ?.takeIf { it in setOf("Artwork","Cosplay","Videos","Comics",
                    "Books","Games","Assets","References") }
            if(route=="Books" && !filename.endsWith(".epub",true))
                throw IllegalArgumentException("Books only accepts EPUB originals")
            if(route=="Comics" && !filename.endsWith(".zip",true))
                throw IllegalArgumentException("Comics only accepts ZIP archives")
            if(route=="Games" && Regex("(?i)\\.(?:mp4|mkv|webm|mov)$")
                    .containsMatchIn(filename))
                throw IllegalArgumentException("Games must contain game files, not videos")
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("User-Agent", userAgent ?: HttpClient.USER_AGENT)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                ResourceDownloads.privacyReferer(url,webView.url.orEmpty())?.let {
                    addRequestHeader("Referer", it)
                }
                val destination = if (route != null) {
                    val host = uri.host.orEmpty()
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                    "AsterionDownloader/" + route + "/" + host + "/" + safeFileName(filename)
                } else "Cosplay/GalleryFlow/provider_downloads/" + safeFileName(filename)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,destination)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id=manager.enqueue(request)
            com.azrael.pixivdumpsync.DirectDownloadTracker.record(
                this,id,safeFileName(filename),route?:"Cosplay",url)
        }.onSuccess {
            Toast.makeText(
                this,
                "Original download queued in Android Downloads",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: "Could not queue provider download", Toast.LENGTH_LONG).show()
        }
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(160)
        .ifBlank { "download.bin" }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FOLDER = "aloha_section_folder"
    }
}
