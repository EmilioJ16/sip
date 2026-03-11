package ua;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.UnauthorizedMessage;
import mensajesSIP.TryingMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.ACKMessage;

public class UaTransactionLayer {
	
	//Estados
	private static final int IDLE = 0;           
	private static final int UNREGISTERED = 1;
	private int state = UNREGISTERED;

	//Une diferentes capas
	private UaUserLayer userLayer;
	private UaTransportLayer transportLayer;

	//Constructor 
	public UaTransactionLayer(int listenPort, String proxyAddress, int proxyPort, UaUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, this);
		
	}
	
	//Arranca red
	public void startListeningNetwork() {
		transportLayer.startListening();
	}

	
	//Decisión de mensajes recibidos
	public void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof InviteMessage) {
			InviteMessage inviteMessage = (InviteMessage) sipMessage;
			switch (state) {
			case IDLE:
				userLayer.onInviteReceived(inviteMessage);
				break;
			default:
				System.err.println("Unexpected messagefor state idle, throwing away");
				break;
			}
		} else if (sipMessage instanceof OKMessage){
			OKMessage okMessage = (OKMessage) sipMessage;
			switch (state) {
			case UNREGISTERED:
				state = IDLE;
				userLayer.on200OKReceived(okMessage);
				break;
			case IDLE:
				userLayer.on200OKReceived(okMessage);
                break;
			default:
				System.err.println("Unexpected OK message, throwing away");
				break;
			}
		} else if (sipMessage instanceof UnauthorizedMessage){
			UnauthorizedMessage unauthorizedMessage = (UnauthorizedMessage) sipMessage;
			userLayer.on401UnauthorizedReceived(unauthorizedMessage);

		} else if (sipMessage instanceof TryingMessage) {
            // Pasa el mensaje al UserLayer
            userLayer.on100TryingReceived((TryingMessage) sipMessage);

        } else if (sipMessage instanceof RingingMessage) {
            // No depende del estado de registro, pasa directo al UserLayer
            userLayer.on180RingingReceived((RingingMessage) sipMessage);
            
        } else if (sipMessage instanceof BusyHereMessage) {
            userLayer.on486BusyHereReceived((BusyHereMessage) sipMessage);
            
        } else if (sipMessage instanceof ACKMessage) {
            // Cuando un ACK llega de la red, pásalo al UserLayer
            userLayer.onACKReceived((ACKMessage) sipMessage);
            
        } else if (sipMessage instanceof ByeMessage) {
            userLayer.onByeReceived((ByeMessage) sipMessage);
            
        } else if (sipMessage instanceof RequestTimeoutMessage) {
            userLayer.on408RequestTimeoutReceived((RequestTimeoutMessage) sipMessage);
            
        } else if (sipMessage instanceof ServiceUnavailableMessage) {
            userLayer.on503ServiceUnavailableReceived((ServiceUnavailableMessage) sipMessage);
            
        } else if (sipMessage instanceof NotFoundMessage) {
            userLayer.on404NotFoundReceived((NotFoundMessage) sipMessage);

		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}
	
	
	
	//Llamadas de mensajes a proxy

	public void register (RegisterMessage registerMessage) throws IOException {
		state = UNREGISTERED;
		transportLayer.sendToProxy(registerMessage);

	}
	
	public void send180Ringing(RingingMessage ringingMessage) throws IOException {
        // Las respuestas viajan de vuelta al proxy
        transportLayer.sendToProxy(ringingMessage); 
    }
	
	public void send200OK(OKMessage okMessage) throws IOException {
        // Las respuestas viajan de vuelta al proxy
        transportLayer.sendToProxy(okMessage);
    }
	
	public void send486BusyHere(BusyHereMessage busyMessage) throws IOException {
        // Las respuestas viajan de vuelta al proxy
        transportLayer.sendToProxy(busyMessage);
    }
	
	public void sendACK(ACKMessage ackMessage) throws IOException {
        transportLayer.sendToProxy(ackMessage);
    }
	
	public void sendACK(ACKMessage ackMessage, String address, int port) throws IOException {
        transportLayer.send(ackMessage, address, port);
    }
	
	public void sendBye(ByeMessage byeMessage) throws IOException {
        transportLayer.sendToProxy(byeMessage);
    }
	
	// Envío de BYE a una IP/Puerto específico (para end-to-end)
    public void sendBye(ByeMessage byeMessage, String address, int port) throws IOException {
        transportLayer.send(byeMessage, address, port);
    }
	
	public void send408RequestTimeout(RequestTimeoutMessage timeoutMessage) throws IOException {
        transportLayer.sendToProxy(timeoutMessage);
    }
	
	public void call(InviteMessage inviteMessage) throws IOException {
		transportLayer.sendToProxy(inviteMessage);
	}
	
	
	
}
