package com.azrael.pixivdumpsync

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

/**
 * Official TeraBox Open Platform uploader:
 * tokeninfo -> precreate -> superfile2(partseq) -> create.
 * Requires a valid, authorized Open Platform access token and the app-assigned
 * sandbox path. No NDUS cookie scraping or undocumented web endpoints.
 */
class TeraboxUploader(private val context: Context) {
    private data class Domains(val api: String, val upload: String)
    private data class LocalFile(val uri: Uri, val size: Long)
    private val TARGET_CHUNK = 8 * 1024 * 1024

    fun uploadArtwork(filename: String): NduRemoteReceipt {
        if (!filename.matches(Regex("[0-9]+_p[0-9]+\\.(jpg|jpeg|png|gif|webp)"))) {
            throw IOException("Invalid artwork filename")
        }
        val token = TeraboxSettings.accessToken(context)
            ?: throw IOException("TeraBox access token not configured")
        val remoteDir = TeraboxSettings.remoteDirectory(context)
        if (!TeraboxSettings.validDirectory(remoteDir)) {
            throw IOException("TeraBox application folder not configured")
        }
        val uri = FileStore.findUri(context, filename)
            ?: throw IOException("Local file missing: $filename")
        val size = contentSize(uri)
        if (size <= 0) throw IOException("Local file is empty: $filename")
        val remotePath = "${remoteDir.trimEnd('/')}/$filename"
        val domains = lookupDomains(token)
        val chunks = calculateBlocks(uri, size)
        val md5List = JSONArray(chunks).toString()
        val precreate = requestForm(
            domains.api,
            "/openapi/api/precreate",
            token,
            mapOf(
                "autoinit" to "1",
                "path" to remotePath,
                "block_list" to md5List
            )
        )
        val uploadId = precreate.optString("uploadid", "")
        if (uploadId.isEmpty()) throw IOException("TeraBox precreate returned no upload ID")
        val requested = precreate.optJSONArray("block_list")
        val missing = if (requested == null || requested.length() == 0) {
            // TeraBox documents [] as equivalent to [0] in precreate.
            setOf(0)
        } else {
            (0 until requested.length()).mapNotNull {
                requested.opt(it)?.toString()?.toIntOrNull()
            }.toSet()
        }
        uploadBlocks(domains.upload, token, uri, remotePath, uploadId, size, chunks, missing)
        val created = requestForm(
            domains.api, "/openapi/api/create", token,
            mapOf(
                "path" to remotePath,
                "size" to size.toString(),
                "uploadid" to uploadId,
                "block_list" to md5List,
                "rtype" to "2"
            )
        )
        val actualSize = created.optLong("size", -1L)
        val createdPath = created.optString("path", "")
        if (!createdPath.startsWith(remoteDir.trimEnd('/') + "/") ||
            created.optLong("fs_id", 0L) <= 0L || actualSize != size
        ) throw IOException("TeraBox did not confirm the expected cloud folder and size")
        // A successful create is a handoff response, not evidence that the
        // object remains visible in the destination. Require a fresh metadata read.
        return verifyRemote(domains.api,token,uri,createdPath,size)
    }

    /** Recheck legacy cloud records; never create a second copy merely because
     * an old request was recorded as successful.
     */
    fun verifyHistoricalArtwork(filename:String,previousPath:String?):NduRemoteReceipt{
        require(filename.matches(Regex("[0-9]+_p[0-9]+\\.(jpg|jpeg|png|gif|webp)")))
        val path=previousPath?.takeIf{it.isNotBlank()}
            ?: throw IOException("Historical remote path is unknown")
        val folder=TeraboxSettings.remoteDirectory(context).trimEnd('/')+"/"
        require(path.startsWith(folder)){"Historic remote destination differs from configured TeraBox folder"}
        val token=TeraboxSettings.accessToken(context)
            ?:throw IOException("TeraBox access token not configured")
        val uri=FileStore.findUri(context,filename)
            ?:throw IOException("Original artwork unavailable for remote verification")
        val size=contentSize(uri)
        require(size>0L){"Original file is empty"}
        return verifyRemote(lookupDomains(token).api,token,uri,path,size)
    }

    private fun verifyRemote(domain:String,token:String,local:Uri,
                             path:String,size:Long):NduRemoteReceipt{
        var lastError:Exception?=null
        repeat(4){attempt->
            try{
                val conn=openConnection(buildUrl(domain,"/openapi/api/filemetas",
                    mapOf("access_tokens" to token,
                        "target" to JSONArray(listOf(path)).toString(),
                        "dlink" to "0")))
                val response=try{parseResponse(conn)}finally{
                    NetworkRequestRegistry.unregister(conn)
                    conn.disconnect()
                }
                val files=response.optJSONArray("info")?:JSONArray()
                for(i in 0 until files.length()){
                    val item=files.optJSONObject(i)?:continue
                    if(item.optString("path")!=path || item.optLong("size",-1L)!=size ||
                        item.optLong("fs_id",0L)<=0L)continue
                    val remoteHash=item.optString("md5").trim()
                    if(remoteHash.isNotBlank()){
                        val md5=java.security.MessageDigest.getInstance("MD5")
                        context.contentResolver.openInputStream(local)?.use{stream->
                            val buffer=ByteArray(131072)
                            while(true){
                                val count=stream.read(buffer)
                                if(count<0)break
                                md5.update(buffer,0,count)
                            }
                        }?:throw IOException("Local file unavailable for remote hash comparison")
                        val digest=md5.digest().joinToString(""){
                            "%02x".format(it.toInt() and 255)
                        }
                        if(!remoteHash.equals(digest,true))
                            throw IOException("TeraBox remote file hash mismatch")
                    }
                    return NduRemoteReceipt(path,size,item.optLong("fs_id"),
                        remoteHash.ifBlank{null},System.currentTimeMillis())
                }
            }catch(e:Exception){lastError=e}
            if(attempt<3)Thread.sleep(900L*(attempt+1))
        }
        throw IOException("TeraBox metadata did not independently verify remote copy",lastError)
    }

    private fun contentSize(uri: Uri): Long =
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
        ).use { c ->
            if (c != null && c.moveToFirst()) c.getLong(0) else -1L
        }

    private fun calculateBlocks(uri: Uri, size: Long): List<String> {
        val partCount = max(1L, (size + TARGET_CHUNK - 1L) / TARGET_CHUNK).toInt()
        val bytesPerPart = (size + partCount - 1L) / partCount
        val blocks = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            repeat(partCount) { part ->
                val digest = MessageDigest.getInstance("MD5")
                var remaining = minOf(bytesPerPart, size - part * bytesPerPart)
                val buffer = ByteArray(128 * 1024)
                while (remaining > 0) {
                    if (SyncControl.isStopping()) throw SyncCancelledException()
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read <= 0) throw IOException("Truncated local artwork file")
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
                blocks += digest.digest().joinToString("") {
                    String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
                }
            }
        } ?: throw IOException("Cannot open artwork for cloud upload")
        return blocks
    }

    private fun uploadBlocks(
        domain: String,
        token: String,
        uri: Uri,
        path: String,
        uploadId: String,
        size: Long,
        hashes: List<String>,
        requiredParts: Set<Int>
    ) {
        val perPart = (size + hashes.size - 1) / hashes.size
        context.contentResolver.openInputStream(uri)?.use { input ->
            for (index in hashes.indices) {
                var remaining = minOf(perPart, size - index * perPart)
                val buffer = ByteArray(remaining.toInt())
                var cursor = 0
                while (remaining > 0L) {
                    if (SyncControl.isStopping()) throw SyncCancelledException()
                    val count = input.read(buffer, cursor, remaining.toInt())
                    if (count <= 0) throw IOException("Truncated local artwork during upload")
                    cursor += count
                    remaining -= count
                }
                if (index !in requiredParts) continue
                val json = postFile(
                    domain,
                    "/rest/2.0/pcs/superfile2",
                    mapOf(
                        "access_tokens" to token,
                        "method" to "upload",
                        "app_id" to "250528",
                        "path" to path,
                        "uploadid" to uploadId,
                        "partseq" to index.toString()
                    ),
                    "file", "${index}.bin", buffer
                )
                if (!hashes[index].equals(json.optString("md5"), ignoreCase = true)) {
                    throw IOException("TeraBox rejected chunk checksum for part $index")
                }
            }
        } ?: throw IOException("Cannot open artwork stream for upload")
    }

    private fun lookupDomains(token: String): Domains {
        val info = postFile(
            "www.terabox.com", "/oauth/tokeninfo", emptyMap(),
            "access_token", "access_token", token.toByteArray(Charsets.UTF_8),
            textPart = true
        )
        val data = info.optJSONObject("data")
            ?: throw IOException("TeraBox tokeninfo returned no data")
        val api = data.optString("api_domain", "")
        val upload = data.optString("upload_domain", "")
        if (!safeHost(api) || !safeHost(upload)) {
            throw IOException("TeraBox tokeninfo did not provide approved API domains")
        }
        return Domains(api, upload)
    }

    private fun safeHost(host: String): Boolean =
        host.isNotEmpty() &&
            host.matches(Regex("[a-zA-Z0-9.-]+")) &&
            (host.equals("terabox.com", true) || host.endsWith(".terabox.com", true))

    private fun requestForm(
        domain: String, endpoint: String, token: String, fields: Map<String, String>
    ): JSONObject {
        val url = buildUrl(domain, endpoint, mapOf("access_tokens" to token))
        val connection = openConnection(url)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
            )
            val form = fields.entries.joinToString("&") {
                encode(it.key) + "=" + encode(it.value)
            }.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(form.size)
            connection.outputStream.use { it.write(form) }
            parseResponse(connection)
        } finally {
            NetworkRequestRegistry.unregister(connection)
            connection.disconnect()
        }
    }

    private fun postFile(
        domain: String,
        endpoint: String,
        query: Map<String, String>,
        field: String,
        filename: String,
        bytes: ByteArray,
        textPart: Boolean = false
    ): JSONObject {
        val connection = openConnection(buildUrl(domain, endpoint, query))
        val boundary = "----KurohaCloudOpenApiBoundary"
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val header = if (textPart) {
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"\r\n\r\n"
                } else {
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                }
                val prefix = header.toByteArray(Charsets.UTF_8)
                val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(prefix.size + bytes.size + suffix.size)
                connection.outputStream.use { output ->
                    output.write(prefix)
                    output.write(bytes)
                    output.write(suffix)
                }
            parseResponse(connection)
        } finally {
            NetworkRequestRegistry.unregister(connection)
            connection.disconnect()
        }
    }

    private fun parseResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("TeraBox HTTP $status")
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val result = try { JSONObject(body) } catch (_: Exception) {
            throw IOException("TeraBox returned invalid JSON")
        }
        val errno = result.optInt("errno", Int.MIN_VALUE)
        if (errno != 0) throw IOException("TeraBox API error $errno")
        return result
    }

    private fun openConnection(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            NetworkRequestRegistry.register(this)
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Kuroha/0.7 (Android)")
        }

    private fun buildUrl(
        domain: String, endpoint: String, query: Map<String, String>
    ): URL {
        if (!safeHost(domain) || !endpoint.startsWith("/")) {
            throw IOException("Untrusted TeraBox API destination")
        }
        val suffix = query.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
        return URL("https://$domain$endpoint" + if (suffix.isEmpty()) "" else "?$suffix")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
