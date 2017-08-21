package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

public abstract class Message {
	public final Type type;
	public static final int MTU = 1200;

	@Override
	public String toString() {
		return "Message "+getClass().getName();
	}

	Message(Type type){
		this.type = type;
	}

	// Note; type ordinals are currently both the network id of the message
	// and a priority order for synchronisation
	// for backward compatibility, we may need to break one of these in future...
	public enum Type{
		HeaderMessage,
		AckMessage,
		StoreStateMessage,
		SyncMsgMessage,
		RequestBlockMessage,
		FileBlockMessage,
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
			case SyncMsgMessage:
				return new SyncMsg(parseBuff);
			case RequestBlockMessage:
				return new RequestBlock(parseBuff);
			case FileBlockMessage:
				return new FileBlock(parseBuff);
		}

		throw new IllegalStateException("Unexpected type!");
	}

	public static long getPackedLong(ByteBuffer buffer){
		long ret=0;
		int shift=0;
		while(true){
			int val = buffer.get() & 0xFF;
			if (val==0)
				break;
			ret |= (val & 0x7f)<<shift;
			shift+=7;
		}
		return ret;
	}

	public static void putPackedLong(ByteBuffer buffer, long value){
		while (value != 0) {
			buffer.put((byte)(0x80 | (value & 0x7f)));
			value = value >>> 7;
		}
		buffer.put((byte)0);
	}

	public boolean write(ByteBuffer buff){
		if (buff.remaining()<3)
			return false;
		buff.mark();
		try {
			buff.put((byte) type.ordinal());
			int lenOffset = buff.position();
			buff.putShort((short) 0);
			if (!serialise(buff)) {
				buff.reset();
				return false;
			}
			int len = buff.position() - lenOffset - 2;
			buff.putShort(lenOffset, (short) len);
			return true;
		} catch (BufferOverflowException e){
			buff.reset();
			return false;
		}
	}

	protected abstract boolean serialise(ByteBuffer buff);

	public abstract void process(Peer peer);
}
