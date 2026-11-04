package com.azrael.pixivdumpsync

import android.app.Activity
import android.net.Uri
import android.widget.*
import com.azrael.galleryflow.SiteLoginActivity
import android.content.Intent
import java.net.URI

/** Kuroha-style comic series archive list, reusing original ZIP download and retry queue. */
object AsterionComicFollowing {
    private val ui=NocturneDesign
    private val hosts=setOf("toonily.com","mangaread.org","sushiscan.net")
    private fun allowed(link:String):Boolean=runCatching{
        val uri=URI(link.trim())
        uri.scheme=="https" && uri.host.orEmpty().lowercase().removePrefix("www.") in hosts
    }.getOrDefault(false)
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        val tasks=ChapterQueue(a).use{it.list().filterNot{job->
            NhentaiChapters.isGallery(job.url)
        }}
        parent.addView(ui.text(a,"Comics · ZIP archives",21f,ui.white,true),ui.lp(a,13))
        val inputCard=ui.card(a)
        val input=EditText(a).apply{
            hint="Toonily / MangaRead / Sushi-Scan series URL"
            setSingleLine(true);textSize=14f
            setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        inputCard.addView(input)
        inputCard.addView(ui.action(a,"ADD SERIES · BACKFILL",true){
            val address=input.text.toString().trim()
            if(!allowed(address)) {
                Toast.makeText(a,"Use a series URL from Toonily, MangaRead or Sushi-Scan",
                    Toast.LENGTH_LONG).show()
                return@action
            }
            val title=Uri.parse(address).pathSegments.lastOrNull().orEmpty()
                .replace('-', ' ').replace('_',' ').ifBlank{"Comic series"}
            val added=ChapterQueue(a).use{it.enqueue(address,title)}
            if(added){ChapterArchives.start(a);refresh()}
            else Toast.makeText(a,"Already in your comics archive",Toast.LENGTH_SHORT).show()
        },ui.lp(a,8))
        parent.addView(inputCard,ui.lp(a,8))
        if(tasks.isEmpty())return
        parent.addView(ui.text(a,tasks.size.toString()+" series in your library",
            13f,ui.secondary),ui.lp(a,14))
        for(task in tasks.take(35)){
            val card=ui.card(a)
            val row=ui.row(a)
            val picture=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_source_toonily)
            }
            row.addView(picture,LinearLayout.LayoutParams(ui.px(a,84),ui.px(a,104)))
            CreatorPreviews.sourceThumbnail(a,picture,task.url)
            val info=LinearLayout(a).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(ui.px(a,13),0,0,0)
            }
            info.addView(ui.text(a,task.title,16f,ui.white,true))
            info.addView(ui.text(a,task.state.uppercase()+" · "+task.progress+"%",12f,
                if(task.state=="error")ui.danger else ui.secondary),ui.lp(a,5))
            if(!task.error.isNullOrBlank())
                info.addView(ui.text(a,task.error.take(135),11f,ui.danger),ui.lp(a,5))
            row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(row)
            val left=ui.action(a,if(task.state=="done")"BACKFILL AGAIN"
                else if(task.state=="error")"RETRY ZIP" else "CHECK / RESUME",false){
                if(task.state=="done"||task.state=="error")
                    ChapterQueue(a).use{it.retry(task.id)}
                ChapterArchives.start(a)
                refresh()
            }
            val right=ui.action(a,"SIGN IN",false){
                a.startActivity(Intent(a,SiteLoginActivity::class.java)
                    .putExtra(SiteLoginActivity.EXTRA_URL,task.url))
            }
            card.addView(ui.weighted(a,left,right),ui.lp(a,9))
            parent.addView(card,ui.lp(a,9))
        }
    }
}
