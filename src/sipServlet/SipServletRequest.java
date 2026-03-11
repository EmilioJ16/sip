package sipServlet;

public class SipServletRequest implements SipServletRequestInterface {
    private String caller;
    private String callee;
    //Guardamos quién es el "dueño" del Servlet (Bob, Alice, etc.)
    private String ownerUri;
    
    // Almacenamos el resultado de la ejecución del servlet aquí
    private SipServletResponse responseGenerated;
    private ProxyImpl proxyImpl;

    public SipServletRequest(String caller, String callee, String ownerUri) {
        this.caller = caller;
        this.callee = callee;
        this.ownerUri = ownerUri; // Guardamos la identidad
        this.proxyImpl = new ProxyImpl(); 
    }

    @Override
    public String getCallerURI() { return caller; }

    @Override
    public String getCalleeURI() { return callee; }
    
    public String getOwnerUri() { return ownerUri;}

    @Override
    public SipServletResponseInterface createResponse(int statuscode) {
        this.responseGenerated = new SipServletResponse(statuscode);
        return this.responseGenerated;
    }

    @Override
    public ProxyInterface getProxy() {
        return this.proxyImpl;
    }

    // --- Métodos para que el Proxy lea qué decidió el Servlet ---
    public SipServletResponse getResponseResult() {
        return responseGenerated;
    }

    public ProxyImpl getProxyResult() {
        return proxyImpl;
    }
}
