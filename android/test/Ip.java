import com.ptrfarhan.kontrolpompa.EspOta;
public class Ip { public static void main(String[] a){ for(String s: new String[]{"192.168.43.20","10.0.0.5","172.20.1.1","8.8.8.8","1.2.3.4","300.1.1.1","192.168.1","pompa.local","172.32.0.1"}) System.out.println(s+" -> "+EspOta.isLocalIpv4(s)); } }
