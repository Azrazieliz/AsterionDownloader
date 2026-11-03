package com.azrael.pixivdumpsync

import android.app.Activity
import android.net.Uri
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Persistent opt-in creator suggestions using real creator index records and preview attachments. */
object AsterionRecommendations {
    private val ui=NocturneDesign
    private fun pref(a:Activity)=a.getSharedPreferences("asterion_recommendations",0)
    private fun words(value:String):Set<String> = CreatorNameTokens.words(value)
    private fun score(seed:List<String>,name:String):Int{
        val tokens=words(name)
        if(tokens.isEmpty())return 0
        return seed.maxOfOrNull{
            val known=words(it)
            val intersection=known.intersect(tokens).size
            if(intersection==0)0 else intersection*100/(known.union(tokens).size.coerceAtLeast(1))
        }?:0
    }
    private fun save(a:Activity,results:List<ArchiveCreator>){
        val arr=JSONArray()
        for(creator in results.take(50))arr.put(JSONObject().apply{
            put("url",creator.url);put("name",creator.name)
        })
        pref(a).edit().putString("results",arr.toString()).apply()
    }
    private fun existing(a:Activity):List<ArchiveCreator> = runCatching{
        val arr=JSONArray(pref(a).getString("results","[]"))
        (0 until arr.length()).mapNotNull{i->
            val row=arr.optJSONObject(i)?:return@mapNotNull null
            CreatorArchives.parseCreatorUrl(row.optString("url"))
                ?.copy(name=row.optString("name"))
        }
    }.getOrDefault(emptyList())
    private fun preview(a:Activity,creator:ArchiveCreator,image:ImageView){
        image.setImageResource(R.drawable.ic_source_creator)
        thread(name="asterion-recommendation-cover"){
            val url=runCatching{
                CreatorArchives.posts(creator,0).asSequence().flatMap{it.files.asSequence()}
                    .map{it.url}.firstOrNull{
                        it.substringBefore('?').matches(Regex("(?i).*\\.(?:jpg|jpeg|png|webp)"))
                    }
            }.getOrNull()
            if(url!=null && !a.isFinishing && !a.isDestroyed)
                a.runOnUiThread{VideoSearchPreviews.display(a,image,url)}
        }
    }
    fun render(a:Activity,p:LinearLayout,refresh:()->Unit){
        p.addView(ui.text(a,"For You",23f,ui.white,true),ui.lp(a,14))
        AsterionContentSuggestions.render(a,p,refresh)
        val discovery=ui.card(a)
        discovery.addView(ui.text(a,"Cross-platform creator discovery",17f,ui.white,true))
        discovery.addView(ui.text(a,
            "Checks actual followed Pixiv, X, cosplay and creator-archive profiles. "+
            "Follows official social links and one-hop link hubs across Japanese, "+
            "Chinese, Korean and international platforms. Similar names alone "+
            "are never treated as one identity.",
            12f,ui.secondary),ui.lp(a,6))
        val discoveryState=ui.text(a,"",12f,ui.secondary)
        discovery.addView(ui.action(a,"CHECK FOLLOWED CREATORS & LINK HUBS",true){
            if(!AppPower.enabled(a)){
                discoveryState.text="Downloader is switched off"
                return@action
            }
            discoveryState.text="Checking linked creator profiles…"
            thread(name="asterion-cross-platform-discovery"){
                val result=runCatching{
                    CreatorLinkDiscovery.scan(a.applicationContext,
                        profilesPerRun=12,hubsPerRun=6)
                }
                a.runOnUiThread {
                    if(a.isDestroyed||a.isFinishing)return@runOnUiThread
                    discoveryState.text=result.fold(
                        {"Current discovery pass finished; unavailable profiles are retried."},
                        {"Discovery could not run: "+(it.message?:"unknown error")})
                    if(result.isSuccess)refresh()
                }
            }
        },ui.lp(a,7))
        discovery.addView(discoveryState,ui.lp(a,5))
        p.addView(discovery,ui.lp(a,10))
        val crossPlatform=CrossPlatformCreatorDb(a).use{it.candidates(45)}
        if(crossPlatform.isNotEmpty()){
            val linked=ui.card(a)
            linked.addView(ui.text(a,"Linked creator profiles",17f,ui.white,true))
            linked.addView(ui.text(a,
                "Cross-site links found in creator profile headers and biographies. "+
                "Separate sites and partial/full posts remain separate originals; "+
                "profile relationships are suggestions, not proof of identical media.",
                12f,ui.secondary),ui.lp(a,5))
            for(candidate in crossPlatform.take(20)){
                val c=ui.card(a)
                val row=ui.row(a)
                val preview=ImageView(a).apply{
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ui.raised)
                    contentDescription=candidate.platform+" linked creator preview"
                }
                row.addView(preview,LinearLayout.LayoutParams(ui.px(a,72),ui.px(a,72)))
                CreatorPreviews.sourceThumbnail(a,preview,candidate.url)
                val names=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(ui.px(a,10),0,0,0)
                }
                names.addView(ui.text(a,candidate.platform+" · "+
                    candidate.url.substringAfter("://").take(75),14f,ui.white,true))
                names.addView(ui.text(a,candidate.evidence.take(140),
                    11f,ui.secondary),ui.lp(a,4))
                row.addView(names,LinearLayout.LayoutParams(0,-2,1f))
                c.addView(row)
                c.addView(ui.action(a,"VIEW LINKED CREATOR",false){
                    a.startActivity(android.content.Intent(a,
                        com.azrael.galleryflow.ProviderWebActivity::class.java)
                        .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_URL,
                            candidate.url))
                },ui.lp(a,6))
                c.addView(ui.action(a,"FOLLOW & BACKFILL PROFILE",true){
                    AsterionSmartAdd.followLinkedProfile(a,candidate.url){result->
                        CrossPlatformCreatorDb(a).use{
                            it.followed(candidate.source,candidate.url)
                        }
                        Toast.makeText(a,result,Toast.LENGTH_LONG).show()
                        refresh()
                    }
                },ui.lp(a,7))
                linked.addView(c,ui.lp(a,8))
            }
            p.addView(linked,ui.lp(a,10))
        }
        val followed=NameWatchDb(a).use{it.list().map{w->w.name}}
        val sourceNames=com.azrael.galleryflow.GalleryDb(a).use{
            it.listEntities().map{e->e.displayName}
        }
        val pixiv=AppDb(a).use{it.listArtists().mapNotNull{u->u.label}}
        val seeds=(followed+sourceNames+pixiv).filter{it.isNotBlank()}.distinct()
        val box=ui.card(a)
        box.addView(ui.text(a,"Interests & followed creators",17f,ui.white,true))
        val input=EditText(a).apply{
            hint="Artist or model names · one per line"
            minLines=3;maxLines=6;setSingleLine(false)
            setTextColor(ui.white);setHintTextColor(ui.secondary);textSize=15f
            setText(pref(a).getString("interests",""))
            background=ui.shape(a,ui.raised,12)
            setPadding(ui.px(a,12),ui.px(a,10),ui.px(a,12),ui.px(a,10))
        }
        box.addView(input,ui.lp(a,9))
        box.addView(ui.text(a,seeds.size.toString()+" existing artist names available as additional interests",
            12f,ui.secondary),ui.lp(a,6))
        val status=ui.text(a,"",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        fun draw(items:List<ArchiveCreator>){
            results.removeAllViews()
            for(creator in items.take(30)){
                val c=ui.card(a)
                val row=ui.row(a)
                val image=ImageView(a).apply{
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ui.raised)
                }
                row.addView(image,LinearLayout.LayoutParams(ui.px(a,88),ui.px(a,88)))
                preview(a,creator,image)
                val name=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(ui.px(a,11),0,0,0)
                }
                name.addView(ui.text(a,creator.name,15f,ui.white,true))
                name.addView(ui.text(a,creator.host.title+" · "+creator.service,12f,ui.secondary),
                    ui.lp(a,4))
                row.addView(name,LinearLayout.LayoutParams(0,-2,1f))
                c.addView(row)
                val isSaved=CreatorArchiveStore.isSaved(a,creator)
                c.addView(ui.action(a,if(isSaved)"FOLLOWING" else "FOLLOW & BACKFILL",!isSaved){
                    if(!CreatorArchiveStore.isSaved(a,creator)){
                        CreatorArchiveStore.set(a,creator,true)
                        CreatorArchiveScheduler.scanNow(a)
                    }
                    refresh()
                },ui.lp(a,8))
                results.addView(c,ui.lp(a,8))
            }
        }
        box.addView(ui.action(a,"FIND RELATED CREATORS",true){
            val typed=input.text.toString().split('\n',',',';').map{it.trim()}
                .filter{it.length>=2}.distinct().take(12)
            pref(a).edit().putString("interests",input.text.toString()).apply()
            val selected=if(typed.isNotEmpty())typed else seeds.take(6)
            if(selected.isEmpty()){
                status.text="Add artist or model names to discover related creators"
                return@action
            }
            status.text="Searching creator indexes…"
            thread(name="asterion-recommendations"){
                val executor=Executors.newFixedThreadPool(3)
                val errors=ArrayList<String>()
                val candidates=ArrayList<ArchiveCreator>()
                try{
                    val futures=selected.take(6).map{seed->
                        executor.submit<ArchiveCreatorSearch>{CreatorArchives.search(seed)}
                    }
                    for(future in futures){
                        runCatching{future.get(65,TimeUnit.SECONDS)}
                            .onSuccess{result->
                                candidates+=result.creators
                                errors+=result.errors
                            }.onFailure{errors+=it.message?:"Creator lookup failed"}
                    }
                }finally{executor.shutdownNow()}
                val followedNames=(selected+seeds).map{it.lowercase(Locale.ROOT)}.toSet()
                val saved=CreatorArchiveStore.list(a).map{it.url}.toSet()
                val ranked=candidates.distinctBy{it.url}.filter{
                    it.name.lowercase(Locale.ROOT) !in followedNames && it.url !in saved
                }.sortedWith(compareByDescending<ArchiveCreator>{score(selected,it.name)}
                    .thenBy{it.name.lowercase(Locale.ROOT)}).take(30)
                save(a,ranked)
                a.runOnUiThread{
                    if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                    draw(ranked)
                    status.text=ranked.size.toString()+" related directory matches · "+
                        errors.size+" source errors"
                }
            }
        },ui.lp(a,9))
        box.addView(status,ui.lp(a,7))
        p.addView(box,ui.lp(a,8))
        p.addView(results,ui.lp(a,9))
        draw(existing(a))
    }
}
