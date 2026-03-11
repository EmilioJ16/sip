package sipServlet;

public class SipServletResponse implements SipServletResponseInterface {
    private int status;
    
    // ESTE ES EL ATRIBUTO "OCULTO" AL SERVLET PERO VISIBLE AL PROXY
    private boolean sent = false; 

    public SipServletResponse(int status) {
        this.status = status;
    }

    // Método de la Interfaz (Lo que usa el Servlet)
    @Override
    public void send() {
        // Cuando el Servlet llama a esto, "marcamos" el objeto
        this.sent = true; 
    }

    // Lo que usa el Proxy
    public int getStatus() { return status; }
    
    public boolean isSent() { return sent; }
}