package com.azrael.pixivdumpsync

import android.app.Activity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Original Pixiv artist-card media strip, shared by website, performer and
 * cosplay cards. The user-approved artist layout uses an 85 dp square image,
 * 7 dp spacing and a horizontally scrollable strip, not a four-column feed
 * of cropped video tiles, captions, or gigantic site-management blocks.
 */
internal object KurohaMediaPreview {
    private val ui = NocturneDesign

    data class Tile(
        val url: String,
        val title: String,
        val thumbnail: String,
        val attribution: String,
        val referer: String,
        val video: Boolean = false
    )

    internal fun usable(tiles: List<Tile>): List<Tile> =
        tiles.asSequence().filter {
            (it.thumbnail.startsWith("content://") ||
                SearchRanking.validUrl(it.thumbnail)) &&
                it.title.isNotBlank() && it.url.isNotBlank()
        }.distinctBy { SearchRanking.canonicalKey(it.url) }
            .take(120).toList()

    fun render(
        a: Activity,
        parent: LinearLayout,
        group: String,
        tiles: List<Tile>,
        onClick: (Tile) -> Unit
    ) {
        val images = usable(tiles)
        if (images.isEmpty()) return

        val prefs = a.getSharedPreferences("asterion_kuroha_visual", 0)
        val key = "strip:" + group.take(120)
        val holder = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        fun paint() {
            holder.removeAllViews()
            val limit = prefs.getInt(key, 5).coerceIn(5, 40)
            val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }

            for (tile in images.take(limit)) {
                val thumb = ImageView(a).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ui.raised)
                    contentDescription = tile.title + " · " + tile.attribution +
                        " · download original"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick(tile) }
                }
                strip.addView(thumb, LinearLayout.LayoutParams(
                    ui.px(a, 85), ui.px(a, 85)
                ).apply { rightMargin = ui.px(a, 7) })

                if (tile.thumbnail.startsWith("content://")) {
                    CreatorPreviews.galleryThumbnail(a, thumb, tile.thumbnail, null)
                } else if (tile.video) {
                    VideoSearchPreviews.display(a, thumb, tile.thumbnail, tile.referer)
                } else {
                    RemoteImageLoader.load(a, thumb, tile.thumbnail, tile.referer)
                }
            }
            holder.addView(HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                clipToPadding = false
                addView(strip)
            }, ui.lp(a, 9))

            if (images.size > limit) {
                val remaining = images.size - limit
                holder.addView(ui.action(a,
                    "SHOW MORE PREVIEWS · " + remaining, false) {
                    prefs.edit().putInt(key, (limit + 8).coerceAtMost(40)).apply()
                    paint()
                }, ui.lp(a, 6))
            }
            if (limit > 5) {
                holder.addView(ui.action(a, "SHOW FEWER PREVIEWS", false) {
                    prefs.edit().putInt(key, 5).apply()
                    paint()
                }, ui.lp(a, 5))
            }
        }
        paint()
        parent.addView(holder, ui.lp(a, 7))
    }
}
