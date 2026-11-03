package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import com.azrael.galleryflow.SiteLoginActivity
import com.azrael.galleryflow.SiteSession
import org.json.JSONObject
import kotlin.concurrent.thread

/** Shared authorized retrieval. Access checks never invent subscriber credentials. */
object AuthenticatedItemDownloader {
    private const val PREF="asterion_access_retry"
    private const val PENDING="pending_item"
    private fun preferences(a:Activity)=a.getSharedPreferences(PREF,Activity.MODE_PRIVATE)
    fun download(a:Activity,item:IndexedItem){
        Toast.makeText(a,"Resolving accessible original…",Toast.LENGTH_SHORT).show()
        thread(name="asterion-authenticated-original"){
            val found=runCatching{AuthorizedOriginalResolver.resolve(item.url,item.area)}
            a.runOnUiThread{
                if(a.isDestroyed||a.isFinishing)return@runOnUiThread
                found.onSuccess{file->
                    preferences(a).edit().remove(PENDING).apply()
                    val folder=when{
                        file.kind=="video"->"Videos"
                        file.kind=="epub"->"Books"
                        file.kind=="model"->"Assets"
                        file.kind=="archive"&&item.area=="COMICS"->"Comics"
                        file.kind=="archive"&&item.area=="GAMES"->"Games"
                        file.kind=="archive"->"Assets"
                        file.kind=="apk"||file.kind=="game"->"Games"
                        else->"Images"
                    }
                    if(file.kind=="video"){
                        val wasNew=VideoDb(a).use{it.addVideo(file.url,item.title)}
                        if(wasNew)VideoEngine.launch(a,false)
                        Toast.makeText(a,if(wasNew)"Video queued" else
                            "Already in downloads",Toast.LENGTH_SHORT).show()
                    }else if(file.kind=="game"){
                        ResourceDownloads.route(a,file.url,folder)
                    }else{
                        ResourceDownloads.downloadFile(a,file.url,folder,
                            file.filename,file.referer)
                    }
                }.onFailure{error->
                    val message=error.message.orEmpty()
                    val needsAccount=Regex("(?i)\\b(?:401|403)\\b|unauthoriz|forbidden|"+
                        "login|log in|authentication|session|subscription|"+
                        "access denied|original was exposed|original unavailable")
                        .containsMatchIn(message)
                    if(needsAccount && SourceIndexParser.safe(item.source)){
                        val data=JSONObject().apply{
                            put("url",item.url);put("source",item.source)
                            put("title",item.title);put("area",item.area)
                            put("kind",item.kind);put("preview",item.preview)
                        }
                        preferences(a).edit().putString(PENDING,data.toString()).apply()
                        AlertDialog.Builder(a).setTitle("Sign in to access this file")
                            .setMessage("This file is not accessible to the current session. "+
                                "Connect an account with access, then retry.\n\n"+
                                message.take(180))
                            .setPositiveButton("SIGN IN"){_,_->
                                a.startActivity(Intent(a,SiteLoginActivity::class.java)
                                    .putExtra(SiteLoginActivity.EXTRA_URL,item.source))
                            }
                            .setNegativeButton("Cancel"){_,_->
                                preferences(a).edit().remove(PENDING).apply()
                            }.show()
                    }else{
                        Toast.makeText(a,"Original unavailable: "+message.take(180),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    fun resumeIfConnected(a:Activity){
        val saved=preferences(a).getString(PENDING,null)?:return
        val item=runCatching{
            val v=JSONObject(saved)
            IndexedItem(v.getString("url"),v.getString("title"),
                v.optString("preview").takeIf{it.isNotBlank()},
                v.getString("source"),v.getString("area"),v.optString("kind"),
                System.currentTimeMillis(),false)
        }.getOrElse{
            preferences(a).edit().remove(PENDING).apply();return
        }
        if(!SiteSession.bound(a,item.source))return
        preferences(a).edit().remove(PENDING).apply()
        AlertDialog.Builder(a).setTitle("Account connected")
            .setMessage("Retry original-file retrieval for "+item.title+"?")
            .setPositiveButton("RETRY"){_,_->download(a,item)}
            .setNegativeButton("Later",null).show()
    }
}
