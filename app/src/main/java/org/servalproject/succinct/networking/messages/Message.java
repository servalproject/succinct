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

	public static long getPackedLong(ByteBuffer buff){
		long ret=0;
		int shift=0;
		while(true){
			int val = buff.get() & 0xFF;
			ret |= (val & 0x7f)<<shift;
			if ((val & 0x80) == 0)
				break;
			shift+=7;
		}
		return ret;
	}

	// Note, not great for negative numbers
	public static void putPackedLong(ByteBuffer buff, long value){
		while(true){
			if ((value & ~0x7f) !=0) {
				buff.put((byte) (0x80 | (value & 0x7f)));
				value = value >>> 7;
			}else {
				buff.put((byte) (value & 0x7f));
				return;
			}
		}
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
