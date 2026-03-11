package ua;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import common.FindMyIPv4;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.UnauthorizedMessage;
import mensajesSIP.TryingMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.ACKMessage;
import mensajesSIP.RequestTimeoutMessage;

public class UaUserLayer {

	//  Estados del UA 
	private static final int UNREGISTERED = 1;
	private static final int REGISTERING = 2;
	private static final int IDLE = 3;
	private static final int CALLING = 4;
	private static final int IN_CALL = 5;
	private static final int TERMINATED = 6;
	private static final int PROCEEDING = 7;
	private static final int COMPLETED = 8;

	// Inicio UA
	private int state = UNREGISTERED;
	
	
	//Guardo la llamada entrante
	private InviteMessage incomingInvite = null; 
	private String incomingRecordRoute = null;
	private String activeRoute = null; // Guardará el Record-Route
    private String activeContact = null; // Guardará el Contact del llamado
	
	
	
	private long cSeqCounter = 0;


	// Timers para el registro
	private Timer resendRegisterTimer;     // Temporizador para reintentos de REGISTER
	private TimerTask resendRegisterTask;  // Trabajo periódico de reenvío
	private  Timer renewTimer;				//  renovar el registro SIP una vez transcurrido el tiempo de expiración(registerTime)
	private Timer requestTimeoutTimer; // Timer para el 408
	private Timer uaRetransmissionTimer; // Para retransmitir 4xx (Callee)
    private TimerTask uaRetransmissionTask;
    private SIPMessage lastUaErrorSent; // El 486 o 408 a retransmitir
    private Timer uaCompletedTimer; // Para la espera de 1s (Caller y Callee)
	
	
	
	// Para recordar los detalles de la llamada activa
    private String activeCallId = null;
    private String activeToName = null;
    private String activeToUri = null;
    private String activeFromName = null;
    private String activeFromUri = null;

	//RTP y SDP
	public static final ArrayList<Integer> RTPFLOWS = new ArrayList<Integer>(Arrays.asList(new Integer[] { 96, 97, 98 }));

	//Capa transacción
	private UaTransactionLayer transactionLayer;
	
	//UA config
	private String myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
	private int rtpPort;
	private int listenPort;
	private boolean debug = true;
	private String userName;
	private String userUri;
	private String userContact;
	private ArrayList<String> originatingVias;
	private int registerTime;
	
	//vitext
	private Process vitextClient = null;
	private Process vitextServer = null;

	public UaUserLayer(int listenPort, String proxyAddress, int proxyPort, String ua, boolean debug,  int registerTime) throws SocketException, UnknownHostException {
		this.transactionLayer = new UaTransactionLayer(listenPort, proxyAddress, proxyPort, this);
		this.listenPort = listenPort;
		this.rtpPort = listenPort + 1;
		this.debug = debug;
		this.userUri = ua;
		this.registerTime = registerTime;
		
		
		int arroba = this.userUri.indexOf('@');

		String userOnly;
		if (arroba > 0) {
			userOnly = this.userUri.substring(0, arroba);
		} else {
		    userOnly = this.userUri;
		}

		if (userOnly.isEmpty()) {
		    this.userName = this.userUri;
		} else {
		    this.userName = userOnly.substring(0, 1) + userOnly.substring(1);
		}

		this.userContact = this.myAddress + ":" + this.listenPort;             
		this.originatingVias = new ArrayList<>(Arrays.asList(this.userContact));
	}
	
	
	//Arrancar la red
	public void startListeningNetwork() {
		transactionLayer.startListeningNetwork();
	}
	
	
	//Teclado
	public void startListeningKeyboard() {
		try (Scanner scanner = new Scanner(System.in)) {
			while (true) {
				prompt();
				String line = scanner.nextLine();
				if (!line.isEmpty()) {
					command(line);
				}
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}
	
	//Programa el ciclo de REGISTER
	public void init() {
		resendRegisterTimerActivate(0, 2000);
	}
	
	//Estados UA 
	private void prompt() {
	    System.out.println("");
	switch (state) {
	        case REGISTERING:
	            System.out.println("REGISTERING... please wait for 200 OK from proxy\n");
				break;
	        case UNREGISTERED:
	            System.out.println("Not registered. Type REGISTER to start.");
                System.out.print("> "); // Añadido para mostrar el prompt
	            break;
			case IDLE:
				promptIdle();
				System.out.print("> ");
				break;
			case CALLING:
				System.out.println("Calling... waiting for response.");
				System.out.print("> ");
				break;
			case PROCEEDING:
				System.out.println("Call in progress (Trying/Ringing)... waiting for 200 OK.");
				System.out.print("> ");
				break;
			case IN_CALL:
				if (incomingInvite != null) {
					// Estado: Recibiendo llamada, esperando S/N
					System.out.println("Escribe 'S' para aceptar o 'N' para rechazar.");
					//System.out.print("> ");
				} else {
                    // Estado: En llamada (ya aceptada o somos el llamante)
                    System.out.println("In a call. Type BYE to hang up.");
                    System.out.print("> ");
                }
	        	break;
			case COMPLETED:
                System.out.println("Transaction completed. Waiting for timers to clear...");
                // No se muestra prompt '>'
                break;
    		case TERMINATED:
	            System.out.println("UA terminated.");
	            break;
	default:
	        throw new IllegalStateException("Unexpected state: " + state);
	}
	}

	//Prompt idle
	private void promptIdle() {
		System.out.println("INVITE xxx");
	}
	
	//Promt register
	private void promptUnregistered() {
		System.out.println("\nREGISTERING\n");
	}
	
	
	
	//Gestiona un INVITE entrante
	public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
		System.out.println("Received INVITE from " + inviteMessage.getFromName());
		
		// Comprobar si ya estamos en llamada
		if (state == CALLING || state == IN_CALL) {
            System.out.println(" [UA] User is busy. Rejecting incoming call from " + inviteMessage.getFromName());
            System.out.println(" [UA] Sending 486 Busy Here.");

            // 1. Construir el mensaje 486 Busy Here
            BusyHereMessage busyMessage = new BusyHereMessage();
            
            // 2. Copiar las cabeceras del NUEVO Invite (no del activo)
            // Esto es vital para que el Proxy sepa a quién devolver el error
            busyMessage.setVias(inviteMessage.getVias());
            busyMessage.setToName(inviteMessage.getToName());
            busyMessage.setToUri(inviteMessage.getToUri());
            busyMessage.setFromName(inviteMessage.getFromName());
            busyMessage.setFromUri(inviteMessage.getFromUri());
            busyMessage.setCallId(inviteMessage.getCallId());
            busyMessage.setcSeqNumber(inviteMessage.getcSeqNumber());
            busyMessage.setcSeqStr(inviteMessage.getcSeqStr());
            busyMessage.setContentLength(0);

            // 3. Enviar el mensaje
            transactionLayer.send486BusyHere(busyMessage);
            showMessage(busyMessage);
            
            return; // Salimos sin cambiar el estado actual del UA
        }
        
        
        // Si estamos IDLE, cambiamos a estado IN_CALL y enviamos 180 Ringing
        state = IN_CALL; // Marcamos que estamos en llamada (recibiendo)
        this.incomingInvite = inviteMessage; // Guardamos el invite para aceptarlo/rechazarlo
        this.incomingRecordRoute = inviteMessage.getRecordRoute();        
        this.activeCallId = inviteMessage.getCallId();
        this.activeToName = inviteMessage.getToName();
        this.activeToUri = inviteMessage.getToUri();
        this.activeFromName = inviteMessage.getFromName();
        this.activeFromUri = inviteMessage.getFromUri();
        this.activeContact = inviteMessage.getContact();
        

        RingingMessage ringingMessage = new RingingMessage();
        ringingMessage.setVias(inviteMessage.getVias());
        ringingMessage.setToName(inviteMessage.getToName());
        ringingMessage.setToUri(inviteMessage.getToUri());
        ringingMessage.setFromName(inviteMessage.getFromName());
        ringingMessage.setFromUri(inviteMessage.getFromUri());
        ringingMessage.setCallId(inviteMessage.getCallId());
        ringingMessage.setcSeqNumber(inviteMessage.getcSeqNumber());
        ringingMessage.setcSeqStr(inviteMessage.getcSeqStr());
        ringingMessage.setContact(this.userContact); // Tu info de contacto 
        ringingMessage.setContentLength(0);
        
        // Usamos la capa de transacción
        if (this.incomingRecordRoute != null) {
            ringingMessage.setRecordRoute(this.incomingRecordRoute);
        }
        transactionLayer.send180Ringing(ringingMessage);
        showMessage(ringingMessage);
        
        // Avisa al usuario para que responda
        System.out.println("\n--- LLAMADA ENTRANTE ---");
        System.out.println("Llamada de: " + inviteMessage.getFromName());
        System.out.println("Escribe 'S' para aceptar o 'N' para rechazar.");
        System.out.print("> ");

		
		runVitextServer();
		startRequestTimeoutTimer();
	}
	
	
	
	
	public void on200OKReceived(OKMessage okMessage) throws IOException{

	    // Comprobar si es un OK para el REGISTER
		if ("REGISTER".equals(okMessage.getcSeqStr())) {
	        if (state == REGISTERING || state == UNREGISTERED) {
	            state = IDLE;
	            cancelResendTimer();
	            scheduleRegisterRenewal(registerTime * 1000); 
	            System.out.println("You've been registered!\n");
	            prompt();
	        }
	    
	    // Comprobar si es un OK para el INVITE
	    } else if ("INVITE".equals(okMessage.getcSeqStr())) {
	        
	        // Solo si estábamos llamando o en proceeding
	        if (state == CALLING || state == PROCEEDING) {
	            state = IN_CALL; // Llamada establecida
	            System.out.println("\n¡Llamada establecida! (Recibido 200 OK). Enviando ACK.");
	            showMessage(okMessage);
	            
	            this.activeRoute = okMessage.getRecordRoute();
                this.activeContact = okMessage.getContact();
	
	            // Crear el ACK para confirmar el 200 OK
	            ACKMessage ackMessage = new ACKMessage();
	            
	            ackMessage.setDestination(okMessage.getToUri()); // Destino
	            ackMessage.setVias(originatingVias); // Solo nuestra Via
	            ackMessage.setMaxForwards(70);
	            ackMessage.setToName(okMessage.getToName());
	            ackMessage.setToUri(okMessage.getToUri());
	            ackMessage.setFromName(okMessage.getFromName());
	            ackMessage.setFromUri(okMessage.getFromUri());
	            ackMessage.setCallId(okMessage.getCallId());
	            ackMessage.setcSeqNumber(okMessage.getcSeqNumber()); // Mismo CSeq num
	            ackMessage.setcSeqStr("ACK"); // método a ACK
	            ackMessage.setContentLength(0);

	            if (this.activeRoute != null) {
                    // Loose-Routing (Record-Route existe): Enviar al Proxy
                    System.out.println("[UA] Loose-routing detectado. Enviando ACK al Proxy.");
                    ackMessage.setRoute(this.activeRoute); // Añadir cabecera Route
                    transactionLayer.sendACK(ackMessage); // Envía al Proxy
                } else {
                    // No Loose-Routing (Sin Record-Route): Enviar directo al Contact
                    System.out.println("[UA] No loose-routing. Enviando ACK directo a: " + this.activeContact);
                    String[] contactParts = activeContact.split(":");
                    String contactAddress = contactParts[0];
                    int contactPort = Integer.parseInt(contactParts[1]);
                    
                    transactionLayer.sendACK(ackMessage, contactAddress, contactPort); // Envía a IP/Puerto
                }
	            
                showMessage(ackMessage);
                prompt();
            }
        } else if ("BYE".equals(okMessage.getcSeqStr())) {
            // Recibimos confirmación de nuestro BYE
            System.out.println("\nLlamada finalizada (BYE confirmado).");
            showMessage(okMessage);
            state = IDLE;
            clearCallData(); // Limpiamos la llamada
            prompt();
        }
	}
	
	private void scheduleRegisterRenewal(int registerTime) {
	    renewTimer = new Timer();
	    renewTimer.schedule(new TimerTask() {
	        @Override
	        public void run() {
	            System.out.println("El registro ha expirado. Intentando registrar de nuevo...");
	            resendRegisterTimerActivate(0, 2000); // reintentos cada 2s como al principio
	        }
	    }, registerTime);
	}
	
	//Gesiona 401 Unauthorized
	public void on401UnauthorizedReceived(UnauthorizedMessage unauthorizedMessage) throws IOException {
		
		if ("REGISTER".equals(unauthorizedMessage.getcSeqStr())) {
            // Error fatal: El REGISTER inicial falló (usuario no en la lista)
			System.out.println("Received UNAUTHORIZED for REGISTER. User not in allowed list? Closing.");
		    state = TERMINATED;
		    cancelResendTimer();
		    cancelRegisterRenewTimer();
	        prompt();
			System.exit(1); 
            
		} else if ("INVITE".equals(unauthorizedMessage.getcSeqStr())) {
            // Error no fatal: El INVITE falló (Proxy reiniciado o registro expirado)
            System.out.println("\nReceived UNAUTHORIZED for INVITE. Registration lost.");
            System.out.println("UA must re-register.");
            
            // Volvemos al estado UNREGISTERED
            state = UNREGISTERED;
            clearCallData(); // Limpia cualquier dato de la llamada fallida
            cancelRegisterRenewTimer(); // Cancela el timer de renovación, ya no es válido
            
            // Re-inicia el ciclo de re-registro automático
            resendRegisterTimerActivate(0, 2000);
            prompt();
            
		} else {
            // Error desconocido
			System.out.println("Received UNAUTHORIZED (" + unauthorizedMessage.getcSeqStr() + "). Closing.");
			state = TERMINATED;
			System.exit(1);
		}
	}
	
	
	
	
	public void on404NotFoundReceived(NotFoundMessage notFoundMessage) throws IOException {
		
		if ("REGISTER".equals(notFoundMessage.getcSeqStr())) {
            // Error fatal: El REGISTER inicial falló (usuario no en la lista)
			System.out.println("Received 404 Not Found for REGISTER. User not in allowed list. Closing.");
		    state = TERMINATED;
		    cancelResendTimer();
		    cancelRegisterRenewTimer();
	        prompt();
			System.exit(1);
            
		} else if ("INVITE".equals(notFoundMessage.getcSeqStr())) {
            // Error no fatal: El INVITE falló.
            // Esto significa que el LLAMADO (Bob) no existe, o que el LLAMANTE (Alice)
            // perdió su registro. El proxy no distingue, y envía 404.
            // El UA (Alice) debe asumir que su registro se perdió.
            System.out.println("\nReceived 404 Not Found for INVITE. Callee not found OR registration lost.");
            System.out.println("UA will re-register.");
            
            // 1. Enviar ACK para el 404
            ACKMessage ackMessage = new ACKMessage();
            ackMessage.setDestination(notFoundMessage.getToUri());
            ackMessage.setVias(originatingVias);
            ackMessage.setMaxForwards(70);
            ackMessage.setToName(notFoundMessage.getToName());
            ackMessage.setToUri(notFoundMessage.getToUri());
            ackMessage.setFromName(notFoundMessage.getFromName());
            ackMessage.setFromUri(notFoundMessage.getFromUri());
            ackMessage.setCallId(notFoundMessage.getCallId());
            ackMessage.setcSeqNumber(notFoundMessage.getcSeqNumber());
            ackMessage.setcSeqStr("ACK");
            ackMessage.setContentLength(0);

            // El 404 vino del Proxy, así que enviamos el ACK al Proxy
            // (La lógica de loose-routing no se aplica aquí de la misma manera)
            transactionLayer.sendACK(ackMessage);
            showMessage(ackMessage);
            
            // 2. Volver al estado COMPLETED y empezar timer
            state = COMPLETED;
            // Pasamos UNREGISTERED como el estado final después del timer
            startUaCompletedTimer(1000, UNREGISTERED);
            prompt();
		}
	}

	
	
	
	public void on100TryingReceived(TryingMessage tryingMessage) throws IOException {
        // Si estábamos llamando, pasamos a Proceeding
        if (state == CALLING) {
            System.out.println("\nProxy is Trying...");
            state = PROCEEDING; // Cambia de estado
            prompt(); // Muestra el nuevo prompt
        }
        showMessage(tryingMessage);
    }
	
	
	public void on180RingingReceived(RingingMessage ringingMessage) throws IOException {
        // Si estábamos llamando o trying, pasamos/mantenemos Proceeding
        if (state == CALLING || state == PROCEEDING) {
            System.out.println("\n...Ringing!");
            state = PROCEEDING; // Cambia o se mantiene en este estado
            prompt();
        }
        showMessage(ringingMessage);
    }
	
	
	public void on486BusyHereReceived(BusyHereMessage busyMessage) throws IOException {
	        
        // Solo si estábamos en mitad de una llamada (CALLING o PROCEEDING)
        if (state == CALLING || state == PROCEEDING) {
            
            System.out.println("\nLlamada rechazada por el usuario (486 Busy Here).");
            showMessage(busyMessage);

            // El diagrama de estados  indica que una respuesta 4xx
            // a un INVITE debe ser confirmada con un ACK.
            
            // 1. Crear el ACK
            ACKMessage ackMessage = new ACKMessage();
            
            // Copiamos las cabeceras de la respuesta 486
            ackMessage.setDestination(busyMessage.getToUri()); // El Request-URI es el To-URI
            ackMessage.setVias(originatingVias); // Solo nuestra Via
            ackMessage.setMaxForwards(70);
            ackMessage.setToName(busyMessage.getToName());
            ackMessage.setToUri(busyMessage.getToUri());
            ackMessage.setFromName(busyMessage.getFromName());
            ackMessage.setFromUri(busyMessage.getFromUri());
            ackMessage.setCallId(busyMessage.getCallId());
            ackMessage.setcSeqNumber(busyMessage.getcSeqNumber()); // Mismo CSeq num que el INVITE
            ackMessage.setcSeqStr("ACK"); // Cambia el método a ACK
            ackMessage.setContentLength(0);

            // 2. Enviar el ACK
            transactionLayer.sendACK(ackMessage);
            showMessage(ackMessage);

            // 2. Volver al estado COMPLETED y empezar timer
            state = COMPLETED;
            // Pasamos IDLE como el estado final después del timer
            startUaCompletedTimer(1000, IDLE);
            prompt();
        }
    }

	
	
	public void onACKReceived(ACKMessage ackMessage) throws IOException {
	    // Caso 1: Estábamos en COMPLETED (esperando ACK para nuestro 486/408)
	    if (state == COMPLETED) {
            System.out.println("\nACK recibido para el error 4xx.");
            showMessage(ackMessage);
            
            // El diagrama del llamado dice que pasemos a COMPLETED
            // (donde ya estamos) y empecemos un timer de 1s.
            cancelUaErrorRetransmissionTimer(); // Paramos de retransmitir el error
            startUaCompletedTimer(1000, IDLE); // Inicia timer de 1s
            prompt();
            return;
	    }

        // Caso 2: Estábamos IN_CALL (ACK para nuestro 200 OK)
	    if (state == IN_CALL && incomingInvite == null) {
	        System.out.println("\nACK recibido. ¡Llamada confirmada!");
	        showMessage(ackMessage);
	        prompt(); 
	    } else {
			System.err.println("Unexpected ACK message, throwing away");
		}
	}
	
	
	
	public void onByeReceived(ByeMessage byeMessage) throws IOException {
        if (state == IN_CALL) {
            System.out.println("\nLlamada finalizada por la otra parte. Enviando 200 OK.");
            showMessage(byeMessage);

            // 1. Crear el 200 OK para el BYE
            OKMessage okMessage = new OKMessage();
            okMessage.setVias(byeMessage.getVias());
            okMessage.setToName(byeMessage.getToName());
            okMessage.setToUri(byeMessage.getToUri());
            okMessage.setFromName(byeMessage.getFromName());
            okMessage.setFromUri(byeMessage.getFromUri());
            okMessage.setCallId(byeMessage.getCallId());
            // El CSeq de la respuesta es el mismo que el del BYE
            okMessage.setcSeqNumber(byeMessage.getcSeqNumber());
            okMessage.setcSeqStr("BYE");
            okMessage.setContentLength(0);
            okMessage.setContact(this.userContact);

            // 2. Enviar el 200 OK
            transactionLayer.send200OK(okMessage);
            showMessage(okMessage);

            // 3. Volver a IDLE
            state = IDLE;
            clearCallData();
            prompt();
        }
    }
	
	
	
	public void on408RequestTimeoutReceived(RequestTimeoutMessage timeoutMessage) throws IOException {
	        
	        // Lógica idéntica a on486BusyHereReceived
	    if (state == CALLING || state == PROCEEDING) {
	        
	        System.out.println("\nLa llamada no fue respondida (408 Request Timeout).");
	        showMessage(timeoutMessage);
	
	        // Enviar ACK para confirmar el error
	        ACKMessage ackMessage = new ACKMessage();
	        ackMessage.setDestination(timeoutMessage.getToUri());
	        ackMessage.setVias(originatingVias);
	        ackMessage.setMaxForwards(70);
	        ackMessage.setToName(timeoutMessage.getToName());
	        ackMessage.setToUri(timeoutMessage.getToUri());
	        ackMessage.setFromName(timeoutMessage.getFromName());
	        ackMessage.setFromUri(timeoutMessage.getFromUri());
	        ackMessage.setCallId(timeoutMessage.getCallId());
	        ackMessage.setcSeqNumber(timeoutMessage.getcSeqNumber());
	        ackMessage.setcSeqStr("ACK");
	        ackMessage.setContentLength(0);
	
	        transactionLayer.sendACK(ackMessage);
	        showMessage(ackMessage);
	
	        // 2. Volver al estado COMPLETED y empezar timer
	        state = COMPLETED;
            // Pasamos IDLE como el estado final después del timer
            startUaCompletedTimer(1000, IDLE);
	        prompt();
	    }
	}

	public void on503ServiceUnavailableReceived(ServiceUnavailableMessage suMessage) throws IOException {
	    
	    // Solo si estábamos intentando llamar
	if (state == CALLING || state == PROCEEDING) {
	    
	    System.out.println("\nLlamada fallida: El proxy está ocupado (503 Service Unavailable).");
	    showMessage(suMessage);
	
	    // 1. Crear el ACK para confirmar el error 503
	    ACKMessage ackMessage = new ACKMessage();
	    
	    // Copiamos las cabeceras de la respuesta 503
	    ackMessage.setDestination(suMessage.getToUri()); // El Request-URI es el To-URI
	    ackMessage.setVias(originatingVias); // Solo nuestra Via
	    ackMessage.setMaxForwards(70);
	    ackMessage.setToName(suMessage.getToName());
	    ackMessage.setToUri(suMessage.getToUri());
	    ackMessage.setFromName(suMessage.getFromName());
	    ackMessage.setFromUri(suMessage.getFromUri());
	    ackMessage.setCallId(suMessage.getCallId());
	    ackMessage.setcSeqNumber(suMessage.getcSeqNumber()); // Mismo CSeq num
	    ackMessage.setcSeqStr("ACK"); // Cambia el método a ACK
	    ackMessage.setContentLength(0);
	
	    // 2. Enviar el ACK
	    // Un 503 siempre viene del proxy, así que enviamos el ACK al proxy
	    // independientemente del loose-routing.
	    System.out.println("[UA] Enviando ACK por error 503 al Proxy.");
	    transactionLayer.sendACK(ackMessage);
	    showMessage(ackMessage);
	
	    // 2. Volver al estado COMPLETED y empezar timer
	    state = COMPLETED;
        // Pasamos IDLE como el estado final después del timer
        startUaCompletedTimer(1000, IDLE);
	    prompt();
	    }
	}
	
	
	
	private void command(String line) throws IOException {
		if (line.startsWith("INVITE")) {
			commandInvite(line);
		
		} else if (line.equalsIgnoreCase("REGISTER")) {
            if (state == IDLE) {
                System.out.println("Already registered.");
            } else if (state == REGISTERING) {
                System.out.println("Registration already in progress.");
            } else { // El estado es UNREGISTERED
                // Esto inicia el bucle de registro cada 2 seg, como hacía init() 
                resendRegisterTimerActivate(0, 2000); 
            }

		} else if (line.equalsIgnoreCase("S")) {
            commandAcceptCall();
        
		} else if (line.equalsIgnoreCase("N")) {
            commandRejectCall();
            
		} else if (line.equalsIgnoreCase("BYE")) {
            commandBye();
            
		} else if (line.equalsIgnoreCase("exit")) {
            state = TERMINATED;
            prompt();
            System.exit(0);
            
		} else {
			System.out.println("Bad command");
			}
	}

	
	//Método Invite
	private void commandInvite(String line) throws IOException {
        if (state == UNREGISTERED) {
            System.out.println("Error: UA is not registered. Cannot send INVITE.");
            System.out.println("Please type REGISTER first.");
            prompt(); // Muestra el prompt de nuevo
            return; // No continúa
        }
        
        if (state == REGISTERING) {
            System.out.println("Error: UA is currently registering. Please wait.");
            prompt();
            return; 
        }

        if (state == CALLING || state == IN_CALL) {
			System.out.println("Error: UA is already in a call. Only one call supported.");
			prompt();
			return;
        }

        // Si estamos IDLE, cambiamos a CALLING
        state = CALLING;
        prompt(); // Muestra el prompt de "Calling..."

		stopVitextServer();
		stopVitextClient();
		
		System.out.println("Inviting...");

		runVitextClient();
        
		
		//datos
		String callId = UUID.randomUUID().toString();
		int i = line.indexOf(' ');
		
//		String mensaje = "";
		String nameDest = "";
		String  uriDest = "";
		
		if (i < 0) {
			System.out.println("No hay destinatario");
		} else {
//			mensaje = line.substring(0, i);
		    nameDest = line.substring(i + 1).trim().toLowerCase();
		    uriDest = nameDest + "@SMA";
		}
		
		//SDP
		SDPMessage sdpMessage = new SDPMessage();
		sdpMessage.setIp(this.myAddress);
		sdpMessage.setPort(this.rtpPort);
		sdpMessage.setOptions(RTPFLOWS);

		//INVITE
		InviteMessage inviteMessage = new InviteMessage();
		inviteMessage.setDestination("sip:" + uriDest);
		inviteMessage.setVias(originatingVias);
		inviteMessage.setMaxForwards(70);
		inviteMessage.setToName(nameDest);
		inviteMessage.setToUri("sip:" + uriDest);
		inviteMessage.setFromName(userName);
		inviteMessage.setFromUri("sip:" + userUri);
		inviteMessage.setCallId(callId);
		cSeqCounter++;
		inviteMessage.setcSeqNumber(String.valueOf(cSeqCounter));
		inviteMessage.setcSeqStr("INVITE");
		inviteMessage.setContact(userContact);
		inviteMessage.setContentType("application/sdp");
		inviteMessage.setContentLength(sdpMessage.toStringMessage().getBytes().length);
		inviteMessage.setSdp(sdpMessage);
		
		
		this.activeCallId = callId;
        this.activeToName = nameDest;
        this.activeToUri = "sip:" + uriDest;
        this.activeFromName = userName;
        this.activeFromUri = "sip:" + userUri;

		transactionLayer.call(inviteMessage);
		showMessage(inviteMessage);
	}
	
	
	//Método Register
	private void commandRegister() throws IOException {
		stopVitextServer();
		stopVitextClient();
	    state = REGISTERING;
        prompt();
	    
		String callId = UUID.randomUUID().toString();
		
		// Construye el mensaje register
		RegisterMessage registerMessage = new RegisterMessage();
		registerMessage.setDestination("sip:registrar.uc3m.es");
		registerMessage.setVias(originatingVias);
		registerMessage.setMaxForwards(70);
		registerMessage.setToName(userName);
		registerMessage.setToUri("sip:" + userUri);
		registerMessage.setFromName(userName);
		registerMessage.setFromUri("sip:" + userUri);
		registerMessage.setCallId(callId);
		cSeqCounter++;
		registerMessage.setcSeqNumber(String.valueOf(cSeqCounter));
		registerMessage.setcSeqStr("REGISTER");
		registerMessage.setContact(userContact);
		registerMessage.setContentLength(0);
		registerMessage.setExpires(String.valueOf(registerTime));

		// Manda el mensaje a la siguiente capa
		transactionLayer.register(registerMessage);
		showMessage(registerMessage);
	}

	//Cancelar temp. register
	private void cancelResendTimer() {
		if (resendRegisterTask != null) { resendRegisterTask.cancel(); resendRegisterTask = null; }
		if (resendRegisterTimer != null) {
			resendRegisterTimer.cancel();
			resendRegisterTimer.purge();
			resendRegisterTimer = null;
		}
	}
	
	private void cancelRegisterRenewTimer() {
	    if (renewTimer != null) {
	    	renewTimer.cancel();
	    	renewTimer.purge();
	    	renewTimer = null;
	    }
	}
	
	//Reenvía register cada 2 seg
	private void resendRegisterTimerActivate (int init, int time) {
		cancelResendTimer();//reseteamos cualquier timer anterior
		resendRegisterTimer = new Timer();
		resendRegisterTask = new TimerTask() {
			int retrys = 0;
			@Override
			public void run() {
				try {
					promptUnregistered();
					state = UNREGISTERED;
					commandRegister();

					if(debug) {
						System.out.println("Try number: "+ retrys);
					}
					retrys++;
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
		resendRegisterTimer.scheduleAtFixedRate(resendRegisterTask, new Date(System.currentTimeMillis() + init), time);	
	}
	
	
	
	/**
     * Construye y envía el mensaje 408 Request Timeout.
     */
    private void send408RequestTimeout() throws IOException {
        if (state != IN_CALL || incomingInvite == null) {
            return; 
        }

        System.out.println("Enviando 408 Request Timeout...");

        RequestTimeoutMessage timeoutMessage = new RequestTimeoutMessage();
        
        // Copiamos cabeceras del INVITE original
        timeoutMessage.setVias(incomingInvite.getVias());
        timeoutMessage.setToName(incomingInvite.getToName());
        timeoutMessage.setToUri(incomingInvite.getToUri());
        timeoutMessage.setFromName(incomingInvite.getFromName());
        timeoutMessage.setFromUri(incomingInvite.getFromUri());
        timeoutMessage.setCallId(incomingInvite.getCallId());
        timeoutMessage.setcSeqNumber(incomingInvite.getcSeqNumber());
        timeoutMessage.setcSeqStr(incomingInvite.getcSeqStr()); // "INVITE"
        timeoutMessage.setContentLength(0);

        // Enviamos
        transactionLayer.send408RequestTimeout(timeoutMessage);
        showMessage(timeoutMessage);

        // Volvemos a IDLE
        state = COMPLETED; 	
        startUaErrorRetransmissionTimer(200); // Inicia retransmisión cada 200ms
        this.lastUaErrorSent = timeoutMessage;
        prompt();
    }
	
	
	
	/**
     * Inicia el temporizador de 10 segundos para el 408.
     */
    private void startRequestTimeoutTimer() {
        cancelRequestTimeoutTimer(); // Cancela cualquier timer anterior
        requestTimeoutTimer = new Timer();
        
        System.out.println("[Timer] Iniciando temporizador de 10s para 408...");

        requestTimeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    // Solo se ejecuta si todavía estamos esperando respuesta
                    if (state == IN_CALL && incomingInvite != null) {
                        System.out.println("\n¡Tiempo de espera agotado! (10s)");
                        send408RequestTimeout();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, 10000); // 10000 milisegundos = 10 segundos 
    }
    
    
    
    /**
     * Cancela el temporizador de 408 (si el usuario pulsa S o N).
     */
    private void cancelRequestTimeoutTimer() {
        if (requestTimeoutTimer != null) {
            System.out.println("[Timer] Cancelando temporizador de 408.");
            requestTimeoutTimer.cancel();
            requestTimeoutTimer.purge();
            requestTimeoutTimer = null;
        }
    }


	/**
     * Inicia el temporizador de retransmisión de 200ms para errores 4xx (Rol Callee).
     * 
     */
    private void startUaErrorRetransmissionTimer(int intervalMs) {
        cancelUaErrorRetransmissionTimer(); // Limpia el anterior
        uaRetransmissionTimer = new Timer();
        uaRetransmissionTask = new TimerTask() {
            @Override
            public void run() {
                // Si todavía estamos en COMPLETED (esperando ACK)
                if (state == COMPLETED) {
                    try {
                        System.out.println("[UA Timer] No se recibió ACK. Retransmitiendo error 4xx...");
                        
                        // Re-enviar el error (usando el método de envío de respuesta)
                        if (lastUaErrorSent instanceof BusyHereMessage) {
                            transactionLayer.send486BusyHere((BusyHereMessage) lastUaErrorSent);
                        } else if (lastUaErrorSent instanceof RequestTimeoutMessage) {
                            transactionLayer.send408RequestTimeout((RequestTimeoutMessage) lastUaErrorSent);
                        }
                        
                        showMessage(lastUaErrorSent);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        // Inicia el timer: reenvía cada 200ms
        System.out.println("[UA Timer] Iniciando retransmisión de error (200ms)...");
        uaRetransmissionTimer.scheduleAtFixedRate(uaRetransmissionTask, intervalMs, intervalMs);
    }

    /**
     * Cancela el temporizador de retransmisión (cuando llega el ACK).
     */
    private void cancelUaErrorRetransmissionTimer() {
        if (uaRetransmissionTask != null) {
            uaRetransmissionTask.cancel();
            uaRetransmissionTask = null;
        }
        if (uaRetransmissionTimer != null) {
            uaRetransmissionTimer.cancel();
            uaRetransmissionTimer.purge();
            uaRetransmissionTimer = null;
        }
        this.lastUaErrorSent = null;
        System.out.println("[UA Timer] Retransmisión de error cancelada.");
    }

    /**
     * Inicia el temporizador de 1 segundo del estado Completed (Rol Caller y Callee).
     */
    private void startUaCompletedTimer(int durationMs, final int finalState) {
        // Asegurarse de que el timer de retransmisión (si existía) está muerto
        cancelUaErrorRetransmissionTimer(); 
        
        if (uaCompletedTimer != null) {
            uaCompletedTimer.cancel();
            uaCompletedTimer.purge();
        }
        uaCompletedTimer = new Timer();
        System.out.println("[UA Timer] Iniciando timer 'Completed' (1s)...");

        uaCompletedTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Timer iniciado.
                System.out.println("[UA Timer] 'Completed' terminado.");
                state = finalState; // Pasa al estado final 
                
                clearCallData(); // Limpia los datos de la llamada AHORA
                
                // Si el estado final es UNREGISTERED (p.ej. por un 404)
                if (finalState == UNREGISTERED) {
                    System.out.println("Pasando a estado UNREGISTERED e iniciando re-registro.");
                    cancelRegisterRenewTimer(); 
                    resendRegisterTimerActivate(0, 2000);
                }
                
                prompt();
            }
        }, durationMs); // 1000 ms = 1 segundo
    }


	private void commandAcceptCall() throws IOException {
		cancelRequestTimeoutTimer();
        // Asegurarnos de que estamos en el estado correcto
        if (state != IN_CALL || incomingInvite == null) {
            System.out.println("No hay ninguna llamada entrante para aceptar.");
            return;
        }

        System.out.println("Aceptando llamada... Enviando 200 OK.");

        // 1. Crear el SDP para este UA (Bob)
        SDPMessage sdpMessage = new SDPMessage();
		sdpMessage.setIp(this.myAddress);
		sdpMessage.setPort(this.rtpPort);
		sdpMessage.setOptions(RTPFLOWS);

        // 2. Crear el mensaje 200 OK 
        OKMessage okMessage = new OKMessage();
        
        // 3. Copiar cabeceras del INVITE original 
        okMessage.setVias(incomingInvite.getVias());
        okMessage.setToName(incomingInvite.getToName());
        okMessage.setToUri(incomingInvite.getToUri());
        okMessage.setFromName(incomingInvite.getFromName());
        okMessage.setFromUri(incomingInvite.getFromUri());
        okMessage.setCallId(incomingInvite.getCallId());
        okMessage.setcSeqNumber(incomingInvite.getcSeqNumber());
        okMessage.setcSeqStr(incomingInvite.getcSeqStr()); // "INVITE"
        okMessage.setContact(this.userContact); // Tu contacto
        
        if (this.incomingRecordRoute != null) {
            okMessage.setRecordRoute(this.incomingRecordRoute);
        }
        
        // 4. Adjuntar el SDP
        okMessage.setSdp(sdpMessage);
        okMessage.setContentLength(sdpMessage.toStringMessage().getBytes().length);

        // 5. Enviar usando la capa de transacción
        transactionLayer.send200OK(okMessage);
        showMessage(okMessage);

        // 6. Limpiar
        this.incomingInvite = null; // Ya hemos respondido a esta invitación
        // (El estado ya es IN_CALL, así que el prompt() ahora mostrará "Type BYE...")
    }
	
	
	
	private void commandRejectCall() throws IOException {
		cancelRequestTimeoutTimer(); // Cancela el timer 408
        // Asegurarnos de que estamos en el estado correcto
        if (state != IN_CALL || incomingInvite == null) {
            System.out.println("No hay ninguna llamada entrante para rechazar.");
            return;
        }

        System.out.println("Rechazando llamada... Enviando 486 Busy Here.");

        // 1. Crear el mensaje 486 Busy Here
        BusyHereMessage busyMessage = new BusyHereMessage();
        
        // 2. Copiar cabeceras del INVITE original
        busyMessage.setVias(incomingInvite.getVias());
        busyMessage.setToName(incomingInvite.getToName());
        busyMessage.setToUri(incomingInvite.getToUri());
        busyMessage.setFromName(incomingInvite.getFromName());
        busyMessage.setFromUri(incomingInvite.getFromUri());
        busyMessage.setCallId(incomingInvite.getCallId());
        busyMessage.setcSeqNumber(incomingInvite.getcSeqNumber());
        busyMessage.setcSeqStr(incomingInvite.getcSeqStr()); // "INVITE"
        busyMessage.setContentLength(0);
        
        
        
        // 3. Enviar usando la capa de transacción
        transactionLayer.send486BusyHere(busyMessage);
        showMessage(busyMessage);

        state = COMPLETED;
        startUaErrorRetransmissionTimer(200); // Inicia retransmisión cada 200ms
        this.lastUaErrorSent = busyMessage;
        prompt();
    }
	
	
	
	
	private void commandBye() throws IOException {
        if (state != IN_CALL || activeCallId == null) {
            System.out.println("Error: No estás en una llamada.");
            return;
        }

        System.out.println("Colgando... Enviando BYE.");

        ByeMessage byeMessage = new ByeMessage();
        
        if (this.userName.equals(activeFromName)) {
            // Soy el LLAMANTE (Alice)
            byeMessage.setDestination(activeToUri);
            byeMessage.setToName(activeToName);
            byeMessage.setToUri(activeToUri);
            byeMessage.setFromName(activeFromName);
            byeMessage.setFromUri(activeFromUri);
        } else {
            // Soy el LLAMADO (Bob)
            byeMessage.setDestination(activeFromUri);
            byeMessage.setToName(activeFromName);
            byeMessage.setToUri(activeFromUri);
            byeMessage.setFromName(activeToName);
            byeMessage.setFromUri(activeToUri);
        }
        
        byeMessage.setVias(originatingVias);
        byeMessage.setMaxForwards(70);
        byeMessage.setCallId(activeCallId);


        cSeqCounter++;
        byeMessage.setcSeqNumber(String.valueOf(cSeqCounter));
        
        byeMessage.setcSeqStr("BYE");
        byeMessage.setContentLength(0);

        if (this.activeRoute != null) {
            // Loose-Routing (Record-Route existe): Enviar al Proxy
            System.out.println("[UA] Loose-routing detectado. Enviando BYE al Proxy.");
            byeMessage.setRoute(this.activeRoute); // Añadir cabecera Route
            transactionLayer.sendBye(byeMessage); // Envía al Proxy
        } else {
            // No Loose-Routing (Sin Record-Route): Enviar directo al Contact
            System.out.println("[UA] No loose-routing. Enviando BYE directo a: " + this.activeContact);
            String[] contactParts = activeContact.split(":");
            String contactAddress = contactParts[0];
            int contactPort = Integer.parseInt(contactParts[1]);
            
            transactionLayer.sendBye(byeMessage, contactAddress, contactPort); // Envía a IP/Puerto
        }
        showMessage(byeMessage);

        System.out.println("Esperando confirmación 200 OK del BYE...");
    }
	
	
	
	private void clearCallData() {
        this.activeCallId = null;
        this.activeToName = null;
        this.activeToUri = null;
        this.activeFromName = null;
        this.activeFromUri = null;
        this.incomingInvite = null; // También limpiamos esto
        this.incomingRecordRoute = null;
        this.activeRoute = null;
        this.activeContact = null;
    }

	
	//VITEXT
	private void runVitextClient() throws IOException {
//		vitextClient = Runtime.getRuntime().exec("xterm -e vitext/vitextclient -p 5000 239.1.2.3");
//		System.out.println("[DEBUG] runVitextClient() llamado, pero 'xterm' está deshabilitado.");
	}

	private void stopVitextClient() {
		if (vitextClient != null) {
			vitextClient.destroy();
		}
	}

	private void runVitextServer() throws IOException {
//		vitextServer = Runtime.getRuntime().exec("xterm -iconic -e vitext/vitextserver -r 10 -p 5000 vitext/1.vtx 239.1.2.3");
//		System.out.println("[DEBUG] runVitextServer() llamado, pero 'xterm' está deshabilitado.");
	}

	private void stopVitextServer() {
		if (vitextServer != null) {
			vitextServer.destroy();
		}
	}
	
	
	//Depuración
	public void showMessage(SIPMessage sipMessage) {
		if (debug) {
			System.out.println(sipMessage.toStringMessage());
		}
	}
	

}
