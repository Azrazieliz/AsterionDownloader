package com.azrael.galleryflow

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.azrael.pixivdumpsync.NocturneDesign as D
import java.util.concurrent.Executors

/** A visual gallery archive with bounded batches of thumbnail decoding. */
class GalleryDetailActivity : Activity() {
    companion object {
        const val EXTRA_GALLERY_ID = "gallery_id"
        const val EXTRA_SOURCE = "gallery_source"
        const val EXTRA_TITLE = "gallery_title"
    }
    private val pool = Executors.newFixedThreadPool(2)
    private lateinit var grid: GridLayout
    private lateinit var loadMore: TextView
    private var shown = 0
    private var images: List<MediaRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = D.background
        window.navigationBarColor = D.background
        val id = intent.getStringExtra(EXTRA_GALLERY_ID).orEmpty()
        val source = runCatching {
            SourceId.fromWire(intent.getStringExtra(EXTRA_SOURCE).orEmpty())
        }.getOrNull()
        if (source == null || id.isEmpty()) { finish(); return }
        val (saved,albumUrl)=GalleryDb(this).use { db ->
            db.galleryMedia(source,id) to db.galleryCanonicalUrl(source,id)
        }
        images=saved.filter{
            it.contentUri != null && it.state == TransferState.COMPLETE &&
                it.filename.orEmpty().matches(
                    Regex("""(?i)^.*\.(?:jpg|jpeg|png|gif|webp|avif)$"""))
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(D.background)
        }
        val top = D.row(this).apply { setPadding(dp(15), dp(15), dp(18), dp(15)) }
        top.addView(D.text(this, "‹", 36f, D.secondary).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back to source"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(38), dp(46)))
        top.addView(D.brandMark(this, 40))
        top.addView(D.text(this, "GALLERY ARCHIVE", 14f, D.white, true).apply {
            setPadding(dp(12), 0, 0, 0)
            letterSpacing = .12f
        })
        shell.addView(top)
        shell.addView(View(this).apply { setBackgroundColor(D.border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(35))
        }
        scroll.addView(root)
        shell.addView(scroll)
        root.addView(D.eyebrow(this, "NOCTURNE / IMAGES"))
        root.addView(D.text(this, intent.getStringExtra(EXTRA_TITLE) ?: "Gallery",
            26f, D.white, true), D.lp(this, 8))
        root.addView(D.text(this, images.size.toString() + " downloaded images",
            13f, D.secondary), D.lp(this, 6, 21))
        if (images.isEmpty()) {
            root.addView(D.card(this).apply {
                addView(D.text(this@GalleryDetailActivity, "Gallery not downloaded",
                    17f, D.white, true))
                addView(D.text(this@GalleryDetailActivity,
                    "Run a sync or backfill to save the images locally.",
                    13f, D.secondary), D.lp(this@GalleryDetailActivity, 7))
            })
        }
        grid = GridLayout(this).apply { columnCount = 2 }
        root.addView(grid)
        loadMore = D.action(this, "Show more images") { addPage() }
        root.addView(loadMore, D.lp(this, 16))
        // CosplayTele can have an independently completed video fallback
        // in VideoFlow. Keep that exact same local file accessible from its
        // gallery as well, without copying it or calling a queued item saved.
        if(source==SourceId.COSPLAYTELE){
            val videos=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                visibility=View.GONE
            }
            root.addView(videos,D.lp(this,16))
            pool.execute{
                val originals=LinkedHashMap<String,String>()
                for(record in saved){
                    if(record.state!=TransferState.COMPLETE ||
                        !record.filename.orEmpty().matches(
                            Regex("""(?i)^.*\.(?:mp4|m4v|webm|mov|mkv|avi|ts)$""")))continue
                    val value=record.contentUri?:continue
                    val uri=Uri.parse(value)
                    if(uri.scheme!="content")continue
                    val readable=runCatching{
                        contentResolver.openFileDescriptor(uri,"r")
                            ?.use{it.statSize>0L}?:false
                    }.getOrDefault(false)
                    if(readable)originals[value]=record.filename.orEmpty()
                }
                if(!albumUrl.isNullOrBlank()){
                    val fallback=runCatching{
                        com.asterion.videoflow.VideoDb(this).use{
                            it.verifiedLocalVideoUriForUrl(albumUrl)
                        }
                    }.getOrNull()
                    if(fallback!=null)originals.putIfAbsent(fallback,"Saved article video")
                }
                runOnUiThread{
                    if(isFinishing || isDestroyed || originals.isEmpty())return@runOnUiThread
                    videos.visibility=View.VISIBLE
                    videos.addView(D.text(this,"Saved videos · "+originals.size,
                        15f,D.white,true))
                    for((uri,label) in originals){
                        videos.addView(D.action(this,label){
                            runCatching{
                                startActivity(Intent(Intent.ACTION_VIEW).apply{
                                    setDataAndType(Uri.parse(uri),"video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }.onFailure{
                                android.widget.Toast.makeText(this,
                                    "No compatible video player",android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },D.lp(this,8))
                    }
                }
            }
        }
        setContentView(shell)
        addPage()
    }

    private fun addPage() {
        val upto = minOf(shown + 24, images.size)
        val width = (resources.displayMetrics.widthPixels - dp(48)) / 2
        for (i in shown until upto) {
            val record = images[i]
            val view = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = D.shape(this@GalleryDetailActivity, D.raised, 13)
                isClickable = true
                isFocusable = true
                contentDescription = "Open image " + (i + 1)
                setOnClickListener {
                    val uri = Uri.parse(record.contentUri)
                    val open = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { startActivity(open) }
                }
            }
            grid.addView(view, GridLayout.LayoutParams().apply {
                this.width = width
                height = width
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
            val uri = Uri.parse(record.contentUri)
            pool.execute {
                val bitmap = runCatching {
                    contentResolver.loadThumbnail(uri, Size(420, 420), null)
                }.getOrNull()
                if (bitmap != null) runOnUiThread {
                    if (!isFinishing && view.parent != null) view.setImageBitmap(bitmap)
                }
            }
        }
        shown = upto
        loadMore.visibility = if (shown < images.size) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int) = D.px(this, value)
}
