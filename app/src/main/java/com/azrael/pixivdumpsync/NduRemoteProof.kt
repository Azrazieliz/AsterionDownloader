package com.azrael.pixivdumpsync

/** Evidence read back from TeraBox after a file upload, not an upload request. */
data class NduRemoteReceipt(
    val path:String,
    val size:Long,
    val fsId:Long,
    val remoteMd5:String?,
    val verifiedAt:Long
) {
    val hashVerified:Boolean get() = !remoteMd5.isNullOrBlank()
}

/** Strict, deterministic checks before a cloud transfer can be called verified. */
object NduRemoteProof {
    fun matches(
        expectedPath:String,expectedSize:Long,localMd5:String,
        remotePath:String,remoteSize:Long,remoteFsId:Long,
        remoteMd5:String?,requireHash:Boolean=false
    ):Boolean {
        if(expectedPath.isBlank() || expectedSize<=0 || remoteFsId<=0L ||
            remotePath!=expectedPath || remoteSize!=expectedSize)return false
        val hash=remoteMd5?.trim().orEmpty()
        if(requireHash && hash.isBlank())return false
        if(hash.isNotBlank() &&
            !hash.equals(localMd5,ignoreCase=true))return false
        return true
    }
}
