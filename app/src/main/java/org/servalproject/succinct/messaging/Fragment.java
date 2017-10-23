package org.servalproject.succinct.messaging;

import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class Fragment  {
	public final long created;
	public final byte[] bytes;
	public final PeerId team;
	public final int seq;
	public final int firstOffset;
	private final ByteBuffer buffer;

	public Fragment(long created, byte[] bytes){
		this.created = created;
		this.bytes = bytes;
		buffer = ByteBuffer.wrap(bytes);
		buffer.order(ByteOrder.BIG_ENDIAN);
		if (buffer.remaining()<13){
			team = null;
			seq = -1;
			firstOffset = 0;
		} else {
			team = new PeerId(buffer);
			seq = buffer.getInt();
			firstOffset = buffer.get() & 0xFF;
		}
		buffer.mark();
	}

	public static final int TYPE_PARTIAL = -1;
	public class Piece{
		public final int type;
		public final int len;
		public final ByteBuffer payload;

		Piece(int type, int len, ByteBuffer payload){
			this.type = type;
			this.len = len;
			this.payload = payload;
		}
	}

	public List<Piece> getPieces(){
		List<Piece> ret = new ArrayList<>();
		buffer.reset();
		if (firstOffset>0){
			int l = buffer.position()+firstOffset;
			if (l<buffer.remaining())
				buffer.limit(buffer.position()+firstOffset);
			ret.add(new Piece(TYPE_PARTIAL, -1, buffer.slice()));
			buffer.position(buffer.limit());
		}
		buffer.limit(buffer.capacity());
		while(buffer.hasRemaining()){
			int type = buffer.get()&0xFF;
			int len = buffer.getShort()&0xFFFF;
			if (len < buffer.remaining())
				buffer.limit(buffer.position()+len);
			ret.add(new Piece(type, len, buffer.slice()));
			buffer.position(buffer.limit());
			buffer.limit(buffer.capacity());
		}
		return ret;
	}

	public static final Factory<Fragment> factory = new Factory<Fragment>() {
		@Override
		public String getFileName() {
			return "fragments";
		}

		@Override
		public Fragment create(DeSerialiser serialiser) {
			long created = serialiser.getRawLong();
			return new Fragment(created, serialiser.getFixedBytes(DeSerialiser.REMAINING));
		}

		@Override
		public void serialise(Serialiser serialiser, Fragment object) {
			serialiser.putRawLong(object.created);
			serialiser.putFixedBytes(object.bytes);
		}
	};

}
