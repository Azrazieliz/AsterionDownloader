package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.azrael.galleryflow.ProviderWebActivity
import com.azrael.galleryflow.SiteLoginActivity
import com.azrael.galleryflow.SiteSession

/** One doujin surface, two independently authenticated hosts, original ZIP pipeline. */
object DoujinGalleryPanel {
    private val ui=NocturneDesign
    internal val hosts=listOf("nhentai.net","nhentai.xxx")
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        parent.addView(ui.text(a,"Doujin · full gallery ZIPs",21f,ui.white,true),ui.lp(a,14))
        parent.addView(ui.text(a,
            "Both nHentai hosts use your existing in-app browser sessions. "+
            "A gallery is complete only after every page is archived in numeric order.",
            12f,ui.secondary),ui.lp(a,6))
        val jobs=ChapterQueue(a).use{it.list().filter{task->NhentaiChapters.isGallery(task.url)}}
        val sourceCard=ui.card(a)
        for((index,host) in hosts.withIndex()){
            val url="https://$host/"
            val bound=SiteSession.bound(a,url)
            val name=ui.text(a,host,16f,ui.white,true)
            sourceCard.addView(name,ui.lp(a,if(index==0)0 else 17))
            sourceCard.addView(ui.text(a,
                (if(bound)"Browser session connected" else "Account not connected")+
                " · "+jobs.count{it.url.contains(host)}+" galleries in download history",
                12f,ui.secondary),ui.lp(a,4))
            val signIn=ui.action(a,if(bound)"ACCOUNT / SESSION" else "SIGN IN",false){
                a.startActivity(Intent(a,SiteLoginActivity::class.java)
                    .putExtra(SiteLoginActivity.EXTRA_URL,url))
            }
            val browse=ui.action(a,"BROWSE",false){
                a.startActivity(Intent(a,ProviderWebActivity::class.java)
                    .putExtra(ProviderWebActivity.EXTRA_URL,url)
                    .putExtra(ProviderWebActivity.EXTRA_FOLDER,"Comics"))
            }
            sourceCard.addView(ui.weighted(a,signIn,browse),ui.lp(a,7))
        }
        parent.addView(sourceCard,ui.lp(a,12))
        val add=ui.card(a)
        val field=EditText(a).apply{
            hint="https://nhentai.net/g/123456/"
            setSingleLine(true)
            textSize=14f;setTextColor(ui.white);setHintTextColor(ui.secondary)
            background=ui.shape(a,ui.raised,12)
            setPadding(ui.px(a,12),ui.px(a,11),ui.px(a,12),ui.px(a,11))
        }
        add.addView(ui.text(a,"Add an entire gallery",16f,ui.white,true))
        add.addView(field,ui.lp(a,9))
        add.addView(ui.action(a,"DOWNLOAD COMPLETE GALLERY AS ZIP",true){
            val address=field.text.toString().trim()
            val galleryId=NhentaiChapters.galleryId(address)
            if(galleryId==null){
                Toast.makeText(a,"Enter a complete gallery /g/ID from either approved host",
                    Toast.LENGTH_LONG).show()
                return@action
            }
            val host=android.net.Uri.parse(address).host.orEmpty().removePrefix("www.")
            val added=ChapterQueue(a).use{
                it.enqueue(address,"Doujin "+host+" · "+galleryId)
            }
            if(added)ChapterArchives.start(a)
            Toast.makeText(a,if(added)"Entire gallery ZIP queued" else
                "This gallery is already in downloads",Toast.LENGTH_SHORT).show()
            refresh()
        },ui.lp(a,10))
        parent.addView(add,ui.lp(a,11))
        if(jobs.isNotEmpty()){
            parent.addView(ui.text(a,"Gallery library · "+jobs.size,
                17f,ui.white,true),ui.lp(a,18))
            for(job in jobs.take(45)){
                val card=ui.card(a)
                val row=ui.row(a)
                val thumb=ImageView(a).apply{
                    setImageResource(R.drawable.ic_source_toonily)
                    scaleType=ImageView.ScaleType.CENTER_CROP
                }
                row.addView(thumb,LinearLayout.LayoutParams(ui.px(a,78),ui.px(a,100)))
                CreatorPreviews.sourceThumbnail(a,thumb,job.url)
                val words=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(ui.px(a,11),0,0,0)
                }
                words.addView(ui.text(a,job.title,14f,ui.white,true))
                words.addView(ui.text(a,job.state.uppercase()+" · "+job.progress+"%",
                    12f,if(job.state=="error")ui.danger else ui.secondary),
                    ui.lp(a,5))
                job.error?.takeIf{it.isNotBlank()}?.let{
                    words.addView(ui.text(a,it.take(140),11f,ui.danger),ui.lp(a,5))
                }
                row.addView(words,LinearLayout.LayoutParams(0,-2,1f))
                card.addView(row)
                card.addView(ui.action(a,
                    if(job.state=="done")"CHECK FOR CHANGES" else "RESUME / RETRY ZIP",false){
                    if(job.state in listOf("error","done"))
                        ChapterQueue(a).use{it.retry(job.id)}
                    ChapterArchives.start(a)
                    refresh()
                },ui.lp(a,8))
                parent.addView(card,ui.lp(a,9))
            }
        }
    }
}
