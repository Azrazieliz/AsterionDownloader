package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
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
                    if(validHandle(found))
                        browser.loadUrl("https://x.com/"+found+"/following")
                    else Toast.makeText(this,
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
            addJavascriptInterface(Bridge(),"AsterionFollowing")
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
                Toast.makeText(this,"No accounts detected. Open the Following list.",Toast.LENGTH_LONG).show()
                return@action
            }
            // Import was already committed progressively as each virtualized cell
            // appeared; the button only returns, never requires manual page batches.
            Toast.makeText(this,"Saved "+discovered.size+" detected accounts ("+
                totalNew+" new). Import stops when this browser closes.",Toast.LENGTH_LONG).show()
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
    inner class Bridge {
        @JavascriptInterface
        fun found(raw:String) {
            val batch=runCatching {
                val obj=JSONObject(raw)
                val arr=obj.optJSONArray("users")?:JSONArray()
                val items=(0 until minOf(arr.length(),500)).map{arr.optString(it)}
                Triple(items,obj.optInt("position",0),obj.optInt("extent",0))
            }.getOrNull()?:return
            runOnUiThread {
                if(!scanning||completed)return@runOnUiThread
                val fresh=batch.first.filter{validHandle(it)&&discovered.add(it)}
                listPosition=batch.second
                listExtent=batch.third
                if(fresh.isNotEmpty()){
                    // Never accumulate an entire thousand-user account list in RAM
                    // before committing. Persistence is serial and idempotent.
                    importWorker.execute {
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
        }
    }
    private fun updateStatus() {
        label.text=if(completed)
            "Automatic scan reached the end. "+discovered.size+
                " accounts detected, "+totalNew+" newly saved. If X limited results, resume."
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
                          const cells=[...document.querySelectorAll(
                            '[data-testid="UserCell"], [data-testid="user"]')];
                          cells.forEach(cell=>{
                            cell.querySelectorAll('[data-testid="User-Name"] a[href],a[href^="/"]')
                              .forEach(a=>{
                                const match=(a.getAttribute('href')||'')
                                  .match(new RegExp('^/([A-Za-z0-9_]{1,15})/?$'));
                                if(match && (cell.innerText||'').toLowerCase()
                                     .includes('@'+match[1].toLowerCase()))
                                  found.add(match[1]);
                              });
                          });
                          const scrollables=[document.scrollingElement,
                              ...document.querySelectorAll('main,section,[data-testid="primaryColumn"],div')]
                            .filter(Boolean).filter(el=>el.scrollHeight>el.clientHeight+120);
                          scrollables.sort((a,b)=>
                            (b.scrollHeight-b.clientHeight)-(a.scrollHeight-a.clientHeight));
                          const target=scrollables[0]||document.scrollingElement;
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
                          AsterionFollowing.found(JSON.stringify({
                            users:[...found],
                            position:Math.round(target.scrollTop),
                            extent:Math.round(target.scrollHeight-target.clientHeight)
                          }));
                          return found.size;
                        })()
                    """.trimIndent(),null)
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
        browser.removeJavascriptInterface("AsterionFollowing")
        browser.destroy()
        super.onDestroy()
    }
}