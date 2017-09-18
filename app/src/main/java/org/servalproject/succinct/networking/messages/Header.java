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

	public Header(PeerId id, boolean unicast){
		super(Type.HeaderMessage);
		this.id = id;
		this.unicast = unicast;
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
			return new Header(id, (flags&1)>0);
		}

		@Override
		public void serialise(Serialiser serialiser, Header object) {
			object.id.serialise(serialiser);
			serialiser.putByte((byte) (object.unicast? 1 : 0));
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
