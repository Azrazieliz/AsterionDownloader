package com.azrael.pixivdumpsync

import android.app.Activity
import android.net.Uri
import android.widget.LinearLayout

/** Show persistent enrollment results without redirecting to individual websites. */
object AsterionAddedSitesPanel {
    fun render(a:Activity,parent:LinearLayout){
        val prefs=a.getSharedPreferences("asterion_custom_site_enrollment",0)
        val entries=prefs.getStringSet("sites",emptySet()).orEmpty().sorted()
        if(entries.isEmpty())return
        val ui=NocturneDesign
        parent.addView(ui.text(a,"Added sites",18f,ui.white,true),ui.lp(a,15))
        for(entry in entries.take(40)){
            val separator=entry.indexOf('|')
            if(separator<=0)continue
            val kind=entry.substring(0,separator)
            val url=entry.substring(separator+1)
            val item=ui.card(a,12)
            item.addView(ui.text(a,kind+" · "+Uri.parse(url).host.orEmpty(),
                14f,ui.white,true))
            item.addView(ui.text(a,url.take(100),11f,ui.secondary),ui.lp(a,3))
            item.addView(ui.text(a,prefs.getString("status_"+entry,
                "Saved").orEmpty().take(180),12f,ui.secondary),ui.lp(a,4))
            parent.addView(item,ui.lp(a,8))
        }
    }
}
