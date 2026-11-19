package com.azrael.pixivdumpsync

import java.nio.charset.StandardCharsets

/**
 * Bounded, pure-JVM metadata inspection. Showing files in an existing .torrent
 * MUST NOT initialize libtorrent JNI on the owner's phone.
 *
 * This decodes v1 descriptors only. Unsupported v2-only descriptors fail
 * explicitly rather than pretending that a file tree was verified.
 */
internal object BencodedTorrentManifest {
    data class Entry(val path:String,val size:Long)
    data class Manifest(val name:String,val files:List<Entry>,val infoHashHex:String)

    fun parse(bytes:ByteArray):Manifest {
        require(bytes.size in 33..8_388_608){"Torrent descriptor exceeds safe limits"}
        val decoder=Decoder(bytes)
        val root=decoder.value(0) as? Map<*,*>
            ?:throw IllegalArgumentException("Torrent root is not a dictionary")
        require(decoder.complete()){"Torrent has trailing data"}
        val info=root["info"] as? Map<*,*>
            ?:throw IllegalArgumentException("Torrent contains no info dictionary")
        val name=part(info["name.utf-8"]?:info["name"])
        val entries=ArrayList<Entry>()
        val multi=info["files"] as? List<*>
        if(multi!=null){
            require(multi.size in 1..20_000){"Torrent file count is unsafe"}
            for(item in multi){
                val row=item as? Map<*,*>
                    ?:throw IllegalArgumentException("Invalid torrent file entry")
                require(!partOrEmpty(row["attr"]).contains('l')){
                    "Symbolic link entries are not supported"
                }
                val length=row["length"] as? Long
                    ?:throw IllegalArgumentException("Missing torrent file size")
                require(length>=0){"Invalid torrent file size"}
                val path=(row["path.utf-8"]?:row["path"]) as? List<*>
                    ?:throw IllegalArgumentException("Missing torrent file path")
                require(path.isNotEmpty() && path.size<=48){"Unsafe torrent path depth"}
                val components=path.map(::part)
                entries.add(Entry((listOf(name)+components).joinToString("/"),length))
            }
        }else{
            val length=info["length"] as? Long
                ?:throw IllegalArgumentException(
                    "Unsupported torrent metadata (v2-only or no v1 files)")
            require(length>=0){"Invalid torrent file size"}
            entries.add(Entry(name,length))
        }
        val paths=HashSet<String>()
        var total=0L
        for(file in entries){
            require(paths.add(file.path)){"Duplicate torrent file path"}
            require(file.size<=Long.MAX_VALUE-total){"Torrent size overflow"}
            total+=file.size
        }
        require(entries.isNotEmpty()){"No selectable torrent files"}
        return Manifest(name,entries,decoder.infoHashHex())
    }

    private fun part(value:Any?):String {
        val bytes=value as? ByteArray
            ?:throw IllegalArgumentException("Invalid torrent name/path")
        require(bytes.size in 1..1024){"Unsafe torrent name length"}
        val text=String(bytes,StandardCharsets.UTF_8)
        require(text.isNotBlank() && text!="." && text!=".." &&
            text.length<=255 &&
            text.none{it=='/' || it=='\\' || it==':' || it.code<32 ||
                it.code==127 || it=='\u0000'}){
            "Unsafe torrent path component"
        }
        return text
    }
    private fun partOrEmpty(value:Any?):String=
        (value as? ByteArray)?.let{String(it,StandardCharsets.UTF_8)}.orEmpty()

    private class Decoder(private val data:ByteArray) {
        private var index=0
        private var nodes=0
        private var rawInfo:ByteArray?=null
        fun infoHashHex():String {
            val bytes=rawInfo?:error("No captured info dictionary")
            return java.security.MessageDigest.getInstance("SHA-1")
                .digest(bytes).joinToString(""){"%02x".format(it)}
        }
        fun complete()=index==data.size
        fun value(depth:Int):Any {
            require(depth<=48 && ++nodes<=150_000){"Torrent metadata too complex"}
            require(index<data.size){"Truncated torrent descriptor"}
            return when(val tag=data[index].toInt().toChar()){
                'd'->{
                    index++
                    val result=LinkedHashMap<String,Any>()
                    while(current()!='e'){
                        val key=String(bytes(),StandardCharsets.ISO_8859_1)
                        require(!result.containsKey(key)){"Duplicate metadata key"}
                        if(depth==0 && key=="info"){
                            val from=index
                            result[key]=value(depth+1)
                            rawInfo=data.copyOfRange(from,index)
                        }else result[key]=value(depth+1)
                    }
                    index++
                    result
                }
                'l'->{
                    index++
                    val result=ArrayList<Any>()
                    while(current()!='e'){
                        require(result.size<30_000){"Too many metadata entries"}
                        result.add(value(depth+1))
                    }
                    index++
                    result
                }
                'i'->{
                    index++
                    val start=index
                    while(current()!='e'){
                        require(data[index].toInt().toChar() in "-0123456789"){
                            "Invalid torrent integer"
                        }
                        index++
                        require(index-start<=21){"Torrent integer overflow"}
                    }
                    val number=String(data,start,index-start,StandardCharsets.US_ASCII)
                        .toLongOrNull()?:throw IllegalArgumentException("Bad torrent integer")
                    index++
                    number
                }
                in '0'..'9'->bytes()
                else->throw IllegalArgumentException("Invalid bencode token")
            }
        }
        private fun current():Char {
            require(index<data.size){"Truncated torrent metadata"}
            return data[index].toInt().toChar()
        }
        private fun bytes():ByteArray{
            val start=index
            while(current()!=':'){
                require(current() in '0'..'9' && index-start<=8){
                    "Invalid bencoded string size"
                }
                index++
            }
            val count=String(data,start,index-start,StandardCharsets.US_ASCII)
                .toIntOrNull()?:throw IllegalArgumentException("Bad metadata size")
            index++
            require(count>=0 && count<=8_388_608 &&
                count<=data.size-index){"Bencoded string out of bounds"}
            val value=data.copyOfRange(index,index+count)
            index+=count
            return value
        }
    }
}
