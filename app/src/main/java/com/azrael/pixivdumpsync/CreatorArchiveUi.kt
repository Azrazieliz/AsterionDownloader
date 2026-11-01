package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Native Android UI for public creator archives. No browser paywall trick or premium credential entry.
object CreatorArchiveUi {
    private val services=listOf("All","Patreon","FANBOX","Fantia","Gumroad",
        "OnlyFans","Fansly","SubscribeStar","Boosty","DLsite","Discord")
    private fun pref(a:Activity)=a.getSharedPreferences("asterion_creator_archive_ui",Activity.MODE_PRIVATE)
    private fun openUrl(a:Activity,url:String) {
        runCatching{a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
            .onFailure{Toast.makeText(a,"No browser available",Toast.LENGTH_LONG).show()}
    }
    private fun savedResults(a:Activity):List<ArchiveCreator> = runCatching {
        val raw=pref(a).getString("results","[]")
        val arr=JSONArray(raw)
        (0 until minOf(arr.length(),60)).mapNotNull { i ->
            val row=arr.optJSONObject(i)?:return@mapNotNull null
            val url=row.optString("url")
            CreatorArchives.parseCreatorUrl(url)?.copy(name=row.optString("name"))
        }
    }.getOrDefault(emptyList())
    private fun remember(a:Activity,query:String,creators:List<ArchiveCreator>) {
        val arr=JSONArray()
        creators.take(60).forEach{
            arr.put(JSONObject().put("url",it.url).put("name",it.name))
        }
        pref(a).edit().putString("query",query).putString("results",arr.toString()).apply()
    }

    fun render(a:Activity,body:LinearLayout,section:ArchiveSection=ArchiveSection.ARTWORK) {
        val ui=NocturneDesign
        val heading=ui.card(a)
        heading.addView(ui.text(a,section.label.uppercase()+" · CREATOR SOURCES",19f,ui.white,true),ui.lp(a,5))
        heading.addView(ui.text(a,
            "Search public archives for Patreon, FANBOX, Fantia, Gumroad, OnlyFans, Fansly and other creator posts. " +
            "Search results are filtered to "+section.label+" file types. Preview availability and original-file access vary by source.",
            12f,ui.secondary),ui.lp(a,10))
        val query=EditText(a).apply{
            hint="Creator name or Pawchive / Kemono / Coomer creator URL"
            setText(pref(a).getString("query","").orEmpty())
            setSingleLine(true);imeOptions=EditorInfo.IME_ACTION_SEARCH
            textSize=14f;setTextColor(ui.white);setHintTextColor(ui.subdued)
            background=ui.shape(a,ui.raised,11)
            setPadding(ui.px(a,12),ui.px(a,10),ui.px(a,12),ui.px(a,10))
        }
        heading.addView(query,ui.lp(a,8))
        heading.addView(ui.text(a,"Platform filter",12f,ui.secondary),ui.lp(a,7))
        val filter=Spinner(a)
        filter.adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,services)
        filter.setSelection(pref(a).getInt("platform",0).coerceIn(0,services.lastIndex))
        heading.addView(filter,ui.lp(a,7))
        val status=ui.text(a,"",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val saved=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val posts=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        lateinit var openCreator:(ArchiveCreator,Int)->Unit

        fun queue(items:List<ArchivePost>) {
            val selected=items.map{ArchiveContent.filtered(section,it)}.filter{it.files.isNotEmpty()}
            val outcome=CreatorArchiveDownloads.enqueue(a,selected)
            status.text=if(!AppPower.enabled(a))
                "App OFF: no download enqueued. Re-enable downloads in the header."
            else "${outcome.queued} queued · ${outcome.skipped} already queued or past the page limit · ${outcome.failed} failed. " +
                "Check Android Downloads for transfer progress. Retry failures by selecting the post again."
        }

        fun renderSaved() {
            saved.removeAllViews()
            val watches=CreatorArchiveStore.list(a)
            saved.addView(ui.text(a,"SAVED CREATORS (${watches.size})",16f,ui.white,true),ui.lp(a,7))
            if(watches.isEmpty()){
                saved.addView(ui.text(a,"No saved creators yet.",12f,ui.secondary),ui.lp(a,5))
            }
            watches.forEach{creator->
                val c=ui.card(a)
                c.addView(ui.text(a,creator.name,15f,ui.white,true),ui.lp(a,4))
                c.addView(ui.text(a,"${creator.host.title} · ${creator.service}",12f,ui.secondary),ui.lp(a,5))
                c.addView(ui.text(a,CreatorArchiveScheduler.status(a,creator).take(170),
                    11f,ui.secondary),ui.lp(a,5))
                val toggle=Switch(a).apply {
                    text="Automatic updates"
                    setTextColor(ui.white)
                    isChecked=CreatorArchiveScheduler.enabled(a,creator)
                    setOnCheckedChangeListener{_,checked->
                        CreatorArchiveScheduler.enable(a,creator,checked)
                    }
                }
                c.addView(toggle,ui.lp(a,5))
                c.addView(ui.action(a,"SYNC NEW + CONTINUE BACKFILL",true){
                    CreatorArchiveScheduler.scanNow(a)
                    status.text="Background sync requested; reopen this page for latest status"
                },ui.lp(a,7))
                c.addView(ui.action(a,"CHECK NEW POSTS",true){openCreator(creator,0)},ui.lp(a,7))
                c.addView(ui.action(a,"REMOVE SAVED CREATOR",false){
                    CreatorArchiveStore.set(a,creator,false)
                    renderSaved()
                },ui.lp(a,6))
                saved.addView(c,ui.lp(a,8))
            }
        }

        fun showCreators(creators:List<ArchiveCreator>){
            results.removeAllViews()
            val selected=services[filter.selectedItemPosition.coerceIn(0,services.lastIndex)]
            val filtered=if(selected=="All")creators else creators.filter{
                it.service.equals(selected,true)
            }
            results.addView(ui.text(a,"CREATOR MATCHES (${filtered.size})",16f,ui.white,true),ui.lp(a,7))
            filtered.forEach{creator->
                val c=ui.card(a)
                c.addView(ui.text(a,creator.name,15f,ui.white,true),ui.lp(a,4))
                c.addView(ui.text(a,"${creator.host.title} · ${creator.service} · ${creator.id}",
                    12f,ui.secondary),ui.lp(a,5))
                c.addView(ui.action(a,"BROWSE POSTS & FILES",true){openCreator(creator,0)},ui.lp(a,7))
                c.addView(ui.action(a,"SAVE CREATOR",false){
                    CreatorArchiveStore.set(a,creator,true)
                    renderSaved()
                    status.text="Saved "+creator.name+"; periodic updates scheduled."
                },ui.lp(a,5))
                results.addView(c,ui.lp(a,8))
            }
        }

        openCreator={creator,offset->
            posts.removeAllViews()
            status.text="Loading ${creator.host.title} ${creator.service} posts at offset $offset…"
            thread(name="asterion-archive-posts") {
                val outcome=runCatching{CreatorArchives.posts(creator,offset)}
                a.runOnUiThread{
                    outcome.onFailure {
                        status.text="${creator.host.title} unavailable: ${it.message ?: "Unknown error"}"
                        posts.addView(ui.action(a,"OPEN ORIGINAL ARCHIVE PAGE",false){
                            openUrl(a,creator.url)
                        },ui.lp(a,7))
                    }.onSuccess{page->
                        status.text="${page.size} posts checked · ${creator.host.title} · offset $offset"
                        val header=ui.card(a)
                        header.addView(ui.text(a,creator.name,19f,ui.white,true),ui.lp(a,5))
                        header.addView(ui.text(a,creator.host.title+" · "+creator.service+" · "+creator.id,
                            12f,ui.secondary),ui.lp(a,6))
                        header.addView(ui.action(a,"OPEN IN BROWSER",false){openUrl(a,creator.url)},ui.lp(a,5))
                        header.addView(ui.action(a,"SAVE CREATOR",false){
                            CreatorArchiveStore.set(a,creator,true);renderSaved()
                        },ui.lp(a,5))
                        posts.addView(header,ui.lp(a,8))
                        if(page.isEmpty())posts.addView(ui.text(a,
                            "No posts returned on this page. This may be an empty archive or an incomplete upstream import.",
                            12f,ui.secondary),ui.lp(a,7))
                        else{
                            posts.addView(ui.action(a,"QUEUE NEXT UP TO 40 FILES FROM THIS PAGE",true){
                                queue(page)
                            },ui.lp(a,10))
                            page.map{ArchiveContent.filtered(section,it)}.filter{it.files.isNotEmpty()}.forEach{post->
                                val card=ui.card(a)
                                ArchiveContent.preview(post)?.let{url->
                                    val thumbnail=ImageView(a).apply{
                                        scaleType=ImageView.ScaleType.CENTER_CROP
                                        setBackgroundColor(ui.raised)
                                    }
                                    card.addView(thumbnail,LinearLayout.LayoutParams(-1,ui.px(a,172)))
                                    RemoteImageLoader.load(a,thumbnail,url,post.url)
                                }
                                card.addView(ui.text(a,post.title,15f,ui.white,true),ui.lp(a,4))
                                card.addView(ui.text(a,
                                    (post.published.take(10).ifBlank{"Undated"})+
                                    " · ${post.files.size} files",12f,ui.secondary),ui.lp(a,5))
                                post.files.take(3).forEach{file->
                                    card.addView(ui.text(a,file.title.take(80),11f,ui.secondary),ui.lp(a,3))
                                }
                                card.addView(ui.action(a,"QUEUE POST FILES",true){queue(listOf(post))},ui.lp(a,7))
                                card.addView(ui.action(a,"OPEN POST",false){openUrl(a,post.url)},ui.lp(a,5))
                                posts.addView(card,ui.lp(a,8))
                            }
                            if(page.size>=50 && offset<50000)posts.addView(
                                ui.action(a,"BACKFILL · NEXT 50 POSTS",true){
                                    openCreator(creator,offset+50)
                                },ui.lp(a,12))
                        }
                    }
                }
            }
        }

        heading.addView(ui.action(a,"SEARCH CREATOR ARCHIVES",true){
            val name=query.text.toString().trim()
            if(name.length<2){
                status.text="Enter a creator name or archive creator URL"
            }else{
                pref(a).edit().putInt("platform",filter.selectedItemPosition).apply()
                status.text="Searching Pawchive, Kemono and Coomer; unavailable indexes are reported."
                results.removeAllViews();posts.removeAllViews()
                thread(name="asterion-creator-search") {
                    val outcome=runCatching{CreatorArchives.search(name)}
                    a.runOnUiThread {
                        outcome.onSuccess{
                            remember(a,name,it.creators)
                            status.text="${it.creators.size} creator matches across ${it.checked} indexes" +
                                (if(it.errors.isEmpty())"" else "\nUnavailable: "+
                                    it.errors.joinToString(" | ").take(450))
                            showCreators(it.creators)
                        }.onFailure {
                            status.text="Creator search failed: ${it.message ?: "Unknown error"}"
                        }
                    }
                }
            }
        },ui.lp(a,10))
        heading.addView(status,ui.lp(a,9))
        body.addView(heading,ui.lp(a,10))
        body.addView(posts,ui.lp(a,8))
        body.addView(results,ui.lp(a,8))
        body.addView(saved,ui.lp(a,8))
        val last=savedResults(a)
        if(last.isNotEmpty())showCreators(last)
        renderSaved()
    }
}
