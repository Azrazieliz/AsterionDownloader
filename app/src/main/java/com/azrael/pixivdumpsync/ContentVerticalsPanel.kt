package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.azrael.galleryflow.ProviderWebActivity
import com.azrael.galleryflow.SiteLoginActivity
import com.azrael.galleryflow.SiteSession
import java.net.URI

/** Unified source shelves, not separate browsers or applications. Each follows the
 * existing SourceIndex queue and uses the same Nocturne cards, sessions, and grid.
 */
object ContentVerticalsPanel {
    enum class Group { ASSETS, GAMES, ADULT }
    data class Provider(val name:String,val url:String)
    data class Vertical(val id:String,val title:String,val subtitle:String,
        val group:Group,val area:String,val sources:List<Provider>)
    val sections=listOf(
        Vertical("STUDIO","Koikatsu After Party · Studio",
            "Character cards remain in Assets; follow Studio scenes, outfits, "+
            "poses, plugins and mod archives for Koikatsu After Party only.",
            Group.ASSETS,"STUDIO",listOf(
                Provider("Bepis Koikatsu","https://db.bepis.moe/koikatsu"),
                Provider("KoikatsuCards","https://koikatsucards.com/"),
                Provider("BOOTH creator assets","https://booth.pm/en"))),
        Vertical("MMD","MMD · Motion Library",
            "PMX/PMD models, VMD motions, stages, poses, motion archives and rigs.",
            Group.ASSETS,"MMD",listOf(
                Provider("BowlRoll","https://bowlroll.net/"),
                Provider("DeviantArt","https://www.deviantart.com/"),
                Provider("BOOTH","https://booth.pm/en"))),
        Vertical("LIVE2D","Live2D Projects",
            "Cubism model source files, expressions, motions, texture atlases "+
            "and downloadable project bundles.",
            Group.ASSETS,"LIVE2D",listOf(
                Provider("BOOTH","https://booth.pm/en"),
                Provider("nizima","https://nizima.com/"))),
        Vertical("ANIMATION","Animated Illustration",
            "Layered PSD / KRA / CLIP sources, Spine animations and projects.",
            Group.ASSETS,"ANIMATION",listOf(
                Provider("BOOTH","https://booth.pm/en"),
                Provider("Gumroad","https://gumroad.com/"),
                Provider("DeviantArt","https://www.deviantart.com/"))),
        Vertical("MODS","Eroge · Visual Novel Mods",
            "Ren'Py and game patches, fan translations, extra routes, "+
            "mods and related game assets; not automatic installation.",
            Group.GAMES,"MODS",listOf(
                Provider("itch.io VN mods","https://itch.io/game-mods/genre-visual-novel"),
                Provider("GameBanana","https://gamebanana.com/"),
                Provider("F95Zone","https://f95zone.to/"))),
        Vertical("VOICE","Doujin Voice · ASMR",
            "Binaural voice works, audio dramas, voice sets and original albums.",
            Group.ADULT,"VOICE",listOf(
                Provider("DLsite doujin","https://www.dlsite.com/maniax/"),
                Provider("BOOTH","https://booth.pm/en"),
                Provider("Fantia","https://fantia.jp/")))
    )
    // Defer Android graphics initialization until an actual Activity renders.
    private val ui get()=NocturneDesign
    private const val PREFS="asterion_integrated_content_verticals"
    private fun prefs(a:Activity)=a.getSharedPreferences(PREFS,Activity.MODE_PRIVATE)
    private fun groupFor(id:String)=sections.firstOrNull{it.id==id}?.group
    fun select(a:Activity,id:String){
        val group=groupFor(id)?:return
        prefs(a).edit().putString("selected_"+group.name,id).apply()
    }
    private fun selected(a:Activity,group:Group):Vertical{
        val options=sections.filter{it.group==group}
        val saved=prefs(a).getString("selected_"+group.name,options.first().id)
        return options.firstOrNull{it.id==saved}?:options.first()
    }
    fun render(a:Activity,parent:LinearLayout,group:Group,refresh:()->Unit){
        val options=sections.filter{it.group==group}
        if(options.isEmpty())return
        val title=when(group){
            Group.ASSETS->"Studio · MMD · Live2D · Animated art"
            Group.GAMES->"Game mods · Patches · Fan translations"
            Group.ADULT->"Doujin voice · ASMR archives"
        }
        parent.addView(ui.text(a,title,20f,ui.white,true),ui.lp(a,20))
        parent.addView(ui.text(a,
            "One downloader library · source sessions · historical scans · original files.",
            12f,ui.secondary),ui.lp(a,5))
        val active=selected(a,group)
        val chips=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        for(opt in options)
            chips.addView(ui.chip(a,opt.id.replace('_',' '),active.id==opt.id){
                select(a,opt.id);refresh()
            },LinearLayout.LayoutParams(-2,-2).apply{
                rightMargin=ui.px(a,7)
            })
        parent.addView(HorizontalScrollView(a).apply{
            isHorizontalScrollBarEnabled=false
            addView(chips)
        },ui.lp(a,10))
        val panel=ui.card(a)
        panel.addView(ui.text(a,active.title,18f,ui.white,true))
        panel.addView(ui.text(a,active.subtitle,12f,ui.secondary),ui.lp(a,5))
        val followed=SourceIndex.status(a,active.area)
        panel.addView(ui.text(a,
            followed.count{it.enabled}.toString()+" followed source(s) · "+
            followed.count{!it.error.isNullOrBlank()}+" source issue(s)",
            12f,ui.secondary),ui.lp(a,8))
        val input=EditText(a).apply{
            hint="Creator, collection or downloadable source URL"
            textSize=14f;setSingleLine(true)
            setTextColor(ui.white);setHintTextColor(ui.secondary)
            background=ui.shape(a,ui.raised,12)
            setPadding(ui.px(a,12),ui.px(a,11),ui.px(a,12),ui.px(a,11))
        }
        panel.addView(input,ui.lp(a,10))
        panel.addView(ui.action(a,"FOLLOW SOURCE & BACKFILL",true){
            val url=input.text.toString().trim()
            if(!SourceIndexParser.safe(url)){
                Toast.makeText(a,"Use a valid HTTPS creator or collection URL",
                    Toast.LENGTH_LONG).show()
                return@action
            }
            val domain=runCatching{URI(url).host.orEmpty()}.getOrDefault("")
            SourceIndex.enroll(a.applicationContext,url,active.area,domain,false)
            Toast.makeText(a,"Source added to "+active.title+
                " · originals are resolved during backfill",Toast.LENGTH_LONG).show()
            refresh()
        },ui.lp(a,9))
        parent.addView(panel,ui.lp(a,10))
        for(provider in active.sources){
            val row=ui.card(a,12)
            val host=runCatching{URI(provider.url).host.orEmpty()}.getOrDefault("")
            row.addView(ui.text(a,provider.name,15f,ui.white,true))
            row.addView(ui.text(a,host+" · "+(if(SiteSession.bound(a,provider.url))
                "session connected" else "account optional / disconnected"),
                11f,ui.secondary),ui.lp(a,4))
            val browse=ui.action(a,"BROWSE IN APP",false){
                a.startActivity(Intent(a,ProviderWebActivity::class.java)
                    .putExtra(ProviderWebActivity.EXTRA_URL,provider.url)
                    .putExtra(ProviderWebActivity.EXTRA_FOLDER,when(group){
                        Group.ASSETS->"Assets";Group.GAMES->"Games";Group.ADULT->"Videos"
                    }))
            }
            val login=ui.action(a,"ACCOUNT",false){
                a.startActivity(Intent(a,SiteLoginActivity::class.java)
                    .putExtra(SiteLoginActivity.EXTRA_URL,provider.url))
            }
            row.addView(ui.weighted(a,browse,login),ui.lp(a,8))
            parent.addView(row,ui.lp(a,8))
        }
        SourceIndexPanel.render(a,parent,active.area)
    }
}
