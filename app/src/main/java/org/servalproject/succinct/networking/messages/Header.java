package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;

public class Header extends Message<Header>{
	public final PeerId id;
	public final boolean unicast;
	public final int seq;

	public Header(PeerId id, boolean unicast){
		this(id, unicast, -1);
	}

	public Header(PeerId id, boolean unicast, int seq){
		super(Type.HeaderMessage);
		this.id = id;
		this.unicast = unicast;
		this.seq = seq;
	}

	public static final Factory<Header> factory = new Factory<Header>() {
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public Header create(DeSerialiser serialiser) {
			PeerId id = new PeerId(serialiser);
			byte flags = serialiser.getByte();
			int seq = -1;
			if (serialiser.hasRemaining())
				seq = serialiser.getShort() & 0xFFFF;
			return new Header(id, (flags&1)>0, seq);
		}

		@Override
		public void serialise(Serialiser serialiser, Header object) {
			object.id.serialise(serialiser);
			serialiser.putByte((byte) (object.unicast? 1 : 0));
			if (object.seq>=0)
				serialiser.putShort((short) object.seq);
		}
	};

	@Override
	protected Factory<Header> getFactory() {
		return factory;
	}

	@Override
	public void process(Peer peer) {

	}
}
