package org.servalproject.succinct.networking.messages;

import android.util.Log;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.networking.PeerSocketLink;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class Ack extends Message {
	public final List<LinkAck> links= new ArrayList<>();

	public static final String TAG = "Ack";

	Ack(ByteBuffer parseBuff) {
		super(Type.AckMessage);
		while(parseBuff.hasRemaining())
			links.add(new LinkAck(parseBuff));
	}

	public Ack(){
		super(Type.AckMessage);
	}

	public void add(Peer peer, PeerSocketLink link){
		links.add(new LinkAck(peer.id, link.heardBroadcast(), link.heardUnicast()));
	}

	@Override
	protected boolean serialise(ByteBuffer buff) {
		if (links.isEmpty())
			return false;
		for(LinkAck linkAck :links)
			linkAck.write(buff);
		return true;
	}

	@Override
	public void process(Peer peer) {
	}

	public class LinkAck {
		public final PeerId id;
		public final boolean broadcast;
		public final boolean unicast;

		private LinkAck(ByteBuffer parseBuff) {
			this.id = new PeerId(parseBuff);
			byte flags = parseBuff.get();
			this.broadcast = (flags &2)>0;
			this.unicast = (flags &1)>0;
		}

		private LinkAck(PeerId id, boolean broadcast, boolean unicast){
			Log.v(TAG, "Building ack ("+broadcast+", "+unicast+")");
			this.id = id;
			this.broadcast = broadcast;
			this.unicast = unicast;
		}

		private boolean write(ByteBuffer buff) {
			if (buff.remaining()<PeerId.LEN+1)
				return false;
			id.write(buff);
			byte flags =0;
			if (broadcast)
				flags |= 2;
			if (unicast)
				flags |= 1;
			buff.put(flags);
			return true;
		}
	}
}
