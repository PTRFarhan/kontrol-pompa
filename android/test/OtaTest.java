import com.ptrfarhan.kontrolpompa.EspOta;
import java.nio.file.*;
import java.util.*;

public class OtaTest {
    static int fails = 0;
    static void check(boolean ok, String msg) { System.out.println((ok ? "  OK     " : "  GAGAL  ") + msg); if (!ok) fails++; }
    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]); String pass = a[1]; Path bin = Paths.get(a[2]); String mode = a[3];
        byte[] data = Files.readAllBytes(bin);
        List<Integer> pcts = new ArrayList<>();
        try {
            EspOta.flash("127.0.0.1", port, pass, data, (p, m) -> pcts.add(p));
            System.out.println("RESULT success");
        } catch (Exception e) { System.out.println("RESULT error " + e.getMessage()); }
        if (mode.equals("good")) check(!pcts.isEmpty() && pcts.get(pcts.size() - 1) == 100, "progres sampai 100");
    }
}
