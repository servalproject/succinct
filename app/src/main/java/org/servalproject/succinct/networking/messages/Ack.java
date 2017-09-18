package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.networking.PeerSocketLink;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.util.ArrayList;
import java.util.List;

public class Ack extends Message<Ack> {
	public final List<LinkAck> links= new ArrayList<>();

	public static final String TAG = "Ack";

	public static Factory<Ack> factory = new Factory<Ack>(){
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public Ack create(DeSerialiser serialiser) {
			Ack ret = new Ack();
			while(serialiser.hasRemaining()){
				PeerId id = new PeerId(serialiser);
				byte flags = serialiser.getByte();
				boolean broadcast = (flags &2)>0;
				boolean unicast = (flags &1)>0;

				ret.add(id, broadcast, unicast);
			}
			return ret;
		}

		@Override
		public void serialise(Serialiser serialiser, Ack object) {
			for(LinkAck l : object.links)
				l.serialise(serialiser);
		}
	};

	public Ack(){
		super(Type.AckMessage);
	}

	public void add(Peer peer, PeerSocketLink link){
		links.add(new LinkAck(peer.id, link.heardBroadcast(), link.heardUnicast()));
	}

	private void add(PeerId id, boolean broadcast, boolean unicast){
		links.add(new LinkAck(id, broadcast, unicast));
	}

	@Override
	protected Factory<Ack> getFactory() {
		return factory;
	}

	@Override
	public void process(Peer peer) {
	}

	public class LinkAck {
		public final PeerId id;
		public final boolean broadcast;
		public final boolean unicast;

		private LinkAck(PeerId id, boolean broadcast, boolean unicast){
			this.id = id;
			this.broadcast = broadcast;
			this.unicast = unicast;
		}

		private boolean serialise(Serialiser serialiser){
			if (serialiser.remaining()<PeerId.LEN+1)
				return false;
			id.serialise(serialiser);
			byte flags =0;
			if (broadcast)
				flags |= 2;
			if (unicast)
				flags |= 1;
			serialiser.putByte(flags);
			return true;
		}
	}
}
