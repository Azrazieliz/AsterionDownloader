package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import com.azrael.galleryflow.SiteLoginActivity

/** Follows names like the performer list, but tracks separate work identities
 * and historical volume floors. No filesystem naming policy is assumed.
 */
object Mp4SeriesPanel {
    private val ui=NocturneDesign
    private fun entry(a:Activity,hint:String)=EditText(a).apply{
        this.hint=hint
        setTextColor(ui.white);setHintTextColor(ui.secondary)
        setSingleLine(true)
    }
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        parent.addView(ui.text(a,"MP4Directs · Follow series",19f,ui.white,true),
            ui.lp(a,12))
        val form=ui.card(a)
        form.addView(ui.text(a,
            "Name the main series once. Each work and spin-off tracks its own backfill and future releases.",
            12f,ui.secondary),ui.lp(a,4))
        val name=entry(a,"Main series name (e.g. Tensura)")
        form.addView(name,ui.lp(a,9))
        form.addView(ui.action(a,"FOLLOW SERIES",true){
            val title=name.text.toString().trim()
            if(title.length<3){
                Toast.makeText(a,"Enter a series name",
                    Toast.LENGTH_LONG).show()
                return@action
            }
            val id=Mp4SeriesStore(a).use{it.add(title,title,1)}
            Mp4SeriesSync.ensure(a)
            Mp4SeriesSync.startBackfill(a,id)
            refresh()
        },ui.lp(a,9))
        form.addView(ui.action(a,"SIGN IN TO MP4DIRECTS",false){
            a.startActivity(Intent(a,SiteLoginActivity::class.java)
                .putExtra(SiteLoginActivity.EXTRA_URL,"https://mp4directs.com/"))
        },ui.lp(a,7))
        form.addView(ui.text(a,
            "Only actual EPUB originals are downloaded for novels; ZIP for image-based related works. Unverified links remain review items.",
            11f,ui.secondary),ui.lp(a,6))
        parent.addView(form,ui.lp(a,8))
        val works=Mp4SeriesStore(a).use{it.list()}
        for(work in works){
            val card=ui.card(a)
            card.addView(ui.text(a,work.name,16f,ui.white,true),ui.lp(a,2))
            card.addView(ui.text(a,work.type+" · "+work.family+
                " · per-volume Rounder inventory reconciliation",12f,ui.secondary),ui.lp(a,3))
            if(!work.error.isNullOrBlank())
                card.addView(ui.text(a,work.error.take(180),11f,ui.danger),ui.lp(a,4))
            val controls=ui.row(a)
            val sync=Switch(a).apply{
                text="Sync new volumes";setTextColor(ui.white)
                isChecked=work.sync
                setOnCheckedChangeListener{_,enabled->
                    Mp4SeriesStore(a).use{it.toggle(work.id,false,enabled)}
                    if(enabled)Mp4SeriesSync.scanNow(a)
                }
            }
            controls.addView(sync)
            card.addView(controls,ui.lp(a,5))
            val aliases=entry(a,"Alternate titles (comma-separated)")
            aliases.setText(work.aliases)
            card.addView(aliases,ui.lp(a,6))
            card.addView(ui.action(a,"SAVE ALTERNATE TITLES",false){
                Mp4SeriesStore(a).use{it.setAliases(work.id,
                    aliases.text.toString().trim())}
                Mp4SeriesSync.scanNow(a)
                refresh()
            },ui.lp(a,3))
            val alias=entry(a,"Related novel, side story or image-based spin-off")
            card.addView(alias,ui.lp(a,5))
            card.addView(ui.action(a,"ADD RELATED WORK",false){
                val value=alias.text.toString().trim()
                if(value.length>=3){
                    AlertDialog.Builder(a).setTitle("Related work format")
                        .setItems(arrayOf("Text novel · EPUB",
                            "Image-based comic · ZIP")){_,format->
                            val kind=if(format==0)"EPUB" else "ZIP"
                            Mp4SeriesStore(a).use{it.add(work.family,value,1,kind)}
                            Mp4SeriesSync.scanNow(a);refresh()
                        }.setNegativeButton("Cancel",null).show()
                }
            },ui.lp(a,3))
            val candidates=Mp4SeriesStore(a).use{it.items(work.id,300)}
                .filter{it.type==work.type && it.volume!=null &&
                    it.state!="REVIEW"}
                .sortedWith(compareBy<Mp4Candidate>{it.volume?:Int.MAX_VALUE}
                    .thenBy{it.title}).take(14)
            for(hit in candidates){
                val line=ui.row(a)
                val label=ui.text(a,"v"+hit.volume+" · "+
                    hit.title.take(75)+" · "+hit.state,11f,
                    if(hit.state=="FAILED")ui.danger else ui.secondary)
                line.addView(label,LinearLayout.LayoutParams(0,-2,1f))
                if(hit.state=="FAILED"){
                    line.addView(ui.action(a,"RETRY",false){
                        CrossSourceDedup(a).use{it.release(hit.url)}
                        Mp4SeriesStore(a).use{it.mark(hit.url,"READY")}
                        Mp4VolumeDispatcher.advance(a.applicationContext)
                        refresh()
                    })
                }
                card.addView(line,ui.lp(a,5))
            }
            val active=Mp4SeriesSync.isBackfilling(work.id)
            card.addView(ui.action(a,if(active)"STOP BACKFILL" else "BACKFILL",false){
                if(active)Mp4SeriesSync.stopBackfill(work.id)
                else if(!AppPower.enabled(a))Toast.makeText(a,
                    "Resume transfers before backfilling",Toast.LENGTH_LONG).show()
                else Mp4SeriesSync.startBackfill(a,work.id)
                refresh()
            },ui.lp(a,7))
            parent.addView(card,ui.lp(a,10))
        }
    }
}
