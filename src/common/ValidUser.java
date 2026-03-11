package common;

public class ValidUser {
	private String userName;
    private String ipAddress;
    private int port;
	private long expirationTime;
	
	public ValidUser(String userName, String ipAddress, int port, int expiresSeconds) {
		this.userName = userName;
        this.ipAddress = ipAddress;
        this.port = port;
        this.expirationTime = System.currentTimeMillis() + (long) expiresSeconds * 1000;
	}
	

	public String getUserName() { return userName; }
    public String getIpAddress() { return ipAddress; }
    public int getPort() { return port; }
    public long getExpirationTime() { return expirationTime; }

    public void setExpirationTime(long expirationTime) { this.expirationTime = expirationTime; }
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setPort(int port) {
        this.port = port;
    }

    
    public boolean isExpired() {
        return System.currentTimeMillis() > expirationTime;
    }

    @Override
    public String toString() {
        return userName + "@" + ipAddress + ":" + port + " (expira en " + (expirationTime - System.currentTimeMillis())/1000 + "s)";
    }
}
