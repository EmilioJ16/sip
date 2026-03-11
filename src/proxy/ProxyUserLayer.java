package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;

import common.ValidUser;
import mensajesSIP.*;
import common.FindMyIPv4;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;

import java.util.concurrent.ConcurrentHashMap; // Import necesario para el mapa

import java.util.HashMap;
import java.util.Map;
import sipServlet.*;
import java.io.InputStream;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import javax.xml.bind.JAXBException;

public class ProxyUserLayer {
    
    // --- CLASE INTERNA PARA GESTIONAR EL ESTADO DE CADA LLAMADA ---
    private class CallTransaction {
        int state = STATE_IDLE;
        String callId;
        Timer retransmissionTimer;
        TimerTask retransmissionTask;
        SIPMessage lastErrorSent;
        String lastErrorAddress;
        int lastErrorPort;

        public CallTransaction(String callId) {
            this.callId = callId;
        }

        // Método para cancelar el timer específico de esta llamada
        public void cancelTimer() {
            if (retransmissionTask != null) {
                retransmissionTask.cancel();
                retransmissionTask = null;
            }
            if (retransmissionTimer != null) {
                retransmissionTimer.cancel();
                retransmissionTimer.purge();
                retransmissionTimer = null;
            }
            this.lastErrorSent = null;
        }
    }
    // -----------------------------------------------------------------------

    // Proxy config
    private ProxyTransactionLayer transactionLayer;
    private ArrayList<ValidUser> validUsersList = new ArrayList<>();
    private ArrayList<ValidUser> usuariosRegistrados = new ArrayList<>();
    
    // --- MAPA DE TRANSACCIONES ACTIVAS ---
    // Clave: Call-ID, Valor: Objeto con el estado de esa llamada
    private ConcurrentHashMap<String, CallTransaction> activeTransactions = new ConcurrentHashMap<>();
    
 // --- Mapa para Servlets (URI -> ClassName) ---
    private Map<String, String> servletMapping = new HashMap<>();
    
    private boolean debug = true;
    private boolean loose_rooting;
    
    private String proxyAddress;
    private int proxyPort;
    
    // Estados 
    private static final int STATE_IDLE = 0;
    private static final int STATE_PROCEEDING = 1; 
    private static final int STATE_COMPLETED = 2; 

    // Inicializa
    public ProxyUserLayer(int listenPort, boolean loose_rooting, boolean debug) throws SocketException, UnknownHostException {
        this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
        this.debug = debug;
        this.loose_rooting = loose_rooting;
        
        this.proxyPort = listenPort;
        this.proxyAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
        System.out.println("[Proxy] Listening on: " + this.proxyAddress + ":" + this.proxyPort);
        
        initAllowedUsers();
        
     // --- Cargar users.xml ---
        loadServletMapping();
        
        if(debug) {
            for (ValidUser u : validUsersList) {
                System.out.println("Usuario "+ u + u.getUserName());
            }
        }
    }
    
    public void startListening() {
        transactionLayer.startListening();
    }
    
    private void initAllowedUsers() {
        int ten_YEARS = 10 * 365 * 24 * 3600; 
        validUsersList.add(new ValidUser("alice", "0.0.0.0", 0, ten_YEARS));
        validUsersList.add(new ValidUser("bob", "0.0.0.0", 0, ten_YEARS));
        validUsersList.add(new ValidUser("jose", "0.0.0.0", 0, ten_YEARS));
        validUsersList.add(new ValidUser("emilio", "0.0.0.0", 0, ten_YEARS));
        validUsersList.add(new ValidUser("boss", "0.0.0.0", 0, ten_YEARS));
        if (debug) System.out.println("[Proxy] Usuarios válidos: " + validUsersList);
    }
    
 // --- Método para parsear XML y llenar el mapa ---
    private void loadServletMapping() {
        System.out.println("[Proxy] Cargando configuración de servlets desde XML...");
        
        try (InputStream xml = getClass().getResourceAsStream("/sipServlet/users.xml")) {
            
            // 1. Detección de Fichero No Encontrado
            if (xml == null) {
                System.err.println("[Proxy Error] El fichero /sipServlet/users.xml no existe en el classpath.");
                return;
            }

            JAXBContext jaxbContext = JAXBContext.newInstance(Users.class);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
            
            // 2. Detección de Etiquetas Mal Formadas (Sintaxis)
            // Si el XML está roto, esta línea lanzará JAXBException
            Users users = (Users) jaxbUnmarshaller.unmarshal(xml);
            
            if (users.getListUsers() == null) {
                System.out.println("[Proxy] El XML es válido pero no contiene usuarios.");
                return;
            }

            // 3. Detección de Contenidos Incorrectos (Validación de Datos)
            for (User user : users.getListUsers()) {
                String uri = user.getId();
                
                // Validación A: ¿Tiene el objeto Servlet-class?
                if (user.getServletClass() == null) {
                    System.err.println("[Proxy XML Error] Usuario ignorado: Falta la etiqueta <Servlet-class> para el id: " + uri);
                    continue; // Saltamos este usuario
                }

                String className = user.getServletClass().getName();

                // Validación B: ¿Son los datos cadenas válidas?
                boolean uriInvalida = (uri == null || uri.trim().isEmpty() || !uri.startsWith("sip:"));
                boolean classInvalida = (className == null || className.trim().isEmpty());

                if (uriInvalida) {
                    System.err.println("[Proxy XML Error] ID de usuario inválido o vacío: '" + uri + "'. Ignorado.");
                    continue;
                }

                if (classInvalida) {
                    System.err.println("[Proxy XML Error] Nombre de clase vacío para el usuario " + uri + ". Ignorado.");
                    continue;
                }

                // Si todo es correcto, lo añadimos al mapa
                servletMapping.put(uri, className);
                System.out.println("[Proxy] Servlet registrado OK: " + uri + " -> " + className);
            }

        } catch (JAXBException e) {
            // ESTO CAPTURA LAS "ETIQUETAS MAL FORMADAS"
            System.err.println("[Proxy XML Critical Error] El fichero users.xml está mal formado o tiene errores de sintaxis.");
            System.err.println("Detalle: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[Proxy Error] Error inesperado leyendo XML.");
            e.printStackTrace();
        }
    }
    
    // --- MÉTODOS AUXILIARES PARA TRANSACCIONES ---
    
    // Obtiene la transacción existente o crea una nueva si es un INVITE nuevo
    private CallTransaction getTransaction(String callId) {
        return activeTransactions.computeIfAbsent(callId, k -> new CallTransaction(k));
    }

    // Elimina la transacción y limpia sus timers (se llama al terminar la llamada)
    private void removeTransaction(String callId, String reason) {
        CallTransaction ctx = activeTransactions.remove(callId);
        if (ctx != null) {
            ctx.cancelTimer();
            if (debug) System.out.println("[Proxy] Transacción " + callId + " finalizada/eliminada: " + reason);
        }
    }
    // -----------------------------------------------------

    
    // GESTIÓN DE MENSAJES RECIBIDOS
    
    public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
        String cid = inviteMessage.getCallId();
        
        // RECUPERAMOS EL CONTEXTO DE ESTA LLAMADA ESPECÍFICA
        CallTransaction ctx = getTransaction(cid);

        System.out.println("Received INVITE from " + inviteMessage.getFromName() + " to " + inviteMessage.getToName() + " (CID: " + cid + ")");

        ArrayList<String> vias = inviteMessage.getVias();
        String origin = vias.get(0);
        String[] originParts = origin.split(":");
        String originAddress = originParts[0];
        int originPort = Integer.parseInt(originParts[1]);
        
        // Comprobar retransmisión usando el estado de ESTA transacción
        if (ctx.state != STATE_IDLE) {
            System.out.println("[Proxy] Retransmisión de INVITE recibida para " + cid + ", ignorando.");
            return;
        }
        

        System.out.println("[Proxy State: IDLE -> PROCEEDING] para CID: " + cid);
        ctx.state = STATE_PROCEEDING;
        
        // --- ENVIAR 100 TRYING ---
        TryingMessage tryingMessage = new TryingMessage();
        tryingMessage.setVias(vias);
        tryingMessage.setToName(inviteMessage.getToName());
        tryingMessage.setToUri(inviteMessage.getToUri());
        tryingMessage.setFromName(inviteMessage.getFromName());
        tryingMessage.setFromUri(inviteMessage.getFromUri());
        tryingMessage.setCallId(cid);
        tryingMessage.setcSeqNumber(inviteMessage.getcSeqNumber());
        tryingMessage.setcSeqStr(inviteMessage.getcSeqStr());
        tryingMessage.setContentLength(0);

        transactionLayer.send100Trying(tryingMessage, originAddress, originPort);
        if (debug) showMessage(tryingMessage);
        
        // --- INICIO MOTOR DE SERVLETS ---
        String callerUri = inviteMessage.getFromUri(); 
        String calleeUri = inviteMessage.getToUri();
        String ownerUri = null;
        String servletClassName = null;
        
        // 1. Buscar Servlet (Prioridad: Llamado -> Llamante)
        servletClassName = servletMapping.get(calleeUri);
        if (servletClassName != null) {
            ownerUri = calleeUri; // El dueño es el Llamado (Bob)
        } else {
            // Si no, buscamos por el CALLER (Llamante)
            servletClassName = servletMapping.get(callerUri);
            if (servletClassName != null) {
                ownerUri = callerUri; // El dueño es el Llamante (Bob llamando a alguien)
            }
        }
        
        String forwardedUri = null; // Solo mantenemos esta variable
        
        if (servletClassName != null) {
        	System.out.println("[Proxy] Ejecutando Servlet de: " + ownerUri + " (" + servletClassName + ")");
            try {
                // Instanciación dinámica
                Class<?> clazz = Class.forName(servletClassName);
                SIPServletInterface servlet = (SIPServletInterface) clazz.getDeclaredConstructor().newInstance();
                
                // Preparar Request
                SipServletRequest request = new SipServletRequest(callerUri, calleeUri, ownerUri);
                
                // Ejecutar doInvite
                servlet.doInvite(request);
                
                // Leer resultado
                SipServletResponse response = request.getResponseResult();
                ProxyImpl proxyResult = request.getProxyResult();

                if (response != null && response.isSent()) {
                    // El Servlet decidió rechazar la llamada
                    System.out.println("[Proxy] Servlet rechazó la llamada. Enviando 486 Busy Here.");
                    
                    // --- CONSTRUCCIÓN DEL ERROR (486 Busy Here) ---
                    BusyHereMessage errorMessage = new BusyHereMessage();
                    
                    errorMessage.setVias(vias);
                    errorMessage.setToName(inviteMessage.getToName());
                    errorMessage.setToUri(inviteMessage.getToUri());
                    errorMessage.setFromName(inviteMessage.getFromName());
                    errorMessage.setFromUri(inviteMessage.getFromUri());
                    errorMessage.setCallId(cid);
                    errorMessage.setcSeqNumber(inviteMessage.getcSeqNumber());
                    errorMessage.setcSeqStr("INVITE");
                    errorMessage.setContentLength(0);

                    System.out.println("[Proxy State: PROCEEDING -> COMPLETED] (Rechazo por Servlet)");
                    ctx.state = STATE_COMPLETED;
                    ctx.lastErrorSent = errorMessage;
                    ctx.lastErrorAddress = originAddress;
                    ctx.lastErrorPort = originPort;

                    // Usamos el método específico para enviar 486
                    transactionLayer.send486BusyHere(errorMessage, originAddress, originPort);
                    startErrorRetransmissionTimer(ctx);
                    
                    if (debug) showMessage(errorMessage);
                    // -------------------------------------

                    return;
                } 
                else if (proxyResult.getTargetUri() != null) {
                    // El Servlet decidió redirigir
                    System.out.println("[Proxy] Servlet redirige llamada a: " + proxyResult.getTargetUri());
                    forwardedUri = proxyResult.getTargetUri();
                    
                    inviteMessage.setToUri(forwardedUri);
                    String newName = forwardedUri.substring(4, forwardedUri.indexOf('@'));
                    inviteMessage.setToName(newName);
                }

            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("[Proxy] Error ejecutando Servlet. Continuando con lógica normal.");
            }
        }
        // --- FIN MOTOR DE SERVLETS ---
        
        String cSeq = inviteMessage.getcSeqNumber();

        // Validaciones
        boolean callerValid = !userRegisterExpired(inviteMessage.getFromName());
        ValidUser callee = findRegisteredUser(inviteMessage.getToName());
        boolean calleeValid = (callee != null);

        if (callerValid && calleeValid) {
            // Forwarding
            String calleeAddress = callee.getIpAddress();
            int calleePort = callee.getPort();
            
            int maxForwards = inviteMessage.getMaxForwards();
            maxForwards--;
            inviteMessage.setMaxForwards(maxForwards);
            
            String myVia = this.proxyAddress + ":" + this.proxyPort;
            inviteMessage.addVia(myVia);
            
            if (this.loose_rooting) {
                System.out.println("[Proxy] Loose-routing=true. Añadiendo Record-Route.");
                String myRoute = "sip:" + this.proxyAddress + ":" + this.proxyPort;
                System.out.println("DEBUG PROXY RECORD-ROUTE: '" + myRoute + "'");
                inviteMessage.setRecordRoute(myRoute);
            } else {
                System.out.println("[Proxy] Loose-routing=false. No se añade Record-Route.");
            }

            System.out.println("Forwarding INVITE to callee at: " + calleeAddress + ":" + calleePort);
            transactionLayer.echoInvite(inviteMessage, calleeAddress, calleePort);
            if (debug) showMessage(inviteMessage);
            
        
        } else {
            // Error 404
            String reason = !callerValid ? 
                "Caller (From) not registered or expired: " + inviteMessage.getFromName() :
                "Callee (To) not found or expired: " + inviteMessage.getToName();
            System.err.println("Not authorized. Reason: " + reason);
            
            NotFoundMessage notFoundMessage = new NotFoundMessage();
            notFoundMessage.setVias(vias);
            notFoundMessage.setToName(inviteMessage.getToName());
            notFoundMessage.setToUri(inviteMessage.getToUri());
            notFoundMessage.setFromName(inviteMessage.getFromName());
            notFoundMessage.setFromUri(inviteMessage.getFromUri());
            notFoundMessage.setCallId(cid);
            notFoundMessage.setcSeqNumber(cSeq);
            notFoundMessage.setcSeqStr("INVITE");
            notFoundMessage.setContentLength(0);
            notFoundMessage.setContact(this.proxyAddress + ":" + this.proxyPort);

            System.out.println("[Proxy State: PROCEEDING -> COMPLETED] (Error 404)");
            
            // GUARDAMOS EL ESTADO EN LA TRANSACCIÓN ESPECÍFICA
            ctx.state = STATE_COMPLETED;
            ctx.lastErrorSent = notFoundMessage;
            ctx.lastErrorAddress = originAddress;
            ctx.lastErrorPort = originPort;

            transactionLayer.send404NotFound(notFoundMessage, originAddress, originPort);
            
            // INICIAMOS EL TIMER ESPECÍFICO DE ESTA TRANSACCIÓN
            startErrorRetransmissionTimer(ctx);
            
            if (debug) showMessage(notFoundMessage);
        }
    }

    public void onRegisterReceived(RegisterMessage registerMessage) throws IOException {
        
        System.out.println("Received REGISTER from " + registerMessage.getFromName());
        ArrayList<String> vias = registerMessage.getVias();
        String origin = vias.get(0);
        String[] originParts = origin.split(":");
        String originAddress = originParts[0];
        int originPort = Integer.parseInt(originParts[1]);    

        String cSeq = registerMessage.getcSeqNumber();
        int expires = Integer.valueOf(registerMessage.getExpires());

        if (esUsuarioValido(registerMessage.getFromName(), expires)) {
            registrarUsuario(registerMessage.getFromName(), originAddress, originPort, expires);
            OKMessage okMessage = new OKMessage();
            okMessage.setVias(vias);
            okMessage.setToName(registerMessage.getToName());
            okMessage.setToUri(registerMessage.getToUri());
            okMessage.setFromName(registerMessage.getFromName());
            okMessage.setFromUri(registerMessage.getFromUri());
            okMessage.setCallId(registerMessage.getCallId());
            okMessage.setcSeqNumber(cSeq);
            okMessage.setcSeqStr("REGISTER");
            okMessage.setExpires(registerMessage.getExpires());
            okMessage.setContact(registerMessage.getContact());
            okMessage.setContentLength(0);
            
            if(debug) {
                for (ValidUser u : usuariosRegistrados) {
                    System.out.println("\nNuevo usuario registrado y guardado:");
                    System.out.println("Usuario "+ u + u.getUserName());
                }
                System.out.println("");
            }

            transactionLayer.send200OK(okMessage, originAddress, originPort);
            if (debug) showMessage(okMessage);
        } else {
            System.err.println("Not authorized user, sending 404 Not Found.");
            NotFoundMessage notFoundMessage = new NotFoundMessage();
            notFoundMessage.setVias(vias);
            notFoundMessage.setToName(registerMessage.getToName());
            notFoundMessage.setToUri(registerMessage.getToUri());
            notFoundMessage.setFromName(registerMessage.getFromName());
            notFoundMessage.setFromUri(registerMessage.getFromUri());
            notFoundMessage.setCallId(registerMessage.getCallId());
            notFoundMessage.setcSeqNumber(cSeq);
            notFoundMessage.setcSeqStr("REGISTER");
            notFoundMessage.setContentLength(0);

            transactionLayer.send404NotFound(notFoundMessage, originAddress, originPort);
            if (debug) showMessage(notFoundMessage);
        }
    }
    
    public void onRingingReceived(RingingMessage ringingMessage) throws IOException {
        System.out.println("Received 180 Ringing from callee. Forwarding to caller.");
        ArrayList<String> vias = ringingMessage.getVias();
        vias.remove(0);
        String destination = vias.get(0);
        String[] destParts = destination.split(":");
        String destAddress = destParts[0];
        int destPort = Integer.parseInt(destParts[1]);

        ringingMessage.setVias(vias);
        transactionLayer.send180Ringing(ringingMessage, destAddress, destPort);
        showMessage(ringingMessage);
    }
    
    public void onOKReceived(OKMessage okMessage) throws IOException {
        String cid = okMessage.getCallId();
        
        // --- LOGICA PARA EXTRAER EL DESTINO ---
        // 1. Eliminamos la Via del Proxy (la nuestra) para evitar bucles
        if (!okMessage.getVias().isEmpty()) {
        	
            okMessage.deleteVia(); 
        }

        // 2. Leemos la siguiente Via (la del destinatario: Alice)
        if (okMessage.getVias().isEmpty()) {
            System.err.println("[Proxy Error] No Vias left to forward 200 OK.");
            return;
        }

        String nextVia = okMessage.getVias().get(0);
        
        // 3. Parseamos IP y Puerto de forma segura
        String[] parts = nextVia.split(":");
        String targetIpRaw = parts[0];
        String targetIp;
        
        // Limpiamos el prefijo "SIP/2.0/UDP " si existe
        if (targetIpRaw.contains(" ")) {
            targetIp = targetIpRaw.substring(targetIpRaw.lastIndexOf(" ") + 1);
        } else {
            targetIp = targetIpRaw;
        }
        
        int targetPort = Integer.parseInt(parts[1]);
        // ------------------------------------------------
        

        if ("INVITE".equals(okMessage.getcSeqStr())) {
            System.out.println("[Proxy] Forwarding 200 OK (INVITE) to caller: " + targetIp + ":" + targetPort);
            
            // Ya hemos borrado la vía arriba, así que el mensaje está listo
            transactionLayer.send200OK(okMessage, targetIp, targetPort);
            
            if (debug) showMessage(okMessage);
            
        } else if ("BYE".equals(okMessage.getcSeqStr())) {
            System.out.println("[Proxy] Forwarding 200 OK (BYE) to caller: " + targetIp + ":" + targetPort);
            
            transactionLayer.send200OK(okMessage, targetIp, targetPort);
            
            if (debug) showMessage(okMessage);
            
            // EL BYE FINALIZA LA TRANSACCIÓN, LIMPIAMOS MAPA
            removeTransaction(cid, "BYE completado");
        }
    }
    
    public void onBusyHereReceived(BusyHereMessage busyMessage) throws IOException {
        String cid = busyMessage.getCallId();
        System.out.println("Received 486 Busy Here from callee. Forwarding to caller.");
        
        ArrayList<String> vias = busyMessage.getVias();
        vias.remove(0);
        String destination = vias.get(0);
        String[] destParts = destination.split(":");
        String destAddress = destParts[0];
        int destPort = Integer.parseInt(destParts[1]);

        busyMessage.setVias(vias);
        transactionLayer.send486BusyHere(busyMessage, destAddress, destPort);
        showMessage(busyMessage);
        
        // El 486 es una respuesta final, limpiamos la transacción
        removeTransaction(cid, "Llamada rechazada por el llamado (486)");
    }
    
    public void onACKReceived(ACKMessage ackMessage) throws IOException {
        System.out.println("Received ACK from caller.");
        String cid = ackMessage.getCallId();
        
        // RECUPERAMOS TRANSACCIÓN
        CallTransaction ctx = activeTransactions.get(cid);

        // Si el ACK es para un error que generó el Proxy (ej. 404), limpiamos
        if (ctx != null && ctx.state == STATE_COMPLETED) {
            removeTransaction(cid, "ACK recibido para error 4xx del Proxy");
            
            System.out.println("[Proxy] ACK consumido localmente (era respuesta a rechazo del Proxy).");
            return;
        }
        
        System.out.println("Forwarding ACK to callee.");
        ValidUser callee = findRegisteredUser(ackMessage.getToName());
        
        if (callee != null) {
            int maxForwards = ackMessage.getMaxForwards();
            maxForwards--;
            ackMessage.setMaxForwards(maxForwards);
            
            String myVia = this.proxyAddress + ":" + this.proxyPort;
            ackMessage.addVia(myVia); 

            String calleeAddress = callee.getIpAddress();
            int calleePort = callee.getPort();

            transactionLayer.sendACK(ackMessage, calleeAddress, calleePort);
            showMessage(ackMessage);
        } else {
            System.err.println("Error: Callee for ACK not found: " + ackMessage.getToName());
        }
    }
    
    public void onByeReceived(ByeMessage byeMessage) throws IOException {
        System.out.println("Received BYE. Forwarding.");
        ValidUser callee = findRegisteredUser(byeMessage.getToName());
        if (callee != null) {
            int maxForwards = byeMessage.getMaxForwards();
            maxForwards--;
            byeMessage.setMaxForwards(maxForwards);
            
            String myVia = this.proxyAddress + ":" + this.proxyPort;
            byeMessage.addVia(myVia); 

            String calleeAddress = callee.getIpAddress();
            int calleePort = callee.getPort();
            transactionLayer.sendBye(byeMessage, calleeAddress, calleePort);
            showMessage(byeMessage);
        } else {
            System.err.println("Error: Callee for BYE not found: " + byeMessage.getToName());
        }
    }
    
    public void on408RequestTimeoutReceived(RequestTimeoutMessage timeoutMessage) throws IOException {
        String cid = timeoutMessage.getCallId();
        System.out.println("Received 408 Request Timeout from callee. Forwarding to caller.");
        
        ArrayList<String> vias = timeoutMessage.getVias();
        vias.remove(0); 
        
        String destination = vias.get(0);
        String[] destParts = destination.split(":");
        String destAddress = destParts[0];
        int destPort = Integer.parseInt(destParts[1]);

        timeoutMessage.setVias(vias);
        transactionLayer.send408RequestTimeout(timeoutMessage, destAddress, destPort);
        showMessage(timeoutMessage);
        
        // 408 es respuesta final, limpiamos transacción
        removeTransaction(cid, "Timeout en el llamado (408)");
    }
    
    // --- MÉTODOS DE VALIDACIÓN DE USUARIOS ---
    private boolean esUsuarioValido(String name, int expires){
        for (ValidUser validUser: validUsersList) {
            if (name.equalsIgnoreCase(validUser.getUserName())) {
                long expiresAt = System.currentTimeMillis() + (long) expires * 1000;
                validUser.setExpirationTime(expiresAt);
                return true;
            }
        }
        return false;
    }
    
    private void registrarUsuario(String userName, String ip, int port, int expiresSeconds) {
        long expiration = System.currentTimeMillis() + (long) expiresSeconds * 1000;
        for (ValidUser user : usuariosRegistrados) {
            if (user.getUserName().equals(userName)) {
                user.setIpAddress(ip);
                user.setPort(port);
                user.setExpirationTime(expiration);
                if (debug) System.out.println("[Proxy] Registro actualizado para: " + user);
                return;
            }
        }
        ValidUser new_user = new ValidUser(userName, ip, port, expiresSeconds);
        if (debug) System.out.println("[Proxy] Nuevo usuario registrado: " + new_user);
        usuariosRegistrados.add(new_user);
    }
    
    private boolean userRegisterExpired(String name){
        for (ValidUser user : usuariosRegistrados) {
            if (user.getUserName().equals(name)) return user.isExpired();
        }
        return true; 
    }
    
    private ValidUser findRegisteredUser(String name) {
        for (ValidUser u : usuariosRegistrados) {
            if (u.getUserName().equals(name) && !u.isExpired()) return u;
        }
        return null;
    }
    
    private void showMessage(SIPMessage sipMessage) {
        if (debug) {
            System.out.println(sipMessage.toStringMessage());
        }
    }
    
    // --- TEMPORIZADOR DE RETRANSMISIÓN ---
    // Ahora recibe el objeto CallTransaction específico
    private void startErrorRetransmissionTimer(CallTransaction ctx) {
        ctx.cancelTimer(); // Limpia anterior si hubiera
        ctx.retransmissionTimer = new Timer();
        ctx.retransmissionTask = new TimerTask() {
            @Override
            public void run() {
                // Usamos el estado y datos de ESTA transacción, no globales
                if (ctx.state == STATE_COMPLETED && ctx.lastErrorSent != null) {
                    try {
                        System.err.println("[Proxy Timer] No se recibió ACK. Retransmitiendo error para " + ctx.callId);
                        if (ctx.lastErrorSent instanceof NotFoundMessage) {
                            transactionLayer.send404NotFound((NotFoundMessage) ctx.lastErrorSent, ctx.lastErrorAddress, ctx.lastErrorPort);
                        } else if (ctx.lastErrorSent instanceof UnauthorizedMessage) {
                            transactionLayer.send401((UnauthorizedMessage) ctx.lastErrorSent, ctx.lastErrorAddress, ctx.lastErrorPort);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        System.out.println("[Proxy Timer] Iniciando retransmisión (200ms) para " + ctx.callId);
        ctx.retransmissionTimer.scheduleAtFixedRate(ctx.retransmissionTask, 200, 200);
    }
}