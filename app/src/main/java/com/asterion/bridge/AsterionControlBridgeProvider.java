package com.asterion.bridge;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Explicit, read-only Asterion Control v1 partner endpoint.
 *
 * This provider does not share files, models, request bodies, secrets, private
 * app logs, or permissions. Each Binder call verifies the ORIGINAL owner Control
 * signer and package. Adding a favorite in Control cannot bypass verification.
 * Only real package/process/Android exit observations are advertised.
 *
 * Engine-specific telemetry can be added separately, after its owner opts in;
 * until then, engine_telemetry_available is always false.
 */
public final class AsterionControlBridgeProvider extends ContentProvider {
    private static final String CONTROL_PACKAGE = "com.asterion.control";
    private static final String OWNER_SHA256 =
        "9bc439712a0a460cc4703ebf254aaaf9bf3cc6e67b0ced6fc12162ed3ca3b9ad";
    private static final String PREFS = "asterion_control_bridge_receipts_v1";
    private static final int MAX_EXITS = 8;

    @Override public boolean onCreate() { return getContext() != null; }

    private Context context() {
        Context ctx = getContext();
        if (ctx == null) throw new IllegalStateException("Provider not attached");
        return ctx;
    }

    private void requireOriginalControlCaller() {
        final Context ctx = context();
        final PackageManager pm = ctx.getPackageManager();
        final int uid = Binder.getCallingUid();
        String[] packages = pm.getPackagesForUid(uid);
        boolean controlUid = false;
        if (packages != null) for (String pkg : packages) {
            if (CONTROL_PACKAGE.equals(pkg)) { controlUid = true; break; }
        }
        if (!controlUid || uid == Process.myUid()) {
            throw new SecurityException("Only the separately installed owner Control may call this bridge");
        }
        try {
            PackageInfo info;
            Signature[] certificates;
            if (Build.VERSION.SDK_INT >= 28) {
                info = pm.getPackageInfo(CONTROL_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signing = info.signingInfo;
                if (signing == null) throw new SecurityException("Caller signer unavailable");
                certificates = signing.getApkContentsSigners();
            } else {
                info = pm.getPackageInfo(CONTROL_PACKAGE, PackageManager.GET_SIGNATURES);
                certificates = info.signatures;
            }
            if (certificates == null || certificates.length != 1)
                throw new SecurityException("Unexpected owner certificate count");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificates[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.US, "%02x", b & 255));
            if (!MessageDigest.isEqual(
                    OWNER_SHA256.getBytes(StandardCharsets.UTF_8),
                    hex.toString().getBytes(StandardCharsets.UTF_8)))
                throw new SecurityException("Asterion Control signing certificate mismatch");
        } catch (PackageManager.NameNotFoundException exception) {
            throw new SecurityException("Owner Control is not installed", exception);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new SecurityException("Cannot verify owner certificate", exception);
        }
    }

    private static boolean requestIdValid(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:-]{8,100}");
    }

    private JSONObject processEvidence() throws Exception {
        Context ctx = context();
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pkg = pm.getPackageInfo(ctx.getPackageName(), 0);
        Runtime runtime = Runtime.getRuntime();
        Debug.MemoryInfo memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        JSONObject evidence = new JSONObject()
            .put("package", ctx.getPackageName())
            .put("version_name", pkg.versionName == null ? "unknown" : pkg.versionName)
            .put("version_code", Build.VERSION.SDK_INT >= 28 ?
                pkg.getLongVersionCode() : pkg.versionCode)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("process_response_verified", true)
            .put("process_heap_used_bytes", Math.max(0L,
                 runtime.totalMemory() - runtime.freeMemory()))
            .put("process_pss_kb", Math.max(0, memory.getTotalPss()))
            .put("snapshot_scope", "partner_process_only")
            .put("engine_telemetry_available", false)
            .put("engine_queue_state_verified", false)
            .put("models_available_verified", false)
            .put("private_files_read", false)
            .put("supported_actions",
                new JSONArray().put("status").put("diagnostics.summary").put("capabilities"));
        JSONArray exits = new JSONArray();
        if (Build.VERSION.SDK_INT >= 30) {
            ActivityManager manager =
                (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                List<ApplicationExitInfo> historic =
                    manager.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, MAX_EXITS);
                for (ApplicationExitInfo exit : historic) {
                    exits.put(new JSONObject()
                        .put("reason_code", exit.getReason())
                        .put("timestamp_ms", exit.getTimestamp())
                        .put("pss_kb", Math.max(0L, exit.getPss()))
                        .put("rss_kb", Math.max(0L, exit.getRss())));
                }
            }
        }
        return evidence.put("android_historical_exits", exits)
            .put("android_historical_exits_count", exits.length());
    }

    private static Bundle reply(JSONObject result) {
        Bundle output = new Bundle();
        String json = result.toString();
        if (json.length() > 8192) throw new IllegalStateException("Response limit exceeded");
        output.putString("result_json", json);
        return output;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireOriginalControlCaller();
        if (extras == null || extras.getInt("protocol_version", 0) != 1)
            throw new IllegalArgumentException("Unsupported bridge protocol");
        String requestId = extras.getString("request_id", "");
        if (!requestIdValid(requestId))
            throw new IllegalArgumentException("Invalid durable request ID");
        SharedPreferences receipts = context().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if ("asterion.v1.cancel".equals(method))
            return reply(new JSONObjectBuilder().put("ok", false)
                .put("status", "unsupported")
                .put("reason", "Read-only snapshots are immediate; no cancellable engine action exists")
                .build());
        if ("asterion.v1.status".equals(method)) {
            boolean saved = receipts.contains("id:" + requestId);
            return reply(new JSONObjectBuilder().put("ok", saved)
                .put("status", saved ? "completed" : "unknown_request")
                .put("request_id", requestId).build());
        }
        if (!"asterion.v1.invoke".equals(method))
            throw new SecurityException("Unknown bridge method");
        String action = extras.getString("action", "");
        if (!("status".equals(action) || "capabilities".equals(action) ||
              "diagnostics.summary".equals(action)))
            return reply(new JSONObjectBuilder().put("ok", false)
                .put("status", "rejected")
                .put("reason", "This partner bridge is read-only; action unsupported")
                .build());
        // Save an idempotent read receipt. It does not start an engine or
        // expose user content, and cannot be used to replay a side effect.
        String key = "id:" + requestId;
        String existing = receipts.getString(key, "");
        if (!existing.isEmpty() && !action.equals(existing))
            return reply(new JSONObjectBuilder().put("ok", false)
                .put("status", "rejected").put("reason", "request_id_collision").build());
        if (existing.isEmpty() && !receipts.edit().putString(key, action).commit())
            throw new IllegalStateException("Cannot persist read receipt");
        try {
            JSONObject result = processEvidence();
            if ("capabilities".equals(action))
                result.put("capability_type", "read_only_process_and_exit_evidence");
            return reply(new JSONObjectBuilder().put("ok", true)
                .put("status", "completed")
                .put("action", action)
                .put("request_id", requestId)
                .put("evidence", result).build());
        } catch (Exception error) {
            return reply(new JSONObjectBuilder().put("ok", false)
                .put("status", "failed")
                .put("reason", "Unable to capture process snapshot").build());
        }
    }

    /** Avoid checked JSONExceptions in the fixed reply envelope. */
    private static final class JSONObjectBuilder {
        private final JSONObject obj = new JSONObject();
        JSONObjectBuilder put(String key, Object value) {
            try { obj.put(key, value); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
            return this;
        }
        JSONObject build() { return obj; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("No partner files or database are exported");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only partner protocol");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only partner protocol");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
        String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only partner protocol");
    }
}
