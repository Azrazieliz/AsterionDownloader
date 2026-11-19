package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.azrael.galleryflow.SiteSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Uses the account's own rendered X Following list, rather than an unauthenticated
 * third-party API. Scans virtualized cells while the user visits /following.
 * No account cookies, profile identifiers or passwords leave app storage.
 */
class XFollowingImportActivity:Activity(){
    private val ui=NocturneDesign
    private val handler=Handler(Looper.getMainLooper())
    private val discovered=linkedSetOf<String>()
    private lateinit var browser:WebView
    private lateinit var label:TextView
    private lateinit var importButton:TextView
    @Volatile private var scanning=true
    private val importWorker=Executors.newSingleThreadExecutor()
    private var scanCycles=0
    private var idleAtBottom=0
    private var previousCount=0
    private var completed=false
    private var totalNew=0
    private var listPosition=0
    private var listExtent=0
    private var declaredFollowingCount:Int?=null
    private val reserved=setOf("home","explore","search","notifications","messages",
        "settings","compose","i","login","signup","privacy","tos","intent","hashtag",
        "following","followers","lists","bookmarks","premium","help","account","media")
    private fun validHandle(s:String)=s.matches(Regex("[A-Za-z0-9_]{1,15}"))&&
        s.lowercase() !in reserved
    private fun allowed(link:String)=runCatching{
        val host=Uri.parse(link).host.orEmpty().removePrefix("www.").lowercase()
        host=="x.com"||host=="twitter.com"
    }.getOrDefault(false)
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(ui.background)
            setPadding(ui.px(this@XFollowingImportActivity,10),ui.px(this@XFollowingImportActivity,10),
                ui.px(this@XFollowingImportActivity,10),ui.px(this@XFollowingImportActivity,10))
        }
        layout.addView(ui.text(this,"X · My Following",21f,ui.white,true))
        label=ui.text(this,"Home's Following feed is not your Following accounts list. Open your profile, then its Following list; scroll to capture accounts.",
            13f,ui.secondary)
        layout.addView(label,ui.lp(this,8))
        val ownHandle=EditText(this).apply{
            hint="Your X username (not a followed artist)"
            setSingleLine(true)
            textSize=14f
            setTextColor(ui.white)
            setHintTextColor(ui.subdued)
            background=ui.shape(this@XFollowingImportActivity,ui.raised,12)
            setPadding(ui.px(this@XFollowingImportActivity,14),ui.px(this@XFollowingImportActivity,9),
                ui.px(this@XFollowingImportActivity,14),ui.px(this@XFollowingImportActivity,9))
        }
        ownHandle.setText(getPreferences(MODE_PRIVATE).getString("own_x_handle",""))
        layout.addView(ownHandle,ui.lp(this,6))
        layout.addView(ui.action(this,"OPEN MY FOLLOWING ACCOUNT LIST",true){
            val handle=ownHandle.text.toString().trim().removePrefix("@")
            if(validHandle(handle)){
                val previousOwner=getPreferences(MODE_PRIVATE)
                    .getString("own_x_handle","").orEmpty().trim().removePrefix("@")
                if(!previousOwner.equals(handle,ignoreCase=true)){
                    // A user switching their own X account must not inherit
                    // the previous account's in-memory scan or progress
                    // counters. Persisted originals are not touched.
                    discovered.clear()
                    scanCycles=0
                    totalNew=0
                    previousCount=0
                }
                getPreferences(MODE_PRIVATE).edit().putString("own_x_handle",handle).apply()
                completed=false; scanning=true;idleAtBottom=0;previousCount=discovered.size
                handler.removeCallbacks(scanner);handler.post(scanner)
                browser.loadUrl("https://x.com/"+handle+"/following")
            }else{
                browser.evaluateJavascript("""(function(){
                    const a=document.querySelector('a[data-testid="AppTabBar_Profile_Link"]');
                    return a?a.getAttribute('href'):'';
                })()"""){raw->
                    val found=raw.trim('"').trim('/').substringBefore('/')
                    if(validHandle(found)){
                        getPreferences(MODE_PRIVATE).edit()
                            .putString("own_x_handle",found).apply()
                        browser.loadUrl("https://x.com/"+found+"/following")
                    }else Toast.makeText(this,
                        "Enter your own X username above to open the account list.",
                        Toast.LENGTH_LONG).show()
                }
            }
        },ui.lp(this,7))
        browser=WebView(this).apply{
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,request:WebResourceRequest):Boolean{
                    val u=request.url.toString()
                    if(allowed(u))return false
                    runCatching{startActivity(Intent(Intent.ACTION_VIEW,request.url))}
                    return true
                }
            }
        }
        layout.addView(browser,LinearLayout.LayoutParams(-1,0,1f).apply{weight=1f})
        layout.addView(ui.action(this,"OPEN MY FOLLOWING LIST",false) {
            browser.evaluateJavascript("""(function(){
              const links=[...document.querySelectorAll('a[href]')];
              const p=links.find(a=>a.getAttribute('data-testid')==='AppTabBar_Profile_Link') ||
                  links.find(a=>a.getAttribute('aria-label')?.toLowerCase()==='profile');
              const path=p?.getAttribute('href');
              if(path && /^\/[A-Za-z0-9_]{1,15}\/?$/.test(path)){
                  window.location.href='https://x.com'+path.replace(/\/$/,'')+'/following';
                  return 'opened';
              }
              return 'manual';
            })()"""){ result ->
                if(result.contains("manual"))
                    Toast.makeText(this,"Open your own profile in X, then tap its Following count.",Toast.LENGTH_LONG).show()
            }
        },ui.lp(this,5))
        importButton=ui.action(this,"STOP & RETURN (PROGRESS SAVED)",true){
            if(discovered.isEmpty()){
                Toast.makeText(this,"No verified accounts detected. Open your own Following list.",Toast.LENGTH_LONG).show()
                return@action
            }
            if(XFollowingImportPolicy.canReconcile(
                    completed && listExtent>0 && scanCycles>=20,
                    discovered.size,declaredFollowingCount)){
                // Old imports could mix unrelated suggestions with real
                // follows. Remove only after a COMPLETE authenticated scan
                // and explicit owner confirmation. Original saved files stay.
                val confirmed=discovered.map{it.lowercase()}.toSet()
                val unmatched=XPhotoQueue(this).use{db->
                    db.allSources().filter{it.handle.lowercase() !in confirmed}
                }
                if(unmatched.isNotEmpty()){
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Reconcile my actual X Following?")
                        .setMessage("The signed-in account's complete Following "+
                            "timeline yielded "+confirmed.size+" verified profiles. "+
                            unmatched.size+" currently saved subscriptions were "+
                            "not in that list. Remove those subscriptions and "+
                            "pause their pending tasks? Previously downloaded "+
                            "photos are kept. Nothing is removed automatically "+
                            "without this confirmation.")
                        .setNegativeButton("Keep all",null)
                        .setPositiveButton("Reconcile"){_,_->
                            importWorker.execute{
                                val removed=XPhotoQueue(applicationContext).use{db->
                                    unmatched.count{db.removeSource(it.url);true}
                                }
                                runOnUiThread{
                                    Toast.makeText(this,
                                        "Removed $removed unverified subscriptions; "+
                                        "saved photos kept",Toast.LENGTH_LONG).show()
                                    setResult(RESULT_OK)
                                    finish()
                                }
                            }
                        }.show()
                    return@action
                }
            }
            Toast.makeText(this,
                "Saved "+discovered.size+" verified accounts ("+totalNew+" new).",
                Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        }
        layout.addView(importButton,ui.lp(this,8))
        layout.addView(ui.action(this,"PAUSE / RESUME AUTOMATIC IMPORT",false){
            scanning=!scanning
            completed=false
            idleAtBottom=0
            handler.removeCallbacks(scanner)
            if(scanning) handler.post(scanner)
            Toast.makeText(this,if(scanning)"Automatic import resumed"
                else "Import paused: saved accounts are preserved",Toast.LENGTH_SHORT).show()
        },ui.lp(this,6))
        setContentView(layout)
        browser.loadUrl("https://x.com/home")
    }
    private data class FollowingScan(
        val users:List<String>,val position:Int,val extent:Int,
        val loggedInProfileHref:String,val declaredFollowingCount:Int?
    )
    private fun acceptScan(raw:String) {
            val batch=runCatching {
                val obj=JSONObject(raw)
                val arr=obj.optJSONArray("users")?:JSONArray()
                val items=(0 until minOf(arr.length(),500)).mapNotNull{index->
                    val evidence=arr.optJSONObject(index)?:return@mapNotNull null
                    val handle=evidence.optString("handle")
                    handle.takeIf {
                        XFollowingImportPolicy.isFollowedAccount(
                            it,evidence.optBoolean("following",false))
                    }
                }
                if(!obj.optBoolean("verifiedTimeline",false))return@runCatching null
                FollowingScan(items,obj.optInt("position",0),
                    obj.optInt("extent",0),obj.optString("profileHref"),
                    obj.optInt("followingCount",-1).takeIf{it>=0})
            }.getOrNull()?:return
                if(!scanning||completed)return
                val own=getPreferences(MODE_PRIVATE).getString("own_x_handle","")
                    .orEmpty().trim().removePrefix("@")
                val page=Uri.parse(browser.url.orEmpty()).path.orEmpty()
                if(!XFollowingImportPolicy.isAuthenticatedOwnFollowingPage(
                        page,own,batch.loggedInProfileHref)){
                    label.text="X import paused: the signed-in profile could not "+
                        "be verified as @"+own+". Open your own account's Following list."
                    return
                }
                val fresh=batch.users.filter{validHandle(it)&&discovered.add(it)}
                listPosition=batch.position
                listExtent=batch.extent
                if(batch.declaredFollowingCount!=null)
                    declaredFollowingCount=batch.declaredFollowingCount
                if(fresh.isNotEmpty()){
                    // Never accumulate an entire thousand-user account list in RAM
                    // before committing. Persistence is serial and idempotent.
                    importWorker.execute {
                        // Serial persistence may lag behind navigation. Never
                        // commit a former account's scan after the owner
                        // switched the authenticated profile being imported.
                        val current=getPreferences(MODE_PRIVATE)
                            .getString("own_x_handle","").orEmpty()
                            .trim().removePrefix("@")
                        if(!current.equals(own,ignoreCase=true))
                            return@execute
                        val count=XPhotoQueue(applicationContext).use{db->
                            fresh.count{db.addSource("https://x.com/"+it)}
                        }
                        runOnUiThread { totalNew+=count; updateStatus() }
                    }
                }
                val atEnd=listExtent>0 && listPosition>=listExtent-15
                if(fresh.isNotEmpty())idleAtBottom=0
                else if(atEnd)idleAtBottom++
                else idleAtBottom=0
                if(idleAtBottom>=14 && scanCycles>14){
                    completed=true
                    scanning=false
                    XPhotoScheduler.ensure(this@XFollowingImportActivity)
                    handler.removeCallbacks(scanner)
                }
                previousCount=discovered.size
                updateStatus()
        }
    private fun updateStatus() {
        importButton.text=if(XFollowingImportPolicy.canReconcile(
            completed && listExtent>0 && scanCycles>=20,
            discovered.size,declaredFollowingCount))
            "RECONCILE MY VERIFIED FOLLOWING LIST"
        else "STOP & RETURN (PROGRESS SAVED)"
        label.text=if(completed)
            "Automatic scan reached the end. "+discovered.size+
                " verified accounts, "+totalNew+" newly saved. "+
                (if(XFollowingImportPolicy.canReconcile(true,
                        discovered.size,declaredFollowingCount))
                    "Owner count reconciled."
                else "Full Following count unverified or incomplete; "+
                    "existing subscriptions cannot be removed.")
        else
            "AUTOMATIC IMPORT: "+discovered.size+" accounts detected; "+
                totalNew+" newly saved. Scrolling the full list without manual steps. "+
                (if(listExtent>0)"Scroll "+listPosition+"/"+listExtent else "Loading X…")
    }
    private val scanner=object:Runnable {
        override fun run(){
            if(!scanning||completed)return
            if(::browser.isInitialized){
                val url=browser.url.orEmpty()
                val path=Uri.parse(url).path.orEmpty()
                if(allowed(url)&&path.matches(
                    Regex("/[A-Za-z0-9_]{1,15}/following/?",RegexOption.IGNORE_CASE))){
                    scanCycles++
                    browser.evaluateJavascript("""
                        (function(){
                          const found=new Set();
                          // Only X's OWN account Following timeline.
                          // The global UserCell selector also returns sidebar
                          // recommendations, promoted accounts and Home posts.
                          // Fail closed if X changes its timeline markup.
                          const primary=document.querySelector(
                            '[data-testid="primaryColumn"]');
                          const timeline=primary?.querySelector(
                            '[aria-label="Timeline: Following"],'+
                            '[aria-label="Timeline: following"]');
                          const cells=timeline?[...timeline.querySelectorAll(
                            '[data-testid="UserCell"]')]:[];
                          cells.forEach(cell=>{
                            if(cell.closest(
                              '[data-testid="whoToFollow"],[data-testid="promoted"],'+
                              '[aria-label="Who to follow"],[aria-label="Suggested"]'
                            ))return;
                            const actions=[...cell.querySelectorAll(
                              'button,[role="button"]')];
                            const following=actions.some(b=>{
                              const id=(b.getAttribute('data-testid')||'')
                                .toLowerCase();
                              const label=(b.getAttribute('aria-label')||
                                b.textContent||'').trim().toLowerCase();
                              return id==='unfollow' ||
                                /(?:^|-)unfollow$/.test(id) ||
                                label==='following'||label==='unfollow';
                            });
                            if(!following)return;
                            cell.querySelectorAll(
                              '[data-testid="User-Name"] a[href]')
                              .forEach(a=>{
                                const match=(a.getAttribute('href')||'')
                                  .match(new RegExp('^/([A-Za-z0-9_]{1,15})/?$'));
                                if(match && (cell.innerText||'').toLowerCase()
                                     .includes('@'+match[1].toLowerCase()))
                                  found.add(match[1]);
                              });
                          });
                          // Scroll within the verified Following pane
                          // only. Global scrolling could advance unrelated
                          // recommendations instead of the account list.
                          const scrollables=timeline?[
                            timeline,...timeline.querySelectorAll('div'),
                            document.scrollingElement
                          ].filter(Boolean).filter(el=>el.scrollHeight>
                              el.clientHeight+120):[];
                          scrollables.sort((a,b)=>
                            (b.scrollHeight-b.clientHeight)-(a.scrollHeight-a.clientHeight));
                          const target=scrollables[0]||
                            timeline||document.scrollingElement;
                          const move=Math.max(380,Math.floor(window.innerHeight*0.78));
                          const before=target.scrollTop;
                          if(target===document.scrollingElement){
                              window.scrollBy(0,move);
                          }else{
                              target.scrollTop+=move;
                          }
                          if(target.scrollTop<=before && cells.length){
                            cells[cells.length-1].scrollIntoView({block:'end'});
                          }
                          const ownerProfile=document.querySelector(
                            'a[data-testid="AppTabBar_Profile_Link"]');
                          const ownerHref=(ownerProfile?.getAttribute('href')||
                            '').replace(/\/$/,'');
                          // Count comes ONLY from the signed-in owner's
                          // own /following link, never a recommendation or
                          // a target person's sidebar "Following" count.
                          const ownerFollowing=[...document.querySelectorAll(
                            'a[href]')].filter(a=>
                              (a.getAttribute('href')||'').toLowerCase()===
                              (ownerHref+'/following').toLowerCase());
                          let followingCount=-1;
                          ownerFollowing.forEach(a=>{
                            const raw=(a.getAttribute('aria-label')||'')+
                              ' '+(a.textContent||'');
                            const match=raw.replace(/,/g,'').match(
                              /(?:^|\s)(\d{1,7})\s*following\b|\bfollowing\s*(\d{1,7})\b/i);
                            if(match)followingCount=Number(match[1]||match[2]);
                          });
                          return JSON.stringify({
                            users:[...found].map(handle=>({handle,following:true})),
                            position:Math.round(target.scrollTop),
                            extent:Math.round(target.scrollHeight-target.clientHeight),
                            profileHref:ownerProfile?.getAttribute('href')||'',
                            followingCount,
                            verifiedTimeline:!!timeline
                          });
                        })()
                    """.trimIndent()){ encoded ->
                        // evaluateJavascript returns only the TOP-level frame's
                        // evaluated value. Unlike addJavascriptInterface, ads
                        // and third-party iframes cannot inject fake followers.
                        val json=runCatching{
                            org.json.JSONTokener(encoded).nextValue() as String
                        }.getOrNull()
                        if(json!=null)acceptScan(json)
                    }
                }else if(allowed(url) && path.trimEnd('/')=="/home"){
                    label.text="Open your own profile /following list. Then this app automatically imports the entire list."
                }
            }
            handler.postDelayed(this,2000L)
        }
    }
    override fun onResume(){super.onResume();scanning=true;handler.post(scanner)}
    override fun onPause(){scanning=false;handler.removeCallbacks(scanner);super.onPause()}
    override fun onDestroy(){
        handler.removeCallbacks(scanner)
        importWorker.shutdown()
        browser.destroy()
        super.onDestroy()
    }
}