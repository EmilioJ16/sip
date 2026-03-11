package proxy;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.SIPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.TryingMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.ACKMessage;
import mensajesSIP.UnauthorizedMessage;

public class ProxyTransactionLayer {
	
	//Estados
	private static final int IDLE = 0;   
	private int state = IDLE;
	
	//Capas
	private ProxyUserLayer userLayer;
	private ProxyTransportLayer transportLayer;

	
	//Constructor
	public ProxyTransactionLayer(int listenPort, ProxyUserLayer userLayer) throws SocketException {
		this.userLayer = userLayer;
		this.transportLayer = new ProxyTransportLayer(listenPort, this);
	}

	
	//Comineza escucha
	public void startListening() {
		transportLayer.startListening();
	}
	
	
	//Manejo de mensajes recibidos
	public void onMessageReceived(SIPMessage sipMessage) throws IOException {
		if (sipMessage instanceof InviteMessage) {
			InviteMessage inviteMessage = (InviteMessage) sipMessage;
			switch (state) {
			case IDLE:
				userLayer.onInviteReceived(inviteMessage);
				break;
			default:
				System.err.println("Unexpected message, throwing away");
				break;
			}
		} else 	if (sipMessage instanceof RegisterMessage) {
			RegisterMessage registerMessage = (RegisterMessage) sipMessage;
			switch (state) {
			case IDLE:
				userLayer.onRegisterReceived(registerMessage);
				break;
			default:
				System.err.println("Unexpected message, throwing away");
				break;
			}
		} else if (sipMessage instanceof RingingMessage) {
            userLayer.onRingingReceived((RingingMessage) sipMessage);

        } else if (sipMessage instanceof OKMessage) {
            // Esto manejará el 200 OK de Bob cuando acepte la llamada
            userLayer.onOKReceived((OKMessage) sipMessage);
            
        } else if (sipMessage instanceof BusyHereMessage) {
            userLayer.onBusyHereReceived((BusyHereMessage) sipMessage);
            
        } else if (sipMessage instanceof ACKMessage) {
            // Pasa el ACK al UserLayer para que lo reenvíe
            userLayer.onACKReceived((ACKMessage) sipMessage);	
		} else if (sipMessage instanceof ByeMessage) {
            userLayer.onByeReceived((ByeMessage) sipMessage);
        
		} else if (sipMessage instanceof RequestTimeoutMessage) {
            userLayer.on408RequestTimeoutReceived((RequestTimeoutMessage) sipMessage);

		} else {
			System.err.println("Unexpected message, throwing away");
		}
	}
	
	
	//Enviados:
	public void echoInvite(InviteMessage inviteMessage, String address, int port) throws IOException {
		transportLayer.send(inviteMessage, address, port);
	}

	
	public void send200OK(OKMessage okMessage, String address, int port) throws IOException {
		transportLayer.send(okMessage, address, port);
	}

	public void send401(UnauthorizedMessage unauthorizedMessage, String address, int port) throws IOException {
		transportLayer.send(unauthorizedMessage, address, port);
	}
	
	public void send404NotFound(NotFoundMessage nfMessage, String address, int port) throws IOException {
		transportLayer.send(nfMessage, address, port);
	}
	
	public void send100Trying(TryingMessage tryingMessage, String address, int port) throws IOException {
        transportLayer.send(tryingMessage, address, port);
    }
	
	public void send180Ringing(RingingMessage ringingMessage, String address, int port) throws IOException {
		transportLayer.send(ringingMessage, address, port);
	}
	
	public void send486BusyHere(BusyHereMessage busyMessage, String address, int port) throws IOException {
		transportLayer.send(busyMessage, address, port);
	}
	
	public void sendACK(ACKMessage ackMessage, String address, int port) throws IOException {
		transportLayer.send(ackMessage, address, port);
	}
	
	public void sendBye(ByeMessage byeMessage, String address, int port) throws IOException {
		transportLayer.send(byeMessage, address, port);
	}
	
	public void send408RequestTimeout(RequestTimeoutMessage timeoutMessage, String address, int port) throws IOException {
		transportLayer.send(timeoutMessage, address, port);
	}
	
	public void send503ServiceUnavailable(ServiceUnavailableMessage suMessage, String address, int port) throws IOException {
        transportLayer.send(suMessage, address, port);
    }

	
}
