package org.servalproject.succinct.messaging;

import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Fragment  {
	public final long created;
	public final int seq;
	public final byte[] bytes;

	public Fragment(long created, byte[] bytes){
		this.created = created;
		this.bytes = bytes;
		ByteBuffer b = ByteBuffer.wrap(bytes);
		b.order(ByteOrder.BIG_ENDIAN);
		seq = b.getInt(8);
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
