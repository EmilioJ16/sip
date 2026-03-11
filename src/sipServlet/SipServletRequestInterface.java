package sipServlet;

public interface SipServletRequestInterface {
    String getCallerURI();
    String getCalleeURI();
    SipServletResponseInterface createResponse(int statuscode);
    ProxyInterface getProxy();
}
