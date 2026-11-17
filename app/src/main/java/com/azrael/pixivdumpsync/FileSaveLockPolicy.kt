package com.azrael.pixivdumpsync

/**
 * Fixed-size per-filename locks: slow Pixiv image downloads must not serialize
 * all other artist originals behind one global FileStore monitor.
 * Same-filename writers still cannot race into duplicate MediaStore records.
 */
internal object FileSaveLockPolicy {
    private val locks = Array(64) { Any() }
    fun <T> forFilename(filename: String, operation: () -> T): T {
        require(filename.isNotBlank())
        val stripe = (filename.hashCode() and Int.MAX_VALUE) % locks.size
        return synchronized(locks[stripe]) { operation() }
    }
}
