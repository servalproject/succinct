package org.servalproject.succinct.networking;

import org.servalproject.succinct.networking.messages.Header;
import org.servalproject.succinct.networking.messages.Message;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

public class PeerConnection extends StreamHandler {
	private final Networks networks;
	private Peer peer;
	private final Queue<Message> queue = new LinkedList<>();

	public PeerConnection(Networks networks, SocketChannel client) {
		this(networks, client, null);
	}

	public PeerConnection(Networks networks, SocketChannel client, Peer peer) {
		super(client);
		this.networks = networks;
		this.peer = peer;
		queue.add(new Header(networks.myId, true));
	}

	@Override
	protected void emptyReadBuffer(ByteBuffer readBuffer) throws ProtocolException {
		while(true) {
			Message msg = Message.parseMessage(readBuffer);
			if (msg == null) {
				if (readBuffer.position()==0 && readBuffer.limit() == readBuffer.capacity())
					throw new IllegalStateException("Failed to empty read buffer");

				return;
			}

			if (msg instanceof Header){
				Header hdr = (Header)msg;
				peer = networks.getPeer(hdr.id);
				peer.setConnection(this);
				continue;
			}

			if (peer == null)
				throw new ProtocolException("Expected header");

			msg.process(peer);
		}
	}

	@Override
	protected void fillWriteBuffer(ByteBuffer writeBuffer) {
		// queued messages
		while(true) {
			Message msg = queue.peek();
			if (msg == null)
				break;
			if (!msg.write(writeBuffer))
				break;
			queue.poll();
		}
		// TODO do file content messages go through the queue? or should we get one now?
	}

	public void queue(Message message) {
		queue.add(message);
		tryFill();
	}
}
