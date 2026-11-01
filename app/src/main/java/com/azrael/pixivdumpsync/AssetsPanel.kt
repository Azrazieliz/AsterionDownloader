package com.azrael.pixivdumpsync

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Environment
import android.widget.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import kotlin.concurrent.thread

object AssetsPanel {
    private val ui=NocturneDesign
    private fun fetch(url:String)=Jsoup.connect(url)
        .userAgent("AsterionDownloader/1.3 Android")
        .ignoreContentType(true).timeout(19000).maxBodySize(6000000).execute().body()
    private fun safe(url:String)=runCatching{
        val u=URI(url);u.scheme=="https" && u.host in setOf("dl.polyhaven.org","ambientcg.com")
    }.getOrDefault(false)
    private fun download(a:Activity,id:String,files:List<Pair<String,String>>){
        val dm=a.getSystemService(DownloadManager::class.java)
        val seen=a.getSharedPreferences("asterion_assets",0)
        var queued=0
        files.forEach{(path,url)->
            if(!safe(url)||seen.contains(url))return@forEach
            val filename=URI(url).path.substringAfterLast('/')
            val directory=path.replace(Regex("[^A-Za-z0-9_./+-]"),"_").take(160)
            runCatching{
                val req=DownloadManager.Request(Uri.parse(url)).apply{
                    setTitle(filename)
                    setDescription("Poly Haven original · "+id)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "AsterionDownloader/Assets/"+id+"/"+directory+"/"+filename)
                }
                seen.edit().putLong(url,dm.enqueue(req)).apply();queued++
            }
        }
        Toast.makeText(a,queued.toString()+" original asset files queued",Toast.LENGTH_LONG).show()
    }
    private fun select(a:Activity,id:String,status:TextView){
        thread(name="asset-files"){
            val output=runCatching{
                val root=JSONObject(fetch("https://api.polyhaven.com/files/"+id))
                val files=ArrayList<Pair<String,String>>()
                fun walk(obj:JSONObject,path:String,depth:Int){
                    if(depth>9||files.size>=160)return
                    if(obj.has("url")){
                        val url=obj.optString("url")
                        if(safe(url))files+=path to url
                        return
                    }
                    for(key in obj.keys()){
                        if(key.matches(Regex("[A-Za-z0-9_.+-]{1,80}")))
                            obj.optJSONObject(key)?.let{walk(it,path+"/"+key,depth+1)}
                    }
                }
                walk(root,"",0);files
            }
            a.runOnUiThread{
                output.onSuccess{files->
                    if(files.isEmpty()){status.text="No publicly downloadable files";return@onSuccess}
                    val picked=BooleanArray(files.size)
                    AlertDialog.Builder(a).setTitle("Select original asset files")
                        .setMultiChoiceItems(files.map{it.first}.toTypedArray(),picked){_,i,v->picked[i]=v}
                        .setNeutralButton("ALL DEPENDENCIES"){_,_->download(a,id,files)}
                        .setPositiveButton("SELECTED"){_,_->
                            download(a,id,files.filterIndexed{i,_->picked[i]})
                        }.setNegativeButton("CANCEL",null).show()
                    status.text=files.size.toString()+" original files found"
                }.onFailure{status.text="File list error: "+it.message}
            }
        }
    }
    fun render(a:Activity,body:LinearLayout){
        body.addView(ui.text(a,"ASSETS · 3D MODELS",22f,ui.white,true),ui.lp(a,12))
        body.addView(ui.text(a,"Download original model files and their textures. Blender and Windows assets require compatible tools, not automatic Android conversion.",12f,ui.secondary),ui.lp(a,7))
        val input=EditText(a).apply{
            hint="Search model or material"
            setTextColor(ui.white);setHintTextColor(ui.secondary);setSingleLine(true)
        }
        body.addView(input,ui.lp(a,8))
        val status=ui.text(a,"Poly Haven · public CC0 model library",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        body.addView(ui.action(a,"SEARCH MODELS",true){
            val q=input.text.toString().trim().lowercase()
            if(q.length<2){status.text="Enter a model name";return@action}
            results.removeAllViews();status.text="Searching Poly Haven"
            thread(name="asset-search"){
                val outcome=runCatching{JSONObject(fetch("https://api.polyhaven.com/assets?type=models"))}
                val ambient=runCatching{JSONObject(fetch("https://ambientcg.com/api/v3/assets?q="+
                    java.net.URLEncoder.encode(q,"UTF-8")+"&limit=12&include=title,tags,downloads"))}
                a.runOnUiThread{
                    outcome.onSuccess{data->
                        var found=0
                        for(id in data.keys()){
                            val item=data.optJSONObject(id)?:continue
                            val name=item.optString("name",id)
                            val terms=q.split(Regex("\\s+"))
                            if(terms.any{it !in (name+" "+id+" "+item.optJSONArray("tags")).lowercase()})continue
                            val tile=ui.card(a)
                            val image=ImageView(a).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
                            tile.addView(image,LinearLayout.LayoutParams(-1,ui.px(a,155)))
                            RemoteImageLoader.load(a,image,"https://cdn.polyhaven.com/asset_img/primary/"+id+".png?height=256",
                                "https://polyhaven.com/a/"+id)
                            tile.addView(ui.text(a,name,16f,ui.white,true),ui.lp(a,6))
                            tile.addView(ui.action(a,"CHOOSE ORIGINAL MODELS / DEPENDENCIES",true){select(a,id,status)},ui.lp(a,8))
                            results.addView(tile,ui.lp(a,9))
                            if(++found>=18)break
                        }
                        status.text=found.toString()+" models found"
                    }.onFailure{status.text="Poly Haven: "+it.message}
                    ambient.onSuccess{catalog->
                        val rows=catalog.optJSONArray("assets")
                        for(i in 0 until minOf(rows?.length()?:0,12)){
                            val item=rows?.optJSONObject(i)?:continue
                            val id=item.optString("id")
                            if(!id.matches(Regex("[A-Za-z0-9_-]{3,80}")))continue
                            val files=item.optJSONArray("downloads")
                            val originals=(0 until (files?.length()?:0)).mapNotNull{j->
                                val f=files?.optJSONObject(j)?:return@mapNotNull null
                                val url=f.optString("url")
                                if(!safe(url))return@mapNotNull null
                                f.optString("attributes","pack")+".zip" to url
                            }
                            val tile=ui.card(a)
                            val image=ImageView(a).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
                            tile.addView(image,LinearLayout.LayoutParams(-1,ui.px(a,145)))
                            CreatorPreviews.sourceThumbnail(a,image,"https://ambientcg.com/a/"+id)
                            tile.addView(ui.text(a,item.optString("title",id),16f,ui.white,true),ui.lp(a,6))
                            tile.addView(ui.text(a,"ambientCG · complete PBR texture ZIPs",12f,ui.secondary),ui.lp(a,3))
                            tile.addView(ui.action(a,"CHOOSE ORIGINAL PACK RESOLUTION",true){
                                val selected=BooleanArray(originals.size)
                                AlertDialog.Builder(a).setTitle(id+" material packs")
                                    .setMultiChoiceItems(originals.map{it.first}.toTypedArray(),
                                        selected){_,j,v->selected[j]=v}
                                    .setNeutralButton("ALL PACKS"){_,_->download(a,id,originals)}
                                    .setPositiveButton("SELECTED"){_,_->
                                        download(a,id,originals.filterIndexed{j,_->selected[j]})
                                    }.setNegativeButton("CANCEL",null).show()
                            },ui.lp(a,8))
                            results.addView(tile,ui.lp(a,9))
                        }
                    }.onFailure{status.text=status.text.toString()+" · ambientCG: "+it.message}
                }
            }
        },ui.lp(a,8))
        body.addView(status,ui.lp(a,6));body.addView(results,ui.lp(a,6))
        body.addView(ui.action(a,"KOIKATSU CHARACTER CARDS",true){
            a.startActivity(Intent(a,KoikatsuCardActivity::class.java))
        },ui.lp(a,10))
        body.addView(ui.action(a,"GUMROAD · BROWSE 3D ASSETS",false){
            a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://gumroad.com/discover?query=3d%20model")))
        },ui.lp(a,7))
        body.addView(ui.action(a,"OPEN LOCAL BLENDER / MODEL / ARCHIVE",false){
            a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
            },5204)
        },ui.lp(a,7))
        CreatorArchiveUi.render(a,body,ArchiveSection.ASSETS)
    }
    fun localFile(a:Activity,uri:Uri?){
        if(uri==null)return
        runCatching{a.startActivity(Intent(Intent.ACTION_VIEW).apply{
            setDataAndType(uri,a.contentResolver.getType(uri)?:"application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })}
    }
}
