package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;

import java.nio.ByteBuffer;

public abstract class Message {
	public final Type type;
	private static final String TAG = "Message";
	Message(Type type){
		this.type = type;
	}

	public enum Type{
		HeaderMessage,
		AckMessage,
		StoreStateMessage,
		SyncKeyMessage
	}

	public static Message parseMessage(ByteBuffer buff){
		if (buff.remaining()<3)
			return null;
		int limit = buff.limit();
		buff.mark();
		Type type = Type.values()[buff.get()];
		short len = buff.getShort();
		if (len > buff.remaining()) {
			buff.reset();
			return null;
		}
		buff.limit(buff.position()+len);
		ByteBuffer parseBuff = buff.slice();
		buff.position(buff.limit());
		buff.limit(limit);

		switch (type){
			case HeaderMessage:
				return new Header(parseBuff);
			case AckMessage:
				return new Ack(parseBuff);
			case StoreStateMessage:
				return new StoreState(parseBuff);
			case SyncKeyMessage:
				return new SyncKey(parseBuff);
		}

		throw new IllegalStateException("Unexpected type!");
	}

	public void write(ByteBuffer buff){
		buff.mark();
		try {
			buff.put((byte) type.ordinal());
			int lenOffset = buff.position();
			buff.putShort((short) 0);
			serialise(buff);
			buff.mark();
			int len = buff.position() - lenOffset -2;
			buff.putShort(lenOffset, (short) len);
		} finally {
			buff.reset();
		}
	}

	protected abstract void serialise(ByteBuffer buff);

	public abstract void process(Peer peer);
}
