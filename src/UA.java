import ua.UaUserLayer;

public class UA {
	public static void main(String[] args) throws Exception {
		
		System.out.println("UA launching with args: " + String.join(", ", args));
		
		String ua = args[0];
		int listenPort = Integer.parseInt(args[1]);
		String proxyAddress = args[2];//Esta es la ip
		int proxyPort = Integer.parseInt(args[3]);
		boolean debug = Boolean.parseBoolean(args[4]); 
		int registerTime = Integer.parseInt(args[5]);
		
		UaUserLayer userLayer = new UaUserLayer(listenPort, proxyAddress, proxyPort, ua, debug, registerTime);
		
		new Thread() {
			@Override
			public void run() {
				userLayer.startListeningNetwork();
			}
		}.start();
				
        userLayer.startListeningKeyboard();
	}
}