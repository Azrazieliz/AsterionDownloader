package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import com.asterion.videoflow.NetworkRoute
import com.asterion.videoflow.Scheduling
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoDiagnosticsReport
import com.asterion.videoflow.VideoEngine
import com.asterion.videoflow.WebDiscovery
import kotlin.concurrent.thread

/** First-class VideoFlow commands. Its original VideoDb, yt-dlp,
 * downloader services, cookies and transfer state remain untouched.
 * No VideoFlow legacy MainActivity is required for any operation.
 */
internal object UnifiedVideoFlowFeatures {
    private val ui=NocturneDesign
    private fun notice(a:Activity,s:String){
        if(!a.isFinishing&&!a.isDestroyed)Toast.makeText(a,s,Toast.LENGTH_LONG).show()
    }
    private fun edit(a:Activity,hint:String,value:String="")=EditText(a).apply {
        this.hint=hint;setText(value);textSize=14f
        setTextColor(ui.white);setHintTextColor(ui.secondary)
        background=ui.shape(a,ui.raised,12)
        setPadding(ui.px(a,12),ui.px(a,10),ui.px(a,12),ui.px(a,10))
    }
    private fun action(a:Activity,p:LinearLayout,label:String,click:()->Unit){
        p.addView(ui.action(a,label,false,click),ui.lp(a,8))
    }
    private fun pair(a:Activity,p:LinearLayout,left:String,right:String,
                     l:()->Unit,r:()->Unit){
        val row=ui.row(a)
        row.addView(ui.action(a,left,false,l),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.action(a,right,false,r),
            LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,8)})
        p.addView(row,ui.lp(a,7))
    }
    fun urlEntry(a:Activity,p:LinearLayout){
        val card=ui.card(a,12)
        card.addView(ui.text(a,"Download an exact video",16f,ui.white,true))
        card.addView(ui.text(a,"Select a published original video URL. "+
            "The file is not marked saved until real bytes and media validation succeed.",
            11f,ui.secondary),ui.lp(a,5))
        val url=edit(a,"https://site.example/watch/selected-original")
        card.addView(url,ui.lp(a,7))
        action(a,card,"ADD VIDEO TO DOWNLOADS"){
            val link=url.text.toString().trim()
            if(!WebDiscovery.isUrl(link))notice(a,"Enter a valid HTTP(S) video URL")
            else{
                val added=VideoDb(a).use{it.addVideo(link,link)}
                if(!added)notice(a,"Video already tracked; check Downloads")
                else if(!AppPower.enabled(a))
                    notice(a,"Video queued. Resume the app to download.")
                else if(VideoEngine.launch(a,false))notice(a,"Video queued; worker requested")
                else notice(a,"Video queued; worker scheduling unavailable")
                if(added)url.text.clear()
            }
        }
        p.addView(card,ui.lp(a,10))
    }
    fun sources(a:Activity,p:LinearLayout,refresh:()->Unit) {
        val card=ui.card(a,12)
        card.addView(ui.text(a,"Original VideoFlow · Monitored sources",
            17f,ui.white,true))
        val auto=Switch(a).apply{
            text="Automatic source checks";textSize=14f;setTextColor(ui.white)
            isChecked=a.getSharedPreferences("video_options",0).getBoolean("auto",true)
            setOnCheckedChangeListener{_,on->Scheduling.configure(a,on)}
        }
        card.addView(auto,ui.lp(a,8))
        val address=edit(a,"https://website.com/videos/")
        card.addView(address,ui.lp(a,8))
        pair(a,card,"ADD MONITORED PAGE","SCAN ENABLED PAGES",
            {
                val url=address.text.toString().trim()
                if(!WebDiscovery.isUrl(url))notice(a,"Valid site URL required")
                else {
                    val added=VideoDb(a).use{it.addWatch(url,Uri.parse(url).host?:url)}
                    if(added){address.text.clear();refresh()}
                    else notice(a,"Page already tracked or invalid")
                }
            },{
                if(!AppPower.enabled(a))notice(a,"Resume the app before scanning")
                else if(!VideoEngine.launch(a,true))
                    notice(a,"Unable to schedule video source scan")
            })
        p.addView(card,ui.lp(a,11))
        for(watch in VideoDb(a).use{it.listWatches()}.take(250)){
            val row=ui.card(a,9)
            row.addView(ui.text(a,watch.label,14f,ui.white,true))
            row.addView(ui.text(a,watch.address,11f,ui.secondary),ui.lp(a,3))
            if(!watch.error.isNullOrBlank())
                row.addView(ui.text(a,"Last scan error: "+watch.error.take(170),
                    11f,ui.danger),ui.lp(a,5))
            pair(a,row,if(watch.enabled)"PAUSE SOURCE" else "ENABLE SOURCE","REMOVE PAGE",
                {
                    VideoDb(a).use{it.enableWatch(watch.id,!watch.enabled)}
                    refresh()
                },{
                    AlertDialog.Builder(a).setTitle("Remove monitored video page?")
                        .setMessage("Saved original files and other subscriptions stay intact.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Remove"){_,_->
                            VideoDb(a).use{it.removeWatch(watch.id)}
                            refresh()
                        }.show()
                })
            if(watch.address.startsWith("https://watchporn.to/models/"))
                action(a,row,"BACKFILL ALL THIS PERFORMER'S VIDEOS"){
                    VideoDb(a).use{it.resetWatchBackfill(watch.id)}
                    if(AppPower.enabled(a))VideoEngine.launch(a,true)
                }
            p.addView(row,ui.lp(a,9))
        }
    }
    fun settings(a:Activity,p:LinearLayout) {
        val options=a.getSharedPreferences("video_options",0)
        val prefs=ui.card(a,13)
        prefs.addView(ui.text(a,"VideoFlow · Download settings",18f,ui.white,true))
        val maxNew=edit(a,"Maximum new videos per source",
            options.getInt("max_new",3).toString()).apply{
                inputType=android.text.InputType.TYPE_CLASS_NUMBER}
        prefs.addView(maxNew,ui.lp(a,9))
        val quality=Spinner(a).apply{
            adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,
                listOf("Compatible MP4 preferred","Best available"))
            setSelection(if(options.getString("quality","")=="best")1 else 0)
        }
        prefs.addView(quality,ui.lp(a,8))
        action(a,prefs,"SAVE VIDEO PREFERENCES"){
            options.edit()
                .putInt("max_new",maxNew.text.toString().toIntOrNull()?.coerceIn(1,20)?:3)
                .putString("quality",if(quality.selectedItemPosition==1)"best"
                    else "best[ext=mp4]/best").apply()
            notice(a,"Video preferences saved")
        }
        p.addView(prefs,ui.lp(a,12))

        val network=ui.card(a,13)
        network.addView(ui.text(a,"Video site network routes",18f,ui.white,true))
        val host=edit(a,"Publisher site URL","https://www.eporner.com/")
        network.addView(host,ui.lp(a,8))
        val mode=Spinner(a).apply{
            adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,
                listOf("Direct","Require Android VPN","HTTP proxy","SOCKS5 proxy"))
        }
        network.addView(mode,ui.lp(a,8))
        val proxy=edit(a,"Proxy hostname or address")
        network.addView(proxy,ui.lp(a,7))
        val port=edit(a,"Proxy port").apply{
            inputType=android.text.InputType.TYPE_CLASS_NUMBER
        }
        network.addView(port,ui.lp(a,7))
        pair(a,network,"LOAD SITE ROUTE","SAVE SITE ROUTE",
            {
                val route=NetworkRoute.configured(a,host.text.toString())
                mode.setSelection(when(route?.mode){"vpn"->1;"http"->2;"socks5"->3;else->0})
                proxy.setText(route?.host.orEmpty())
                port.setText(route?.port?.takeIf{it>0}?.toString().orEmpty())
            },{
                runCatching{
                    NetworkRoute.save(a,host.text.toString(),
                        when(mode.selectedItemPosition){
                            1->"vpn";2->"http";3->"socks5";else->"direct"
                        },proxy.text.toString().trim(),port.text.toString().toIntOrNull()?:0)
                }.onSuccess{notice(a,"Site route saved")}
                    .onFailure{notice(a,"Site route invalid: "+it.message)}
            })
        pair(a,network,"ANDROID VPN SETTINGS","VERIFY EXIT",
            {runCatching{a.startActivity(Intent("android.settings.VPN_SETTINGS"))}
                .onFailure{notice(a,"Open Android VPN Settings")}},
            {
                thread(name="asterion-video-vpn-verify"){
                    val result=runCatching{
                        NetworkRoute.verifyVpnExit(a.applicationContext,host.text.toString())
                    }.getOrElse{it.message?:"VPN unavailable"}
                    a.runOnUiThread{notice(a,result)}
                }
            })
        action(a,network,"TEST SELECTED SITE EXIT IP"){
            val address=host.text.toString()
            thread(name="asterion-video-route-check"){
                val result=runCatching{
                    NetworkRoute.compareExitIp(a.applicationContext,address)
                }.getOrElse{"Exit not verified: "+it.message.orEmpty()}
                a.runOnUiThread{notice(a,result)}
            }
        }
        p.addView(network,ui.lp(a,9))

        val auth=ui.card(a,11)
        auth.addView(ui.text(a,"Authenticated video sessions",17f,ui.white,true))
        auth.addView(ui.text(a,
            "Import your own Netscape-format cookies.txt. The file stays private to Downloader; diagnostics never export it.",
            11f,ui.secondary),ui.lp(a,6))
        action(a,auth,"IMPORT AUTHORIZED COOKIE FILE"){
            @Suppress("DEPRECATION")
            a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
            },551)
        }
        p.addView(auth,ui.lp(a,9))

        val cloud=ui.card(a,11)
        cloud.addView(ui.text(a,"VideoFlow cloud handoff",17f,ui.white,true))
        val toggle=Switch(a).apply{
            text="Request Round Sync after completed videos"
            setTextColor(ui.white);isChecked=options.getBoolean("cloud_after",false)
        }
        cloud.addView(toggle,ui.lp(a,8))
        val taskId=edit(a,"Round Sync COPY task ID",
            options.getInt("cloud_task",-1).takeIf{it>=0}?.toString().orEmpty())
        cloud.addView(taskId,ui.lp(a,8))
        action(a,cloud,"SAVE VIDEO CLOUD SETTINGS"){
            options.edit().putBoolean("cloud_after",toggle.isChecked)
                .putInt("cloud_task",taskId.text.toString().toIntOrNull()?:-1).apply()
            notice(a,"Cloud handoff settings saved")
        }
        p.addView(cloud,ui.lp(a,9))

        val diagnostics=ui.card(a,11)
        diagnostics.addView(ui.text(a,"Crash and transfer diagnostics",17f,ui.white,true))
        action(a,diagnostics,"SHOW VERIFIED ENGINE DIAGNOSTICS"){
            val text=runCatching{VideoDiagnosticsReport.create(a)}
                .getOrElse{"Unable to read diagnostics: "+
                    VideoDiagnosticsReport.sanitize(it.message.orEmpty())}
            val viewer=ScrollView(a).apply{
                addView(ui.text(a,text,12f,ui.white).apply{
                    setTextIsSelectable(true);setPadding(ui.px(a,14),
                        ui.px(a,14),ui.px(a,14),ui.px(a,14))
                })
            }
            AlertDialog.Builder(a).setTitle("VideoFlow diagnostics")
                .setView(viewer).setPositiveButton("COPY REPORT"){_,_->
                    val clipboard=a.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("VideoFlow diagnostic report",text))
                    notice(a,"Report copied")
                }.setNegativeButton("Close",null).show()
        }
        p.addView(diagnostics,ui.lp(a,9))
    }
}
