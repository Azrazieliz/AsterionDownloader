package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Search by title first; each result has a truthful download action and format. */
object CatalogSearchUi {
    private fun pref(a:Activity) =
        a.getSharedPreferences("asterion_catalog_results",Activity.MODE_PRIVATE)

    private fun decode(raw:String):List<CatalogHit> = runCatching {
        val arr=JSONArray(raw)
        (0 until minOf(100,arr.length())).mapNotNull { i ->
            val item=arr.optJSONObject(i)?:return@mapNotNull null
            val kind=runCatching{CatalogKind.valueOf(item.getString("kind"))}.getOrNull()
                ?:return@mapNotNull null
            CatalogHit(item.optString("title"),item.optString("source"),
                item.optString("url"),kind,item.optString("filename"),
                item.optString("details"),item.optString("coverUrl"))
        }.filter{SearchRanking.validUrl(it.url) ||
            it.url.startsWith("magnet:?xt=urn:btih:",true)}
    }.getOrDefault(emptyList())

    private fun persist(a:Activity,games:Boolean,title:String,hits:List<CatalogHit>) {
        val array=JSONArray()
        hits.take(100).forEach{hit->
            array.put(JSONObject().apply{
                put("title",hit.title);put("source",hit.source);put("url",hit.url)
                put("kind",hit.kind.name);put("filename",hit.filename);put("details",hit.details)
                put("coverUrl",hit.coverUrl)
            })
        }
        val prefix=if(games)"games" else "books"
        pref(a).edit().putString(prefix+"_title",title)
            .putString(prefix+"_hits",array.toString()).apply()
    }

    fun render(a:Activity,body:LinearLayout,games:Boolean) {
        val ui=NocturneDesign
        val name=if(games)"Games" else "Books"
        val prefKey=if(games)"games" else "books"
        val card=ui.card(a)
        card.addView(ui.text(a,"SEARCH "+name.uppercase()+" BY NAME",17f,ui.white,true),ui.lp(a,4))
        card.addView(ui.text(a,if(games)
            "Search torrent catalogs by game title. Tap a result to choose files, then watch the transfer queue. Games are saved to Downloads for external Android players."
            else "Search book title and author for EPUB editions. Comic chapter ZIPs are in Comics.",
            12f,ui.secondary),ui.lp(a,8))
        val query=EditText(a).apply{
            hint=if(games)"Game, visual novel or eroge title" else "Book title or author"
            setText(pref(a).getString(prefKey+"_title","").orEmpty())
            setSingleLine(true)
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            textSize=15f
            setTextColor(ui.white)
            setHintTextColor(ui.subdued)
            background=ui.shape(a,ui.raised,11)
            setPadding(ui.px(a,12),ui.px(a,10),ui.px(a,12),ui.px(a,10))
        }
        card.addView(query,ui.lp(a,9))
        val status=ui.text(a,"",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        fun show(hits:List<CatalogHit>){
            results.removeAllViews()
            results.addView(ui.text(a,hits.size.toString()+" matching files / listings",
                12f,ui.secondary),ui.lp(a,5))
            hits.forEach{hit->
                if((!games && hit.kind!=CatalogKind.EPUB) ||
                    (games && hit.kind!=CatalogKind.TORRENT))return@forEach
                val item=ui.card(a)
                if(SearchRanking.validUrl(hit.coverUrl)){
                    val cover=android.widget.ImageView(a).apply{
                        scaleType=android.widget.ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(ui.raised)
                    }
                    item.addView(cover,LinearLayout.LayoutParams(-1,ui.px(a,145)))
                    RemoteImageLoader.load(a,cover,hit.coverUrl,hit.url)
                }
                item.addView(ui.text(a,hit.title,15f,ui.white,true),ui.lp(a,5))
                val type=when(hit.kind){
                    CatalogKind.EPUB->"EPUB file"
                    CatalogKind.BOOK_FILE->"Original book · "+hit.filename.substringAfterLast('.', "file").uppercase()
                    CatalogKind.APK->"Android APK"
                    CatalogKind.GAME_ARCHIVE->"ZIP game archive"
                    CatalogKind.TORRENT->"Torrent index / file list"
                }
                item.addView(ui.text(a,hit.source+" · "+type,12f,ui.primary),ui.lp(a,5))
                if(hit.details.isNotBlank())item.addView(ui.text(a,hit.details.take(180),
                    11f,ui.secondary),ui.lp(a,5))
                val label=when(hit.kind){
                    CatalogKind.EPUB->"DOWNLOAD EPUB"
                    CatalogKind.BOOK_FILE->"DOWNLOAD ORIGINAL BOOK FILE"
                    CatalogKind.APK->"DOWNLOAD ANDROID APK"
                    CatalogKind.GAME_ARCHIVE->"DOWNLOAD GAME ZIP"
                    CatalogKind.TORRENT->"DOWNLOAD VIA TORRENT · SELECT FILES"
                }
                item.addView(ui.action(a,label,true){
                    when(hit.kind){
                        CatalogKind.TORRENT->ResourceDownloads.route(a,hit.url,"Games")
                        CatalogKind.EPUB,CatalogKind.BOOK_FILE->
                            ResourceDownloads.downloadFile(a,hit.url,"Books",hit.filename)
                        CatalogKind.APK,CatalogKind.GAME_ARCHIVE->
                            ResourceDownloads.downloadFile(a,hit.url,"Games",hit.filename)
                    }
                },ui.lp(a,7))
                results.addView(item,ui.lp(a,8))
            }
        }
        val button=ui.action(a,"SEARCH "+name.uppercase(),true){
            val title=query.text.toString().trim()
            if(title.length<2){
                Toast.makeText(a,"Enter a title",Toast.LENGTH_LONG).show()
            }else{
                status.text="Searching multiple live sources…"
                results.removeAllViews()
                thread(name="asterion-catalog-"+prefKey){
                    val found=runCatching{CatalogDiscovery.search(title,games)}
                    a.runOnUiThread{
                        found.onSuccess{outcome->
                            persist(a,games,title,outcome.hits)
                            status.text=outcome.hits.size.toString()+
                                " matching files · "+outcome.searched+" sources"+(
                                if(outcome.failures.isEmpty())"" else
                                    "\nUnavailable: "+outcome.failures.take(3).joinToString(" | ").take(300))
                            show(outcome.hits)
                        }.onFailure{error->
                            status.text="Search failed: "+(error.message?:"Unknown error")
                        }
                    }
                }
            }
        }
        card.addView(button,ui.lp(a,8))
        card.addView(status,ui.lp(a,8))
        body.addView(card,ui.lp(a,12))
        body.addView(results,ui.lp(a,8))
        val saved=decode(pref(a).getString(prefKey+"_hits","[]").orEmpty())
        if(saved.isNotEmpty())show(saved)
        if(games){
            val runtimes=ui.card(a)
            runtimes.addView(ui.text(a,"PLAY OUTSIDE ASTERION",14f,ui.white,true),ui.lp(a,5))
            runtimes.addView(ui.text(a,
                "Native APK → install separately. Ren'Py, RPG Maker, TyranoBuilder, Godot, HTML5 → JoiPlay + engine plugin. "+
                "Windows games → Winlator where compatible. ZIP assets can be extracted for an external player.",
                12f,ui.secondary),ui.lp(a,8))
            runtimes.addView(ui.action(a,"JOIPLAY / ANDROID GAME RUNTIMES",false){
                a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.joiplay.net/")))
            },ui.lp(a,6))
            runtimes.addView(ui.action(a,"WINLATOR / WINDOWS GAME COMPATIBILITY",false){
                a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/brunodev85/winlator")))
            },ui.lp(a,6))
            body.addView(runtimes,ui.lp(a,10))
        }
    }
}
