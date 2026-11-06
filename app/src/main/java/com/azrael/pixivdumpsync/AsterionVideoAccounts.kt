package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import com.azrael.galleryflow.SiteLoginActivity

/** Site authentication remains within the module and uses the existing session WebView. */
object AsterionVideoAccounts {
    fun render(a:Activity,p:LinearLayout){
        val providers=AlohaSources.all.filter{
            it.area==AlohaArea.ADULT || it.area==AlohaArea.HENTAI
        }.distinctBy{it.host}.sortedBy{it.name.lowercase()}
        if(providers.isEmpty())return
        p.addView(NocturneDesign.action(a,"CONNECT A VIDEO WEBSITE ACCOUNT",false){
            AlertDialog.Builder(a).setTitle("Select a video website to sign in")
                .setItems(providers.map{it.name}.toTypedArray()){_,choice->
                    a.startActivity(Intent(a,SiteLoginActivity::class.java)
                        .putExtra(SiteLoginActivity.EXTRA_URL,providers[choice].url))
                }.setNegativeButton("Cancel",null).show()
        },NocturneDesign.lp(a,8))
    }
}
