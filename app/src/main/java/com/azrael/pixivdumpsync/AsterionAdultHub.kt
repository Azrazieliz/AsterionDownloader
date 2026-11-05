package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.azrael.galleryflow.SiteLoginActivity
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
                existing.firstOrNull{SiteControls.key(it.url)==host})
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
        if(running.state=="ERROR")return "Check failed"
        val s=site.source
        if(s!=null && !s.error.isNullOrBlank())return "Source error"
        if(s==null||s.lastCheck<=0L)return "Not checked"
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
    internal fun realMedia(item:IndexedItem):Boolean{
        if(item.preview.isNullOrBlank()||!SearchRanking.validUrl(item.preview))
            return false
        if(item.title.isBlank() || item.title.length<4)return false
        val name=item.title.trim().lowercase()
        if(name.matches(Regex("(?i)^(portugu[eê]s|english|espa[nñ]ol|fran[cç]ais|deutsch|italiano|language|category|categories|browse|search|home|next page|previous page|index|login|register|all videos|trending|latest|popular)$")))
            return false
        val u=runCatching{Uri.parse(item.url)}.getOrNull()?:return false
        val path=u.path.orEmpty().trim('/').lowercase()
        if(path.isBlank())return false
        val segments=path.split('/')
        if(segments.firstOrNull() in setOf("tags","tag","category","categories",
                "language","languages","search","browse","popular","latest",
                "login","register","privacy","home","contact","page","model",
                "models","artists","performers","users"))return false
        if(segments.lastOrNull() in setOf("portugues","espanol","francais",
                "deutsch","italiano","english","home","popular"))return false
        return true
    }
    private fun media(a:Activity,parent:LinearLayout,site:Site,
                      cached:List<IndexedItem>){
        val chosen=cached.filter{SiteControls.key(it.source)==site.host && realMedia(it)}
            .take(24)
        val holder=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        if(chosen.isEmpty()){
            holder.addView(ui.text(a,
                "No usable previews indexed for this site yet. Backfill to check originals.",
                12f,ui.secondary),ui.lp(a,8))
            parent.addView(holder,ui.lp(a,6))
            return
        }
        holder.addView(ui.text(a,"Media · "+chosen.size+" recent results",
            13f,ui.white,true),ui.lp(a,8))
        val dense=a.getSharedPreferences("asterion_display",0).getBoolean("compact",true)
        val columns=if(dense)3 else 2
        for(set in chosen.chunked(columns)){
            val row=ui.row(a).apply{gravity=Gravity.TOP}
            for(item in set){
                val tile=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(ui.px(a,5),ui.px(a,5),ui.px(a,5),ui.px(a,8))
                    background=ui.touch(a,ui.raised,12)
                    isClickable=true;isFocusable=true
                    contentDescription="Download "+item.title
                    setOnClickListener{SourceIndexPanel.details(a,item)}
                }
                val cover=ImageView(a).apply{
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ui.surface)
                    setImageResource(R.drawable.ic_source_video)
                }
                tile.addView(cover,LinearLayout.LayoutParams(-1,
                    ui.px(a,if(columns==3)112 else 158)))
                item.preview?.let{RemoteImageLoader.load(a,cover,it,item.source)}
                tile.addView(ui.text(a,item.title.take(70),
                    11f,ui.white,true).apply{
                    maxLines=2;ellipsize=TextUtils.TruncateAt.END
                },ui.lp(a,5))
                row.addView(tile,LinearLayout.LayoutParams(0,-2,1f)
                    .apply{rightMargin=ui.px(a,5)})
            }
            repeat(columns-set.size){
                row.addView(View(a),LinearLayout.LayoutParams(0,1,1f))
            }
            holder.addView(row,ui.lp(a,6))
        }
        parent.addView(holder,ui.lp(a,7))
    }
    fun render(a:Activity,parent:LinearLayout,area:String,refresh:()->Unit){
        require(area in setOf("ADULT","HENTAI"))
        val title=if(area=="ADULT")"Porn" else "Hentai"
        parent.addView(ui.text(a,title,23f,ui.white,true),ui.lp(a,12))
        if(!AppPower.enabled(a)){
            val pause=ui.card(a,11)
            pause.addView(ui.text(a,"Downloads and sync paused",14f,ui.danger,true))
            pause.addView(ui.action(a,"RESUME",true){
                AppPower.set(a,true)
                refresh()
            },ui.lp(a,6))
            parent.addView(pause,ui.lp(a,8))
        }
        val sources=sites(a,area)
        val previews=SourceIndex.items(a,area,280)
        val selectedPrefs=a.getSharedPreferences("asterion_adult_site_focus",0)
        val focus=selectedPrefs.getString(area,"").orEmpty()
        val sitePane=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        for(site in sources){
            val status=sourceState(a,site)
            val issue=error(site,a)
            val operation=SiteRunControl.status(a,site.url)
            val active=operation.state in setOf("RUNNING","STOPPING")
            val expanded=focus==site.host
            val card=ui.card(a,9)
            val heading=ui.row(a).apply{gravity=Gravity.CENTER_VERTICAL}
            val nameBlock=LinearLayout(a).apply{
                orientation=LinearLayout.VERTICAL
                isClickable=true
                isFocusable=true
                contentDescription=site.name+" · "+status+
                    if(expanded)" · collapse media" else " · show media"
                setOnClickListener{
                    selectedPrefs.edit().putString(area,
                        if(expanded)"" else site.host).apply()
                    refresh()
                }
            }
            nameBlock.addView(ui.text(a,site.name,16f,ui.white,true).apply{
                maxLines=1;ellipsize=TextUtils.TruncateAt.END
            })
            nameBlock.addView(ui.text(a,status,11f,
                if(issue!=null)ui.danger else ui.secondary),ui.lp(a,3))
            heading.addView(nameBlock,LinearLayout.LayoutParams(0,-2,1f))
            val sync=Switch(a).apply{
                text="Sync"
                textSize=12f;setTextColor(ui.secondary)
                isChecked=SiteControls.sync(a,site.url,site.source?.enabled?:true)
                contentDescription="Automatically sync "+site.name
                setOnCheckedChangeListener{_,value->
                    SiteControls.setSync(a,site.url,value)
                    thread(name="asterion-site-toggle"){
                        SiteControls.applyToEngines(a.applicationContext,site.url)
                    }
                }
            }
            heading.addView(sync,LinearLayout.LayoutParams(-2,-2))
            card.addView(heading)
            val commands=ui.row(a)
            commands.addView(ui.action(a,if(active)"STOP" else
                if(operation.state=="ERROR")"RETRY BACKFILL" else "BACKFILL",!active){
                if(active){
                    SiteRunControl.stop(a,site.url)
                    refresh()
                }else if(!AppPower.enabled(a)){
                    Toast.makeText(a,"Resume downloads first",Toast.LENGTH_SHORT).show()
                }else{
                    val launched=SiteRunControl.start(a,site.url,area)
                    if(!launched)Toast.makeText(a,"This site is already being checked",
                        Toast.LENGTH_SHORT).show()
                    refresh()
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            val extraLabel=when{
                active->"STOPPING"
                issue?.contains("403")==true ||
                    issue?.contains("login",true)==true ->"SIGN IN"
                expanded->"HIDE MEDIA"
                else->"MEDIA"
            }
            commands.addView(ui.action(a,extraLabel,false){
                if(extraLabel=="SIGN IN"){
                    a.startActivity(Intent(a,SiteLoginActivity::class.java)
                        .putExtra(SiteLoginActivity.EXTRA_URL,site.url))
                }else if(active)SiteRunControl.stop(a,site.url)
                else{
                    selectedPrefs.edit().putString(area,if(expanded)"" else site.host).apply()
                    refresh()
                }
            },LinearLayout.LayoutParams(0,-2,0.68f).apply{
                leftMargin=ui.px(a,7)
            })
            card.addView(commands,ui.lp(a,7))
            if(!issue.isNullOrBlank()){
                val message=when{
                    issue.contains("403")->"403 · Sign-in or provider-specific access required"
                    issue.contains("redirect",true)->"Unexpected redirect · provider adapter needs repair"
                    else->issue.take(110)
                }
                card.addView(ui.text(a,message,11f,ui.danger),ui.lp(a,4))
            }
            if(expanded)media(a,card,site,previews)
            sitePane.addView(card,ui.lp(a,6))
        }
        parent.addView(sitePane,ui.lp(a,8))
        if(area=="ADULT")AsterionUnifiedFollowing.adult(a,parent,refresh)
    }
}
