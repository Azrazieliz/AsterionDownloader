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
    /** The module contains its source rows and media, not an overlapping Add
     * form or a general website browser. Enrollment is shared with Search.
     */
    fun render(a:Activity,parent:LinearLayout,group:Group,refresh:()->Unit){
        val options=sections.filter{it.group==group}
        if(options.isEmpty())return
        val active=selected(a,group)
        val chips=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        for(opt in options){
            chips.addView(ui.chip(a,when(opt.id){
                "STUDIO"->"Studio"
                "MMD"->"MMD"
                "LIVE2D"->"Live2D"
                "ANIMATION"->"Animation"
                "MODS"->"Mods"
                else->opt.id
            },active.id==opt.id){
                select(a,opt.id)
                refresh()
            },LinearLayout.LayoutParams(-2,-2).apply{
                rightMargin=ui.px(a,7)
            })
        }
        parent.addView(HorizontalScrollView(a).apply{
            isHorizontalScrollBarEnabled=false
            addView(chips)
        },ui.lp(a,10))
        val card=ui.card(a,11)
        card.addView(ui.text(a,active.title.replace(" · "," / "),
            17f,ui.white,true))
        val count=SourceIndex.status(a,active.area)
        card.addView(ui.text(a,count.size.toString()+" saved sources · "+
            count.count{!it.error.isNullOrBlank()}+" need attention",
            12f,ui.secondary),ui.lp(a,6))
        if(count.isEmpty()){
            card.addView(ui.text(a,
                "Use Search to add a creator or source URL to "+active.id+
                ". Sources will appear here with their own Sync and Backfill controls.",
                12f,ui.secondary),ui.lp(a,6))
        }
        parent.addView(card,ui.lp(a,9))
        SourceIndexPanel.render(a,parent,active.area)
    }
}
