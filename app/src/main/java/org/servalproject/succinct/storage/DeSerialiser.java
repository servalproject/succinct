package org.servalproject.succinct.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DeSerialiser {
	private final ByteBuffer buff;
	public DeSerialiser(byte[] bytes){
		buff = ByteBuffer.wrap(bytes);
	}
	public DeSerialiser(ByteBuffer buff){
		this.buff = buff;
		buff.order(ByteOrder.BIG_ENDIAN);
	}

	public int remaining(){
		return buff.remaining();
	}

	public boolean hasRemaining(){
		return buff.hasRemaining();
	}

	// return a NULL terminated string
	public String getString(){
		int i;
		for (i = buff.position(); i<buff.limit() && buff.get(i)!=0;i++)
			;
		String ret = new String(getFixedBytes(i - buff.position()));
		if (i<buff.limit())
			buff.get();
		return ret;
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

	public int getRawInt(){
		return buff.getInt();
	}

	// Time since epoc in 0.1s
	public long getTime(long epoc){
		return (getRawInt()*100L) + epoc;
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
