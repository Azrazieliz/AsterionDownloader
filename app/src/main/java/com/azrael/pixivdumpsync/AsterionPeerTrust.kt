package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.widget.LinearLayout
import java.security.MessageDigest

/** Independent Asterion apps use different signing certificates. Validate the OS-supplied
 * caller UID, an installed package's real certificate, and user opt-in (one time per cert).
 * Unlike signature-only permissions this can work across separately signed Asterion apps.
 */
object AsterionPeerTrust {
    private const val PREF="asterion_cross_app_peer_certificates"
    data class Peer(val pkg:String,val digest:String,val approved:Boolean)
    private fun prefs(c:Context)=c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    internal fun allowedPackage(pkg:String):Boolean=
        // These exact package identities are taken from the currently connected
        // Asterion Core and Asterion Games Android manifests. Both still need
        // independent certificate validation and explicit user approval.
        pkg in setOf("com.asterion.art","com.ailm.android","com.gamepilot") ||
            pkg.startsWith("com.asterion.") ||
            pkg.startsWith("com.azrael.asterion.")
    private fun signature(c:Context,pkg:String):String?=runCatching{
        @Suppress("DEPRECATION")
        val info=c.packageManager.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES)
        val signing=info.signingInfo ?:return null
        val certs=if(signing.hasMultipleSigners())signing.apkContentsSigners
            else signing.signingCertificateHistory
        val cert=certs.firstOrNull()?:return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString(""){"%02x".format(it.toInt() and 255)}
    }.getOrNull()
    fun verifiedCaller(c:Context,uid:Int):Peer?{
        if(uid<=0)return null
        for(pkg in c.packageManager.getPackagesForUid(uid).orEmpty()){
            if(!allowedPackage(pkg))continue
            val digest=signature(c,pkg)?:continue
            val approved=prefs(c).getString("approved_"+pkg,null)==digest ||
                c.packageManager.checkSignatures(c.packageName,pkg)==
                    PackageManager.SIGNATURE_MATCH
            if(!approved)prefs(c).edit().putString("pending_"+pkg,digest).apply()
            return Peer(pkg,digest,approved)
        }
        return null
    }
    fun pending(c:Context):List<Peer> =
        prefs(c).all.entries.filter{it.key.startsWith("pending_")}.mapNotNull{entry->
            val pkg=entry.key.removePrefix("pending_")
            val digest=entry.value as? String ?:return@mapNotNull null
            if(!allowedPackage(pkg)||signature(c,pkg)!=digest)return@mapNotNull null
            Peer(pkg,digest,false)
        }
    fun approved(c:Context):List<String> =
        prefs(c).all.keys.filter{it.startsWith("approved_")}
            .map{it.removePrefix("approved_")}
    fun approve(c:Context,peer:Peer){
        require(signature(c,peer.pkg)==peer.digest){"App certificate changed"}
        prefs(c).edit().putString("approved_"+peer.pkg,peer.digest)
            .remove("pending_"+peer.pkg).apply()
        InterAppDownloadDb(c).use{it.approve(peer.pkg)}
        AsterionDownloadWorker.schedule(c)
    }
    fun revoke(c:Context,pkg:String){
        prefs(c).edit().remove("approved_"+pkg).apply()
        InterAppDownloadDb(c).use{it.freeze(pkg)}
    }
    fun render(a:Activity,parent:LinearLayout){
        val ui=NocturneDesign
        val section=ui.card(a)
        section.addView(ui.text(a,"Asterion app connections",17f,ui.white,true))
        section.addView(ui.text(a,"Approve each separately signed app once. "+
            "Unknown senders are rejected and unapproved requests cannot download.",
            12f,ui.secondary),ui.lp(a,6))
        val waiting=pending(a)
        for(peer in waiting){
            section.addView(ui.action(a,"APPROVE "+peer.pkg,false){
                AlertDialog.Builder(a).setTitle("Trust "+peer.pkg+"?")
                    .setMessage("Allow this installed Asterion application "+
                        "to request downloads automatically?\nSigning fingerprint:\n"+
                        peer.digest)
                    .setPositiveButton("ALLOW"){_,_->
                        approve(a,peer);a.recreate()
                    }.setNegativeButton("Cancel",null).show()
            },ui.lp(a,8))
        }
        for(pkg in approved(a))
            section.addView(ui.action(a,"REVOKE "+pkg,false){
                revoke(a,pkg);a.recreate()
            },ui.lp(a,7))
        if(waiting.isEmpty() && approved(a).isEmpty())
            section.addView(ui.text(a,"No connected apps yet.",12f,ui.subdued),
                ui.lp(a,8))
        parent.addView(section,ui.lp(a,10))
    }
}
