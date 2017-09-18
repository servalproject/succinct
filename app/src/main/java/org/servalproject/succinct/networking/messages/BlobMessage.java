package org.servalproject.succinct.networking.messages;

import java.nio.ByteBuffer;

public abstract class BlobMessage<T extends BlobMessage<T>> extends Message<T>{
	protected final byte[] blob;

	BlobMessage(Type type, byte[] blob) {
		super(type);
		this.blob = blob;
	}

	BlobMessage(Type type, ByteBuffer buff) {
		this(type, buff, buff.remaining());
	}

	BlobMessage(Type type, ByteBuffer buff, int size) {
		this(type, getBytes(buff, size));
	}

	public static byte[] getBytes(ByteBuffer buffer, int size){
		byte[] ret = new byte[size];
		buffer.get(ret);
		return ret;
	}
}
