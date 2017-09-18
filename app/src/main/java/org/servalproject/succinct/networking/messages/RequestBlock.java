package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;

public class RequestBlock extends Message<RequestBlock> {
	private static final int HASH_LEN=8;
	public final String filename;
	public final long offset;
	public final long length;

	public RequestBlock(String filename, long offset, long length) {
		super(Type.RequestBlockMessage);
		this.filename = filename;
		this.offset = offset;
		this.length = length;
	}

	public static final Factory<RequestBlock> factory = new Factory<RequestBlock>() {
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public RequestBlock create(DeSerialiser serialiser) {
			long offset = serialiser.getLong();
			long length = serialiser.getLong();
			String filename = new String(serialiser.getFixedBytes(DeSerialiser.REMAINING));
			return new RequestBlock(filename, offset, length);
		}

		@Override
		public void serialise(Serialiser serialiser, RequestBlock object) {
			serialiser.putLong(object.offset);
			serialiser.putLong(object.length);
			serialiser.putFixedBytes(object.filename.getBytes());
		}
	};

	@Override
	protected Factory<RequestBlock> getFactory() {
		return factory;
	}

	@Override
	public void process(Peer peer) {
		peer.processRequest(this);
	}
}
