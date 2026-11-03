package com.azrael.galleryflow

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.util.Size
import java.util.concurrent.Executors
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.view.View
import com.azrael.pixivdumpsync.NocturneDesign

class MainActivity : Activity() {
    private var siteFilter: String? = null
    private val thumbnailPool = Executors.newFixedThreadPool(2)
    private lateinit var addressInput: EditText
    private var requestedSiteLayout: String = "auto"
    private lateinit var status: TextView
    private lateinit var entityList: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val snapshot = SyncControl.snapshot()
            if (page == Page.TRANSFERS) status.text = snapshot.message
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NocturneDesign.background
        window.navigationBarColor = NocturneDesign.background
        GalleryDb(this).use{it.seedCatalogOnce(this)}
        siteFilter = intent.getStringExtra(EXTRA_SITE_ID)
        setContentView(buildUi())
        if (intent.getBooleanExtra(EXTRA_ADD_SITE, false)) {
            addressInput.requestFocus()
        }
        requestNotificationPermission()
        Scheduler.ensure(this)
    }

    override fun onResume() {
        super.onResume()
        renderEntities()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onDestroy() {
        thumbnailPool.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private enum class Page { LIBRARY, DISCOVER, TRANSFERS, SETTINGS }
    private var page = Page.LIBRARY
    private lateinit var panel: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var scroller: ScrollView
    private val ui get() = NocturneDesign

    private fun buildUi(): LinearLayout {
        if (intent.getBooleanExtra(EXTRA_ADD_SITE, false)) page = Page.DISCOVER
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
        }
        val head = ui.row(this@MainActivity).apply {
            setPadding(dp(15), dp(14), dp(18), dp(14))
        }
        head.addView(ui.text(this@MainActivity, "‹", 34f, ui.secondary).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back to Asterion Downloader"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(35), dp(45)))
        head.addView(ui.brandMark(this@MainActivity, 43), LinearLayout.LayoutParams(dp(43), dp(43)))
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        words.addView(ui.text(this@MainActivity, "ASTERION", 17f, ui.white, true).apply {
            letterSpacing = .16f
        })
        words.addView(ui.text(this@MainActivity, "IMAGES / GALLERIES", 10f, ui.secondary, true).apply {
            letterSpacing = .12f
        })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.chip(this@MainActivity, "Hub") { finish() })
        shell.addView(head)
        shell.addView(View(this).apply { setBackgroundColor(ui.border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        scroller = ScrollView(this).apply { clipToPadding = false; isFillViewport = false }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(35))
        }
        scroller.addView(panel)
        shell.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        bottom = ui.row(this@MainActivity).apply {
            setPadding(dp(11), dp(10), dp(11), dp(10))
            setBackgroundColor(ui.surface)
        }
        shell.addView(bottom)
        status = ui.text(this@MainActivity, "Idle", 13f, ui.primary)
        renderCurrent()
        return shell
    }

    private fun go(next: Page) {
        page = next
        renderCurrent()
        scroller.post { scroller.scrollTo(0, 0) }
    }

    private fun renderCurrent() {
        if (!::panel.isInitialized) return
        panel.removeAllViews()
        when (page) {
            Page.LIBRARY -> renderLibrary()
            Page.DISCOVER -> renderDiscover()
            Page.TRANSFERS -> renderTransfers()
            Page.SETTINGS -> renderSettings()
        }
        bottom.removeAllViews()
        listOf(Page.LIBRARY to "Library", Page.DISCOVER to "Add source",
            Page.TRANSFERS to "Transfers", Page.SETTINGS to "Settings")
            .forEach { (target, title) ->
                val active = target == page
                val tab = ui.text(this@MainActivity, title, 12f,
                    if (active) ui.primary else ui.secondary, active).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(46)
                    background = if (active) ui.shape(this@MainActivity,
                        ui.soft, 13, ui.soft) else null
                    setOnClickListener { if (page != target) go(target) }
                    isClickable = true
                    isFocusable = true
                }
                bottom.addView(tab, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    leftMargin = dp(2); rightMargin = dp(2)
                })
            }
    }

    private fun heading(title: String, description: String) {
        panel.addView(ui.eyebrow(this@MainActivity, "ASTERION / KYORA"))
        panel.addView(ui.text(this@MainActivity, title, 30f, ui.white, true), ui.lp(this@MainActivity, 5))
        panel.addView(ui.text(this@MainActivity, description, 13f, ui.secondary),
            ui.lp(this@MainActivity, 3, 22))
    }

    private fun section(title: String, extra: String = "") {
        val row = ui.row(this@MainActivity)
        row.addView(ui.text(this@MainActivity, title, 18f, ui.white, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (extra.isNotBlank()) row.addView(ui.text(this@MainActivity, extra, 12f, ui.subdued))
        panel.addView(row, ui.lp(this@MainActivity, 25, 12))
    }

    private fun message(title: String, detail: String): LinearLayout =
        ui.card(this@MainActivity).apply {
            addView(ui.text(this@MainActivity, title, 18f, ui.white, true))
            addView(ui.text(this@MainActivity, detail, 13f, ui.secondary),
                ui.lp(this@MainActivity, 7))
        }

    private fun buttonPair(a: String, b: String, fa: () -> Unit, fb: () -> Unit) =
        ui.weighted(this@MainActivity, ui.action(this@MainActivity, a, true, fa), ui.action(this@MainActivity, b, false, fb))

    private fun switched(title: String, initial: Boolean,
                         onChange: (Boolean) -> Unit): LinearLayout =
        ui.row(this@MainActivity).apply {
            addView(ui.text(this@MainActivity, title, 14f, ui.white),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = initial
                thumbTintList = android.content.res.ColorStateList.valueOf(ui.primary)
                setOnCheckedChangeListener { _, value -> onChange(value) }
            })
        }

    private fun renderLibrary() {
        val entities = GalleryDb(this).use { it.listEntities() }
        val current = entities.firstOrNull { it.entityId == siteFilter }
        if (current != null) {
            renderDetail(current)
            return
        }
        siteFilter = null
        heading("Kyora · Sites & galleries", "Original supported sites and your saved creators, managed together.")
        val hero = ui.card(this@MainActivity, 21).apply {
            background = ui.shape(this@MainActivity, ui.soft, 23)
            addView(ui.eyebrow(this@MainActivity, "CONNECTED SOURCES"))
            addView(ui.text(this@MainActivity, entities.size.toString(), 42f,
                ui.primary, true), ui.lp(this@MainActivity, 4))
            addView(ui.text(this@MainActivity,
                if (entities.isEmpty()) "Add your first image website."
                else "Your source catalog is ready.",
                13f, ui.secondary), ui.lp(this@MainActivity, 3, 17))
            addView(buttonPair("Add a website", "Transfers",
                { go(Page.DISCOVER) }, { go(Page.TRANSFERS) }))
        }
        panel.addView(hero)
        panel.addView(buttonPair("Enable all sources","Disable all sources",
            { GalleryDb(this).use{it.setAllSelected(true);it.setAllLiveEnabled(true)}; renderCurrent() },
            { GalleryDb(this).use{it.setAllSelected(false);it.setAllLiveEnabled(false)}; renderCurrent() }),
            ui.lp(this,13))
        section("Saved websites", entities.size.toString() + " SOURCES")
        if (entities.isEmpty()) {
            panel.addView(message("Your library is empty",
                "Add a cosplay website, creator page or individual gallery URL when you need it."))
            panel.addView(ui.action(this@MainActivity, "Add a website") {
                go(Page.DISCOVER)
            }, ui.lp(this@MainActivity, 13))
            return
        }
        entities.forEach { entity ->
            val domain = runCatching {
                java.net.URI(entity.canonicalUrl).host.orEmpty().removePrefix("www.")
            }.getOrDefault(entity.source.wireName)
            val tile = ui.row(this@MainActivity).apply {
                setPadding(dp(15), dp(16), dp(15), dp(16))
                background = ui.touch(this@MainActivity, ui.surface, 19)
                isClickable = true
                isFocusable = true
                setOnClickListener { siteFilter = entity.entityId; renderCurrent() }
            }
            tile.addView(ui.text(this@MainActivity, domain.take(1).uppercase(), 20f, ui.primary, true).apply {
                gravity = Gravity.CENTER
                background = ui.shape(this@MainActivity, ui.soft, 14, ui.soft)
            }, LinearLayout.LayoutParams(dp(49), dp(49)))
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            content.addView(ui.text(this@MainActivity, entity.displayName, 16f, ui.white, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            content.addView(ui.text(this@MainActivity,
                if (!entity.lastError.isNullOrBlank()) "Check connection"
                else if (entity.liveEnabled) domain + "  ·  Live enabled"
                else domain + "  ·  Manual sync",
                12f, if (!entity.lastError.isNullOrBlank()) ui.danger else ui.secondary))
            tile.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
            tile.addView(ui.text(this@MainActivity, "›", 27f, ui.primary))
            panel.addView(tile, ui.lp(this@MainActivity, 0, 10))
        }
    }

    private fun renderDetail(entity: EntityRecord) {
        panel.addView(ui.chip(this@MainActivity, "‹ All websites") {
            siteFilter = null
            renderCurrent()
        }, ui.lp(this@MainActivity, 0, 16))
        val domain = runCatching { java.net.URI(entity.canonicalUrl).host.orEmpty() }
            .getOrDefault(entity.source.wireName)
        heading(entity.displayName, domain)
        val card = ui.card(this@MainActivity, 21)
        card.addView(ui.eyebrow(this@MainActivity, "SOURCE CONTROLS"))
        card.addView(ui.text(this@MainActivity, "Sync this website", 22f, ui.white, true), ui.lp(this@MainActivity, 7))
        card.addView(ui.text(this@MainActivity,
            "Get recent posts or download older galleries.", 13f, ui.secondary),
            ui.lp(this@MainActivity, 5, 16))
        card.addView(buttonPair("Sync now", "Backfill",
            { startSync(SyncMode.LIVE) }, { startSync(SyncMode.BACKFILL) }))
        card.addView(ui.action(this@MainActivity, "Block tags for this website") {
            showSiteExcludedTagsDialog(entity.entityId)
        }, ui.lp(this@MainActivity, 10))
        card.addView(switched("Include in bulk sync", entity.selected) {
            GalleryDb(this).use { db -> db.setSelected(entity.source, entity.entityId, it) }
        }, ui.lp(this@MainActivity, 14))
        card.addView(switched("Check automatically", entity.liveEnabled) {
            GalleryDb(this).use { db -> db.setLiveEnabled(entity.source, entity.entityId, it) }
        }, ui.lp(this@MainActivity, 6))
        if (!entity.lastError.isNullOrBlank())
            card.addView(ui.text(this@MainActivity, "Last issue: " + entity.lastError, 12f, ui.danger),
                ui.lp(this@MainActivity, 11))
        card.addView(ui.action(this@MainActivity, "Remove website from library") {
            AlertDialog.Builder(this)
                .setTitle("Remove this website?")
                .setMessage("This stops tracking the website and hides it from Kyora. Existing downloaded files will not be deleted. You can add the URL again later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    GalleryDb(this).use { db ->
                        db.archiveEntity(entity.source, entity.entityId)
                    }
                    siteFilter = null
                    renderCurrent()
                }.show()
        }, ui.lp(this@MainActivity, 12))
        panel.addView(card)
        val retryCount = GalleryDb(this).use {
            it.retryCount(entity.source, entity.entityId)
        }
        if (retryCount > 0) {
            panel.addView(message("Incomplete galleries: " + retryCount,
                "Backfill continues past failed albums. Run Backfill again to retry them."),
                ui.lp(this@MainActivity, 11))
        }
        val galleries = GalleryDb(this).use { it.galleryCards(entity.entityId) }
        section("Downloaded galleries", galleries.size.toString() + " RECENT")
        if (galleries.isEmpty()) {
            panel.addView(message("No galleries downloaded",
                "Run Backfill above to discover available images. Results will appear here."))
            return
        }
        galleries.forEach { gallery ->
            val tile = ui.row(this@MainActivity).apply {
                setPadding(dp(10), dp(10), dp(12), dp(10))
                background = ui.touch(this@MainActivity, ui.surface, 18)
                isClickable = true
                isFocusable = true
            }
            val thumb = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_gallery)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.shape(this@MainActivity, ui.raised, 12)
            }
            tile.addView(thumb, LinearLayout.LayoutParams(dp(84), dp(84)))
            val words = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            words.addView(ui.text(this@MainActivity, gallery.title, 15f, ui.white, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            words.addView(ui.text(this@MainActivity, gallery.count.toString() + " images · " +
                gallery.state, 12f, ui.secondary), ui.lp(this@MainActivity, 7))
            tile.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            tile.setOnClickListener {
                startActivity(Intent(this, GalleryDetailActivity::class.java)
                    .putExtra(GalleryDetailActivity.EXTRA_GALLERY_ID, gallery.galleryId)
                    .putExtra(GalleryDetailActivity.EXTRA_SOURCE, gallery.source.wireName)
                    .putExtra(GalleryDetailActivity.EXTRA_TITLE, gallery.title))
            }
            panel.addView(tile, ui.lp(this@MainActivity, 0, 9))
            gallery.firstUri?.let { url ->
                thumbnailPool.execute {
                    val bmp = runCatching {
                        contentResolver.loadThumbnail(Uri.parse(url), Size(220, 220), null)
                    }.getOrNull()
                    if (bmp != null) runOnUiThread {
                        if (!isFinishing && thumb.parent != null) thumb.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun renderDiscover() {
        heading("Add a website", "Add cosplay galleries or other image websites whenever you choose.")
        val entry = ui.card(this@MainActivity, 20)
        entry.addView(ui.eyebrow(this@MainActivity, "WEBSITE OR ALBUM URL"))
        addressInput = EditText(this).apply {
            hint = "https://example.com/album/..."
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
            setTextColor(ui.white)
            setHintTextColor(ui.subdued)
            background = ui.shape(this@MainActivity, ui.background, 15)
            setPadding(dp(14), 0, dp(14), 0)
        }
        entry.addView(addressInput, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(14)
        })
        entry.addView(ui.text(this@MainActivity,
            "Website structure", 14f, ui.secondary), ui.lp(this@MainActivity, 12, 7))
        val layoutChoices = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((title, mode) in listOf("Auto" to "auto", "Gallery" to "gallery_only", "Albums" to "site")) {
                val choice = android.widget.RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = title
                    textSize = 13f
                    setTextColor(ui.white)
                    buttonTintList = android.content.res.ColorStateList.valueOf(ui.primary)
                    isChecked = requestedSiteLayout == mode
                    setOnClickListener { requestedSiteLayout = mode }
                }
                addView(choice, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }
        entry.addView(layoutChoices, ui.lp(this@MainActivity, 5, 2))
        entry.addView(ui.action(this@MainActivity, "Add website to Kyora", true) {
            addSite()
        }, ui.lp(this@MainActivity, 12))
        panel.addView(entry)

        section("How it works")
        panel.addView(message("Your sites, your choice",
            "Select Gallery for a flat image feed, Albums for multi-gallery websites, or Auto to detect the page structure. Each source remembers its mode. Original Kyora adapters are recognized automatically."))
        panel.addView(ui.text(this@MainActivity,
            "For example, paste a PornPics gallery URL when you choose to add that site.",
            12f, ui.subdued), ui.lp(this@MainActivity, 12))
        panel.addView(ui.text(this@MainActivity,
            "Kuroha handles Pixiv separately. Kyora preserves your cosplay galleries, tag exclusions, flat image archive and Round Sync settings.",
            12f, ui.subdued), ui.lp(this@MainActivity, 16))
    }

    private fun addSite() {
        if (!::addressInput.isInitialized) return
        val raw = addressInput.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Enter a website or gallery URL.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Checking source...", Toast.LENGTH_SHORT).show()
        val layout = requestedSiteLayout
        Thread {
            val result = runCatching {
                val adapter = AdapterRegistry.forUrl(raw)
                if (!adapter.enabled) throw AdapterException(adapter.statusLabel)
                adapter.resolveEntity(raw).let { discovered ->
                    // Keep each canonical site-specific adapter's own source semantics.
                    // The saved kind controls GenericSiteAdapter enumeration on future syncs.
                    val configured = if (adapter.source == SourceId.GENERIC)
                        discovered.copy(kind = if (layout == "auto") discovered.kind else layout)
                    else discovered
                    GalleryDb(this).use { db -> db.addEntity(configured) }
                    configured
                }
            }
            runOnUiThread {
                result.onSuccess {
                    siteFilter = it.stableId
                    go(Page.LIBRARY)
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Cannot add website",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun renderTransfers() {
        heading("Transfers", "Download new galleries and monitor scheduled checks.")
        val hero = ui.card(this@MainActivity, 21)
        hero.addView(ui.eyebrow(this@MainActivity, "TRANSFER STATUS"))
        hero.addView(ui.text(this@MainActivity, "Gallery downloader", 22f, ui.white, true), ui.lp(this@MainActivity, 7))
        status = ui.text(this@MainActivity,
            SyncControl.snapshot().message,
            13f, ui.primary)
        hero.addView(status, ui.lp(this@MainActivity, 9, 15))
        hero.addView(ui.text(this@MainActivity, if (siteFilter == null)
            "Scope: selected websites" else "Scope: current website",
            12f, ui.violet), ui.lp(this@MainActivity, 0, 15))
        hero.addView(buttonPair("Sync now", "Backfill",
            { startSync(SyncMode.LIVE) }, { startSync(SyncMode.BACKFILL) }))
        hero.addView(ui.weighted(this@MainActivity,
            ui.action(this@MainActivity, "Pause / Resume") {
                val action = if (SyncControl.snapshot().paused)
                    SyncForegroundService.ACTION_RESUME else SyncForegroundService.ACTION_PAUSE
                startService(Intent(this, SyncForegroundService::class.java)
                    .setAction(action))
            },
            ui.action(this@MainActivity, "Stop transfer") {
                startService(Intent(this, SyncForegroundService::class.java)
                    .setAction(SyncForegroundService.ACTION_STOP))
            }), ui.lp(this@MainActivity, 10))
        panel.addView(hero)
        panel.addView(message("Sync or backfill?",
            "Sync checks newly available content. Backfill retrieves older albums or posts where the site supports it."),
            ui.lp(this@MainActivity, 13))
        section("Automation")
        val auto = ui.card(this@MainActivity)
        auto.addView(switched("Automatic gallery checks", Prefs.autoSync(this)) {
            Prefs.setAutoSync(this, it)
            if (it) Scheduler.ensure(this) else Scheduler.cancel(this)
        })
        auto.addView(ui.text(this@MainActivity,
            "Android schedules checks in the background. Downloads can be paused or stopped above.",
            12f, ui.subdued), ui.lp(this@MainActivity, 7))
        panel.addView(auto)
    }

    private fun renderSettings() {
        heading("Preferences", "Download rules, exports and cloud destinations.")
        section("Content filters")
        val filters = ui.card(this@MainActivity)
        filters.addView(ui.text(this@MainActivity, "Exclude unwanted tags", 18f, ui.white, true))
        filters.addView(ui.text(this@MainActivity,
            "Skip exact matching tags before downloading. Existing files remain untouched.",
            13f, ui.secondary), ui.lp(this@MainActivity, 7, 13))
        filters.addView(ui.action(this@MainActivity, "Edit global blocked tags") {
            showExcludedTagsDialog()
        })
        filters.addView(ui.action(this@MainActivity, "Edit current website tags") {
            val id = siteFilter
            if (id == null) Toast.makeText(this,
                "Open a website from the Library first.", Toast.LENGTH_LONG).show()
            else showSiteExcludedTagsDialog(id)
        }, ui.lp(this@MainActivity, 10))
        panel.addView(filters)
        section("Cloud backup")
        val cloud = ui.card(this@MainActivity)
        cloud.addView(ui.text(this@MainActivity,"Direct TeraBox upload",18f,ui.white,true))
        cloud.addView(ui.text(this@MainActivity,
            "Paste your NDUS cookie once. Upload saved cosplay images directly to TeraBox without Round Sync.",
            13f,ui.secondary),ui.lp(this@MainActivity,7,13))
        cloud.addView(ui.action(this@MainActivity,"Configure TeraBox / NDUS") {
            com.azrael.pixivdumpsync.NduSettingsDialog.show(this)
        })
        cloud.addView(ui.action(this@MainActivity,"Upload pending images") {
            com.azrael.pixivdumpsync.NduSettingsDialog.start(this)
        },ui.lp(this@MainActivity,10))
        cloud.addView(ui.action(this@MainActivity,"Include existing downloads") {
            com.azrael.pixivdumpsync.NduSettingsDialog.start(this,true)
        },ui.lp(this@MainActivity,10))
        panel.addView(cloud)
        section("Data portability")
        val files = ui.card(this@MainActivity)
        files.addView(ui.text(this@MainActivity, "Catalog and backup", 18f, ui.white, true))
        files.addView(ui.text(this@MainActivity, "Restore website settings from Kyora or export your catalog.",
            13f, ui.secondary), ui.lp(this@MainActivity, 7, 13))
        files.addView(ui.action(this@MainActivity, "Import previous Kyora sources") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), 708)
        })
        files.addView(ui.weighted(this@MainActivity,
            ui.action(this@MainActivity, "Export JSON") { export(true) },
            ui.action(this@MainActivity, "Export CSV") { export(false) }), ui.lp(this@MainActivity, 10))
        panel.addView(files)
    }

    private fun showSiteExcludedTagsDialog(entityId: String) {
        val editor = EditText(this).apply {
            setText(Prefs.siteExcludedTags(this@MainActivity, entityId))
            hint = "One exact post/gallery tag per line"
            minLines = 6
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Exclude tags for this site only")
            .setMessage("Matching source and gallery tags are excluded. Global exclusions also apply. Previously downloaded images are not deleted.")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setSiteExcludedTags(this, entityId, editor.text.toString())
                Toast.makeText(this, "Site-specific tags saved", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showExcludedTagsDialog() {
        val editor = EditText(this).apply {
            setText(Prefs.excludedTags(this@MainActivity))
            hint = "One exact gallery tag per line"
            minLines = 6
            gravity = Gravity.TOP
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(label("One tag per line. No partial matches; old downloads are not deleted.", 13f, MUTED))
            addView(editor)
        }
        AlertDialog.Builder(this).setTitle("Excluded gallery tags").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setExcludedTags(this, editor.text.toString())
                Toast.makeText(this, "Tag exclusions saved", Toast.LENGTH_SHORT).show()
            }.show()
    }

    @Deprecated("Legacy file picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 708 || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val result = runCatching { LegacySourceImporter(this).importSites(uri) }
            runOnUiThread {
                result.onSuccess { count ->
                    siteFilter = null
                    renderEntities()
                    Toast.makeText(this, "Imported $count site entries. Old image records remain in the former app.", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun renderEntities() {
        if (::panel.isInitialized && page == Page.LIBRARY) renderCurrent()
    }

    private fun startSync(mode: SyncMode) {
        if (entityCount() == 0) {
            Toast.makeText(this, "Add an image source before syncing.", Toast.LENGTH_LONG).show()
            go(Page.DISCOVER)
            return
        }
        startForegroundService(
            Intent(this, SyncForegroundService::class.java)
                .setAction(SyncForegroundService.ACTION_START)
                .putExtra(SyncForegroundService.EXTRA_MODE, mode.name)
                .putExtra(SyncForegroundService.EXTRA_SITE_ID, siteFilter)
        )
    }

    private fun export(json: Boolean) {
        Thread {
            val result = runCatching {
                if (json) ExportManager(this).exportJson() else ExportManager(this).exportCsv()
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, "Exported to Downloads/" + getString(com.azrael.pixivdumpsync.R.string.gallery_download_folder) + "/exports", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Export failed", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun entityCount(): Int =
        runCatching { GalleryDb(this).use { it.listEntities().size } }.getOrDefault(0)

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 30)
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SITE_ID = "extra_site_id"
        const val EXTRA_ADD_SITE = "extra_add_site"
        private val BG = com.azrael.pixivdumpsync.NocturneDesign.background
        private val SURFACE = com.azrael.pixivdumpsync.NocturneDesign.surface
        private val MUTED = com.azrael.pixivdumpsync.NocturneDesign.secondary
        private val ACCENT = com.azrael.pixivdumpsync.NocturneDesign.primary
        private val ERROR = com.azrael.pixivdumpsync.NocturneDesign.danger
    }
}
