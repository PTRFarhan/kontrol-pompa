package com.ptrfarhan.kontrolpompa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pemantau notifikasi. Berjalan sebagai layanan latar depan (ada ikon kecil di bilah status) dan membuka satu
 * aliran realtime ke folder data milikmu di Firebase. Tanpa server tambahan, tanpa Telegram, tanpa biaya.
 * Hanya terhubung ke alamat *.firebasedatabase.app, dan hanya membaca data.
 */
public class PantauService extends Service {
    static final String PREFS = "pantau", KEY_CFG = "cfg";
    private static final String CH_PANTAU = "pantau", CH_PERINGATAN = "peringatan";
    private static final int ID_PANTAU = 1;
    private static final Pattern FOLDER = Pattern.compile("^[A-Za-z0-9_-]{1,80}$");

    private final Object kunci = new Object();
    private volatile boolean jalan = false;
    private volatile HttpURLConnection koneksi = null;
    private volatile PantauLogika.Aturan aturan = new PantauLogika.Aturan();
    private volatile String alamat = "";
    private Thread thread;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;

    /** Alamat database harus https dan berakhiran .firebasedatabase.app, nama folder hanya huruf, angka, garis bawah, strip. */
    static boolean sah(JSONObject o) {
        try {
            String db = o.optString("db", ""), folder = o.optString("folder", "");
            if (!FOLDER.matcher(folder).matches()) return false;
            URI u = new URI(db);
            return "https".equals(u.getScheme()) && u.getHost() != null && u.getHost().endsWith(".firebasedatabase.app");
        } catch (Exception e) { return false; }
    }

    /** Menyalakan atau mematikan layanan sesuai pengaturan yang tersimpan. */
    static void mulaiAtauBerhenti(Context c) {
        boolean aktif = false;
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CFG, ""));
            aktif = sah(o) && PantauLogika.Aturan.dari(o).adaYangAktif();
        } catch (Exception ignored) { }
        Intent i = new Intent(c, PantauService.class);
        try {
            if (aktif) { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); }
            else c.stopService(i);
        } catch (Exception ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        buatSaluran();
        Notification n = notifTetap();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PANTAU, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_PANTAU, n);

        JSONObject cfg = null;
        try { cfg = new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CFG, "")); } catch (Exception ignored) { }
        if (cfg == null || !sah(cfg) || !PantauLogika.Aturan.dari(cfg).adaYangAktif()) { stopSelf(); return START_NOT_STICKY; }

        aturan = PantauLogika.Aturan.dari(cfg);
        String baru = cfg.optString("db") + "/data/" + cfg.optString("folder") + ".json";
        boolean ganti = !baru.equals(alamat);
        alamat = baru;
        if (!jalan) {
            jalan = true;
            daftarJaringan();
            thread = new Thread(new Runnable() { @Override public void run() { putar(); } }, "pantau");
            thread.start();
        } else if (ganti) {
            sambungUlang();
        }
        return START_STICKY;
    }

    private void putar() {
        long tunggu = 5000;
        while (jalan) {
            try {
                if (alirkan()) tunggu = 5000;
            } catch (Exception ignored) { }
            if (!jalan) break;
            synchronized (kunci) { try { kunci.wait(tunggu); } catch (InterruptedException e) { if (!jalan) break; } }
            tunggu = Math.min(tunggu * 2, 60000);
        }
    }

    /** Membuka aliran dan memproses kejadian sampai putus. Mengembalikan true bila sempat menerima data. */
    private boolean alirkan() throws Exception {
        final PantauLogika logika = new PantauLogika();
        HttpURLConnection c = (HttpURLConnection) new URL(alamat).openConnection();
        koneksi = c;
        boolean dapat = false;
        try {
            c.setRequestProperty("Accept", "text/event-stream");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);                          // Firebase mengirim keep-alive tiap sekitar 30 detik
            if (c.getResponseCode() != 200) return false;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String jenis = "", data = "", baris;
            while (jalan && (baris = r.readLine()) != null) {
                if (baris.startsWith("event:")) jenis = baris.substring(6).trim();
                else if (baris.startsWith("data:")) data = baris.substring(5).trim();
                else if (baris.isEmpty()) {
                    if ("cancel".equals(jenis) || "auth_revoked".equals(jenis)) break;
                    if (("put".equals(jenis) || "patch".equals(jenis)) && !data.isEmpty()) { logika.terapkan(jenis, data); dapat = true; }
                    if (!jenis.isEmpty()) {
                        List<PantauLogika.Pesan> pesan = logika.evaluasi(aturan, SystemClock.elapsedRealtime());
                        for (PantauLogika.Pesan p : pesan) tampil(p);
                    }
                    jenis = ""; data = "";
                }
            }
        } finally {
            koneksi = null;
            try { c.disconnect(); } catch (Exception ignored) { }
        }
        return dapat;
    }

    private void sambungUlang() {
        HttpURLConnection c = koneksi;
        if (c != null) { try { c.disconnect(); } catch (Exception ignored) { } }
        synchronized (kunci) { kunci.notifyAll(); }
    }

    private void daftarJaringan() {
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { sambungUlang(); }     // jaringan kembali: sambung ulang segera
            };
            cm.registerDefaultNetworkCallback(callback);
        } catch (Exception ignored) { }
    }

    private void buatSaluran() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel a = new NotificationChannel(CH_PANTAU, "Pemantauan berjalan", NotificationManager.IMPORTANCE_LOW);
        a.setDescription("Ikon kecil yang menandakan aplikasi sedang memantau toren dan pompa");
        nm.createNotificationChannel(a);
        NotificationChannel b = new NotificationChannel(CH_PERINGATAN, "Peringatan toren dan pompa", NotificationManager.IMPORTANCE_HIGH);
        b.setDescription("Toren hampir kosong atau penuh, alat terputus, pompa menyala atau mati");
        nm.createNotificationChannel(b);
    }

    private PendingIntent bukaAplikasi() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Builder pembuat(String saluran) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, saluran) : new Notification.Builder(this);
    }

    private Notification notifTetap() {
        Notification.Builder b = pembuat(CH_PANTAU)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Kontrol Pompa memantau")
                .setContentText("Notifikasi toren dan pompa aktif")
                .setContentIntent(bukaAplikasi())
                .setOngoing(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    private void tampil(PantauLogika.Pesan p) {
        try {
            Notification.Builder b = pembuat(CH_PERINGATAN)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(p.judul)
                    .setContentText(p.isi)
                    .setStyle(new Notification.BigTextStyle().bigText(p.isi))
                    .setContentIntent(bukaAplikasi())
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(p.id, b.build());
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        jalan = false;
        sambungUlang();
        if (thread != null) thread.interrupt();
        try { if (cm != null && callback != null) cm.unregisterNetworkCallback(callback); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
