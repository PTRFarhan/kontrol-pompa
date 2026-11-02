import com.ptrfarhan.kontrolpompa.*;
import org.json.*;
import java.util.*;
public class LogikaTest {
  static int ok=0, gagal=0;
  static void cek(boolean c, String m){ if(c){ok++; System.out.println("  OK     "+m);} else {gagal++; System.out.println("  GAGAL  "+m);} }
  public static void main(String[] x) throws Exception {
    Class<?> k = Class.forName("com.ptrfarhan.kontrolpompa.PantauLogika");
    java.lang.reflect.Constructor<?> ct = k.getDeclaredConstructor(); ct.setAccessible(true);
    Object L = ct.newInstance();
    java.lang.reflect.Method terapkan = k.getDeclaredMethod("terapkan", String.class, String.class); terapkan.setAccessible(true);
    java.lang.reflect.Method evaluasi = k.getDeclaredMethod("evaluasi", Class.forName("com.ptrfarhan.kontrolpompa.PantauLogika$Aturan"), long.class); evaluasi.setAccessible(true);
    Class<?> ak = Class.forName("com.ptrfarhan.kontrolpompa.PantauLogika$Aturan");
    java.lang.reflect.Method dari = ak.getDeclaredMethod("dari", JSONObject.class); dari.setAccessible(true);
    Object A = dari.invoke(null, new JSONObject("{\"rendah\":true,\"rendahPct\":20,\"penuh\":true,\"penuhPct\":95,\"putus\":true,\"pompa\":true,\"nama\":\"Budi\"}"));
    class H { Object L; java.lang.reflect.Method ev; Object A; List<String> ev(long t) throws Exception { List<?> r=(List<?>)ev.invoke(L,A,t); List<String> o=new ArrayList<>(); for(Object p:r){ java.lang.reflect.Field f=p.getClass().getDeclaredField("judul"); f.setAccessible(true); o.add((String)f.get(p)); } return o; } }
    H h = new H(); h.L=L; h.ev=evaluasi; h.A=A;
    System.out.println("Awal: snapshot penuh");
    terapkan.invoke(L,"put","{\"path\":\"/\",\"data\":{\"toren\":{\"d7\":{\"jarak\":55.5,\"kosong\":100,\"penuh\":20,\"kapasitas\":1000},\"d8\":{\"jarak\":35.2,\"kosong\":100,\"penuh\":20,\"kapasitas\":1000}},\"pompa\":{\"status\":{\"state\":\"OFF\",\"sumber\":\"-\"}},\"perangkat\":{\"ts\":1000}}}");
    cek(h.ev(0).isEmpty(), "toren 56% dan 81%, tidak ada notifikasi");
    terapkan.invoke(L,"patch","{\"path\":\"/toren/d7\",\"data\":{\"jarak\":90,\"n\":4}}");
    List<String> r = h.ev(10000);
    cek(r.size()==1 && r.get(0).equals("Toren D7 hampir kosong"), "D7 turun ke 12%, notifikasi hampir kosong: "+r);
    cek(h.ev(20000).isEmpty(), "tidak berulang selama masih rendah");
    terapkan.invoke(L,"put","{\"path\":\"/toren/d7/jarak\",\"data\":70}");
    cek(h.ev(30000).isEmpty(), "naik ke 37%, sudah di atas batas pemulihan");
    terapkan.invoke(L,"put","{\"path\":\"/toren/d7/jarak\",\"data\":88}");
    r = h.ev(40000); cek(r.contains("Toren D7 hampir kosong"), "turun lagi ke 15% setelah pulih: diberi tahu ulang "+r);
    terapkan.invoke(L,"put","{\"path\":\"/toren/d8/jarak\",\"data\":21}");
    r = h.ev(50000); cek(r.contains("Toren D8 penuh"), "D8 penuh: "+r);
    terapkan.invoke(L,"put","{\"path\":\"/toren/d8/jarak\",\"data\":null}");
    cek(h.ev(55000).isEmpty(), "pembacaan hilang tidak memicu apa pun");
    System.out.println("Pompa");
    terapkan.invoke(L,"patch","{\"path\":\"/pompa/status\",\"data\":{\"state\":\"ON\",\"sumber\":\"JADWAL\",\"oleh\":\"\"}}");
    r = h.ev(60000); cek(r.contains("Pompa menyala"), "pompa menyala dari jadwal: "+r);
    terapkan.invoke(L,"patch","{\"path\":\"/pompa/status\",\"data\":{\"state\":\"OFF\",\"sumber\":\"APP\",\"oleh\":\"budi\"}}");
    cek(h.ev(70000).isEmpty(), "dimatikan oleh diri sendiri (Budi), tidak diberitahu");
    terapkan.invoke(L,"patch","{\"path\":\"/pompa/status\",\"data\":{\"state\":\"ON\",\"sumber\":\"APP\",\"oleh\":\"Sari\"}}");
    r = h.ev(80000); cek(r.contains("Pompa menyala"), "pompa dinyalakan Sari: "+r);
    System.out.println("Alat terputus");
    terapkan.invoke(L,"put","{\"path\":\"/perangkat/ts\",\"data\":2000}");
    cek(h.ev(90000).isEmpty(), "detak baru, normal");
    cek(h.ev(90000+120000).isEmpty(), "2 menit tanpa detak, belum dianggap terputus");
    r = h.ev(90000+190000); cek(r.contains("Alat terputus"), "lebih dari 3 menit: "+r);
    cek(h.ev(90000+250000).isEmpty(), "tidak berulang");
    terapkan.invoke(L,"put","{\"path\":\"/perangkat\",\"data\":{\"ts\":3000,\"ssid\":\"x\"}}");
    r = h.ev(90000+260000); cek(r.contains("Alat tersambung kembali"), "pulih: "+r);
    System.out.println("Data rusak");
    boolean aman=false; try{ terapkan.invoke(L,"put","bukan json"); }catch(Exception e){ aman=true; }
    cek(aman, "JSON rusak melempar galat yang bisa ditangkap");
    System.out.println("-> "+ok+" lulus, "+gagal+" gagal");
  }
}
