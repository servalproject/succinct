package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;

import java.nio.ByteBuffer;

public class RequestBlock extends Message {
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

	RequestBlock(ByteBuffer buffer){
		super(Type.RequestBlockMessage);
		offset = Message.getPackedLong(buffer);
		length = Message.getPackedLong(buffer);
		byte[] nameBytes = new byte[buffer.remaining()];
		buffer.get(nameBytes);
		filename = new String(nameBytes);
	}

	@Override
	protected boolean serialise(ByteBuffer buff) {
		Message.putPackedLong(buff, offset);
		Message.putPackedLong(buff, length);
		buff.put(filename.getBytes());
		return true;
	}

	@Override
	public void process(Peer peer) {
		peer.processRequest(this);
	}
}
