package com.ptrfarhan.kontrolpompa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Pengirim firmware ke ESP8266 lewat protokol yang sama dengan ArduinoOTA (espota.py di Arduino IDE).
 * Kelas ini tidak memakai API Android supaya bisa diuji di komputer.
 *
 * Alur: kirim undangan lewat UDP ke alat, jawab tantangan sandi (MD5, sandi tidak dikirim polos),
 * lalu alat menyambung balik lewat TCP dan firmware dikirim ke sana.
 */
public final class EspOta {
    public interface Listener { void onProgress(int percent, String message); }

    public static final int MAX_BIN = 1000 * 1024;   // batas aman partisi OTA ESP8266 4 MB

    private EspOta() {}

    /** Hanya alamat jaringan lokal yang diizinkan, supaya aplikasi tidak bisa dipakai menyerang alamat luar. */
    public static boolean isLocalIpv4(String ip) {
        if (ip == null || !ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return false;
        String[] p = ip.split("\\.");
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) { v[i] = Integer.parseInt(p[i]); if (v[i] > 255) return false; }
        if (v[0] == 10) return true;
        if (v[0] == 172 && v[1] >= 16 && v[1] <= 31) return true;
        if (v[0] == 192 && v[1] == 168) return true;
        if (v[0] == 169 && v[1] == 254) return true;
        return v[0] == 127;
    }

    /** Berkas firmware ESP8266 selalu diawali byte 0xE9. */
    public static boolean looksLikeEsp8266Image(byte[] d) {
        return d != null && d.length > 4096 && d.length <= MAX_BIN && (d[0] & 0xFF) == 0xE9;
    }

    static String md5Hex(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) { throw new IOException("MD5 tidak tersedia"); }
    }

    private static String str(byte[] b, int n) { return new String(b, 0, n, StandardCharsets.US_ASCII).trim(); }

    public static void flash(String ip, int otaPort, String password, byte[] data, Listener l) throws IOException {
        if (!isLocalIpv4(ip)) throw new IOException("Alamat alat harus alamat jaringan lokal, contoh 192.168.43.20");
        if (!looksLikeEsp8266Image(data)) throw new IOException("Berkas bukan firmware ESP8266 yang valid");
        if (password == null) password = "";
        String md5 = md5Hex(data);
        InetAddress target = InetAddress.getByName(ip);

        ServerSocket server = new ServerSocket(0);          // alat akan menyambung balik ke sini
        DatagramSocket udp = new DatagramSocket();
        Socket conn = null;
        try {
            int localPort = server.getLocalPort();
            String invite = "0 " + localPort + " " + data.length + " " + md5 + "\n";
            byte[] inv = invite.getBytes(StandardCharsets.US_ASCII);
            l.onProgress(0, "Menghubungi alat " + ip + "...");

            udp.setSoTimeout(3000);
            String reply = null;
            byte[] buf = new byte[64];
            for (int attempt = 0; attempt < 5 && reply == null; attempt++) {
                udp.send(new DatagramPacket(inv, inv.length, target, otaPort));
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    udp.receive(in);
                    reply = str(buf, in.getLength());
                } catch (SocketTimeoutException e) { /* coba lagi */ }
            }
            if (reply == null) throw new IOException("Alat tidak menjawab. Pastikan alat dan ponsel di jaringan yang sama.");

            if (reply.startsWith("AUTH")) {
                String[] parts = reply.split("\\s+");
                if (parts.length < 2) throw new IOException("Jawaban alat tidak dikenal");
                String nonce = parts[1];
                String cnonce = md5Hex((ip + data.length + md5 + System.nanoTime()).getBytes(StandardCharsets.US_ASCII));
                String passMd5 = md5Hex(password.getBytes(StandardCharsets.UTF_8));
                String result = md5Hex((passMd5 + ":" + nonce + ":" + cnonce).getBytes(StandardCharsets.US_ASCII));
                byte[] auth = ("200 " + cnonce + " " + result + "\n").getBytes(StandardCharsets.US_ASCII);
                udp.send(new DatagramPacket(auth, auth.length, target, otaPort));
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    udp.receive(in);
                    reply = str(buf, in.getLength());
                } catch (SocketTimeoutException e) { throw new IOException("Sandi OTA salah atau alat tidak menjawab"); }
            }
            if (!reply.startsWith("OK")) throw new IOException("Alat menolak: " + reply);

            l.onProgress(1, "Alat siap, mengirim firmware...");
            server.setSoTimeout(20000);
            try { conn = server.accept(); }
            catch (SocketTimeoutException e) { throw new IOException("Alat tidak menyambung balik ke ponsel. Cek jaringan dan firewall."); }
            if (!conn.getInetAddress().equals(target)) throw new IOException("Sambungan datang dari alamat yang tidak dikenal");

            final Socket sock = conn;
            final long[] last = { System.currentTimeMillis() };
            final boolean[] done = { false };
            Thread watchdog = new Thread(() -> {                // putuskan bila tidak ada kemajuan 20 detik
                while (!done[0]) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    if (System.currentTimeMillis() - last[0] > 20000) { try { sock.close(); } catch (IOException ignored) {} return; }
                }
            });
            watchdog.setDaemon(true); watchdog.start();

            try {
                OutputStream out = sock.getOutputStream();
                InputStream in = sock.getInputStream();
                sock.setSoTimeout(200);
                final int chunk = 1460;
                int sent = 0, lastPct = -1;
                byte[] sink = new byte[64];
                StringBuilder tail = new StringBuilder();
                while (sent < data.length) {
                    int n = Math.min(chunk, data.length - sent);
                    out.write(data, sent, n);
                    sent += n; last[0] = System.currentTimeMillis();
                    try { int r = in.available(); if (r > 0) { r = in.read(sink, 0, Math.min(r, sink.length)); if (r > 0) tail.append(new String(sink, 0, r, StandardCharsets.US_ASCII)); } }
                    catch (IOException ignored) { }
                    int pct = (int) ((long) sent * 98 / data.length) + 1;
                    if (pct != lastPct) { lastPct = pct; l.onProgress(pct, "Mengirim " + (sent / 1024) + " dari " + (data.length / 1024) + " KB"); }
                }
                out.flush();
                l.onProgress(99, "Menunggu alat menulis dan memeriksa firmware...");
                sock.setSoTimeout(1000);
                long deadline = System.currentTimeMillis() + 60000;
                boolean ok = tail.indexOf("OK") >= 0;
                while (!ok && System.currentTimeMillis() < deadline) {
                    try {
                        int r = in.read(sink);
                        if (r < 0) break;
                        last[0] = System.currentTimeMillis();
                        tail.append(new String(sink, 0, r, StandardCharsets.US_ASCII));
                        ok = tail.indexOf("OK") >= 0;
                    } catch (SocketTimeoutException e) { last[0] = System.currentTimeMillis(); }
                }
                if (!ok) throw new IOException("Alat tidak mengonfirmasi. Update mungkin gagal, cek layar alat.");
            } finally { done[0] = true; }
            l.onProgress(100, "Selesai. Alat akan restart.");
        } finally {
            try { if (conn != null) conn.close(); } catch (IOException ignored) {}
            try { server.close(); } catch (IOException ignored) {}
            udp.close();
        }
    }
}
