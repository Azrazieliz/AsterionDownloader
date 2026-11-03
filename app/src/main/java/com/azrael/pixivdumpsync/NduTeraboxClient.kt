package com.azrael.pixivdumpsync

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

/**
 * Unofficial TeraBox web uploader based on the documented open-source
 * OpenList/rclone-extra flows. NDUS remains on device; all requests are HTTPS
 * and restricted to TeraBox hosts. No browser, Round Sync, or secondary copy.
 */
class NduTeraboxClient(private val ndus: String,
                      private val domain: String = "www.terabox.com") {
    private var jsToken = ""
    private val createdFolders = hashSetOf<String>()
    private val base = "https://$domain"
    private val userAgent = "terabox;1.37.0.7;PC;PC-Windows;10.0.22631;WindowsTeraBox"

    init { require(domain in setOf("www.terabox.com","nephobox.com")) }

    companion object {
        const val CHUNK_SIZE = 4*1024*1024
        /** TeraBox increases part size above 4 GiB to keep block counts bounded.
         * Matching the provider's transfer strategy avoids oversize block lists for games.
         */
        internal fun chunkSizeFor(fileSize:Long):Int {
            require(fileSize>0)
            var chunk=CHUNK_SIZE.toLong()
            var threshold=4L*1024*1024*1024
            while(fileSize>threshold){
                chunk*=2
                threshold*=2
            }
            return chunk.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        // Values used by the open-source TeraBox web backend for precreate.
        private const val DUMMY_MD5_A = "5910a591dd8fc18c32a8f3df4fdc1761"
        private const val DUMMY_MD5_B = "a5fc157d78e6ad1c7e114b056c92821e"
        fun checkedUploadHost(host: String): String {
            val clean = host.trim().lowercase(Locale.ROOT)
            require(clean.matches(Regex("[a-z0-9.-]{4,200}")) &&
                (clean.endsWith(".terabox.com") || clean.endsWith(".nephobox.com"))) {
                "Upload endpoint was not a trusted TeraBox host"
            }
            return clean
        }
        fun safeFileName(raw: String): String {
            val name = raw.substringAfterLast('/').trim()
            require(name.length in 1..180 && !name.contains("..") &&
                name.none { it in "\\/\r\n\t" || it.code < 32 }) { "Invalid filename" }
            return name
        }
        fun formEncode(values: Map<String,String>): String =
            values.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key,"UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value,"UTF-8")
            }
        fun partHashes(bytes: ByteArray): String = MessageDigest.getInstance("MD5")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun connection(url: String, method: String, attachCookie: Boolean): HttpURLConnection {
        val uri = Uri.parse(url)
        val host=uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(uri.scheme=="https" &&
            (host=="www.terabox.com" || host=="nephobox.com" ||
             host.endsWith(".terabox.com") || host.endsWith(".nephobox.com"))) {
            "Refusing untrusted TeraBox endpoint"
        }
        val conn=URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects=false
        conn.requestMethod=method
        conn.connectTimeout=20000
        conn.readTimeout=60000
        conn.setRequestProperty("User-Agent",userAgent)
        conn.setRequestProperty("Accept","application/json, text/plain, */*")
        conn.setRequestProperty("Referer",base)
        conn.setRequestProperty("X-Requested-With","XMLHttpRequest")
        if (attachCookie) conn.setRequestProperty("Cookie","ndus=$ndus")
        return conn
    }
    private fun read(conn: HttpURLConnection): String {
        try {
            val status=conn.responseCode
            if (status !in 200..299)
                throw java.io.IOException("TeraBox HTTP $status")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1_000_000) }
        } finally { conn.disconnect() }
    }
    private fun urlFor(path: String, params: Map<String,String> = emptyMap()): String {
        val common=linkedMapOf("app_id" to "250528","web" to "1",
            "channel" to "dubox","clienttype" to "0")
        if (jsToken.isNotBlank()) common["jsToken"]=jsToken
        common.putAll(params)
        return base+path+"?"+formEncode(common)
    }
    private fun request(path: String, method: String="GET",
                        query: Map<String,String> = emptyMap(),
                        fields: Map<String,String>? = null): JSONObject {
        val c=connection(urlFor(path,query),method,true)
        if (fields!=null) {
            c.doOutput=true
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            val body=formEncode(fields).toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val text=read(c)
        return try { JSONObject(text) }
        catch (e: Exception) { throw java.io.IOException("TeraBox did not return JSON",e) }
    }
    private fun ok(json: JSONObject, step: String) {
        val code=json.optInt("errno",0)
        if (code!=0) throw java.io.IOException("$step rejected by TeraBox (code $code)")
    }

    fun connect() {
        // Check cookie before exposing any upload operation.
        val login=request("/api/check/login")
        ok(login,"NDUS authentication")
        // Web token is necessary for precreate/create. Sources sometimes ship
        // the token in a URL-encoded inline JS function.
        val c=connection(base,"GET",true)
        val html=read(c)
        val patterns=listOf(
            Regex("fn%28%22([a-zA-Z0-9_-]{8,})%22%29"),
            Regex("window\\.jsToken\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]"),
            Regex("[\"']jsToken[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
        )
        jsToken=patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }.orEmpty()
        if (jsToken.isBlank())
            throw java.io.IOException("NDUS login accepted, but TeraBox web token could not be read")
    }

    fun createDirectory(path: String) {
        val verified=NduSettings.validateDirectory(path)
        if (verified in createdFolders) return
        var dir=""
        for (segment in verified.split('/').drop(1)) {
            dir+="/$segment"
            val result=request("/api/create","POST",
                mapOf("a" to "commit"),
                mapOf("path" to dir,"isdir" to "1","block_list" to "[]"))
            if (result.optInt("errno",0)!=0) {
                // Directory already existing: verify via API before ignoring failure.
                val check=request("/api/list","GET",mapOf("dir" to dir,"page" to "1","num" to "1"))
                ok(check,"Check directory $dir")
            }
            createdFolders.add(dir)
        }
    }

    /** Do not mark an upload complete just because /api/create accepted the request.
     * Check that the remote object exists at the expected path with exact file size.
     * When a remote whole-file hash is available, require it to match as well.
     */
    private fun confirmRemote(path:String,size:Long,localMd5:String,
                              requireRemoteHash:Boolean=false):Boolean {
        repeat(4){attempt->
            val response=request("/api/filemetas","GET",mapOf(
                "target" to JSONArray(listOf(path)).toString(),"dlink" to "0"))
            ok(response,"Remote file verification")
            val files=response.optJSONArray("info")?:JSONArray()
            for(i in 0 until files.length()){
                val item=files.optJSONObject(i)?:continue
                if(item.optString("path")!=path ||
                    item.optLong("size",-1L)!=size ||
                    item.optLong("fs_id",0L)<=0L)continue
                val remoteMd5=item.optString("md5").trim()
                if(requireRemoteHash && remoteMd5.isBlank())continue
                if(remoteMd5.isNotBlank() &&
                    !remoteMd5.equals(localMd5,true))
                    throw java.io.IOException("TeraBox whole-file integrity mismatch")
                return true
            }
            if(attempt<3)Thread.sleep(1200L*(attempt+1))
        }
        return false
    }
    fun upload(context: Context, uri: Uri, fileName: String, categoryPath: String,
               cancelled: () -> Boolean = { false },
               progress: (Long,Long) -> Unit = { _,_ -> }) {
        val name=safeFileName(fileName)
        val folder=NduSettings.validateDirectory(categoryPath)
        val path="$folder/$name"
        val resolver=context.contentResolver
        val size=resolver.openFileDescriptor(uri,"r")?.use { it.statSize } ?: -1
        require(size>0) { "Empty or inaccessible local file" }
        require(size<=128L*1024*1024*1024) { "File larger than 128 GB" }
        createDirectory(folder)

        val blocks=if (size>CHUNK_SIZE) JSONArray(listOf(DUMMY_MD5_A,DUMMY_MD5_B))
            else JSONArray(listOf(DUMMY_MD5_A))
        val pre=request("/api/precreate","POST",emptyMap(),mapOf(
            "path" to path,"autoinit" to "1","target_path" to folder,
            "block_list" to blocks.toString(),"local_mtime" to (System.currentTimeMillis()/1000).toString(),
            "file_limit_switch_v34" to "true"))
        ok(pre,"Precreate")
        // Rapid upload is not proof that this file's bytes are present remotely.
        // Compute the actual content hash before accepting that response.
        if(pre.optInt("return_type")==2){
            val whole=MessageDigest.getInstance("MD5")
            resolver.openInputStream(uri)?.use { stream ->
                val buffer=ByteArray(128*1024)
                while(true){
                    if(cancelled())throw InterruptedException("Cloud upload cancelled")
                    val read=stream.read(buffer)
                    if(read<0)break
                    whole.update(buffer,0,read)
                }
            }?:throw java.io.IOException("Cannot read rapid-upload source")
            val md5=whole.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            if(!confirmRemote(path,size,md5,requireRemoteHash=true))
                throw java.io.IOException("Rapid-upload response did not prove identical remote content")
            progress(size,size)
            return
        }
        val uploadId=pre.optString("uploadid").takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("TeraBox did not return upload ID")
        val locate=JSONObject(read(connection(
            "https://jp-data.terabox.com/rest/2.0/pcs/file?method=locateupload",
            "GET",false)))
        val host=checkedUploadHost(locate.optString("host"))
        val md5List=JSONArray()
        val whole=MessageDigest.getInstance("MD5")
        val chunkSize=chunkSizeFor(size)
        var uploaded=0L
        resolver.openInputStream(uri)?.use { stream ->
            var part=0
            while(uploaded<size) {
                if (cancelled()) throw InterruptedException("Cloud upload cancelled")
                val length=min(chunkSize.toLong(),size-uploaded).toInt()
                val bytes=ByteArray(length)
                var n=0
                while(n<length) {
                    val v=stream.read(bytes,n,length-n)
                    if (v<0) throw java.io.EOFException("Local file truncated")
                    n+=v
                }
                val md5=partHashes(bytes)
                whole.update(bytes)
                uploadChunk(host,path,uploadId,part,name,bytes,md5)
                md5List.put(md5)
                uploaded+=length
                progress(uploaded,size)
                part++
            }
        } ?: throw java.io.IOException("Cannot read local download")
        val finalized=request("/api/create","POST",
            mapOf("isdir" to "0","rtype" to "1"),
            mapOf("path" to path,"size" to size.toString(),"uploadid" to uploadId,
                "target_path" to folder,"block_list" to md5List.toString(),
                "local_mtime" to (System.currentTimeMillis()/1000).toString()))
        ok(finalized,"Finalize upload")
        val md5=whole.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        if(!confirmRemote(path,size,md5))
            throw java.io.IOException("TeraBox has not confirmed the uploaded file")
    }

    private fun uploadChunk(host:String, path:String, uploadId:String, part:Int,
                            name:String, data:ByteArray, md5:String) {
        val uri="https://$host/rest/2.0/pcs/superfile2?"+formEncode(
            linkedMapOf("method" to "upload","path" to path,
                "uploadid" to uploadId,"partseq" to part.toString(),
                "app_id" to "250528","web" to "1"))
        val boundary="----Nocturne"+System.nanoTime().toString(16)
        val prefix=("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" +
            name.replace("\"","") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray()
        val suffix="\r\n--$boundary--\r\n".toByteArray()
        var failure:Exception?=null
        repeat(4) { attempt ->
            if(attempt>0) Thread.sleep(1200L*(attempt+1))
            try {
                val conn=connection(uri,"POST",true)
                conn.doOutput=true
                conn.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
                conn.setFixedLengthStreamingMode(prefix.size+data.size+suffix.size)
                conn.outputStream.use { it.write(prefix);it.write(data);it.write(suffix) }
                val result=JSONObject(read(conn))
                val remoteHash=result.optString("md5")
                if(!remoteHash.equals(md5,true))
                    throw java.io.IOException("TeraBox chunk integrity mismatch")
                return
            } catch (e:Exception) { failure=e }
        }
        throw java.io.IOException("TeraBox upload chunk failed: " +
            (failure?.message ?: "unknown"))
    }
}
