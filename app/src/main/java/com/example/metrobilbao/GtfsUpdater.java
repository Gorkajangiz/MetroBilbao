package com.example.metrobilbao;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Descarga y cachea el zip GTFS oficial en filesDir/gtfs/.
 * Solo descarga cuando ETag/Last-Modified cambia o cuando pasan MAX_AGE_MS.
 * Descarga atómica: escribe en gtfs.tmp, luego renameTo(gtfs).
 */
public class GtfsUpdater {

    private static final String TAG = "MetroTren";
    private static final String DIR = "gtfs";
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private static final String[] REQUIRED = {
            "stops.txt", "trips.txt", "stop_times.txt", "shapes.txt"
    };

    public interface Callback {
        void onReady(File gtfsDir, boolean fromDownload);
        void onError(Throwable t);
    }

    public interface Progress {
        void onProgress(String message, boolean indeterminate, long bytes, long total);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    public static void ensureFresh(Context ctx, Callback cb, Progress progress) {
        new Thread(
                () -> new GtfsUpdater().run(ctx.getApplicationContext(), cb, progress),
                "gtfs-updater"
        ).start();
    }

    private void run(Context ctx, Callback cb, Progress progress) {
        File dir = new File(ctx.getFilesDir(), DIR);
        File etagFile = new File(dir, ".etag");

        try {
            boolean hasCache = validarCache(dir);

            boolean needsCheck = !hasCache
                    || (System.currentTimeMillis() - lastCheck(dir)) > MAX_AGE_MS;

            if (!needsCheck) {
                post(cb, () -> cb.onReady(dir, false));
                return;
            }

            postProgress(progress, "Comprobando actualizaciones…", true, 0, 0);
            String remoteEtag = headEtag(ctx.getString(R.string.gtfs_url));
            String cachedEtag = etagFile.isFile() ? readTrim(etagFile) : "";

            boolean changed = hasCache && remoteEtag != null && !remoteEtag.equals(cachedEtag);
            boolean needDownload = !hasCache || changed;

            if (!needDownload) {
                touchLastCheck(dir);
                post(cb, () -> cb.onReady(dir, false));
                return;
            }

            postProgress(progress, "Descargando datos GTFS…", true, 0, 0);
            File tmp = new File(dir.getParentFile(), DIR + ".tmp");
            deleteRecursive(tmp);
            if (!tmp.mkdirs()) throw new IOException("No se pudo crear " + tmp);

            downloadAndExtract(ctx.getString(R.string.gtfs_url), tmp, progress);

            // swap atómico
            deleteRecursive(dir);
            if (!tmp.renameTo(dir)) throw new IOException("renameTo falló");
            if (remoteEtag != null) writeString(etagFile, remoteEtag);
            else etagFile.setLastModified(System.currentTimeMillis());

            post(cb, () -> cb.onReady(dir, true));

        } catch (Throwable t) {
            Log.e(TAG, "GtfsUpdater error", t);
            if (validarCache(dir)) {
                // Tenemos cache válida: usarla aunque la actualización haya fallado
                post(cb, () -> cb.onReady(dir, false));
            } else {
                post(cb, () -> cb.onError(t));
            }
        }
    }

    private static boolean validarCache(File dir) {
        if (!dir.isDirectory()) return false;
        for (String f : REQUIRED) if (!new File(dir, f).isFile()) return false;
        return true;
    }

    private static long lastCheck(File dir) {
        File marker = new File(dir, ".lastcheck");
        return marker.exists() ? marker.lastModified() : 0L;
    }

    private static void touchLastCheck(File dir) {
        try { new File(dir, ".lastcheck").setLastModified(System.currentTimeMillis()); }
        catch (Throwable ignored) {}
    }

    private static String headEtag(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            if (c.getResponseCode() != 200) return null;
            String e = c.getHeaderField("ETag");
            if (e == null) e = c.getHeaderField("Last-Modified");
            return e;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void downloadAndExtract(String urlStr, File outDir, Progress progress) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());

            long total = c.getContentLength();
            long downloaded = 0;

            try (InputStream in = c.getInputStream();
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry e;
                byte[] buf = new byte[8192];
                while ((e = zis.getNextEntry()) != null) {
                    File out = new File(outDir, e.getName());
                    if (!out.getCanonicalPath().startsWith(outDir.getCanonicalPath() + File.separator)) {
                        throw new IOException("Zip traversal: " + e.getName());
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    if (e.isDirectory()) continue;
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            downloaded += n;
                            long lastReport = 0;
                            downloaded += n;
                            if (total > 0 && downloaded - lastReport > 256 * 1024) {
                                lastReport = downloaded;
                                postProgress(progress, "Descargando…", false, downloaded, total);
                            }
                        }
                    }
                }
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static String readTrim(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) { in.read(b); }
            return new String(b, "UTF-8").trim();
        } catch (IOException e) { return ""; }
    }

    private static void writeString(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        }
    }

    private void post(Callback cb, Runnable r) { main.post(r); }

    private void postProgress(Progress p, String msg, boolean indet, long bytes, long total) {
        if (p == null) return;
        main.post(() -> p.onProgress(msg, indet, bytes, total));
    }
}