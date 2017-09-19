package org.servalproject.succinct.networking.messages;

import android.util.Log;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public abstract class Message<T extends Message<T>> {
	public final Type type;
	public static final int MTU = 1200;
	private static final String TAG = "Message";

	@Override
	public String toString() {
		return "Message "+getClass().getName();
	}

	protected Message(Type type){
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
		TeamMessage,
	}
	private static Type[] types = Type.values();

	public static Message parseMessage(ByteBuffer buff){
		if (buff.remaining()<3)
			return null;
		int limit = buff.limit();
		buff.mark();

		int t = buff.get();
		if (t >= types.length)
			throw new IllegalStateException("Unexpected type "+t);
		Type type = types[t];

		short len = buff.getShort();
		if (len > buff.remaining()) {
			buff.reset();
			return null;
		}

		buff.limit(buff.position()+len);
		try {
			DeSerialiser serialiser = new DeSerialiser(buff);

			switch (type) {
				case HeaderMessage:
					return Header.factory.create(serialiser);
				case AckMessage:
					return Ack.factory.create(serialiser);
				case StoreStateMessage:
					return StoreState.factory.create(serialiser);
				case SyncMsgMessage:
					return SyncMsg.factory.create(serialiser);
				case RequestBlockMessage:
					return RequestBlock.factory.create(serialiser);
				case FileBlockMessage:
					return FileBlock.factory.create(serialiser);
			}
			throw new IllegalStateException("Unexpected type!");
		}catch (BufferUnderflowException e){
			Log.e(TAG, e.getMessage(), e);
			return null;
		}finally{
			buff.limit(limit);
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

			Serialiser serialiser = new Serialiser(buff);
			Factory<T> factory = getFactory();
			factory.serialise(serialiser, (T)this);

			int len = buff.position() - lenOffset - 2;
			buff.putShort(lenOffset, (short) len);
			return isComplete();
		} catch (BufferOverflowException e){
			buff.reset();
			return false;
		}
	}

	protected abstract Factory<T> getFactory();

	// give the sub type a chance to stay in the transmit queue
	protected boolean isComplete(){
		return true;
	}

	public abstract void process(Peer peer);
}
