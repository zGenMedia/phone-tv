package com.zgenmedia.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Self-updater for sideloaded installs.
 *
 * Reads {@code version.json} from the project site, and when a newer build
 * exists downloads the APK, verifies its SHA-256 and hands it to the system
 * PackageInstaller. Android shows one confirmation; since every release is
 * signed with the same key, it installs over the top and keeps all settings.
 *
 * Fully silent installs are reserved for app stores and device owners, so the
 * single confirm tap is the most an independently distributed app can do.
 */
final class Updater {
    static final String MANIFEST_URL = "https://zgenmedia.github.io/phone-tv/version.json";
    static final String ACTION_INSTALL_STATUS = "com.zgenmedia.tvhome.INSTALL_STATUS";
    private static final long CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L;

    /** What the manifest advertises. */
    static final class Release {
        int versionCode;
        String versionName, apkUrl, sha256, notes;
    }

    private final Context ctx;
    private long lastCheck;

    Updater(Context ctx) { this.ctx = ctx; }

    boolean due() { return System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS; }

    /** @return the newer release, or null if this build is current. Blocking. */
    Release check() throws IOException, JSONException {
        lastCheck = System.currentTimeMillis();
        JSONObject o = new JSONObject(new String(Http.get(MANIFEST_URL), StandardCharsets.UTF_8));
        Release r = new Release();
        r.versionCode = o.getInt("versionCode");
        r.versionName = o.getString("versionName");
        r.apkUrl = o.getString("apkUrl");
        r.sha256 = o.optString("sha256", "").toLowerCase(java.util.Locale.ROOT);
        r.notes = o.optString("notes", "");
        return r.versionCode > BuildConfig.VERSION_CODE ? r : null;
    }

    /** Downloads and verifies the APK. Blocking. */
    File download(Release r) throws IOException {
        File apk = new File(ctx.getCacheDir(), "update-" + r.versionCode + ".apk");
        byte[] data = Http.get(r.apkUrl, java.util.Collections.emptyMap(), 30_000);
        if (!r.sha256.isEmpty() && !r.sha256.equals(sha256(data))) {
            throw new IOException("download didn't match the published checksum");
        }
        try (OutputStream out = new FileOutputStream(apk)) { out.write(data); }
        return apk;
    }

    /**
     * Streams the APK into a PackageInstaller session. The result arrives at
     * {@link HomeActivity#onNewIntent} with {@link #ACTION_INSTALL_STATUS}; when
     * the status is PENDING_USER_ACTION the activity launches the system's
     * confirmation screen.
     */
    void install(Activity activity, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("phonetv.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(activity, HomeActivity.class).setAction(ACTION_INSTALL_STATUS);
            // Must be mutable: the installer fills in the status extras.
            PendingIntent pi = PendingIntent.getActivity(activity, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());
        }
    }

    /** True if Android will let this app install packages (the "unknown apps" toggle). */
    boolean canInstall() {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
