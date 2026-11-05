package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
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
        val next=entry(a,"First volume to acquire (1 for a new series)")
        next.inputType=InputType.TYPE_CLASS_NUMBER
        next.setText("1")
        form.addView(name,ui.lp(a,9))
        form.addView(next,ui.lp(a,5))
        form.addView(ui.action(a,"FOLLOW · BACKFILL & SYNC",true){
            val title=name.text.toString().trim()
            val floor=next.text.toString().trim().toIntOrNull()
            if(title.length<3 || floor==null || floor !in 1..999){
                Toast.makeText(a,"Enter a series name and a starting volume",
                    Toast.LENGTH_LONG).show()
                return@action
            }
            Mp4SeriesStore(a).use{it.add(title,title,floor)}
            Mp4SeriesSync.ensure(a)
            Mp4SeriesSync.scanNow(a)
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
                " · from volume "+work.firstVolume,12f,ui.secondary),ui.lp(a,3))
            if(!work.error.isNullOrBlank())
                card.addView(ui.text(a,work.error.take(180),11f,ui.danger),ui.lp(a,4))
            val controls=ui.row(a)
            val backfill=Switch(a).apply{
                text="Backfill";setTextColor(ui.white)
                isChecked=work.backfill
            }
            val sync=Switch(a).apply{
                text="Sync";setTextColor(ui.white)
                isChecked=work.sync
            }
            backfill.setOnCheckedChangeListener{_,v->
                Mp4SeriesStore(a).use{it.toggle(work.id,v,sync.isChecked)}
                if(v)Mp4SeriesSync.scanNow(a)
            }
            sync.setOnCheckedChangeListener{_,v->
                Mp4SeriesStore(a).use{it.toggle(work.id,backfill.isChecked,v)}
                if(v)Mp4SeriesSync.scanNow(a)
            }
            controls.addView(backfill)
            controls.addView(sync)
            card.addView(controls,ui.lp(a,5))
            val actions=ui.row(a)
            val floor=entry(a,"Start volume")
            floor.setText(work.firstVolume.toString())
            floor.inputType=InputType.TYPE_CLASS_NUMBER
            actions.addView(floor,LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(ui.action(a,"SAVE FLOOR",false){
                val n=floor.text.toString().toIntOrNull()
                if(n!=null && n in 1..999){
                    Mp4SeriesStore(a).use{it.startAt(work.id,n)}
                    Mp4SeriesSync.scanNow(a);refresh()
                }
            })
            card.addView(actions,ui.lp(a,6))
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
            val candidates=Mp4SeriesStore(a).use{it.items(work.id,8)}
            for(hit in candidates){
                val label=ui.text(a,hit.title.take(110)+
                    " · "+(hit.volume?.let{"v$it"}?:"volume unverified")+
                    " · "+hit.state,11f,ui.secondary)
                label.isClickable=true
                label.setOnClickListener{
                    a.startActivity(Intent(a,
                        com.azrael.galleryflow.ProviderWebActivity::class.java)
                        .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_URL,
                            hit.url)
                        .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_FOLDER,
                            if(work.type=="ZIP")"Comics" else "Books"))
                }
                card.addView(label,ui.lp(a,5))
            }
            card.addView(ui.action(a,"CHECK THIS SERIES",false){
                Mp4SeriesSync.scanNow(a)
                Toast.makeText(a,"Subscription scan requested",
                    Toast.LENGTH_SHORT).show()
            },ui.lp(a,7))
            parent.addView(card,ui.lp(a,10))
        }
        val inventory=RounderInventoryIndex.confirmedCount(a)
        val tree=RounderTreeScanner.status(a)
        parent.addView(ui.text(a,"Rounder collection · "+tree.files+
            " raw files indexed · "+tree.pendingDirectories+
            " folders awaiting scan · "+inventory+" confirmed volume records.",
            12f,ui.secondary),ui.lp(a,8))
        tree.lastError?.let{
            parent.addView(ui.text(a,"Rounder index: "+it.take(170),
                11f,ui.danger),ui.lp(a,5))
        }
        parent.addView(ui.text(a,
            "Raw folder visibility is not proof of an existing volume. Verified volume records are needed before automatic cloud duplicate suppression.",
            11f,ui.secondary),ui.lp(a,5))
    }
}
