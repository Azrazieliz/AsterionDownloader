package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import com.asterion.videoflow.WebDiscovery
import com.azrael.galleryflow.AdapterRegistry
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.SyncMode
import java.net.URI

/** One top-level application for all sources; legacy specialists remain advanced tools. */
class AsterionHomeActivity:Activity(){
    private val theme=NocturneDesign
    private lateinit var root:LinearLayout
    private lateinit var body:LinearLayout
    private lateinit var scroller:ScrollView
    private var page="Home"
    private var pendingChapterRetry:Long=-1L
    private val chapterSessionRequest=418
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==5210) {
            if(resultCode==RESULT_OK && data?.data!=null)
                GamePortPlanner.inspect(this,data.data!!,
                    importedFileName(data.data!!))
            return
        }
        if(requestCode==5204) {
            if(resultCode==RESULT_OK)AssetsPanel.localFile(this,data?.data)
            return
        }
        if(requestCode in listOf(5201,5202,5203)) {
            if(resultCode==RESULT_OK)
                ResourceDownloads.onFilePicked(this,requestCode,data?.data)
            return
        }
        if(requestCode!=chapterSessionRequest)return
        val id=pendingChapterRetry
        pendingChapterRetry=-1L
        if(resultCode==RESULT_OK && id>=0){
            ChapterQueue(this).use{it.retry(id)}
            ChapterArchives.start(this)
            toast("Saved browser session. Retrying the chapter ZIP.")
        }else if(id>=0) toast("Browser connection was not saved; ZIP retry remains paused.")
        if(::body.isInitialized)render()
    }
    /** Document-provider URIs often use numeric IDs, not filenames. Resolve
     * the real DISPLAY_NAME before deciding whether the game is a ZIP.
     */
    private fun importedFileName(source:Uri):String =
        runCatching{
            contentResolver.query(source,arrayOf(OpenableColumns.DISPLAY_NAME),
                null,null,null)?.use{cursor->
                if(cursor.moveToFirst())cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf{it.isNotBlank()}
            ?:source.lastPathSegment.orEmpty()

    private val pages=listOf("Home","Add","Search","Queue","Recommend")
    private val navButtons=linkedMapOf<String,TextView>()
    private val navIcons=linkedMapOf<String,ImageView>()
    private val navSurfaces=linkedMapOf<String,LinearLayout>()
    private val pageScroll=linkedMapOf<String,Int>()
    private fun dp(v:Int)=theme.px(this,v)
    private fun text(v:String,size:Float=14f,tint:Int=theme.white,bold:Boolean=false)=
        theme.text(this,v,size,tint,bold)
    private fun button(label:String,action:()->Unit)=theme.action(this,label,false,action)
    private fun moduleAccent():Int=when(page){
        "Pixiv"->theme.pixivBlue
        "X"->theme.xCyan
        "Site"->theme.cosplayRose
        "Books","Toonily"->theme.booksGold
        "Torrents"->theme.gamesTeal
        "Assets"->theme.assetsLilac
        "Porn"->theme.violet
        else->theme.primary
    }
    private fun section(title:String,subtitle:String=""){
        val row=theme.row(this)
        val indicator=View(this).apply{
            background=theme.shape(this@AsterionHomeActivity,moduleAccent(),3,moduleAccent())
        }
        row.addView(indicator,LinearLayout.LayoutParams(dp(4),dp(26)).apply{
            rightMargin=dp(10)
        })
        row.addView(text(title,22f,theme.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        body.addView(row,theme.lp(this,16,3))
        if(subtitle.isNotBlank())
            body.addView(text(subtitle,12f,theme.secondary),theme.lp(this,2,3))
    }
    private fun card():LinearLayout=theme.card(this)
    private fun put(parent:LinearLayout,v:View,top:Int=9){
        parent.addView(v,theme.lp(this,top))
    }
    private fun edit(hint:String):EditText=EditText(this).apply{
        this.hint=hint;setSingleLine(true);textSize=15f
        setTextColor(theme.white);setHintTextColor(theme.subdued)
        background=theme.shape(this@AsterionHomeActivity,theme.raised,12)
        setPadding(dp(12),dp(9),dp(12),dp(9))
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=theme.background
        window.navigationBarColor=theme.background
        GalleryDb(this).use{it.seedCatalogOnce(this)}
        AlohaDefaults.ensure(this)
        SourceIndex.ensure(this)
        KoikatsuFollowing.ensure(this)
        RounderDispatch.ensure(this)
        DirectDownloadRetry.ensure(this)
        AsterionDownloadWorker.ensure(this)
        RounderDispatch.scanAsync(applicationContext)
        kotlin.concurrent.thread(name="asterion-seed-name-vocabulary"){
            runCatching{CreatorNames.seed(applicationContext)}
        }
        ChapterRecovery.ensure(this)
        CreatorArchiveScheduler.ensure(this)
        XPhotoScheduler.ensure(this)
        page=getSharedPreferences("asterion_display",MODE_PRIVATE)
            .getString("start_page","Home").orEmpty().takeIf{it in pages} ?: "Home"
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(theme.background)}
        val head=theme.row(this).apply{setPadding(dp(14),dp(12),dp(14),dp(12))}
        head.addView(theme.brandMark(this,47),LinearLayout.LayoutParams(dp(47),dp(47)))
        val label=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(11),0,0,0)}
        label.addView(text("ASTERION DOWNLOADER",19f,theme.white,true))
        label.addView(text("Discover · Follow · Download",11f,theme.secondary))
        head.addView(label,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(head)
        val settings=text("SETTINGS",12f,theme.secondary,true).apply{
            gravity=Gravity.CENTER
            isClickable=true
            isFocusable=true
            contentDescription="Open settings"
            setPadding(dp(12),dp(10),dp(12),dp(10))
            setOnClickListener{open("Settings")}
        }
        head.addView(settings)
        scroller=ScrollView(this).apply{isFillViewport=true}
        body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(17),dp(5),dp(17),dp(34))
        }
        scroller.addView(body)
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        val tabs=theme.row(this).apply{
            setPadding(dp(5),dp(5),dp(5),dp(5))
            setBackgroundColor(theme.surface)
        }
        pages.forEach{tab->
            val title=when(tab){
                "Home"->"Home";"Add"->"Add";"Search"->"Search"
                "Queue"->"Downloads";else->"For You"
            }
            val resource=when(tab){
                "Home"->R.drawable.ic_nav_home
                "Add"->R.drawable.ic_nav_add
                "Search"->R.drawable.ic_nav_search
                "Queue"->R.drawable.ic_nav_downloads
                else->R.drawable.ic_nav_suggest
            }
            val item=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER
                minimumHeight=dp(60)
                setPadding(dp(2),dp(7),dp(2),dp(7))
                background=theme.touch(this@AsterionHomeActivity,theme.surface,14)
                isClickable=true;isFocusable=true
                contentDescription=title
                setOnClickListener{open(tab)}
            }
            val image=ImageView(this).apply{
                setImageResource(resource)
                scaleType=ImageView.ScaleType.FIT_CENTER
            }
            item.addView(image,LinearLayout.LayoutParams(dp(23),dp(23)))
            val t=text(title,10f,theme.secondary,true).apply{
                gravity=Gravity.CENTER
                maxLines=1
                isSingleLine=true
                setPadding(0,dp(3),0,0)
            }
            item.addView(t,LinearLayout.LayoutParams(-1,-2))
            navButtons[tab]=t
            navIcons[tab]=image
            navSurfaces[tab]=item
            tabs.addView(item,LinearLayout.LayoutParams(0,-2,1f).apply{
                leftMargin=dp(2);rightMargin=dp(2)
            })
        }
        root.addView(tabs)
        // Android 15+ enforces edge-to-edge: keep the status/navigation bars clear.
        if(android.os.Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener{_,insets->
                val status=insets.getInsets(android.view.WindowInsets.Type.statusBars())
                val navigation=insets.getInsets(android.view.WindowInsets.Type.navigationBars())
                head.setPadding(dp(14),dp(12)+status.top,dp(14),dp(12))
                tabs.setPadding(dp(5),dp(7),dp(5),dp(7)+navigation.bottom)
                insets
            }
        }
        setContentView(root)
        window.decorView.systemUiVisibility=0
        when(intent?.action){
            Intent.ACTION_SEND->intent.getStringExtra(Intent.EXTRA_TEXT)?.let{submitInput(it)}
        }
        render()
    }
    override fun onResume(){
        super.onResume()
        if(::body.isInitialized){
            render()
            AuthenticatedItemDownloader.resumeIfConnected(this)
        }
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed(){
        if(page!="Home")open("Home") else super.onBackPressed()
    }
    private fun open(target:String){
        if(::scroller.isInitialized)pageScroll[page]=scroller.scrollY
        val previous=page
        page=target
        if(::body.isInitialized){
            render()
            val where=if(target==previous)0 else pageScroll[target]?:0
            scroller.post{scroller.scrollTo(0,where)}
        }
    }
    private fun render(){
        val pending=runCatching{
            val video=VideoDb(this).use{
                it.count("queued")+it.count("running")+it.count("retry_wait")
            }
            val chapters=ChapterQueue(this).use{queue->
                queue.list().count{it.state=="working"||it.state=="queued"}
            }
            val direct=DirectDownloadTracker.states(this,60).count{
                it.status in setOf(android.app.DownloadManager.STATUS_PENDING,
                    android.app.DownloadManager.STATUS_RUNNING,
                    android.app.DownloadManager.STATUS_PAUSED)
            }
            video+chapters+direct
        }.getOrDefault(0)
        navButtons["Queue"]?.text=if(pending>0)"Downloads ($pending)" else "Downloads"
        val selectedTab=if(page in pages)page else "Home"
        navButtons.forEach{(tab,view)->
            val selected=tab==selectedTab
            view.setTextColor(if(selected)theme.primary else theme.secondary)
            view.setTypeface(null,if(selected)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            navIcons[tab]?.imageTintList=android.content.res.ColorStateList.valueOf(
                if(selected)theme.primary else theme.secondary)
            navSurfaces[tab]?.background=theme.touch(this,
                if(selected)theme.soft else theme.surface,14,
                if(selected)theme.primary else theme.surface)
        }
        body.removeAllViews()
        val power = Switch(this).apply {
            text=if(AppPower.enabled(this@AsterionHomeActivity))
                "APP ON · Downloads and background sync enabled"
                else "APP OFF · All downloads and scheduled sync suspended"
            textSize=13f
            setTextColor(theme.white)
            isChecked=AppPower.enabled(this@AsterionHomeActivity)
            setOnCheckedChangeListener{_,turnOn->
                AppPower.set(this@AsterionHomeActivity,turnOn)
                render()
            }
        }
        if(page=="Settings")put(body,power,4)
        if(!AppPower.enabled(this)){
            put(body,text("Sync paused · enable in Settings to resume",12f,theme.danger),5)
        }
        when(page){
            "Home"->renderHome()
            "Pixiv"->AsterionAccountPanels.pixiv(this,body){render()}
            "X"->AsterionUnifiedFollowing.x(this,body){render()}
            "Site"->{AsterionUnifiedFollowing.kyora(this,body){render()};SourceIndexPanel.render(this,body,"COSPLAY")}
            "Porn"->{
                AsterionUnifiedFollowing.adult(this,body){render()}
                SourceIndexPanel.render(this,body,
                    listOf("ADULT","HENTAI","CREATORS"),"Porn & Hentai")
                AsterionVideoAccounts.render(this,body)
            }
            "Books"->renderBooks()
            "Toonily"->renderToonily()
            "Torrents"->renderTorrents()
            "Assets"->{AssetsPanel.render(this,body){render()};SourceIndexPanel.render(this,body,"ASSETS")}
            "Add"->{AsterionSmartAdd.render(this,body){render()};AsterionAddedSitesPanel.render(this,body)}
            "Search"->AsterionSearchHub.render(this,body){render()}
            "Recommend"->AsterionRecommendations.render(this,body){render()}
            "Queue"->UnifiedDownloadsUi.render(this,body){render()}
            "Library"->renderLibrary()
            "Sources"->renderSources()
            else->renderSettings()
        }
    }
    private fun sourceTile(label:String,subtitle:String,icon:Int,tint:Int,
                           target:String):LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        gravity=Gravity.CENTER_HORIZONTAL
        minimumHeight=dp(138)
        setPadding(dp(11),dp(15),dp(11),dp(12))
        background=theme.touch(this@AsterionHomeActivity,theme.surface,19)
        isClickable=true
        isFocusable=true
        contentDescription="$label. $subtitle"
        setOnClickListener{
            when(target){
                "Pixiv"->open("Pixiv")
                "Kyora"->open("Site")
                "Koikatsu"->startActivity(Intent(this@AsterionHomeActivity,KoikatsuCardActivity::class.java))
                "Site"->startActivity(Intent(this@AsterionHomeActivity,com.azrael.galleryflow.MainActivity::class.java)
                    .putExtra(com.azrael.galleryflow.MainActivity.EXTRA_ADD_SITE,true))
                else->open(target)
            }
        }
        val symbol=ImageView(this@AsterionHomeActivity).apply{
            setImageResource(icon)
            imageTintList=android.content.res.ColorStateList.valueOf(tint)
            scaleType=ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11),dp(11),dp(11),dp(11))
            background=theme.shape(this@AsterionHomeActivity,theme.raised,18)
            contentDescription="$label icon"
        }
        addView(symbol,LinearLayout.LayoutParams(dp(52),dp(52)))
        addView(text(label,16f,theme.white,true).apply{
            gravity=Gravity.CENTER
            setPadding(0,dp(10),0,0)
            maxLines=2
        },LinearLayout.LayoutParams(-1,-2))
        addView(text(subtitle,11f,theme.secondary).apply{
            gravity=Gravity.CENTER
            setPadding(0,dp(6),0,0)
            maxLines=2
        },LinearLayout.LayoutParams(-1,-2))
        // Navigation icon only; media belongs to each individual creator card.
    }
    private fun tileRow(left:View,right:View){
        val row=theme.row(this)
        row.gravity=Gravity.TOP
        row.addView(left,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(right,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(11)})
        put(body,row,12)
    }
    private var bookMode="EPUB"
    private var torrentMode=TorrentArea.GAMES
    private fun renderHome(){
        section("Your library","Choose what to follow or download.")
        tileRow(
            sourceTile("Porn & Hentai","Performers · Videos · Sync",
                R.drawable.ic_source_video,theme.violet,"Porn"),
            sourceTile("Pixiv","Artists · Original Kuroha interface",
                R.drawable.ic_source_pixiv,theme.pixivBlue,"Pixiv")
        )
        tileRow(
            sourceTile("X","Following · Photos · Backfill",
                R.drawable.ic_source_x,theme.xCyan,"X"),
            sourceTile("Cosplay","Kyora · Galleries · Videos",
                R.drawable.ic_source_kyora,theme.cosplayRose,"Kyora")
        )
        tileRow(
            sourceTile("Books & Comics","EPUB · Toonily · MangaRead · Sushi",
                R.drawable.ic_source_toonily,theme.booksGold,"Books"),
            sourceTile("Torrents / Games","Game releases · Magnet · Progress",
                R.drawable.ic_source_video,theme.gamesTeal,"Torrents")
        )
        put(body,sourceTile("3D Assets","Koikatsu cards · Gumroad · Models",
            R.drawable.ic_source_koikatsu,theme.assetsLilac,"Assets"),10)
        val summary=card()
        val xCount=XPhotoQueue(this).use{it.allSources().size}
        val kyoraCount=GalleryDb(this).use{it.listEntities().size}
        val artistCount=AppDb(this).use{it.listArtists().size}
        put(summary,text("$artistCount Pixiv artists · $xCount X accounts · $kyoraCount cosplay sources",
            12f,theme.secondary),0)
        put(summary,button("OPEN DOWNLOADED FILES"){open("Library")},7)
        put(body,summary,12)
    }
    private fun renderBooks(){
        section("Books & Comics","EPUB for books · ZIP for comic chapters.")
        val switches=theme.row(this)
        switches.addView(theme.chip(this,"EPUB",bookMode=="EPUB"){bookMode="EPUB";render()},
            LinearLayout.LayoutParams(0,-2,1f))
        switches.addView(theme.chip(this,"COMICS · ZIP",bookMode=="COMICS"){bookMode="COMICS";render()},
            LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(9)})
        put(body,switches,10)
        if(bookMode=="EPUB"){
            SourceIndexPanel.render(this,body,"BOOKS")
            put(body,button("SEARCH EPUB LIBRARY"){open("Search")},11)
        }else {
            AsterionComicFollowing.render(this,body){render()}
            SourceIndexPanel.render(this,body,"COMICS")
        }
    }
    private fun renderTorrents(){
        section("Torrents & Games","Search actual releases, inspect files, then transfer.")
        val choices=listOf("GAMES" to TorrentArea.GAMES,
            "ADULT VIDEO" to TorrentArea.ADULT,"EPUB" to TorrentArea.BOOKS)
        val line=theme.row(this)
        for((label,area) in choices){
            line.addView(theme.chip(this,label,torrentMode==area){
                torrentMode=area;render()
            },LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(6)})
        }
        put(body,line,9)
        TorrentSearchUi.render(this,body,torrentMode)
        if(torrentMode==TorrentArea.GAMES){
            put(body,button("PLAY / PORT A GAME TO ANDROID"){
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                    type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
                },5210)
            },11)
        }
        SourceIndexPanel.render(this,body,"GAMES")
    }
    private fun renderToonily(){
        section("Comics · Toonily / MangaRead",
            "Archive accessible Toonily or MangaRead chapters into one flat, ordered ZIP.")
        val inputCard=card()
        put(inputCard,text("Full series download",18f,theme.white,true),0)
        put(inputCard,text("Paste a Toonily or mangaread.org manga series URL. Chapter order and decimal chapters are preserved.",
            12f,theme.secondary))
        val input=edit("https://toonily.com/serie/...")
        put(inputCard,input)
        put(inputCard,theme.action(this,"SAVE ALL CHAPTERS AS ZIP",true){
            val url=input.text.toString().trim()
            if(!WebDiscovery.isUrl(url)){toast("Enter a valid series or chapter URL");return@action}
            val slug=runCatching{
                val pieces=URI(url).path.trim('/').split('/')
                if(pieces.firstOrNull() in listOf("serie","series","manga","manhwa"))
                    pieces.getOrNull(1) else pieces.lastOrNull()
            }.getOrNull().orEmpty().ifBlank{"Manhwa series"}
            val added=ChapterQueue(this).use{it.enqueue(url,slug)}
            if(added)ChapterArchives.start(this)
            toast(if(added)"Series ZIP queued" else "Series is already in downloads")
            render()
        })
        put(inputCard,button("OPEN SOURCE IN BROWSER / SAVE SESSION"){
            val raw=input.text.toString().trim()
            val address=if(raw.isBlank())"https://toonily.com/" else raw
            if(!com.azrael.galleryflow.SiteSession.validLoginUrl(address)){
                toast("Enter a valid HTTPS source");return@button
            }
            startActivity(Intent(this,com.azrael.galleryflow.SiteLoginActivity::class.java)
                .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,address))
        })
        put(inputCard,button("OPEN MANGAREAD"){
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.mangaread.org/")))
        })
        put(body,inputCard,12)
        section("Series downloads","Progress and site-specific access errors are shown here.")
        val jobs=ChapterQueue(this).use{it.list().take(25)}
        if(jobs.isEmpty())put(body,text("No manhwa series queued yet.",13f,theme.secondary),10)
        for(job in jobs){
            val c=card()
            put(c,text(job.title,16f,theme.white,true),0)
            put(c,text(job.state.uppercase()+" · "+job.progress+"% · "+
                job.attempts+" attempt(s)",12f,theme.secondary))
            if(!job.error.isNullOrBlank())
                put(c,text(job.error.take(330),12f,theme.danger))
            if(job.state=="error"){
                put(c,button("RETRY ZIP"){
                    ChapterQueue(this).use{it.retry(job.id)}
                    ChapterArchives.start(this)
                    render()
                })
                if(job.error.orEmpty().contains("403") ||
                    job.error.orEmpty().contains("browser verification",true)){
                    put(c,button("OPEN SOURCE BROWSER & RETRY"){
                        pendingChapterRetry=job.id
                        startActivityForResult(
                            Intent(this,com.azrael.galleryflow.SiteLoginActivity::class.java)
                                .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,job.url),
                            chapterSessionRequest)
                    })
                }
            }
            if(job.state=="done" && !job.savedUri.isNullOrBlank())
                put(c,text("ZIP saved to Downloads / AsterionDownloader / Comics",
                    12f,theme.primary))
            put(body,c,10)
        }
    }
    private fun renderAdd(){
        section("Video tools","Performer search, monitored sources, native torrent downloads and direct video transfers.")
        val byLink=card()
        put(byLink,text("Download a video URL",18f,theme.white,true),0)
        put(byLink,text("Video sites and direct files. Other supported links are routed automatically.",
            12f,theme.secondary))
        val input=edit("Paste a video URL")
        val clip=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        put(byLink,input)
        put(byLink,theme.action(this,"DOWNLOAD LINK",true){
            submitInput(input.text.toString().trim())
        })
        put(byLink,button("PASTE FROM CLIPBOARD"){
            val value=clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if(value.isNotBlank())input.setText(value) else toast("Clipboard is empty")
        })
        put(byLink,button("SIGN IN TO A VIDEO WEBSITE"){
            val address=input.text.toString().trim()
            if(!com.azrael.galleryflow.SiteSession.validLoginUrl(address)){
                toast("Paste the HTTPS website or video URL first");return@button
            }
            startActivity(Intent(this,com.azrael.galleryflow.SiteLoginActivity::class.java)
                .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,address))
        })
        put(byLink,text("Signed-in downloads can use the access already available to your account. The app cannot unlock another website's paid content.",12f,theme.secondary))
        put(body,byLink,12)
        GeneralVideoSearch.render(this,body)
        CreatorArchiveUi.render(this,body,ArchiveSection.VIDEOS)

        val general=card()
        put(general,text("VIDEOFLOW · URL AND CUSTOM SOURCE LISTS",17f,theme.white,true))
        put(general,text("For general video downloads, paste a direct video URL or follow a specific channel. Adult provider searches are in Porn & Hentai.",12f,theme.secondary))
        put(general,button("OPEN VIDEOFLOW SOURCES & DOWNLOADS"){
            startActivity(Intent(this,com.asterion.videoflow.MainActivity::class.java))
        })
        put(body,general,10)
    }
    private fun renderPorn(){
        section("Porn & Hentai","Adult site libraries, performer tracking, creator video archives and torrent search.")
        WatchPornPanel.render(this,body)
        CreatorArchiveUi.render(this,body,ArchiveSection.VIDEOS)
        TorrentSearchUi.render(this,body,TorrentArea.ADULT)
        val byName=card()
        put(byName,text("Search videos by name",18f,theme.white,true),0)
        put(byName,text("Search anime videos, adult videos or torrent indexes; torrent results can open in a BitTorrent client.",
            12f,theme.secondary))
        val searchPrefs=getSharedPreferences("asterion_video_search",MODE_PRIVATE)
        val query=edit("Creator, title or keywords")
        query.setText(searchPrefs.getString("last_query",""))
        put(byName,query)
        put(byName,text("SEARCH AREA",12f,theme.secondary,true),9)
        val scopes=listOf(
            "Hentai" to VideoSearchArea.HENTAI,
            "Porn" to VideoSearchArea.PORN,
            "Piracy / Darkweb" to VideoSearchArea.PIRACY_DARKWEB
        )
        val selectedArea=searchPrefs.getString("area","PORN").orEmpty()
            .takeIf{it in listOf("HENTAI","PORN","PIRACY_DARKWEB")} ?: "PORN"
        if(selectedArea!=searchPrefs.getString("area",""))
            searchPrefs.edit().putString("area",selectedArea).apply()
        val activeArea=text("Selected: "+scopes.firstOrNull{it.second.name==selectedArea}
            ?.first.orEmpty().ifBlank{"Porn"},12f,theme.primary)
        put(byName,activeArea,3)
        for(group in scopes.chunked(3)){
            val r=theme.row(this)
            group.forEachIndexed { index,pair ->
                val b=button(pair.first) {
                    searchPrefs.edit().putString("area",pair.second.name).apply()
                    activeArea.text="Selected: "+pair.first
                }
                r.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{
                    if(index>0)leftMargin=dp(5)
                })
            }
            put(byName,r,6)
        }
        val follow=CheckBox(this).apply{
            text="Keep checking for new videos"
            setTextColor(theme.secondary)
            isChecked=false
        }
        put(byName,follow)
        put(byName,theme.action(this,"SEARCH VIDEOS",true){
            val value=query.text.toString().trim()
            if(value.isBlank()){toast("Enter a name or keywords");return@action}
            val area=runCatching{VideoSearchArea.valueOf(
                searchPrefs.getString("area","PORN") ?: "PORN")}
                .getOrDefault(VideoSearchArea.PORN)
            val queryKey=area.name+":"+value
            searchPrefs.edit().putString("last_query",value)
                .remove("last_error").apply()
            if(follow.isChecked)NameWatchDb(this).use{it.add(value)}
            toast("Searching your saved video sources…")
            Thread {
                val outcome=runCatching{KeywordSources.searchDetailed(applicationContext,value,
                    area)}
                outcome.onSuccess { result->
                    SearchResultsDb(applicationContext).use{it.save(queryKey,result.hits)}
                    val failures=result.failures.take(5).joinToString(" | ")
                    searchPrefs.edit().putString("last_error",failures).apply()
                    runOnUiThread{
                        toast("${result.hits.size} matches from ${result.checked} sources")
                        render()
                    }
                }.onFailure{error->
                    searchPrefs.edit().putString("last_error",error.message).apply()
                    runOnUiThread{toast("Search error: ${error.message}");render()}
                }
            }.start()
        })
        put(body,byName,12)

        val lastQuery=searchPrefs.getString("last_query","").orEmpty()
        val queryKey=selectedArea+":"+lastQuery
        val hits=if(lastQuery.isBlank())emptyList() else
            SearchResultsDb(this).use{it.listForQuery(queryKey,60)}
        val sourceErrors=searchPrefs.getString("last_error","").orEmpty()
        if(lastQuery.isNotBlank()){
            section("Results for: "+lastQuery,
                "${hits.size} saved candidates · ranked by match. Results are not completed downloads.")
            if(sourceErrors.isNotBlank())put(body,
                text("Source errors: "+sourceErrors.take(350),12f,theme.danger))
            if(hits.isEmpty())put(body,
                text("No matching videos indexed yet. Check the source errors or search another name.",
                    13f,theme.secondary))
            for(hit in hits.take(25)){
                val c=card()
                val thumbnail=ImageView(this).apply{
                    minimumHeight=dp(124)
                    setBackgroundColor(theme.raised)
                    adjustViewBounds=true
                    layoutParams=LinearLayout.LayoutParams(-1,dp(160))
                }
                put(c,thumbnail,0)
                VideoSearchPreviews.display(this,thumbnail,hit.thumbnail)
                put(c,text(hit.title,16f,theme.white,true),8)
                val isIndex=SearchRanking.isIndexPage(hit.url)
                put(c,text(hit.source+" · "+(if(isIndex)"Indexed source page" else "Video result"),
                    12f,theme.primary))
                put(c,text(hit.url.take(120),11f,theme.subdued))
                if(isIndex){
                    put(c,button("OPEN TORRENT SOURCE"){
                        ResourceDownloads.openTorrentSource(this,hit.url)
                    })
                }else if(!hit.queued){
                    put(c,button("DOWNLOAD THIS RESULT"){
                        val added=VideoDb(this).use{it.addVideo(hit.url,hit.title)}
                        if(added){
                            SearchResultsDb(this).use{it.markQueued(hit.query,hit.url)}
                            VideoEngine.launch(this,false)
                        }
                        toast(if(added)"Added to downloads" else "Already in downloads")
                        open("Queue")
                    })
                }else put(c,text("Already added to downloads",12f,theme.primary))
                put(body,c,8)
            }
        }
    }
    private fun submitInput(raw:String){
        val value=raw.trim()
        if(!WebDiscovery.isUrl(value)){
            toast("Enter a valid HTTP(S) link");return
        }
        when(SourceRouter.classify(value)){
            DownloadKind.PIXIV->{
                val id=SourceRouter.pixivArtistId(value)
                if(id==null){toast("Add a Pixiv artist profile URL");return}
                AppDb(this).use{it.addArtist(id)}
                val sync=Intent(this,SyncForegroundService::class.java).apply{
                    action=SyncForegroundService.ACTION_START
                    putExtra(SyncForegroundService.EXTRA_MODE,"BACKFILL")
                    putExtra(SyncForegroundService.EXTRA_TARGET_ARTIST_ID,id)
                }
                startForegroundService(sync)
                toast("Pixiv artist added")
            }
            DownloadKind.X->{
                val ok=XPhotoQueue(this).use{it.addSource(value)}
                if(ok){XPhotoScheduler.ensure(this);XPhotoEngine.start(this)}
                toast(if(ok)"X images queued" else "X account/post already saved")
            }
            DownloadKind.COMIC->{
                val ok=ChapterQueue(this).use{it.enqueue(value,URI(value).path.takeLast(70))}
                if(ok)ChapterArchives.start(this)
                toast(if(ok)"Chapter ZIP queued" else "Archive already in queue")
            }
            DownloadKind.VIDEO->{
                val ok=VideoDb(this).use{it.addVideo(value,value)}
                if(ok)VideoEngine.launch(this,false)
                toast(if(ok)"Video queued" else "Already in video history")
            }
            DownloadKind.GALLERY->{
                toast("Resolving gallery…")
                Thread{
                    val result=runCatching{
                        val adapter=AdapterRegistry.forUrl(value)
                        val entity=adapter.resolveEntity(value)
                        GalleryDb(this).use{it.addEntity(entity)}
                        val service=Intent(this,com.azrael.galleryflow.SyncForegroundService::class.java)
                            .setAction(com.azrael.galleryflow.SyncForegroundService.ACTION_START)
                            .putExtra(com.azrael.galleryflow.SyncForegroundService.EXTRA_MODE,SyncMode.BACKFILL.name)
                            .putExtra(com.azrael.galleryflow.SyncForegroundService.EXTRA_SITE_ID,entity.stableId)
                        startForegroundService(service)
                    }
                    runOnUiThread{toast(if(result.isSuccess)"Gallery queued" else
                        "Gallery error: ${result.exceptionOrNull()?.message}")}
                }.start()
            }
        }
        page="Queue";render()
    }
    private fun renderQueue(){
        section("Downloads","Queued, downloading, completed and failed files in one place.")
        val videos=VideoDb(this).use{it.listVideos(200)}
        val comics=ChapterQueue(this).use{it.list()}
        val xphotos=XPhotoQueue(this).use{it.recent(100)}
        val artists=AppDb(this).use{it.listArtists()}
        val galleries=GalleryDb(this).use{it.listEntities()}
        put(body,text("${videos.count{it.status=="running"||it.status=="queued"}+
            comics.count{it.state=="working"||it.state=="queued"}+
            xphotos.count{it.state=="running"||it.state=="queued"}} pending   •   "+
            "${videos.count{it.status=="error"}+comics.count{it.state=="error"}+xphotos.count{it.state=="error"}} failed",
            14f,theme.primary,true))
        DirectDownloadTracker.render(this,body)
        val actions=theme.row(this)
        actions.addView(button("RETRY VIDEOS"){VideoEngine.launch(this,false)},
            LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(button("RETRY CHAPTERS"){ChapterArchives.start(this)},
            LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(8)})
        put(body,actions)
        put(body,button("RETRY X IMAGES"){XPhotoEngine.start(this)})
        xphotos.take(35).forEach{photo->
            val c=card()
            put(c,text("X IMAGE · ${photo.state.uppercase()}",13f,theme.primary,true),0)
            put(c,text(photo.photo,11f,theme.secondary))
            if(photo.error!=null)put(c,text(photo.error,11f,theme.danger))
            if(photo.state=="error")put(c,button("RETRY"){
                XPhotoQueue(this).use{it.retry(photo.id)}
                XPhotoEngine.start(this);render()
            })
            if(photo.saved!=null)put(c,button("OPEN"){openFile(photo.saved,"image/*")})
            put(body,c,9)
        }
        videos.take(70).forEach{v->
            val c=card()
            put(c,text("VIDEO  ·  ${v.status.uppercase()}  ·  ${v.progress}%",13f,theme.primary,true),0)
            put(c,text(v.title.take(120),13f))
            if(v.error!=null)put(c,text(v.error.take(150),11f,theme.danger))
            if(v.status=="error")put(c,button("RETRY"){
                VideoDb(this).use{it.retry(v.id)}
                VideoEngine.launch(this,false);render()
            })
            if(v.savedUri!=null)put(c,button("OPEN"){
                openFile(v.savedUri,"video/*")
            })
            put(body,c,9)
        }
        comics.take(70).forEach{v->
            val c=card()
            put(c,text("CHAPTER ZIP  ·  ${v.state.uppercase()}  ·  ${v.progress}%",13f,theme.violet,true),0)
            put(c,text(v.title,13f))
            if(v.error!=null)put(c,text(v.error.take(150),11f,theme.danger))
            if(v.state=="error")put(c,button("RETRY"){
                ChapterQueue(this).use{it.retry(v.id)}
                ChapterArchives.start(this);render()
            })
            if(v.savedUri!=null)put(c,button("OPEN"){openFile(v.savedUri,"application/zip")})
            put(body,c,9)
        }
        put(body,text("${artists.size} Pixiv artists  ·  ${galleries.size} image sources",13f,theme.secondary))
        artists.take(20).forEach{a->put(body,text("PIXIV  ·  ${a.label?:a.userId}  ·  ${a.archiveStatus}",12f))}
        galleries.take(20).forEach{g->put(body,text("IMAGES  ·  ${g.displayName}",12f))}
    }
    private fun openFile(uri:String,mime:String){
        runCatching{startActivity(Intent(Intent.ACTION_VIEW).apply{
            setDataAndType(Uri.parse(uri),mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })}.onFailure{toast("No app is available to open this file")}
    }
    private fun renderLibrary(){
        section("My files","Downloaded images, videos and chapter ZIPs.")
        val projection=arrayOf(MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,MediaStore.Downloads.SIZE)
        runCatching{
            contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,projection,
                null,null,"${MediaStore.Downloads.DATE_ADDED} DESC")?.use{c->
                var count=0
                while(c.moveToNext()&&count<100){
                    val dir=c.getString(2).orEmpty()
                    if(!listOf("VideoFlow","Kuroha","NocturnePixiv","GalleryFlow",
                            "NocturneGallery","AsterionDownloader").any{dir.contains(it,true)})
                        continue
                    val item=card()
                    val name=c.getString(1).orEmpty()
                    val id=c.getLong(0)
                    val uri=ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,id)
                    put(item,text(name,14f,theme.white,true),0)
                    put(item,text("${dir} · ${c.getLong(3)/1024} KB",11f,theme.secondary))
                    put(item,button("OPEN"){
                        openFile(uri.toString(),if(name.endsWith(".zip",true))
                            "application/zip" else if(name.endsWith(".mp4",true))
                            "video/*" else "image/*")
                    })
                    put(body,item,8);count++
                }
                if(count==0)put(body,text("No visible downloaded media yet.",13f,theme.secondary))
            }
        }.onFailure{put(body,text("Library could not be read: ${it.message}",12f,theme.danger))}
    }
    private fun renderSources(){
        section("Monitored sources","Pixiv artists, image sites, video accounts and name searches.")
        put(body,button("SCAN X PHOTOS"){XPhotoEngine.start(this)})
        val watches=VideoDb(this).use{it.listWatches()}
        for(w in watches){
            val c=card()
            put(c,text(w.label,14f,theme.white,true),0)
            put(c,text(w.address,11f,theme.secondary))
            put(c,button(if(w.enabled)"PAUSE SOURCE" else "ENABLE SOURCE"){
                VideoDb(this).use{it.enableWatch(w.id,!w.enabled)};render()
            })
            put(body,c,9)
        }
        NameWatchDb(this).use{db->
            for(w in db.list()){
                val c=card()
                put(c,text("NAME · ${w.name}",14f,theme.white,true),0)
                put(c,text(if(w.source.isBlank())"Internet Archive public search"
                    else w.source,11f,theme.secondary))
                if(w.lastError!=null)put(c,text(w.lastError,11f,theme.danger))
                put(c,button(if(w.enabled)"PAUSE SEARCH" else "ENABLE SEARCH"){
                    db.setEnabled(w.id,!w.enabled);render()
                })
                put(body,c,9)
            }
        }
        put(body,button("SCAN ENABLED SOURCES"){VideoEngine.launch(this,true)})
        val discoveries=SearchResultsDb(this).use{it.list(60)}
        if(discoveries.isNotEmpty()){
            put(body,text("SEARCH RESULTS",17f,theme.white,true),21)
            put(body,text("Candidates include original source URLs. A result is not a completed download.",12f,theme.secondary))
            for(hit in discoveries){
                val c=card()
                put(c,text(hit.title,14f,theme.white,true),0)
                put(c,text("${hit.source} · ${hit.query} · relevance ${hit.score}",
                    11f,theme.secondary))
                put(c,text(hit.url,11f,theme.subdued))
                if(!hit.queued)put(c,button("QUEUE THIS VIDEO"){
                    val added=VideoDb(this).use{it.addVideo(hit.url,hit.title)}
                    if(added){
                        SearchResultsDb(this).use{it.markQueued(hit.query,hit.url)}
                        VideoEngine.launch(this,false)
                    }
                    toast(if(added)"Video queued" else "Already in video download history")
                    render()
                })
                put(body,c,9)
            }
        }
    }
    private fun renderSettings(){
        section("Settings","Appearance, downloads, privacy and source controls.")
        AsterionPeerTrust.render(this,body)
        val appearance=getSharedPreferences("asterion_display",MODE_PRIVATE)
        val visual=card()
        put(visual,text("DISPLAY & NAVIGATION",16f,theme.white,true),0)
        fun appearanceSwitch(label:String,key:String,default:Boolean){
            val switch=Switch(this).apply{
                text=label
                textSize=13f
                setTextColor(theme.white)
                isChecked=appearance.getBoolean(key,default)
                setOnCheckedChangeListener{_,value->
                    appearance.edit().putBoolean(key,value).apply()
                    render()
                }
            }
            put(visual,switch,8)
        }
        appearanceSwitch("Compact cards (less scrolling)","compact",true)
        appearanceSwitch("Show image and video previews","previews",true)
        appearanceSwitch("Larger preview covers","large_covers",false)
        val start=appearance.getString("start_page","Home").orEmpty()
        put(visual,button("START TAB: "+when(start){
            "Queue"->"Downloads";"Recommend"->"For You";else->"Home"
        }){
            val choices=arrayOf("Home","Downloads","For You")
            val keys=arrayOf("Home","Queue","Recommend")
            AlertDialog.Builder(this).setTitle("Open Asterion on")
                .setSingleChoiceItems(choices,keys.indexOf(start).coerceAtLeast(0)){dialog,index->
                    appearance.edit().putString("start_page",keys[index]).apply()
                    dialog.dismiss();render()
                }.setNegativeButton("Cancel",null).show()
        },9)
        put(body,visual,10)
        val netPrefs=getSharedPreferences("video_options",MODE_PRIVATE)
        val tor=Switch(this).apply{
            text="Use Orbot / Tor for hidden (.onion) sites"
            setTextColor(theme.white)
            isChecked=netPrefs.getBoolean("tor_proxy",false)
            setOnCheckedChangeListener{_,value->
                netPrefs.edit().putBoolean("tor_proxy",value).apply()
            }
        }
        put(body,tor)
        val prefs=getSharedPreferences("video_options",MODE_PRIVATE)
        put(body,text("VIDEO DISCOVERY",16f,theme.white,true),11)
        put(body,text("Search runs across integrated video sites and your saved video watches automatically. No search-provider setup is needed.",
            12f,theme.secondary),6)
        put(body,button("MANAGE SAVED SEARCHES"){page="Sources";render()})
        val quality=card()
        put(quality,text("VIDEO QUALITY",14f,theme.white,true),0)
        val choices=listOf("Best MP4","Best available","1080p max","720p max","Audio only")
        val values=listOf("best[ext=mp4]/best","best",
            "bestvideo[height<=1080]+bestaudio/best[height<=1080]",
            "bestvideo[height<=720]+bestaudio/best[height<=720]","bestaudio/best")
        val spinner=Spinner(this)
        spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices)
        spinner.setSelection(values.indexOf(prefs.getString("quality",values[0])).coerceAtLeast(0))
        put(quality,spinner)
        put(quality,button("SAVE QUALITY"){
            prefs.edit().putString("quality",values[spinner.selectedItemPosition]).apply()
            toast("Quality saved")
        })
        put(body,quality)
        val advanced=appearance.getBoolean("advanced_open",false)
        put(body,button(if(advanced)"HIDE LEGACY ENGINE TOOLS"
            else "SHOW LEGACY ENGINE TOOLS"){
            appearance.edit().putBoolean("advanced_open",!advanced).apply()
            render()
        },12)
        if(advanced){
            val settings=card()
            put(settings,text("ADVANCED COMPATIBILITY",14f,theme.white,true),0)
            put(settings,text("These are existing original-engine utilities within the "+
                "same APK. Normal use stays in Asterion's unified modules.",
                12f,theme.secondary),6)
            put(settings,button("Pixiv filters and account tools"){
                startActivity(Intent(this,MainActivity::class.java))
            },9)
            put(settings,button("Kyora gallery configuration"){
                startActivity(Intent(this,com.azrael.galleryflow.MainActivity::class.java))
            },6)
            put(settings,button("VideoFlow compatibility settings"){
                startActivity(Intent(this,com.asterion.videoflow.MainActivity::class.java))
            },6)
            put(body,settings,10)
        }
        AlohaSources.render(this,body,listOf(AlohaArea.STUDY),"RELATED SAVED REFERENCE")
        put(body,text("Cloud uploads are not marked verified without confirmation from the destination. Originals are retained.",12f,theme.secondary))
    }
}
