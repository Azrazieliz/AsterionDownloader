package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.azrael.galleryflow.SiteLoginActivity
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import kotlin.concurrent.thread

/** Compact site-row module. A provider row owns its controls and its media.
 * No duplicated provider picker, browser, global backfill toggle or false
 * completion indicators.
 */
object AsterionAdultHub {
    private val ui=NocturneDesign
    private data class Site(val name:String,val url:String,val source:IndexedSource?){
        val host:String get()=SiteControls.key(url)
    }
    private fun sites(a:Activity,area:String):List<Site>{
        val results=LinkedHashMap<String,Site>()
        val approved=AlohaSources.by(if(area=="ADULT")AlohaArea.ADULT else AlohaArea.HENTAI)
        val existing=SourceIndex.status(a,area)
        for(site in approved){
            val host=SiteControls.key(site.url)
            if(host.isNotBlank())results[host]=Site(site.name,site.url,
                SiteRunControl.matchingSource(site.url,area,existing))
        }
        for(site in existing){
            val host=SiteControls.key(site.url)
            if(host.isNotBlank() && host !in results)
                results[host]=Site(site.name,site.url,site)
        }
        return results.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.name})
    }
    private fun sourceState(a:Activity,site:Site):String{
        val running=SiteRunControl.status(a,site.url)
        if(running.state=="RUNNING")return "Backfilling"
        if(running.state=="STOPPING")return "Stopping"
        if(running.state=="STOPPED")return "Stopped"
        if(running.state=="QUEUED")return "History queued; cursor retained"
        if(running.state=="INTERRUPTED")return "Interrupted; retry available"
        if(running.state=="ERROR")return "Check failed"
        val s=site.source
        if(s!=null && !s.error.isNullOrBlank())return "Source error"
        if(s==null||s.lastCheck<=0L)return "Not checked"
        if(!s.historyComplete && !s.cursor.isNullOrBlank())
            return "Older pages pending · "+s.discovered+" indexed"
        return if(s.discovered>0)"Indexed "+s.discovered else "Checked · no items"
    }
    private fun error(site:Site,a:Activity):String?{
        val r=SiteRunControl.status(a,site.url)
        if(r.state=="ERROR")return r.message
        return site.source?.error
    }
    /** Do not recommend site menus, language directories, tags or category pages
     * as if they were individual media. Never invent missing thumbnails.
     */
    internal fun realMedia(item:IndexedItem):Boolean =
        IndexedMediaQuality.isMedia(item.url,item.title,item.preview,item.area)
    /** Kuroha/Pixiv behavior: actual thumbnails appear immediately when the
     * category opens. Website management is separate and never gates media.
     * Every media tile retains its original download action.
     */
    fun render(a:Activity,parent:LinearLayout,area:String,refresh:()->Unit){
        require(area in setOf("ADULT","HENTAI"))
        val title=if(area=="ADULT")"Porn" else "Hentai"
        val sources=sites(a,area)
        val preferences=a.getSharedPreferences("asterion_gallery_layout",0)
        val manage=preferences.getBoolean(area+"_websites",false)
        val performers=preferences.getBoolean("performers",false)

        val heading=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.addView(ui.text(a,title,22f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(ui.chip(a,
            if(manage)"CLOSE WEBSITES" else "WEBSITES · "+sources.size,
            manage){
            preferences.edit().putBoolean(area+"_websites",!manage).apply()
            refresh()
        },LinearLayout.LayoutParams(-2,-2))
        parent.addView(heading,ui.lp(a,7))

        if(manage){
            val panel=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            val explanation=ui.text(a,
                "Sync checks for new posts automatically. Backfill checks older " +
                    "pages only when requested. Indexed does not mean downloaded.",
                12f,ui.secondary)
            panel.addView(explanation,ui.lp(a,7))
            panel.addView(ui.action(a,"REFRESH WEBSITE STATUS",false){
                refresh()
            },ui.lp(a,7))
            for(site in sources){
                val issue=error(site,a)
                val state=sourceState(a,site)
                val operation=SiteRunControl.status(a,site.url)
                val running=operation.state in setOf("RUNNING","QUEUED","STOPPING")
                val card=ui.card(a,10)
                val row=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
                val identity=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
                identity.addView(ui.text(a,site.name,15f,ui.white,true)
                    .apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END})
                identity.addView(ui.text(a,state,11f,
                    if(issue.isNullOrBlank())ui.secondary else ui.danger),
                    ui.lp(a,3))
                row.addView(identity,LinearLayout.LayoutParams(0,-2,1f))
                val sync=Switch(a).apply{
                    text="Sync";textSize=12f;setTextColor(ui.secondary)
                    isChecked=SiteControls.sync(a,site.url,
                        site.source?.enabled?:false)
                    contentDescription="Sync new items from "+site.name
                    setOnCheckedChangeListener{_,enabled->
                        SiteControls.setSync(a,site.url,enabled)
                        thread(name="asterion-site-toggle"){
                            val app=a.applicationContext
                            if(enabled){
                                val address=site.source?.url?:site.url
                                SourceIndexDb(app).use{db->
                                    db.add(address,site.name,area,"native")
                                    db.enable(address,true)
                                }
                                VideoDb(app).use{db->db.addWatch(address,site.name)}
                            }
                            SiteControls.applyToEngines(app,site.url)
                            if(enabled)VideoEngine.launch(app,true)
                        }
                    }
                }
                row.addView(sync,LinearLayout.LayoutParams(-2,-2))
                card.addView(row)
                val actions=ui.row(a)
                actions.addView(ui.action(a,if(running)"STOP" else
                    if(operation.state=="ERROR")"RETRY BACKFILL" else "BACKFILL",
                    !running){
                    if(running){
                        SiteRunControl.stop(a,site.url)
                        refresh()
                    }else if(!AppPower.enabled(a)){
                        Toast.makeText(a,"Resume downloads to run backfill",
                            Toast.LENGTH_SHORT).show()
                    }else{
                        if(!SiteRunControl.start(a,site.url,area)){
                            Toast.makeText(a,"Site check already active",
                                Toast.LENGTH_SHORT).show()
                        }
                        refresh()
                    }
                },LinearLayout.LayoutParams(0,-2,1f))
                val needsLogin=issue?.contains("403")==true ||
                    issue?.contains("login",true)==true ||
                    issue?.contains("account",true)==true
                if(needsLogin){
                    actions.addView(ui.action(a,"SIGN IN",false){
                        a.startActivity(Intent(a,SiteLoginActivity::class.java)
                            .putExtra(SiteLoginActivity.EXTRA_URL,site.url))
                    },LinearLayout.LayoutParams(0,-2,0.7f).apply{
                        leftMargin=ui.px(a,8)
                    })
                }
                card.addView(actions,ui.lp(a,7))
                if(!issue.isNullOrBlank()){
                    val readable=when{
                        issue.contains("403")->"Access requires a valid session or a supported provider adapter."
                        issue.contains("429")->"Rate limited. Try again after the provider's cooldown."
                        issue.contains("redirect",true)->"Unexpected redirect. Source adapter needs repair."
                        else->issue.take(120)
                    }
                    card.addView(ui.text(a,readable,11f,ui.danger),ui.lp(a,5))
                }
                panel.addView(card,ui.lp(a,6))
            }
            parent.addView(panel,ui.lp(a,9))
        }

        // Query the existing cache; no website request and no hidden media button.
        val indexed=SourceIndex.items(a,area,360)
            .asSequence()
            .filter{realMedia(it) && !it.preview.isNullOrBlank() &&
                SearchRanking.validUrl(it.preview)}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .sortedByDescending{it.discoveredAt}
            .take(180).toList()
        val selectedHost=preferences.getString(area+"_filter","").orEmpty()
        val available=linkedMapOf<String,String>()
        for(site in sources){
            if(indexed.any{SiteControls.key(it.source)==site.host})
                available[site.host]=site.name
        }
        val activeHost=selectedHost.takeIf{it in available}.orEmpty()
        val selected=if(activeHost.isBlank())indexed
            else indexed.filter{SiteControls.key(it.source)==activeHost}
        val galleryHeader=ui.row(a)
        galleryHeader.addView(ui.text(a,"Latest media",18f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        galleryHeader.addView(ui.text(a,""+selected.size+" previews",
            11f,ui.secondary),LinearLayout.LayoutParams(-2,-2))
        parent.addView(galleryHeader,ui.lp(a,12))
        if(indexed.isNotEmpty()){
            val chips=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
            chips.addView(ui.chip(a,"All sites",activeHost.isBlank()){
                preferences.edit().putString(area+"_filter","").apply()
                refresh()
            },LinearLayout.LayoutParams(-2,-2).apply{
                rightMargin=ui.px(a,6)
            })
            for((host,name) in available){
                chips.addView(ui.chip(a,name,activeHost==host){
                    preferences.edit().putString(area+"_filter",host).apply()
                    refresh()
                },LinearLayout.LayoutParams(-2,-2).apply{
                    rightMargin=ui.px(a,6)
                })
            }
            parent.addView(HorizontalScrollView(a).apply{
                isHorizontalScrollBarEnabled=false
                addView(chips)
            },ui.lp(a,8))
            UnifiedMediaGrid.render(a,parent,selected,listOf(area),title){
                SourceIndexPanel.details(a,it)
            }
            parent.addView(ui.text(a,
                "Previews are indexed media, not completed downloads.",
                11f,ui.secondary),ui.lp(a,4))
        }else{
            val card=ui.card(a,12)
            card.addView(ui.text(a,
                "No genuine thumbnails are indexed yet. Select WEBSITES to check " +
                    "specific sources; unavailable providers are not shown as fake media.",
                12f,ui.secondary))
            parent.addView(card,ui.lp(a,9))
        }

        if(area=="ADULT"){
            val followRow=ui.row(a)
            followRow.addView(ui.text(a,"Followed performers",
                17f,ui.white,true),LinearLayout.LayoutParams(0,-2,1f))
            followRow.addView(ui.chip(a,
                if(performers)"HIDE" else "MANAGE",performers){
                preferences.edit().putBoolean("performers",!performers).apply()
                refresh()
            },LinearLayout.LayoutParams(-2,-2))
            parent.addView(followRow,ui.lp(a,17))
            if(performers)AsterionUnifiedFollowing.adult(a,parent,refresh)
        }
    }
}
