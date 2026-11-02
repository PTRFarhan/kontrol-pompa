package com.ptrfarhan.kontrolpompa;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Otak pemantau notifikasi. Tidak memakai kelas Android, jadi bisa diuji di komputer.
 * Menyimpan salinan data folder Firebase (diperbarui dari aliran put dan patch), lalu menilai aturan
 * dan menghasilkan pesan yang perlu ditampilkan.
 */
final class PantauLogika {
    static final long PUTUS_MS = 3L * 60L * 1000L;

    static final class Aturan {
        boolean rendah = true, penuh = true, putus = true, pompa = false;
        int rendahPct = 20, penuhPct = 95;
        String nama = "";

        boolean adaYangAktif() { return rendah || penuh || putus || pompa; }

        static Aturan dari(JSONObject o) {
            Aturan a = new Aturan();
            a.rendah = o.optBoolean("rendah", true);
            a.penuh = o.optBoolean("penuh", true);
            a.putus = o.optBoolean("putus", true);
            a.pompa = o.optBoolean("pompa", false);
            a.rendahPct = Math.max(1, Math.min(60, o.optInt("rendahPct", 20)));
            a.penuhPct = Math.max(60, Math.min(100, o.optInt("penuhPct", 95)));
            a.nama = o.optString("nama", "");
            return a;
        }
    }

    static final class Pesan {
        final int id; final String judul, isi;
        Pesan(int id, String judul, String isi) { this.id = id; this.judul = judul; this.isi = isi; }
    }

    private JSONObject root = new JSONObject();
    private final boolean[] rendahKirim = new boolean[2], penuhKirim = new boolean[2];
    private boolean putusKirim = false;
    private long tsTerakhir = 0, tsBerubahMs = -1;
    private String pompaTerakhir = null;

    /** Terapkan satu kejadian aliran Firebase. jenis: "put" atau "patch". json: {"path":"/..","data":...}. */
    void terapkan(String jenis, String json) throws JSONException {
        JSONObject m = new JSONObject(json);
        String path = m.optString("path", "/");
        Object data = m.has("data") ? m.get("data") : JSONObject.NULL;
        boolean gabung = "patch".equals(jenis);
        if ("/".equals(path) || path.isEmpty()) {
            if (gabung && data instanceof JSONObject) gabungkan(root, (JSONObject) data);
            else root = (data instanceof JSONObject) ? (JSONObject) data : new JSONObject();
            return;
        }
        String[] bagian = path.split("/");
        JSONObject cur = root;
        List<String> kunci = new ArrayList<>();
        for (String b : bagian) if (!b.isEmpty()) kunci.add(b);
        for (int i = 0; i < kunci.size() - 1; i++) {
            Object n = cur.opt(kunci.get(i));
            if (!(n instanceof JSONObject)) { n = new JSONObject(); cur.put(kunci.get(i), n); }
            cur = (JSONObject) n;
        }
        String k = kunci.get(kunci.size() - 1);
        if (data == JSONObject.NULL) cur.remove(k);
        else if (gabung && cur.opt(k) instanceof JSONObject && data instanceof JSONObject) gabungkan((JSONObject) cur.get(k), (JSONObject) data);
        else cur.put(k, data);
    }

    private static void gabungkan(JSONObject ke, JSONObject dari) throws JSONException {
        Iterator<String> it = dari.keys();
        List<String> semua = new ArrayList<>();
        while (it.hasNext()) semua.add(it.next());
        for (String k : semua) {
            Object v = dari.get(k);
            if (v == JSONObject.NULL) ke.remove(k); else ke.put(k, v);
        }
    }

    /** Persen isi toren, atau -1 bila belum ada pembacaan yang sah. */
    static double persen(JSONObject t) {
        if (t == null) return -1;
        double jarak = t.optDouble("jarak", -1), kosong = t.optDouble("kosong", 100), penuh = t.optDouble("penuh", 20);
        if (!(jarak > 0) || !(kosong > penuh)) return -1;
        double p = (kosong - jarak) / (kosong - penuh) * 100.0;
        return Math.max(0, Math.min(100, p));
    }

    List<Pesan> evaluasi(Aturan a, long sekarangMs) {
        List<Pesan> out = new ArrayList<>();
        JSONObject toren = root.optJSONObject("toren");
        String[] id = { "d7", "d8" };
        for (int i = 0; i < 2; i++) {
            JSONObject t = toren == null ? null : toren.optJSONObject(id[i]);
            double p = persen(t);
            if (p < 0) continue;
            int bulat = (int) Math.round(p);
            long liter = Math.round(p / 100.0 * t.optDouble("kapasitas", 0));
            String nm = "Toren " + id[i].toUpperCase();
            if (a.rendah) {
                if (bulat <= a.rendahPct && !rendahKirim[i]) { rendahKirim[i] = true; out.add(new Pesan(100 + i, nm + " hampir kosong", "Sisa " + bulat + "% (" + liter + " liter)")); }
                else if (bulat >= a.rendahPct + 10) rendahKirim[i] = false;
            } else rendahKirim[i] = false;
            if (a.penuh) {
                if (bulat >= a.penuhPct && !penuhKirim[i]) { penuhKirim[i] = true; out.add(new Pesan(110 + i, nm + " penuh", "Terisi " + bulat + "% (" + liter + " liter)")); }
                else if (bulat <= a.penuhPct - 10) penuhKirim[i] = false;
            } else penuhKirim[i] = false;
        }

        JSONObject per = root.optJSONObject("perangkat");
        long ts = per == null ? 0 : per.optLong("ts", 0);
        if (ts != 0) {
            if (tsBerubahMs < 0) { tsTerakhir = ts; tsBerubahMs = sekarangMs; }
            else if (ts != tsTerakhir) {
                tsTerakhir = ts; tsBerubahMs = sekarangMs;
                if (putusKirim) { putusKirim = false; if (a.putus) out.add(new Pesan(120, "Alat tersambung kembali", "Alat kembali mengirim kabar.")); }
            } else if (a.putus && !putusKirim && sekarangMs - tsBerubahMs >= PUTUS_MS) {
                putusKirim = true;
                out.add(new Pesan(120, "Alat terputus", "Tidak ada kabar dari alat lebih dari " + (PUTUS_MS / 60000L) + " menit. Periksa listrik dan WiFi di lokasi."));
            }
        }

        JSONObject st = null;
        JSONObject pompa = root.optJSONObject("pompa");
        if (pompa != null) st = pompa.optJSONObject("status");
        if (st != null && st.has("state")) {
            String s = st.optString("state", "");
            if (pompaTerakhir == null) pompaTerakhir = s;
            else if (!s.equals(pompaTerakhir)) {
                pompaTerakhir = s;
                String sumber = st.optString("sumber", ""), oleh = st.optString("oleh", "");
                boolean dariSaya = "APP".equals(sumber) && !a.nama.isEmpty() && a.nama.equalsIgnoreCase(oleh);
                if (a.pompa && !dariSaya) {
                    String dari = "MANUAL".equals(sumber) ? "tombol manual" : ("JADWAL".equals(sumber) ? "jadwal" : ("APP".equals(sumber) ? "aplikasi" + (oleh.isEmpty() ? "" : " (" + oleh + ")") : "alat"));
                    out.add(new Pesan(130, "ON".equals(s) ? "Pompa menyala" : "Pompa mati", "Dari " + dari + "."));
                }
            }
        }
        return out;
    }
}
