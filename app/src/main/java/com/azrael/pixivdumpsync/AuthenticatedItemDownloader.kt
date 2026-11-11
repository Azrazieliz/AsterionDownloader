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
        if(item.area=="COMICS" && NhentaiChapters.isGallery(item.url)){
            ChapterQueue(a).use{it.enqueue(item.url,item.title)}
            ChapterArchives.start(a)
            Toast.makeText(a,"Full gallery queued as chapter-order ZIP",
                Toast.LENGTH_SHORT).show()
            return
        }
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
                    val failure=error as? OriginalAccessException
                    val requiresLogin=failure?.reason==OriginalAccessCause.SIGN_IN ||
                        (failure?.reason==OriginalAccessCause.NO_ORIGINAL &&
                            !SiteSession.bound(a,item.source))
                    val restricted=failure?.reason==OriginalAccessCause.ENTITLEMENT
                    if((requiresLogin || restricted) && SourceIndexParser.safe(item.source)){
                        val data=JSONObject().apply{
                            put("url",item.url);put("source",item.source)
                            put("title",item.title);put("area",item.area)
                            put("kind",item.kind);put("preview",item.preview)
                        }
                        preferences(a).edit().putString(PENDING,data.toString()).apply()
                        AlertDialog.Builder(a).setTitle(if(restricted)
                            "Access not granted by provider" else "Account session required")
                            .setMessage(if(restricted)
                                "The current account has no permission for this original. "+
                                "Use an account with the appropriate subscription, "+
                                "purchase or creator permission.\n\n"+message.take(180)
                                else "Connect an authorized account and retry the "+
                                    "original-file retrieval.\n\n"+message.take(180))
                            .setPositiveButton("CONNECT ACCOUNT"){_,_->
                                a.startActivity(Intent(a,SiteLoginActivity::class.java)
                                    .putExtra(SiteLoginActivity.EXTRA_URL,item.source))
                            }
                            .setNegativeButton("Close"){_,_->
                                preferences(a).edit().remove(PENDING).apply()
                            }.show()
                    }else{
                        val summary=when(failure?.reason){
                            OriginalAccessCause.RATE_LIMIT->
                                "Provider rate-limited this request. Try again later."
                            OriginalAccessCause.NOT_FOUND->
                                "Original no longer exists on the provider."
                            OriginalAccessCause.NO_ORIGINAL->
                                "No original download is exposed by this account."
                            else->"Original unavailable: "+message.take(180)
                        }
                        Toast.makeText(a,summary,Toast.LENGTH_LONG).show()
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
