package com.ptrfarhan.kontrolpompa;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cangkang aplikasi: menampilkan halaman aplikasi yang tersimpan di dalam APK (tanpa membuka situs web)
 * dan menyediakan satu jembatan kecil untuk mengirim firmware ke alat lewat jaringan lokal.
 *
 * Pengaman:
 *  - hanya halaman milik aplikasi dan Firebase yang boleh dimuat, semua alamat lain diblokir
 *  - tidak ada akses berkas, tidak ada konten campuran HTTP, debugging web dimatikan
 *  - jembatan hanya menerima alamat jaringan lokal dan berkas yang berbentuk firmware ESP8266
 */
public class MainActivity extends Activity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final int REQ_BIN = 41;
    private static final int OTA_PORT = 8266;

    private WebView web;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile byte[] binData = null;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#E4EEEE"));
        setContentView(web);

        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                if (APP_HOST.equals(host)) return loader.shouldInterceptRequest(u);
                if ("https".equals(u.getScheme()) && host != null && host.endsWith(".firebasedatabase.app")) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                        new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !APP_HOST.equals(request.getUrl().getHost());   // tidak pernah pindah ke situs lain
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidOta");
        web.loadUrl(APP_URL);
    }

    private void emit(final String type, final boolean ok, final String message, final int percent) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("ok", ok);
            o.put("pesan", message == null ? "" : message);
            o.put("persen", percent);
            final String js = "window.otaEvent && window.otaEvent(" + o.toString() + ")";
            runOnUiThread(new Runnable() {
                @Override public void run() { web.evaluateJavascript(js, null); }
            });
        } catch (Exception ignored) { }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        String p = uri.getLastPathSegment();
        return p == null ? "firmware.bin" : p;
    }

    private void loadBin(final Uri uri) {
        io.execute(new Runnable() {
            @Override public void run() {
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(b)) > 0) {
                        total += n;
                        if (total > EspOta.MAX_BIN + 1) throw new IOException("Berkas terlalu besar untuk firmware ESP8266");
                        bo.write(b, 0, n);
                    }
                    byte[] d = bo.toByteArray();
                    if (!EspOta.looksLikeEsp8266Image(d)) throw new IOException("Berkas ini bukan firmware ESP8266 (.bin hasil Export Compiled Binary)");
                    binData = d;
                    emit("bin", true, displayName(uri) + " (" + (d.length / 1024) + " KB)", -1);
                } catch (Exception e) {
                    binData = null;
                    emit("bin", false, e.getMessage() == null ? "Gagal membaca berkas" : e.getMessage(), -1);
                } finally {
                    try { if (in != null) in.close(); } catch (IOException ignored) { }
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BIN && resultCode == RESULT_OK && data != null && data.getData() != null) loadBin(data.getData());
    }

    public class Bridge {
        @JavascriptInterface
        public void pilihBin() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, REQ_BIN);
                }
            });
        }

        @JavascriptInterface
        public void kirim(final String ip, final String sandi) {
            if (busy) { emit("selesai", false, "Pengiriman sebelumnya masih berjalan", -1); return; }
            final byte[] d = binData;
            if (d == null) { emit("selesai", false, "Pilih berkas .bin dulu", -1); return; }
            busy = true;
            runOnUiThread(new Runnable() { @Override public void run() { web.setKeepScreenOn(true); } });
            io.execute(new Runnable() {
                @Override public void run() {
                    try {
                        EspOta.flash(ip == null ? "" : ip.trim(), OTA_PORT, sandi, d, new EspOta.Listener() {
                            @Override public void onProgress(int percent, String message) { emit("progres", true, message, percent); }
                        });
                        emit("selesai", true, "Firmware terkirim. Alat restart, tunggu sekitar 30 detik.", 100);
                    } catch (Exception e) {
                        emit("selesai", false, e.getMessage() == null ? "Gagal mengirim firmware" : e.getMessage(), -1);
                    } finally {
                        busy = false;
                        runOnUiThread(new Runnable() { @Override public void run() { web.setKeepScreenOn(false); } });
                    }
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (web != null) { web.removeJavascriptInterface("AndroidOta"); web.destroy(); }
        super.onDestroy();
    }
}
