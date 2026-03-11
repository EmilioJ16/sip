package sipServlet;

public class ProxyImpl implements ProxyInterface {
    private String targetUri = null;

    @Override
    public void proxyTo(String uri) {
        this.targetUri = uri; // El servlet redirige aquí
    }

    public String getTargetUri() { return targetUri; }
}
