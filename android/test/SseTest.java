import java.lang.reflect.*; import java.util.*;
public class SseTest {
  public static void main(String[] a) throws Exception {
    Class<?> k = Class.forName("com.ptrfarhan.kontrolpompa.PantauService");
    Object svc = k.getDeclaredConstructor().newInstance();
    Field f = k.getDeclaredField("alamat"); f.setAccessible(true); f.set(svc, a[0]);
    Field jf = k.getDeclaredField("jalan"); jf.setAccessible(true); jf.set(svc, true);
    Field af = k.getDeclaredField("aturan"); af.setAccessible(true);
    Class<?> ak = Class.forName("com.ptrfarhan.kontrolpompa.PantauLogika$Aturan");
    Method dari = ak.getDeclaredMethod("dari", org.json.JSONObject.class); dari.setAccessible(true);
    af.set(svc, dari.invoke(null, new org.json.JSONObject("{\"pompa\":true,\"nama\":\"Budi\"}")));
    Method m = k.getDeclaredMethod("alirkan"); m.setAccessible(true);
    Object r = m.invoke(svc);
    System.out.println("dapat data: " + r + " | notifikasi terkirim (id): " + android.app.NotificationManager.dikirim);
    boolean ok = Boolean.TRUE.equals(r) && android.app.NotificationManager.dikirim.equals(Arrays.asList(100, 130));
    System.out.println(ok ? "  OK     aliran SSE diproses benar" : "  GAGAL  aliran SSE");
    System.exit(ok ? 0 : 1);
  }
}
