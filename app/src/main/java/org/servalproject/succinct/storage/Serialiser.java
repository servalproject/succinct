package org.servalproject.succinct.storage;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class Serialiser {
	private ByteBuffer buff;
	public Serialiser(){
		buff = ByteBuffer.allocate(1200);
	}

	static final Charset UTF_8 = Charset.forName("UTF-8");

	public void putString(String value){
		putBytes(value.getBytes(UTF_8));
	}

	public void putDouble(double value){
		buff.putDouble(value);
	}

	public void putFloat(float value){
		buff.putFloat(value);
	}

	public void putByte(byte value){
		buff.put(value);
	}

	public void putBytes(byte[] value){
		putBytes(value, 0, value.length);
	}

	public void putBytes(byte[] value, int offset, int length){
		putLong(length);
		buff.put(value, offset, length);
	}

	public void putLong(long value){
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

	public byte[] getResult(){
		buff.flip();
		byte[] ret = new byte[buff.remaining()];
		buff.get(ret);
		return ret;
	}
}
