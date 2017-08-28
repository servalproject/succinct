package org.servalproject.succinct.storage;

import java.nio.ByteBuffer;

public class DeSerialiser {
	private final ByteBuffer buff;
	public DeSerialiser(byte[] bytes){
		buff = ByteBuffer.wrap(bytes);
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

	public byte[] getBytes(){
		int length = (int)getLong();
		byte[] bytes = new byte[length];
		buff.get(bytes);
		return bytes;
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
