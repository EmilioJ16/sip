package common;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

public class FindMyIPv4 {
    public static void main(String[] args) throws SocketException, UnknownHostException {
        System.out.println(findMyIPv4Address().getHostAddress());
    }

    public static Inet4Address findMyIPv4Address() throws SocketException, UnknownHostException {
        Inet4Address myAddr = null;
        Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces();
        
        while (n.hasMoreElements()) {
            NetworkInterface e = n.nextElement();

            // Si la interfaz es virbr0, docker, vnet, etc., la saltamos.
            if (e.getName().startsWith("virbr") || e.getName().startsWith("docker") || e.getName().startsWith("vnet")) {
                continue;
            }

            Enumeration<InetAddress> a = e.getInetAddresses();
            while (a.hasMoreElements()) {
                InetAddress addr = a.nextElement();
                if (!(addr instanceof Inet4Address)) {
                    continue;
                }
                if (addr.isLoopbackAddress()) {
                    continue;
                }
                
                myAddr = (Inet4Address) addr;
                return myAddr; // Devolvemos inmediatamente la IP de eth0
            }
        }
        
        if (myAddr == null) {
            InetAddress loopback = InetAddress.getLocalHost();
            if (loopback instanceof Inet4Address) {
                myAddr = (Inet4Address) loopback;
            }
        }
        return myAddr;
    }
}
