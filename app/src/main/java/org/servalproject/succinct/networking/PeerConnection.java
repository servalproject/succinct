
package org.servalproject.succinct.networking;

import android.util.Log;

import org.servalproject.succinct.networking.messages.Header;
import org.servalproject.succinct.networking.messages.Message;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

public class PeerConnection extends StreamHandler {
	private final Networks networks;
	private Peer peer;
	final boolean initiated;
	boolean shutdown = false;
	private static final String TAG = "Connection";

	private final Queue<Message> queue = new PriorityQueue<>(10, new Comparator<Message>() {
		@Override
		public int compare(Message one, Message two) {
			return two.type.ordinal() - one.type.ordinal();
		}
	});

	public PeerConnection(Networks networks, SocketChannel client) {
		this(networks, client, null, false);
	}

	public PeerConnection(Networks networks, SocketChannel client, Peer peer) {
		this(networks, client, peer, true);
	}

	private PeerConnection(Networks networks, SocketChannel client, Peer peer, boolean initiated) {
		super(client);
		this.initiated = initiated;
		this.networks = networks;
		this.peer = peer;
		if (peer!=null)
			peer.setConnection(this);
		queue.add(new Header(networks.myId, true));
		tryFill();
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
				if (peer == null) {
					Header hdr = (Header) msg;
					peer = networks.createPeer(hdr.id);
					peer.setConnection(this);
				}
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
			if (peer != null)
				peer.wrote(msg);
			queue.poll();
		}
	}

	@Override
	public void write() throws IOException {
		super.write();
		if (shutdown && queue.isEmpty() && (getInterest() & SelectionKey.OP_WRITE)==0) {
			Log.v(TAG, "Graceful close (write)");
			close();
		}
	}

	public void queue(Message message) {
		if (shutdown)
			throw new IllegalStateException();
		queue.add(message);
		tryFill();
	}

	public void shutdown(){
		if (shutdown)
			return;
		Log.v(TAG, "Shutdown");
		shutdown = true;
		if (peer!=null && peer.connection == this)
			peer.setConnection(null);
		if (queue.isEmpty()) {
			Log.v(TAG, "Graceful close");
			close();
		}
	}

	@Override
	public void close() {
		super.close();
		if (!shutdown)
			Log.v(TAG, "Forceful close?");
		if (peer!=null && peer.connection == this)
			peer.setConnection(null);
	}
}
