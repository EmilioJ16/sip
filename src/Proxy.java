import proxy.ProxyUserLayer;

public class Proxy {
	public static void main(String[] args) throws Exception {
		System.out.println("Proxy launching with args: " + String.join(", ", args));
		int listenPort = Integer.parseInt(args[0]);
		boolean loose_rooting = Boolean.parseBoolean(args[1]);
		boolean debug = Boolean.parseBoolean(args[2]);
		ProxyUserLayer userLayer = new ProxyUserLayer(listenPort, loose_rooting, debug);
		userLayer.startListening();
	}
}
