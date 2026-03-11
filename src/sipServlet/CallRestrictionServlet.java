package sipServlet;

import java.time.LocalTime;

public class CallRestrictionServlet implements SIPServletInterface {

    @Override
    public void doInvite(SipServletRequestInterface requestInterface) {
        // Hacemos casting para acceder al método extra getOwnerUri()
        SipServletRequest request = (SipServletRequest) requestInterface;

        String caller = request.getCallerURI();
        String callee = request.getCalleeURI();
        String myUserUri = request.getOwnerUri(); // "sip:bob@SMA" (o quien sea)
        
        int currentHour = LocalTime.now().getHour();

        System.out.println("[Servlet] Ejecutando reglas para el usuario: " + myUserUri);

        // Comparamos el destino (Callee) con el dueño del Servlet (myUserUri) DUEÑO BOB PORQUE ESTÁ EN XML
        boolean soyElLlamado = callee.equals(myUserUri);
        
        if (soyElLlamado) {
            // --- REGLA 1: LLAMADAS ENTRANTES (Alguien llama al Dueño) ---
            System.out.println("[Servlet] Modo: LLAMADA ENTRANTE (Incoming)");
            
            boolean horarioEntrada = (currentHour >= 9 && currentHour < 17);
            boolean esJefe = caller.contains("boss");

            if (horarioEntrada && esJefe) {
                 System.out.println("[Servlet] Jefe llamando en horario. Redirigiendo.");
                 // Redirigir a C (Jose)
                 request.getProxy().proxyTo("sip:jose@SMA");
            } else {
                 System.out.println("[Servlet] Rechazando llamada entrante (486).");
                 request.createResponse(486).send();
            }
            
        } else {
            // --- REGLA 2: LLAMADAS SALIENTES (El Dueño llama a fuera) ---
            System.out.println("[Servlet] Modo: LLAMADA SALIENTE (Outgoing)");
            
            boolean horarioSalida = (currentHour >= 10 && currentHour < 11);
            
            if (horarioSalida) {
                System.out.println("[Servlet] Llamada saliente permitida (Horario 10-11).");
                
            } else {
                System.out.println("[Servlet] Llamada saliente bloqueada (Fuera de horario). Enviando 486.");
                request.createResponse(486).send();
            }
        }
    }
}