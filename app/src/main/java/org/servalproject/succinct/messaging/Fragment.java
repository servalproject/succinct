package org.servalproject.succinct.messaging;

import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;

public class Fragment  {
	public final byte[] bytes;

	public Fragment(byte[] bytes){
		this.bytes = bytes;
	}

	public static final Factory<Fragment> factory = new Factory<Fragment>() {
		@Override
		public String getFileName() {
			return "fragments";
		}

		@Override
		public Fragment create(DeSerialiser serialiser) {
			return new Fragment(serialiser.getFixedBytes(DeSerialiser.REMAINING));
		}

		@Override
		public void serialise(Serialiser serialiser, Fragment object) {
			serialiser.putFixedBytes(object.bytes);
		}
	};

}
