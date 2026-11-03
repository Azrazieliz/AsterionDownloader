package com.azrael.pixivdumpsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An NDUS value grants access to the entire cloud account. Never log or export it. */
object NduSettings {
    private const val PREF = "nocturne_ndus_cloud"
    private const val ALIAS = "nocturne-ndus-aes-gcm-v1"
    private const val SECRET = "ndus_cipher"
    private const val ENABLE = "enabled"
    private const val KUROHA_DIR = "kuroha_dir"
    private const val KYORA_DIR = "kyora_dir"
    private const val DOMAIN = "domain"

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREF,0).getBoolean(ENABLE,false)
    fun setEnabled(ctx: Context, yes: Boolean) {
        ctx.getSharedPreferences(PREF,0).edit().putBoolean(ENABLE,yes).apply()
        NduCloudScheduler.configure(ctx,yes)
    }
    fun domain(ctx: Context) = ctx.getSharedPreferences(PREF,0)
        .getString(DOMAIN,"www.terabox.com") ?: "www.terabox.com"
    fun setDomain(ctx: Context, host: String) {
        require(host in allowedDomains) { "Unsupported TeraBox domain" }
        ctx.getSharedPreferences(PREF,0).edit().putString(DOMAIN,host).apply()
    }
    private val allowedDomains = setOf("www.terabox.com","nephobox.com")

    fun destination(ctx: Context, category: String): String {
        require(category in setOf("kuroha","kyora","x","comics","videos",
            "books","games","assets","koikatsu","images","torrents","originals"))
        val key = when(category) {
            "kuroha"->KUROHA_DIR;"kyora"->KYORA_DIR
            else->category+"_dir"
        }
        val folder = when(category) {
            "kuroha"->"Kuroha";"kyora"->"Kyora"
            "x"->"X";"comics"->"Comics"
            "videos"->"Videos";"books"->"Books";"games"->"Games"
            "assets"->"Assets";"koikatsu"->"Koikatsu"
            "images"->"Images";"torrents"->"Torrents"
            else->"Originals"
        }
        val fallback="/Nocturne/$folder"
        return ctx.getSharedPreferences(PREF,0).getString(key,fallback) ?: fallback
    }
    fun setDestination(ctx: Context, category: String, directory: String) {
        require(category in setOf("kuroha","kyora"))
        val path = validateDirectory(directory)
        ctx.getSharedPreferences(PREF,0).edit()
            .putString(when(category){
                "kuroha"->KUROHA_DIR;"kyora"->KYORA_DIR
                else->category+"_dir"
            },path).apply()
    }
    fun validateDirectory(input: String): String {
        val path = input.trim().trimEnd('/')
        require(path.length in 2..180 && path.startsWith("/") &&
            !path.contains("..") && !path.contains('\\') &&
            !path.contains('?') && !path.contains('#') &&
            !path.contains('\n') && !path.contains('\r') &&
            path.split('/').drop(1).all { it.isNotBlank() && it != "." }) {
            "Use a cloud folder like /Nocturne/Kyora"
        }
        return path
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
    }
    fun save(ctx: Context, input: String) {
        val cookie = parseNdus(input)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val a=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)
        val b=Base64.encodeToString(cipher.doFinal(cookie.toByteArray()),Base64.NO_WRAP)
        ctx.getSharedPreferences(PREF,0).edit().putString(SECRET,"$a:$b").apply()
    }
    fun parseNdus(input: String): String {
        val cleaned=input.trim()
        val value = if (cleaned.contains(';') || cleaned.startsWith("ndus=",true)) {
            val item=cleaned.removePrefix("Cookie:").split(';').firstOrNull {
                it.trim().startsWith("ndus=",ignoreCase=true)
            } ?: throw IllegalArgumentException("No ndus value found")
            item.substringAfter('=').trim()
        } else cleaned
        require(value.length in 8..4096 && !value.contains('\r') &&
            !value.contains('\n') && !value.contains(';') &&
            !value.contains(' ')) { "Invalid NDUS cookie" }
        return value
    }
    fun cookie(ctx: Context): String? {
        val encoded=ctx.getSharedPreferences(PREF,0).getString(SECRET,null) ?: return null
        return runCatching {
            val parts=encoded.split(':',limit=2)
            require(parts.size==2)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),
                GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)))
        }.getOrNull()
    }
    fun clear(ctx: Context) {
        NduCloudScheduler.configure(ctx,false)
        ctx.getSharedPreferences(PREF,0).edit().remove(SECRET)
            .putBoolean(ENABLE,false).apply()
    }
    fun configured(ctx: Context) = enabled(ctx) && cookie(ctx)!=null
}
