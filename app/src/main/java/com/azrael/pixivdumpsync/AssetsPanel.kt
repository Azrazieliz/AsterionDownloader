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
        val u=URI(url)
        u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.rawFragment==null && u.path.orEmpty().isNotBlank() &&
            u.host.orEmpty().lowercase(java.util.Locale.ROOT) in
                setOf("dl.polyhaven.org","ambientcg.com")
    }.getOrDefault(false)
    private fun download(a:Activity,id:String,files:List<Pair<String,String>>){
        val dm=a.getSystemService(DownloadManager::class.java)
        val seen=a.getSharedPreferences("asterion_assets",0)
        var queued=0
        files.forEach{(path,url)->
            if(!safe(url))return@forEach
            val existing=seen.getLong(url,-1L)
            if(existing>0){
                val current=runCatching{
                    dm.query(DownloadManager.Query().setFilterById(existing))?.use{cursor->
                        if(!cursor.moveToFirst())false
                        else {
                            val status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            status in listOf(DownloadManager.STATUS_SUCCESSFUL,
                                DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED,
                                DownloadManager.STATUS_PENDING)
                        }
                    }
                }.getOrDefault(false)
                if(current==true)return@forEach
            }
            val original=URI(url).path.substringAfterLast('/')
            val filename=if(original.contains("."))original else path.substringAfterLast('/')
            val directory=path.replace(Regex("[^A-Za-z0-9_./+-]"),"_").take(160)
            runCatching{
                val req=DownloadManager.Request(Uri.parse(url)).apply{
                    setTitle(filename)
                    setDescription("Poly Haven original · "+id)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "AsterionDownloader/Assets/"+id+"/"+directory+"/"+filename)
                }
                val jobId=dm.enqueue(req)
                try {
                    // Assets previously bypassed the ONLY unified queue
                    // and therefore had no usable Cancel, Retry or provider
                    // row even when hundreds of MB were transferring.
                    DirectDownloadTracker.record(a,jobId,filename,"Assets",url)
                    DirectDownloadRetry.remember(a,jobId,url,"Assets",filename,null)
                    seen.edit().putLong(url,jobId).apply()
                    queued++
                }catch(failure:Exception){
                    dm.remove(jobId)
                    runCatching{DirectDownloadTracker.forget(a,jobId)}
                    runCatching{DirectRetryVault.remove(a,jobId)}
                    runCatching{seen.edit().remove(url).apply()}
                    throw failure
                }
            }
        }
        Toast.makeText(a,queued.toString()+" original asset files queued",Toast.LENGTH_LONG).show()
    }
    private fun select(a:Activity,id:String,status:TextView){
        thread(name="asset-files"){
            val output=runCatching{
                require(id.matches(Regex("[A-Za-z0-9_-]{2,120}"))){"Invalid asset ID"}
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
    /** Render a genuine indexed file's complete source and original action. */
    private fun appendIndexedOriginal(a:Activity,results:LinearLayout,
                                      file:OpenWebModelSearch.Candidate){
        val tile=ui.card(a)
        // An indexed file may carry a genuine OG image
        // from its own model detail page. Older web
        // cards showed text-only even where a poster
        // existed, making model discovery look broken.
        file.thumbnail?.let{poster->
            val preview=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                contentDescription="Model preview · "+file.title
                setImageResource(R.drawable.ic_source_video)
            }
            tile.addView(preview,LinearLayout.LayoutParams(
                -1,ui.px(a,136)))
            RemoteImageLoader.load(a,preview,poster,file.url)
        }
        tile.addView(ui.text(a,file.title,15f,ui.white,true),
            ui.lp(a,6))
        tile.addView(ui.text(a,
            file.host+" · "+file.format+
            " · indexed file, not a completed download",
            11f,ui.secondary),ui.lp(a,4))
        val verifyStatus=ui.text(a,"",11f,ui.secondary)
        tile.addView(ui.action(a,"DOWNLOAD ORIGINAL FILE",true){
            // The federated catalogue sometimes labels an original GLB
            // while serving a ZIP archive (verified with real ZIP bytes).
            // Validate the actual publisher response before assigning an
            // extension and entering the unified Downloads queue.
            val federated=runCatching{
                URI(file.url).host.equals("3d.shep.bot",true)
            }.getOrDefault(false)
            val requested=file.format.lowercase()
            if(!federated){
                val original=runCatching{
                    URI(file.url).path.substringAfterLast('/')
                }.getOrDefault("")
                val filename=if(original.endsWith("."+requested,true))original
                    else file.title.replace(
                        Regex("[^A-Za-z0-9._-]"),"_").take(95)+
                        "."+requested
                ResourceDownloads.downloadFile(
                    a,file.url,"Assets",filename,file.referer)
                return@action
            }
            verifyStatus.text="Checking actual publisher file format…"
            val token=Any()
            verifyStatus.tag=token
            thread(name="asterion-model-original-probe"){
                val verified=runCatching{
                    com.azrael.galleryflow.HttpClient(a.applicationContext)
                        .open(file.url).use{stream->
                            val header=ByteArray(16)
                            var read=0
                            while(read<header.size){
                                val n=stream.input.read(header,read,header.size-read)
                                if(n<0)break
                                read+=n
                            }
                            ModelOriginalPayloadFormat.detect(header.copyOf(read))
                                ?:error("Publisher did not return a GLB or ZIP original")
                        }
                }
                a.runOnUiThread{
                    if(a.isDestroyed || a.isFinishing ||
                        verifyStatus.tag!==token)return@runOnUiThread
                    verified.onSuccess{actual->
                        val filename=file.title.replace(
                            Regex("[^A-Za-z0-9._-]"),"_").take(95)+
                            "."+actual.lowercase()
                        ResourceDownloads.downloadFile(
                            a,file.url,"Assets",filename,file.referer)
                        verifyStatus.text="Original "+actual+" verified · queued"
                    }.onFailure{failure->
                        verifyStatus.text="Original not queued: "+
                            (failure.message?:"Publisher file inaccessible").take(120)
                    }
                }
            }
        },ui.lp(a,8))
        tile.addView(verifyStatus,ui.lp(a,4))
        results.addView(tile,ui.lp(a,9))
    }
    fun render(a:Activity,body:LinearLayout,refresh:()->Unit={}){
        // A focused workspace, not three independent galleries and provider
        // administration panels stacked on the same phone screen.
        val pref=a.getSharedPreferences("asterion_asset_workspace_v2",0)
        val section=pref.getString("section","CARDS").orEmpty()
            .takeIf{it in setOf("CARDS","MODELS")} ?:"CARDS"
        val tabs=ui.row(a)
        for((name,key) in listOf("Koikatsu" to "CARDS",
            "3D models" to "MODELS")){
            tabs.addView(ui.chip(a,name,section==key){
                pref.edit().putString("section",key).apply()
                refresh()
            },LinearLayout.LayoutParams(0,-2,1f).apply{
                rightMargin=ui.px(a,5)
            })
        }
        body.addView(tabs,ui.lp(a,9))
        when(section){
            "CARDS"->{
                // Character-card LOOKUP, not an unrequested follow.
                KoikatsuInlineSearch.render(a,body)
                return
            }
        }
        body.addView(ui.text(a,"3D models & materials",19f,ui.white,true),ui.lp(a,12))
        body.addView(ui.text(a,"Download original model files and their textures. Blender and Windows assets require compatible tools, not automatic Android conversion.",12f,ui.secondary),ui.lp(a,7))
        val input=CreatorNames.input(a,"Search model or material","ASSETS")
        body.addView(input,ui.lp(a,8))
        val status=ui.text(a,
            "Search genuine model originals from Poly Haven, ambientCG and "+
                "available public file indexes (not the entire internet).",
            12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        var modelWorker:Thread?=null
        val stopSearch=ui.action(a,"STOP MODEL SEARCH",false){
            // Invalidating the view token also prevents a late provider
            // response from repainting a search the owner cancelled.
            results.tag=Any()
            modelWorker?.interrupt()
            status.text="Model search stopped · previously saved files retained"
        }.apply{visibility=android.view.View.GONE}
        body.addView(ui.action(a,"SEARCH MODELS",true){
            val q=input.text.toString().trim().lowercase()
            CreatorNames.remember(a,q,"ASSETS",true)
            if(q.length<2){status.text="Enter a model name";return@action}
            modelWorker?.interrupt()
            results.removeAllViews()
            status.text="Checking original file catalogs and public indexes…"
            stopSearch.visibility=android.view.View.VISIBLE
            // Each search owns its view token. A later query or navigation
            // cannot be overwritten by a stale asynchronous response.
            val requestToken=Any()
            results.tag=requestToken
            // All result-card mutations run on the UI thread.
            val published=LinkedHashSet<String>()
            fun appendIncremental(files:List<OpenWebModelSearch.Candidate>){
                for(file in files){
                    if(published.size>=72)break
                    if(!published.add(SearchRanking.canonicalKey(file.url)))continue
                    appendIndexedOriginal(a,results,file)
                }
            }
            modelWorker=thread(name="asset-search"){
                // The earlier serial fetches could keep the first source's
                // successful result hidden behind a 19-second second-source
                // timeout. Fetch both approved public catalogs concurrently.
                val pool=java.util.concurrent.Executors.newFixedThreadPool(3)
                val searches=try{
                    val poly=pool.submit<JSONObject>{
                        JSONObject(fetch("https://api.polyhaven.com/assets?type=models"))
                    }
                    val ambientResult=pool.submit<JSONObject>{
                        JSONObject(fetch("https://ambientcg.com/api/v3/assets?q="+
                            java.net.URLEncoder.encode(q,"UTF-8")+
                            "&limit=12&include=title,tags,downloads"))
                    }
                    val web=pool.submit<Pair<
                        List<OpenWebModelSearch.Candidate>,List<String>>>{
                        OpenWebModelSearch.find(q){batch->
                            a.runOnUiThread{
                                if(a.isDestroyed || a.isFinishing ||
                                    results.parent==null ||
                                    results.tag!==requestToken)return@runOnUiThread
                                appendIncremental(batch)
                                status.text=published.size.toString()+
                                    " indexed model-file links · checking other sources…"
                            }
                        }
                    }
                    // Catalog fetches already run concurrently. Waiting on
                    // each unbounded Future.get() in declaration order
                    // nevertheless held a fast Poly Haven/model result behind
                    // the slowest provider. One bounded overall deadline
                    // returns all completed original-file results together.
                    val deadline=System.nanoTime()+
                        java.util.concurrent.TimeUnit.SECONDS.toNanos(26)
                    fun <T> bounded(job:java.util.concurrent.Future<T>):Result<T> =
                        runCatching{job.get(
                            (deadline-System.nanoTime()).coerceAtLeast(0L),
                            java.util.concurrent.TimeUnit.NANOSECONDS)}
                    Triple(bounded(poly),bounded(ambientResult),bounded(web))
                }finally{pool.shutdownNow()}
                val (outcome,ambient,web)=searches
                a.runOnUiThread{
                    if(a.isDestroyed || a.isFinishing ||
                        results.parent==null || results.tag!==requestToken)
                        return@runOnUiThread
                    stopSearch.visibility=android.view.View.GONE
                    var polyCount=0
                    var ambientCount=0
                    val errors=ArrayList<String>()
                    outcome.onSuccess{data->
                        var found=0
                        for(id in data.keys()){
                            if(!id.matches(Regex("[A-Za-z0-9_-]{2,120}")))continue
                            val item=data.optJSONObject(id)?:continue
                            val name=item.optString("name",id)
                            val terms=q.split(Regex("\\s+"))
                            if(terms.any{it !in (name+" "+id+" "+item.optJSONArray("tags")).lowercase()})continue
                            val tile=ui.card(a)
                            val image=ImageView(a).apply{
                                scaleType=ImageView.ScaleType.FIT_CENTER
                                adjustViewBounds=true
                                setBackgroundColor(ui.raised)
                            }
                            tile.addView(image,LinearLayout.LayoutParams(-1,ui.px(a,155)))
                            RemoteImageLoader.load(a,image,"https://cdn.polyhaven.com/asset_img/primary/"+id+".png?height=256",
                                "https://polyhaven.com/a/"+id)
                            tile.addView(ui.text(a,name,16f,ui.white,true),ui.lp(a,6))
                            tile.addView(ui.action(a,"CHOOSE ORIGINAL MODELS / DEPENDENCIES",true){select(a,id,status)},ui.lp(a,8))
                            results.addView(tile,ui.lp(a,9))
                            if(++found>=18)break
                        }
                        polyCount=found
                    }.onFailure{errors+="Poly Haven unavailable: "+
                        (it.cause?.message?:it.message).orEmpty().take(120)}
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
                            val image=ImageView(a).apply{
                                scaleType=ImageView.ScaleType.FIT_CENTER
                                adjustViewBounds=true
                                setBackgroundColor(ui.raised)
                            }
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
                            ambientCount++
                        }
                    }.onFailure{errors+="ambientCG unavailable: "+
                        (it.cause?.message?:it.message).orEmpty().take(120)}
                    var webCount=0
                    web.onSuccess{(candidates,failures)->
                        errors.addAll(failures)
                        appendIncremental(candidates.take(72))
                        webCount=published.size
                    }.onFailure{errors+="Open web unavailable: "+
                        (it.cause?.message?:it.message).orEmpty().take(120)}
                    status.text=buildString{
                        append(polyCount).append(" Poly Haven · ")
                        append(ambientCount).append(" ambientCG · ")
                        append(webCount).append(" verified public model-file links")
                        if(polyCount+ambientCount+webCount==0 && errors.isEmpty())
                            append(" · No matching originals")
                        if(errors.isNotEmpty())
                            append(" · ").append(errors.joinToString(" · "))
                    }
                }
            }
        },ui.lp(a,8))
        body.addView(stopSearch,ui.lp(a,5))
        body.addView(status,ui.lp(a,6));body.addView(results,ui.lp(a,6))
    }
    private fun renderFilesAndSources(
        a:Activity,body:LinearLayout,refresh:()->Unit
    ){
        body.addView(ui.text(a,"Files & source libraries",19f,ui.white,true),
            ui.lp(a,10))
        body.addView(ui.text(a,
            "Manage approved model sources and synchronize original downloadable assets.",
            12f,ui.secondary),ui.lp(a,6))
        // Downloader-only workspace: paid Gumroad redirect and local Blender
        // file opener were not requested and did not download anything.
        val pref=a.getSharedPreferences("asterion_asset_workspace_v2",0)
        val open=pref.getBoolean("provider_tools",false)
        body.addView(ui.action(a,
            if(open)"HIDE 3D WEBSITE CONTROLS" else "3D SOURCES · SYNC & BACKFILL",
            false){
            pref.edit().putBoolean("provider_tools",!open).apply()
            refresh()
        },ui.lp(a,8))
        if(open){
            // No duplicate source controls on unrelated screens.
            SourceIndexPanel.render(a,body,"ASSETS")
            AlohaSources.render(a,body,listOf(AlohaArea.ASSETS),
                "ASSETS · MODELS & CONVERTERS")
        }
        ContentVerticalsPanel.render(a,body,ContentVerticalsPanel.Group.ASSETS,refresh)
    }
    internal fun supportedModelOrArchive(filename:String):Boolean{
        val name=filename.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
        if(name.isBlank() || name.startsWith('.') || name.contains(".."))return false
        return setOf("blend","blend1","fbx","obj","stl","gltf","glb",
            "dae","usd","usdz","3ds","abc","ply","x3d","3mf","ksp",
            "unitypackage","zip","rar","7z","tar","gz")
            .any{name.endsWith("."+it)}
    }
    fun localFile(a:Activity,uri:Uri?){
        if(uri==null)return
        val name=runCatching{
            a.contentResolver.query(uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,null,null)?.use{cursor->
                if(cursor.moveToFirst())cursor.getString(0) else null
            }
        }.getOrNull().orEmpty()
        if(!supportedModelOrArchive(name)){
            Toast.makeText(a,
                "Choose an actual 3D model or archive; images are not model files",
                Toast.LENGTH_LONG).show()
            return
        }
        runCatching{a.startActivity(Intent(Intent.ACTION_VIEW).apply{
            setDataAndType(uri,a.contentResolver.getType(uri)?:"application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })}.onFailure{
            Toast.makeText(a,"No compatible model viewer is installed",
                Toast.LENGTH_LONG).show()
        }
    }
}
