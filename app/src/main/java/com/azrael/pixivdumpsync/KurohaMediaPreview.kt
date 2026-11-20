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
        val video: Boolean = false,
        val duration: String? = null
    )

    internal fun usable(tiles: List<Tile>): List<Tile> {
        // An indexed item may have only its selected-page URL while the
        // independent public listing has the genuine poster for that same
        // video. Earlier distinctBy() always kept the FIRST entry (often
        // a blank poster), discarding later image evidence.
        val selected=LinkedHashMap<String,Tile>()
        for(tile in tiles){
            if(!((tile.thumbnail.startsWith("content://") ||
                SearchRanking.validUrl(tile.thumbnail) ||
                (tile.video && SourceIndexParser.safe(tile.url))) &&
                tile.title.isNotBlank() && tile.url.isNotBlank()))continue
            val key=SearchRanking.canonicalKey(tile.url)
            val existing=selected[key]
            val hasPoster=tile.thumbnail.startsWith("content://") ||
                SearchRanking.validUrl(tile.thumbnail)
            val oldHasPoster=existing?.let{
                it.thumbnail.startsWith("content://") ||
                    SearchRanking.validUrl(it.thumbnail)
            }?:false
            if(existing==null || (hasPoster && !oldHasPoster))
                selected[key]=tile
        }
        return selected.values.take(120)
    }

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
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setBackgroundColor(ui.raised)
                    // A failed remote request must not leave a featureless square.
                    setImageResource(if(tile.video)R.drawable.ic_source_video
                        else R.drawable.ic_source_kyora)
                    contentDescription = tile.title + " · " + tile.attribution +
                        " · download original"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick(tile) }
                }
                // Owner-requested media identity under each thumbnail.
                // Keep the original 85dp preview itself unchanged and retain
                // the same horizontal strip and selected-original callback.
                val tileColumn=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(0,0,ui.px(a,5),0)
                }
                tileColumn.addView(thumb,LinearLayout.LayoutParams(
                    ui.px(a,85),ui.px(a,85)))
                tileColumn.addView(ui.text(a,tile.title,11f,ui.white,true).apply{
                    maxLines=2
                    ellipsize=android.text.TextUtils.TruncateAt.END
                    contentDescription=tile.title
                    setOnClickListener{onClick(tile)}
                },ui.lp(a,4))
                tile.duration?.takeIf{it.isNotBlank()}?.let{duration->
                    tileColumn.addView(ui.text(a,duration,10f,ui.secondary),
                        ui.lp(a,2))
                }
                strip.addView(tileColumn,LinearLayout.LayoutParams(
                    ui.px(a,124),-2).apply{rightMargin=ui.px(a,7)})

                if (tile.thumbnail.startsWith("content://")) {
                    CreatorPreviews.galleryThumbnail(a, thumb, tile.thumbnail, null)
                } else if (SearchRanking.validUrl(tile.thumbnail)) {
                    if (tile.video) {
                        VideoSearchPreviews.display(a, thumb, tile.thumbnail, tile.referer)
                    } else {
                        RemoteImageLoader.load(a, thumb, tile.thumbnail, tile.referer)
                    }
                }
                else if(tile.video){
                    // Rule34Video may publish the video without a catalog
                    // poster. The loader can inspect the exact selected page
                    // asynchronously; unrelated providers retain their icon.
                    VideoSearchPreviews.display(a,thumb,null,tile.referer)
                }
                // No verified poster: keep the honest tappable video icon.
                // Do not count a placeholder as a successful thumbnail.
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
