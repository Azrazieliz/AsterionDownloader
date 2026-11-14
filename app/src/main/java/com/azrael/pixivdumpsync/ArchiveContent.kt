package com.azrael.pixivdumpsync

enum class ArchiveSection(val label:String,val folder:String) {
    ARTWORK("Artwork","Artwork"), COSPLAY("Kyora","Cosplay"),
    VIDEOS("Videos","Videos"), ASSETS("Assets","Assets"),
    BOOKS("Books","Books"), GAMES("Games","Games")
}
object ArchiveContent {
    fun category(file:ArchiveFile,post:ArchivePost):ArchiveSection {
        val name=file.title.lowercase()
        val type=name.substringAfterLast('.', "")
        val context=(name+" "+post.title+" "+post.creator.service).lowercase()
        if(type in setOf("blend","fbx","obj","glb","gltf","vrm","pmx","pmd",
            "stl","dae","usd","usdz","mtl","exr","hdr","dds","anim","bvh","unitypackage"))
            return ArchiveSection.ASSETS
        if(type in setOf("mp4","mkv","webm","mov","avi","m4v","ts"))
            return ArchiveSection.VIDEOS
        if(type in setOf("epub","pdf","cbz","cbr","mobi","azw3","djvu"))
            return ArchiveSection.BOOKS
        if(type in setOf("apk","rpa","xp3","nsa","ns2","rpyc","exe","obb"))
            return ArchiveSection.GAMES
        if(type in setOf("zip","rar","7z","tar","gz")){
            if(listOf("model","blender","texture","koikatsu","asset","rig","mesh","gumroad").any{it in context})
                return ArchiveSection.ASSETS
            if(listOf("novel","book","manga","chapter","comic").any{it in context})
                return ArchiveSection.BOOKS
            if(listOf("game","eroge","visual novel","renpy").any{it in context})
                return ArchiveSection.GAMES
        }
        if(listOf("cosplay","photoset","gravure","photobook").any{it in context})
            return ArchiveSection.COSPLAY
        return if(post.creator.service.lowercase()=="gumroad") ArchiveSection.ASSETS
            else ArchiveSection.ARTWORK
    }
    fun filtered(section:ArchiveSection,post:ArchivePost):ArchivePost=
        post.copy(files=post.files.filter{category(it,post)==section})
    fun preview(post:ArchivePost):String?=post.files.firstOrNull{
        it.title.lowercase().substringAfterLast('.') in setOf("jpg","jpeg","png","webp","gif")
    }?.url
}