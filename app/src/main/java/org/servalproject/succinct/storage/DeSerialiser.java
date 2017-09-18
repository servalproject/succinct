package org.servalproject.succinct.storage;

import java.nio.ByteBuffer;

public class DeSerialiser {
	private final ByteBuffer buff;
	public DeSerialiser(byte[] bytes){
		buff = ByteBuffer.wrap(bytes);
	}
	public DeSerialiser(ByteBuffer buff){
		this.buff = buff;
	}

	public int remaining(){
		return buff.remaining();
	}

	public boolean hasRemaining(){
		return buff.hasRemaining();
	}

	public String getString(){
		return new String(getBytes(), Serialiser.UTF_8);
	}

	public double getDouble(){
		return buff.getDouble();
	}

	public float getFloat(){
		return buff.getFloat();
	}

	public byte getByte(){
		return buff.get();
	}

	public static final int REMAINING = -1;
	public byte[] getFixedBytes(int length){
		if (length==REMAINING)
			length = remaining();
		byte[] bytes = new byte[length];
		buff.get(bytes);
		return bytes;
	}

	public void getFixedBytes(byte[] bytes){
		buff.get(bytes);
	}

	public byte[] getBytes(){
		int length = (int)getLong();
		return getFixedBytes(length);
	}

	public long getRawLong(){
		return buff.getLong();
	}

	public long getLong(){
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
}
