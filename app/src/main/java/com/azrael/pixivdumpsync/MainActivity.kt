package com.azrael.pixivdumpsync

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var artistList: LinearLayout
    private lateinit var authStatus: TextView
    private lateinit var lastSyncStatus: TextView
    private lateinit var runStatus: TextView
    private lateinit var artistCount: TextView
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var checkNowButton: Button
    private lateinit var backfillButton: Button
    private lateinit var followingFeedSwitch: Switch
    private lateinit var autoSyncSwitch: Switch
    private lateinit var autoSyncStatus: TextView
    private lateinit var followImportButton: Button
    private lateinit var followingPreviewStatus: TextView
    private lateinit var followingPreviewRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var enrichStarted = false
    private var importFollowsRunning = false
    private var followingPreviewLoading = false
    private var visibleArtistLimit = 20
    private var refreshTick = 0
    private var lastSyncRunning = false

    private val refreshTask = object : Runnable {
        override fun run() {
            val wasRunning = lastSyncRunning
            updateSyncControls()
            val running = SyncControl.snapshot().running
            refreshTick++

            if (wasRunning && !running) {
                renderArtists()
                lastSyncStatus.text =
                    "Last run • ${SessionStore.lastSyncSummary(this@MainActivity)}"
                if (::autoSyncStatus.isInitialized) {
                    autoSyncStatus.text = if (SessionStore.autoSync(this@MainActivity)) {
                        "Auto sync • ${SessionStore.lastAutoSyncResult(this@MainActivity)}"
                    } else {
                        "Auto sync • disabled"
                    }
                }
            }

            lastSyncRunning = running
            handler.postDelayed(this, 500L)
        }
    }

    private fun dp(value: Int) = UiKit.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = UiKit.bg
        window.navigationBarColor = UiKit.bg
        buildUi()
        requestNotificationPermissionIfNeeded()
        if (SessionStore.autoSync(this)) {
            Scheduler.ensure(this)
            Scheduler.runSoon(this)
        } else {
            Scheduler.cancel(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
        enrichMissingArtists()
        refreshFollowingPreview()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(UiKit.bg)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_kuroha)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "ASTERION DOWNLOADER / PIXIV"
            UiKit.body(this, 10f)
        })
        titles.addView(TextView(this).apply {
            text = "Pixiv archive"
            UiKit.title(this, 27f)
        })
        titles.addView(TextView(this).apply {
            text = "Private Pixiv archive & following feed"
            UiKit.body(this, 13.5f)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(
            titles,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        tabs.addView(Button(this).apply {
            text = "Archive"
            UiKit.stylePrimaryButton(this@MainActivity, this)
            isEnabled = false
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(Button(this).apply {
            text = "New artworks"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FollowingActivity::class.java))
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            leftMargin = dp(8)
        })
        root.addView(tabs)

        root.addView(section("ACCOUNT"), sectionParams())

        val accountCard = card()
        val accountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val accountText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        accountText.addView(TextView(this).apply {
            text = "Pixiv account"
            UiKit.title(this, 17f)
        })
        authStatus = TextView(this).apply {
            UiKit.body(this, 13f)
            setPadding(0, dp(4), 0, 0)
        }
        accountText.addView(authStatus)
        accountRow.addView(
            accountText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        accountRow.addView(Button(this).apply {
            text = "Account"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            minHeight = dp(42)
            setPadding(dp(15), 0, dp(15), 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            }
        })
        accountCard.addView(accountRow)
        root.addView(accountCard, cardParams())

        root.addView(section("SYNC CONTROL"), sectionParams())

        val syncCard = card()

        runStatus = TextView(this).apply {
            UiKit.title(this, 16f)
        }
        syncCard.addView(runStatus)

        lastSyncStatus = TextView(this).apply {
            UiKit.body(this, 12.5f)
            setPadding(0, dp(5), 0, 0)
        }
        syncCard.addView(lastSyncStatus)

        val mainActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        checkNowButton = Button(this).apply {
            text = "Sync now"
            UiKit.stylePrimaryButton(this@MainActivity, this)
            setOnClickListener { startSync(SyncMode.LIVE) }
        }
        mainActions.addView(
            checkNowButton,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        backfillButton = Button(this).apply {
            text = "Backfill selected"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            setOnClickListener { startSync(SyncMode.BACKFILL) }
        }
        mainActions.addView(
            backfillButton,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                leftMargin = dp(9)
            }
        )
        syncCard.addView(
            mainActions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        pauseButton = Button(this).apply {
            text = "Pause"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            setOnClickListener { togglePause() }
        }
        transport.addView(
            pauseButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        stopButton = Button(this).apply {
            text = "Stop"
            UiKit.styleDangerButton(this@MainActivity, this)
            setOnClickListener { requestStop() }
        }
        transport.addView(
            stopButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(9)
            }
        )
        syncCard.addView(
            transport,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) }
        )

        syncCard.addView(TextView(this).apply {
            text = "Live Sync checks the Pixiv Following feed plus watched artists. Backfill is manual and downloads older works for selected watched artists."
            UiKit.body(this, 12f)
            setPadding(0, dp(14), 0, 0)
        })

        val feedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val feedText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        feedText.addView(TextView(this).apply {
            text = "Following feed"
            UiKit.title(this, 14.5f)
        })
        feedText.addView(TextView(this).apply {
            text = "Automatically archive newest works from every Pixiv account you follow."
            UiKit.body(this, 11.5f)
            setPadding(0, dp(2), dp(8), 0)
        })
        feedRow.addView(
            feedText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        followingFeedSwitch = Switch(this).apply {
            isChecked = SessionStore.followingFeedEnabled(this@MainActivity)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(UiKit.accent, UiKit.muted)
            )
            setOnCheckedChangeListener { _, checked ->
                SessionStore.setFollowingFeedEnabled(this@MainActivity, checked)
                Scheduler.ensure(this@MainActivity)

                if (checked) {
                    if (SessionStore.isLoggedIn(this@MainActivity)) {
                        startFeedSyncNow(
                            showToast = true,
                            autoTriggered = SessionStore.autoSync(this@MainActivity)
                        )
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Following feed disabled",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        feedRow.addView(followingFeedSwitch)
        syncCard.addView(feedRow)

        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val autoText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        autoText.addView(TextView(this).apply {
            text = "Automatic sync"
            UiKit.title(this, 14.5f)
        })
        autoText.addView(TextView(this).apply {
            text = "Periodically sync new Following-feed artworks in the background."
            UiKit.body(this, 11.5f)
            setPadding(0, dp(2), dp(8), 0)
        })
        autoRow.addView(
            autoText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        autoSyncSwitch = Switch(this).apply {
            isChecked = SessionStore.autoSync(this@MainActivity)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(UiKit.accent, UiKit.muted)
            )
            setOnCheckedChangeListener { _, checked ->
                SessionStore.setAutoSync(this@MainActivity, checked)
                if (checked) {
                    Scheduler.ensure(this@MainActivity)
                    if (
                        SessionStore.followingFeedEnabled(this@MainActivity) &&
                        SessionStore.isLoggedIn(this@MainActivity)
                    ) {
                        startFeedSyncNow(
                            showToast = false,
                            autoTriggered = true
                        )
                    } else {
                        Scheduler.runSoon(this@MainActivity)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (SessionStore.followingFeedEnabled(this@MainActivity)) {
                            "Automatic sync enabled • checking feed now"
                        } else {
                            "Automatic sync enabled • enable Following feed to use it"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Scheduler.cancel(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        "Automatic sync disabled",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        autoRow.addView(autoSyncSwitch)
        syncCard.addView(autoRow)

        autoSyncStatus = TextView(this).apply {
            UiKit.body(this, 11.5f)
            setPadding(0, dp(7), 0, 0)
        }
        syncCard.addView(autoSyncStatus)

        root.addView(syncCard, cardParams())

        root.addView(section("DOWNLOAD FILTERS"), sectionParams())
        val filterCard = card()
        filterCard.addView(TextView(this).apply {
            text = "Blocked Pixiv tags"
            UiKit.title(this, 16f)
        })
        filterCard.addView(TextView(this).apply {
            text = "Artworks containing any blocked tag are skipped before downloading or bookmarking. Enter one exact tag per line; original and English-translated Pixiv tags are checked. Existing files are not deleted."
            UiKit.body(this, 12f)
            setPadding(0, dp(8), 0, 0)
        })
        filterCard.addView(Button(this).apply {
            text = "Edit excluded tags"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            setOnClickListener { showExcludedTagsDialog() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(12) })
        root.addView(filterCard, cardParams())
        root.addView(section("T ERABOX CLOUD BACKUP".replace(" ", "")), sectionParams())
        val cloudCard = card()
        cloudCard.addView(TextView(this).apply {
            text = "TeraBox Open Platform"
            UiKit.title(this, 16f)
        })
        cloudCard.addView(TextView(this).apply {
            text = "Optional API upload with a durable retry queue. Local copies are retained. Requires an authorized Open Platform access token."
            UiKit.body(this, 12f)
            setPadding(0, dp(8), 0, 0)
        })
        cloudCard.addView(Button(this).apply {
            text = "TeraBox settings"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            setOnClickListener { TeraboxSettingsDialog.show(this@MainActivity) }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        cloudCard.addView(Button(this).apply {
            text = "Upload existing / pending"
            UiKit.stylePrimaryButton(this@MainActivity, this)
            setOnClickListener {
                if (!TeraboxSettings.enabled(this@MainActivity) ||
                    !TeraboxSettings.isConfigured(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Configure and enable TeraBox first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                CloudUploadQueue(this@MainActivity).use { queue ->
                    FileStore.listArtworkFiles(this@MainActivity).forEach { queue.enqueue(it) }
                }
                startSync(SyncMode.CLOUD)
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        root.addView(cloudCard, cardParams())
        val ndusCard = card()
        ndusCard.addView(TextView(this).apply {
            text = "TeraBox Direct / NDUS"
            UiKit.title(this,16f)
        })
        ndusCard.addView(TextView(this).apply {
            text = "Upload Pixiv images directly using an encrypted NDUS cookie. No Round Sync needed. Images stay on your phone."
            UiKit.body(this,12f)
        })
        ndusCard.addView(Button(this).apply {
            text = "Configure direct TeraBox"
            UiKit.styleSecondaryButton(this@MainActivity,this)
            setOnClickListener { NduSettingsDialog.show(this@MainActivity) }
        },LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(12) })
        ndusCard.addView(Button(this).apply {
            text = "Upload pending NDUS queue"
            UiKit.stylePrimaryButton(this@MainActivity,this)
            setOnClickListener { NduSettingsDialog.start(this@MainActivity) }
        },LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(8) })
        ndusCard.addView(Button(this).apply {
            text = "Queue all existing Pixiv / cosplay images"
            UiKit.styleSecondaryButton(this@MainActivity,this)
            setOnClickListener { NduSettingsDialog.start(this@MainActivity,true) }
        },LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(8) })
        root.addView(ndusCard,cardParams())



        root.addView(section("FOLLOWING"), sectionParams())

        val followingCard = card()
        val followingHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val followingTitleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        followingTitleWrap.addView(TextView(this).apply {
            text = "Latest from people you follow"
            UiKit.title(this, 15.5f)
        })
        followingPreviewStatus = TextView(this).apply {
            text = "Loading your Pixiv Following feed…"
            UiKit.body(this, 11.5f)
            setPadding(0, dp(3), dp(8), 0)
        }
        followingTitleWrap.addView(followingPreviewStatus)
        followingHeader.addView(
            followingTitleWrap,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        followingHeader.addView(Button(this).apply {
            text = "Refresh"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            minHeight = dp(38)
            setPadding(dp(11), 0, dp(11), 0)
            setOnClickListener { refreshFollowingPreview(force = true) }
        })
        followingCard.addView(followingHeader)

        val followingScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        followingPreviewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(11), 0, 0)
        }
        followingScroller.addView(followingPreviewRow)
        followingCard.addView(followingScroller)
        root.addView(followingCard, cardParams())

        root.addView(section("ARTISTS"), sectionParams())

        val artistToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        artistCount = TextView(this).apply {
            UiKit.body(this, 13f)
        }
        artistToolbar.addView(
            artistCount,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        artistToolbar.addView(Button(this).apply {
            text = "All"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            minHeight = dp(40)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                AppDb(this@MainActivity).use { it.setAllSelected(true) }
                renderArtists()
            }
        })

        artistToolbar.addView(Button(this).apply {
            text = "None"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            minHeight = dp(40)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                AppDb(this@MainActivity).use { it.setAllSelected(false) }
                renderArtists()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply { leftMargin = dp(7) })

        artistToolbar.addView(Button(this).apply {
            text = "+ Add"
            UiKit.stylePrimaryButton(this@MainActivity, this)
            minHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { showAddArtistsDialog() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply { leftMargin = dp(7) })

        root.addView(artistToolbar)

        followImportButton = Button(this).apply {
            text = "Import Pixiv follows"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            minHeight = dp(44)
            setOnClickListener { importPixivFollows() }
        }
        root.addView(
            followImportButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(9) }
        )

        artistList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            artistList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        root.addView(TextView(this).apply {
            text = "New files are saved in Downloads/" + getString(R.string.pixiv_download_folder) + ". " + (if (resources.getBoolean(R.bool.nocturne_preview)) "This preview does not reuse Kuroha files. " else "Legacy Downloads/PixiFlow files are still recognized. ") + "Works are bookmarked on Pixiv only after every image page exists locally."
            UiKit.body(this, 11.5f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), 0)
        })

        setContentView(scroll)
    }

    private fun showExcludedTagsDialog() {
        val input = EditText(this).apply {
            setText(SessionStore.excludedTagsText(this@MainActivity))
            hint = "One Pixiv tag per line"
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Excluded artwork tags")
            .setMessage("Exact, case-insensitive matches. Applies to Live Sync, Backfill and direct download; never removes existing files.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                SessionStore.setExcludedTagsText(this, input.text.toString())
                val count = SessionStore.excludedTags(this).size
                Toast.makeText(this, "$count excluded tags saved", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refresh() {
        authStatus.text = if (SessionStore.isLoggedIn(this)) {
            "Connected • verified session"
        } else {
            "Not connected"
        }
        authStatus.setTextColor(
            if (SessionStore.isLoggedIn(this)) UiKit.success else UiKit.danger
        )
        lastSyncStatus.text = "Last run • ${SessionStore.lastSyncSummary(this)}"
        if (::autoSyncStatus.isInitialized) {
            autoSyncStatus.text = if (SessionStore.autoSync(this)) {
                "Auto sync • ${SessionStore.lastAutoSyncResult(this)}"
            } else {
                "Auto sync • disabled"
            }
        }
        updateSyncControls()
        renderArtists()
    }

    private fun updateSyncControls() {
        val s = SyncControl.snapshot()

        runStatus.text = when {
            s.stopping -> "Stopping safely…"
            s.paused -> "Paused • ${modeName(s.mode)}"
            s.running -> if (s.pendingLive) "${s.message} • Live check queued" else s.message
            else -> "No sync running"
        }

        runStatus.setTextColor(
            when {
                s.stopping -> UiKit.danger
                s.paused -> Color.rgb(255, 184, 76)
                s.running -> UiKit.success
                else -> UiKit.text
            }
        )

        pauseButton.text = if (s.paused) "Resume" else "Pause"
        pauseButton.isEnabled = s.running && !s.stopping
        stopButton.isEnabled = s.running && !s.stopping
        checkNowButton.isEnabled = !s.running
        backfillButton.isEnabled = !s.running
    }

    private fun renderArtists() {
        if (!::artistList.isInitialized) return
        artistList.removeAllViews()

        AppDb(this).use { db ->
            val artists = db.listArtists()
            val selected = artists.count { it.selected }
            val shown = artists.take(visibleArtistLimit)
            artistCount.text =
                "${artists.size} watched • $selected selected • ${shown.size} shown"

            if (artists.isEmpty()) {
                val empty = card()
                empty.gravity = Gravity.CENTER
                empty.addView(TextView(this).apply {
                    text = "No watched artists yet"
                    UiKit.title(this, 16f)
                    gravity = Gravity.CENTER
                })
                empty.addView(TextView(this).apply {
                    text = "Add a Pixiv user ID or import your follows. Kuroha will show each profile and its newest works."
                    UiKit.body(this, 12.5f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
                artistList.addView(empty)
                return
            }

            for ((index, artist) in shown.withIndex()) {
                val progress = db.artistProgress(artist.userId, artist.knownTotal)
                artistList.addView(
                    artistCard(db, artist, progress),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) topMargin = dp(9)
                    }
                )
            }

            if (shown.size < artists.size) {
                val remaining = artists.size - shown.size
                artistList.addView(
                    Button(this).apply {
                        text = "Show ${minOf(20, remaining)} more • $remaining remaining"
                        UiKit.styleSecondaryButton(this@MainActivity, this)
                        minHeight = dp(46)
                        setOnClickListener {
                            visibleArtistLimit += 20
                            enrichStarted = false
                            renderArtists()
                            enrichMissingArtists()
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                    ).apply { topMargin = dp(10) }
                )
            }
        }
    }

    private fun artistCard(
        db: AppDb,
        artist: ArtistRecord,
        progress: ArtistProgress
    ): View {
        val card = card()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val selectedBox = CheckBox(this).apply {
            isChecked = artist.selected
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(UiKit.accent, UiKit.muted)
            )
            setOnCheckedChangeListener { _, checked ->
                db.setArtistSelected(artist.userId, checked)
                updateArtistCountOnly()
            }
        }
        top.addView(selectedBox)

        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiKit.rounded(this@MainActivity, UiKit.surfaceAlt, 14, UiKit.line)
            clipToOutline = true
            setImageResource(R.drawable.ic_kuroha)
            contentDescription = artist.label ?: "Pixiv artist"
            setOnClickListener {
                openPixivUrl("https://www.pixiv.net/users/${artist.userId}")
            }
        }
        RemoteImageLoader.load(
            this,
            avatar,
            artist.avatarUrl,
            "https://www.pixiv.net/users/${artist.userId}"
        )
        top.addView(
            avatar,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                leftMargin = dp(3)
            }
        )

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        identity.addView(TextView(this).apply {
            text = artist.label ?: "Resolving artist…"
            UiKit.title(this, 15.5f)
            maxLines = 1
        })
        identity.addView(TextView(this).apply {
            text = "Pixiv ID ${artist.userId}"
            UiKit.body(this, 12f)
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(
            identity,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val liveSwitch = Switch(this).apply {
            isChecked = artist.liveEnabled
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(UiKit.accent, UiKit.muted)
            )
            setOnCheckedChangeListener { _, checked ->
                AppDb(this@MainActivity).use {
                    it.setArtistLiveEnabled(artist.userId, checked)
                }
            }
        }
        top.addView(liveSwitch)
        card.addView(top)

        val previews = ArtworkPreviewCodec.decode(artist.previewJson)
        if (previews.isNotEmpty()) {
            val previewScroller = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val previewRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(38), dp(10), 0, 0)
            }

            for ((previewIndex, preview) in previews.take(5).withIndex()) {
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = UiKit.rounded(
                        this@MainActivity,
                        UiKit.surfaceAlt,
                        12,
                        UiKit.line
                    )
                    clipToOutline = true
                    setImageResource(R.drawable.ic_kuroha)
                    contentDescription = preview.title.ifBlank { "Pixiv artwork ${preview.id}" }
                    setOnClickListener {
                        openPixivUrl("https://www.pixiv.net/artworks/${preview.id}")
                    }
                }
                RemoteImageLoader.load(
                    this,
                    thumb,
                    preview.thumbnailUrl,
                    "https://www.pixiv.net/artworks/${preview.id}"
                )
                previewRow.addView(
                    thumb,
                    LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                        if (previewIndex > 0) leftMargin = dp(7)
                    }
                )
            }

            previewScroller.addView(previewRow)
            card.addView(previewScroller)
        }

        val status = TextView(this).apply {
            text = artistStatusText(artist, progress)
            UiKit.body(this, 12.5f)
            setTextColor(
                when {
                    !artist.lastError.isNullOrBlank() -> UiKit.danger
                    artist.archiveStatus == "COMPLETE" -> UiKit.success
                    else -> UiKit.muted
                }
            )
            setPadding(dp(38), dp(10), 0, 0)
        }
        card.addView(status)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(38), dp(10), 0, 0)
        }

        bottom.addView(TextView(this).apply {
            text = if (artist.liveEnabled) "LIVE ON" else "LIVE OFF"
            setTextColor(if (artist.liveEnabled) UiKit.success else UiKit.muted)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!artist.lastError.isNullOrBlank()) {
            bottom.addView(Button(this).apply {
                text = "Retry"
                UiKit.styleSecondaryButton(this@MainActivity, this)
                minHeight = dp(38)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { retryArtist(artist) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
            ).apply { rightMargin = dp(7) })
        }

        bottom.addView(Button(this).apply {
            text = "Backfill"
            UiKit.styleSecondaryButton(this@MainActivity, this)
            minHeight = dp(38)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { startSync(SyncMode.BACKFILL, artist.userId) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(40)
        ).apply { rightMargin = dp(7) })

        bottom.addView(Button(this).apply {
            text = "Remove"
            UiKit.styleDangerButton(this@MainActivity, this)
            minHeight = dp(38)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Remove artist?")
                    .setMessage("Downloaded files and Pixiv bookmarks will not be deleted.")
                    .setPositiveButton("Remove") { _, _ ->
                        AppDb(this@MainActivity).use { it.removeArtist(artist.userId) }
                        renderArtists()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        card.addView(bottom)

        return card
    }

    private fun artistStatusText(
        artist: ArtistRecord,
        progress: ArtistProgress
    ): String {
        if (!artist.lastError.isNullOrBlank()) {
            return "Error • ${artist.lastError}"
        }

        val archive = when (artist.archiveStatus) {
            "COMPLETE" -> "Archive synced"
            "RUNNING" -> "Backfill running"
            "PARTIAL" -> "Archive partial"
            "ERROR" -> "Archive error"
            else -> "Archive not started"
        }

        val counts = if (artist.knownTotal > 0) {
            " • ${progress.done}/${artist.knownTotal} saved • ${progress.pending} pending"
        } else {
            ""
        }

        val live = if (artist.liveCursor.isNullOrBlank()) {
            " • Live baseline pending"
        } else {
            " • Live ready"
        }

        return archive + counts + live
    }

    private fun updateArtistCountOnly() {
        AppDb(this).use { db ->
            val artists = db.listArtists()
            artistCount.text = "${artists.size} artists • ${artists.count { it.selected }} selected"
        }
    }

    private fun showAddArtistsDialog() {
        val input = EditText(this).apply {
            hint = "Pixiv user ID or profile URL"
            setHintTextColor(UiKit.muted)
            setTextColor(UiKit.text)
            gravity = Gravity.TOP
            minLines = 4
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = UiKit.rounded(
                this@MainActivity,
                UiKit.surfaceAlt,
                12,
                UiKit.line
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Add artists")
            .setMessage(
                "Paste one or several Pixiv IDs/profile URLs. New artists start with Live enabled; older works are downloaded only when you run Backfill."
            )
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val ids = InputParser.parseUserIds(input.text.toString())
                if (ids.isEmpty()) {
                    Toast.makeText(this, "No Pixiv user IDs found", Toast.LENGTH_LONG).show()
                } else {
                    addArtists(ids)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addArtists(ids: List<String>) {
        Toast.makeText(this, "Looking up ${ids.size} artist(s)…", Toast.LENGTH_SHORT).show()

        Thread {
            var resolved = 0
            AppDb(applicationContext).use { db ->
                val api = if (SessionStore.isLoggedIn(applicationContext)) {
                    runCatching { PixivApi(applicationContext) }.getOrNull()
                } else {
                    null
                }

                for (id in ids) {
                    val profile = api?.let { runCatching { it.userProfile(id) }.getOrNull() }
                    val snapshot = api?.let {
                        runCatching {
                            it.userArtworkSnapshot(
                                id,
                                previewLimit = 5,
                                resolveMissingPreviews = true
                            )
                        }.getOrNull()
                    }
                    db.addArtist(
                        userId = id,
                        label = profile?.name,
                        liveCursor = snapshot?.ids?.firstOrNull(),
                        avatarUrl = profile?.imageUrl,
                        previewJson = snapshot?.previews
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { ArtworkPreviewCodec.encode(it) }
                    )
                    if (profile != null) resolved++
                    Thread.sleep(120L)
                }
            }

            runOnUiThread {
                renderArtists()
                Toast.makeText(
                    this,
                    "Added ${ids.size} artist(s) • $resolved profiles resolved",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun enrichMissingArtists() {
        if (enrichStarted || !SessionStore.isLoggedIn(this)) return
        enrichStarted = true

        Thread {
            runCatching {
                AppDb(applicationContext).use { db ->
                    val api = PixivApi(applicationContext)
                    for (artist in db.listArtists().take(visibleArtistLimit)) {
                        val needsProfile =
                            artist.label.isNullOrBlank() || artist.avatarUrl.isNullOrBlank()
                        val storedPreviews = ArtworkPreviewCodec.decode(artist.previewJson)
                        val needsArtwork =
                            artist.liveCursor.isNullOrBlank() ||
                                artist.previewJson.isNullOrBlank() ||
                                storedPreviews.none { !it.thumbnailUrl.isNullOrBlank() }

                        if (needsProfile || needsArtwork) {
                            val profile = if (needsProfile) {
                                runCatching { api.userProfile(artist.userId) }.getOrNull()
                            } else {
                                null
                            }
                            val snapshot = if (needsArtwork) {
                                runCatching {
                                    api.userArtworkSnapshot(
                                        artist.userId,
                                        previewLimit = 5,
                                        resolveMissingPreviews = true
                                    )
                                }.getOrNull()
                            } else {
                                null
                            }

                            db.updateArtistIdentity(
                                userId = artist.userId,
                                label = profile?.name,
                                liveCursor = snapshot?.ids?.firstOrNull(),
                                avatarUrl = profile?.imageUrl,
                                previewJson = snapshot?.previews
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { ArtworkPreviewCodec.encode(it) }
                            )
                            Thread.sleep(120L)
                        }
                    }
                }
            }
            runOnUiThread { renderArtists() }
        }.start()
    }

    private fun refreshFollowingPreview(force: Boolean = false) {
        if (!::followingPreviewRow.isInitialized) return

        val cached = ArtworkPreviewCodec.decode(SessionStore.followingPreviewJson(this))
        if (cached.isNotEmpty()) {
            renderFollowingPreview(cached, "Cached latest works • refreshing…")
        }

        if (!SessionStore.isLoggedIn(this)) {
            followingPreviewStatus.text = "Connect your Pixiv account to view Following."
            return
        }
        if (followingPreviewLoading) return

        followingPreviewLoading = true
        Thread {
            val result = runCatching {
                PixivApi(applicationContext).followingFeedPage(1).items.take(10)
            }

            runOnUiThread {
                followingPreviewLoading = false
                result.onSuccess { items ->
                    if (items.isNotEmpty()) {
                        SessionStore.setFollowingPreviewJson(
                            this,
                            ArtworkPreviewCodec.encode(items)
                        )
                        renderFollowingPreview(
                            items,
                            "Latest Pixiv Following works • ${items.size} shown"
                        )
                    } else {
                        followingPreviewRow.removeAllViews()
                        followingPreviewStatus.text = "No Following artworks returned."
                    }
                }.onFailure { error ->
                    followingPreviewStatus.text =
                        if (cached.isNotEmpty()) {
                            "Showing cached works • refresh failed: ${error.message}"
                        } else {
                            "Could not load Following: ${error.message}"
                        }
                }
            }
        }.start()
    }

    private fun renderFollowingPreview(
        items: List<PixivApi.ArtworkPreview>,
        statusText: String
    ) {
        if (!::followingPreviewRow.isInitialized) return
        followingPreviewRow.removeAllViews()
        followingPreviewStatus.text = statusText

        for ((index, preview) in items.take(10).withIndex()) {
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = UiKit.rounded(
                    this@MainActivity,
                    UiKit.surfaceAlt,
                    12,
                    UiKit.line
                )
                clipToOutline = true
                setImageResource(R.drawable.ic_kuroha)
                contentDescription = preview.title.ifBlank { "Pixiv artwork ${preview.id}" }
                setOnClickListener {
                    openPixivUrl("https://www.pixiv.net/artworks/${preview.id}")
                }
            }
            RemoteImageLoader.load(
                this,
                thumb,
                preview.thumbnailUrl,
                "https://www.pixiv.net/artworks/${preview.id}"
            )
            followingPreviewRow.addView(
                thumb,
                LinearLayout.LayoutParams(dp(82), dp(82)).apply {
                    if (index > 0) leftMargin = dp(7)
                }
            )
        }
    }

    private fun importPixivFollows() {
        if (importFollowsRunning) return
        if (!SessionStore.isLoggedIn(this)) {
            Toast.makeText(this, "Connect your Pixiv account first", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Import Pixiv follows?")
            .setMessage(
                "Kuroha will add the accounts you follow to Watched Artists. Existing artists are kept. Profiles import first; artwork previews and Live baselines fill in afterward."
            )
            .setPositiveButton("Import") { _, _ ->
                importFollowsRunning = true
                followImportButton.isEnabled = false
                followImportButton.text = "Importing follows…"

                Thread {
                    var imported = 0
                    var errorMessage: String? = null

                    runCatching {
                        val api = PixivApi(applicationContext)
                        val follows = api.followingUsers()

                        AppDb(applicationContext).use { db ->
                            for (profile in follows) {
                                db.addArtist(
                                    userId = profile.userId,
                                    label = profile.name,
                                    avatarUrl = profile.imageUrl
                                )
                                imported++
                            }
                        }
                    }.onFailure {
                        errorMessage = it.message ?: it.javaClass.simpleName
                    }

                    runOnUiThread {
                        importFollowsRunning = false
                        followImportButton.isEnabled = true
                        followImportButton.text = "Import Pixiv follows"
                        visibleArtistLimit = 20
                        renderArtists()

                        if (errorMessage == null) {
                            enrichStarted = false
                            enrichMissingArtists()
                            Toast.makeText(
                                this,
                                "Imported $imported followed artist(s). Profiles will finish enriching in the background.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                "Import failed: $errorMessage",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun retryArtist(artist: ArtistRecord) {
        if (SyncControl.snapshot().running) {
            Toast.makeText(this, "Stop the current sync before retrying.", Toast.LENGTH_SHORT).show()
            return
        }

        AppDb(this).use { it.clearArtistError(artist.userId) }
        val mode = if (
            artist.archiveStatus == "PARTIAL" ||
            artist.archiveStatus == "ERROR" ||
            artist.archiveStatus == "RUNNING"
        ) {
            SyncMode.BACKFILL
        } else {
            SyncMode.LIVE
        }

        startSync(mode, artist.userId)
    }

    private fun openPixivUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "Could not open Pixiv link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFeedSyncNow(
        showToast: Boolean,
        autoTriggered: Boolean = false
    ) {
        if (!SessionStore.isLoggedIn(this)) return
        if (!SessionStore.followingFeedEnabled(this)) return

        if (SyncControl.snapshot().running) {
            Scheduler.runSoon(this)
            return
        }

        val intent = Intent(this, SyncForegroundService::class.java)
            .setAction(SyncForegroundService.ACTION_START)
            .putExtra(SyncForegroundService.EXTRA_MODE, SyncMode.LIVE.name)
            .putExtra(SyncForegroundService.EXTRA_SELECTED_ONLY, false)
            .putExtra(SyncForegroundService.EXTRA_FEED_ONLY, true)
            .putExtra(SyncForegroundService.EXTRA_AUTO_TRIGGERED, autoTriggered)

        startForegroundService(intent)
        if (showToast) {
            Toast.makeText(
                this,
                "Syncing new Following artworks",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startSync(mode: SyncMode, targetArtistId: String? = null) {
        if (mode != SyncMode.CLOUD && !SessionStore.isLoggedIn(this)) {
            Toast.makeText(this, "Connect your Pixiv account first", Toast.LENGTH_LONG).show()
            return
        }
        if (mode == SyncMode.CLOUD && (!TeraboxSettings.enabled(this) ||
            !TeraboxSettings.isConfigured(this))) {
            Toast.makeText(this, "Configure and enable TeraBox first", Toast.LENGTH_LONG).show()
            return
        }

        if (mode != SyncMode.CLOUD) AppDb(this).use { db ->
            val eligible = if (targetArtistId != null) {
                listOfNotNull(db.artist(targetArtistId))
            } else if (mode == SyncMode.LIVE) {
                db.listSelectedLiveArtists()
            } else {
                db.listSelectedArtists()
            }

            val feedCanRun =
                targetArtistId == null &&
                    mode == SyncMode.LIVE &&
                    SessionStore.followingFeedEnabled(this)

            if (eligible.isEmpty() && !feedCanRun) {
                Toast.makeText(
                    this,
                    if (mode == SyncMode.LIVE) {
                        "Enable the Following feed or select at least one watched artist with Live enabled"
                    } else {
                        "Select at least one artist"
                    },
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val intent = Intent(this, SyncForegroundService::class.java)
            .setAction(SyncForegroundService.ACTION_START)
            .putExtra(SyncForegroundService.EXTRA_MODE, mode.name)
            .putExtra(SyncForegroundService.EXTRA_SELECTED_ONLY, targetArtistId == null)
        if (targetArtistId != null) {
            intent.putExtra(SyncForegroundService.EXTRA_TARGET_ARTIST_ID, targetArtistId)
        }

        startForegroundService(intent)
        Toast.makeText(
            this,
            if (targetArtistId != null) {
                "Retry started"
            } else if (mode == SyncMode.CLOUD) {
                "TeraBox upload started"
            } else if (mode == SyncMode.LIVE) {
                if (SessionStore.followingFeedEnabled(this)) {
                    "Checking your Following feed and selected watched artists"
                } else {
                    "Checking selected watched artists for new works"
                }
            } else {
                "Backfill started"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun togglePause() {
        val s = SyncControl.snapshot()
        if (!s.running) return

        val action = if (s.paused) {
            SyncForegroundService.ACTION_RESUME
        } else {
            SyncForegroundService.ACTION_PAUSE
        }
        startService(Intent(this, SyncForegroundService::class.java).setAction(action))
        updateSyncControls()
    }

    private fun requestStop() {
        if (!SyncControl.snapshot().running) return
        startService(
            Intent(this, SyncForegroundService::class.java)
                .setAction(SyncForegroundService.ACTION_STOP)
        )
        updateSyncControls()
    }

    private fun modeName(mode: SyncMode?): String =
        when (mode) {
            SyncMode.LIVE -> "Live Sync"
            SyncMode.BACKFILL -> "Archive Backfill"
            SyncMode.DIRECT -> "Direct Download"
            SyncMode.CLOUD -> "TeraBox Upload"
            null -> ""
        }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = UiKit.rounded(
                this@MainActivity,
                UiKit.surface,
                17,
                UiKit.line
            )
        }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        UiKit.sectionLabel(this)
    }

    private fun sectionParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) }

    private fun cardParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(9) }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                10
            )
        }
    }
}
