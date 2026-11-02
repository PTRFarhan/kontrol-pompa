package com.ptrfarhan.kontrolpompa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
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
import java.io.OutputStream;
import java.nio.charset.Charset;
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
    private static final int REQ_NOTIF = 42;
    private static final int REQ_CSV = 43;
    private static final int MAX_CSV = 4 * 1024 * 1024;
    private static final int OTA_PORT = 8266;

    private WebView web;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile byte[] binData = null;
    private volatile boolean busy = false;
    private volatile byte[] csvData = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#06141B"));
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

        // Tanpa WebChromeClient, confirm() di halaman selalu dijawab "batal" tanpa muncul apa-apa.
        // Akibatnya tombol Kirim dan perbarui, Ganti kode akses, dan Nyalakan tanpa timer tidak bereaksi.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("Lanjut", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("Batal", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) { result.confirm(); }
                        })
                        .show();
                return true;
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
        if (requestCode == REQ_CSV) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) tulisCsv(data.getData());
            else csvData = null;
        }
    }

    /** Menulis CSV ke berkas yang dipilih pengguna lewat pemilih berkas bawaan Android. */
    private void tulisCsv(final Uri uri) {
        final byte[] d = csvData;
        csvData = null;
        if (d == null) return;
        io.execute(new Runnable() {
            @Override public void run() {
                OutputStream out = null;
                try {
                    out = getContentResolver().openOutputStream(uri, "wt");
                    if (out == null) throw new IOException("Tidak bisa membuka berkas tujuan");
                    out.write(d);
                    out.flush();
                    emit("csv", true, "File CSV tersimpan (" + Math.max(1, d.length / 1024) + " KB)", -1);
                } catch (Exception e) {
                    emit("csv", false, e.getMessage() == null ? "Gagal menyimpan file CSV" : "Gagal menyimpan: " + e.getMessage(), -1);
                } finally {
                    try { if (out != null) out.close(); } catch (IOException ignored) { }
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            PantauService.mulaiAtauBerhenti(this);
            web.evaluateJavascript("window.izinNotifEvent && window.izinNotifEvent()", null);
        }
    }

    public class Bridge {
        /** Menyimpan pengaturan notifikasi dan menyalakan atau mematikan pemantau. Hanya alamat Firebase yang diterima. */
        @JavascriptInterface
        public void aturNotif(String json) {
            try {
                JSONObject o = new JSONObject(json);
                if (!PantauService.sah(o)) return;
                getSharedPreferences(PantauService.PREFS, MODE_PRIVATE).edit().putString(PantauService.KEY_CFG, o.toString()).apply();
                PantauService.mulaiAtauBerhenti(MainActivity.this);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean izinNotif() {
            return Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void mintaIzinNotif() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                }
            });
        }

        @JavascriptInterface
        public boolean bebasBaterai() {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void mintaBebasBaterai() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception ignored) { }
                }
            });
        }

        /** Menyimpan teks CSV: membuka pemilih berkas supaya pengguna menentukan nama dan lokasi. Aman, tanpa izin penyimpanan. */
        @JavascriptInterface
        public void simpanCsv(String nama, String teks) {
            if (teks == null || teks.length() == 0) { emit("csv", false, "Tidak ada data untuk disimpan", -1); return; }
            if (teks.length() > MAX_CSV) { emit("csv", false, "Data terlalu besar untuk satu file", -1); return; }
            String aman = (nama == null ? "" : nama).replaceAll("[^A-Za-z0-9._-]", "_");
            if (aman.length() == 0 || aman.length() > 80) aman = "data-pompa.csv";
            if (!aman.endsWith(".csv")) aman = aman + ".csv";
            final String judul = aman;
            byte[] isi = teks.getBytes(Charset.forName("UTF-8"));
            // BOM UTF-8 supaya Excel membaca huruf dengan benar
            byte[] d = new byte[isi.length + 3];
            d[0] = (byte) 0xEF; d[1] = (byte) 0xBB; d[2] = (byte) 0xBF;
            System.arraycopy(isi, 0, d, 3, isi.length);
            csvData = d;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("text/csv");
                        i.putExtra(Intent.EXTRA_TITLE, judul);
                        startActivityForResult(i, REQ_CSV);
                    } catch (Exception e) {
                        csvData = null;
                        emit("csv", false, "Tidak ada aplikasi pemilih berkas di ponsel ini", -1);
                    }
                }
            });
        }

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
