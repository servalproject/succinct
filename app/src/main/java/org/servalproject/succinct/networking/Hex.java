package org.servalproject.succinct.networking;

import java.nio.ByteBuffer;

public class Hex {
	private Hex(){}

	private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

	public static String toString(ByteBuffer buff){
		if (buff==null || !buff.hasRemaining())
			return "";
		int len = buff.remaining();
		char[] output = new char[len*2];
		int j=0;
		for (int i=buff.position(); i < buff.position()+len; i++) {
			int value = buff.get(i) & 0xFF;
			output[j++] = hexArray[value>>>4];
			output[j++] = hexArray[value & 0xF];
		}
		return new String(output);
	}

	public static byte[] fromString(String hex){
		int len = hex.length();
		byte[] ret = new byte[len/2];
		for (int i=0;i<ret.length;i++){
			ret[i]= (byte) (Character.digit(hex.charAt(i*2),16)<<4|
								Character.digit(hex.charAt(i*2+1),16));
		}
		return ret;
	}

	public static String toString(byte[] bytes, int offset, int length){
		if (bytes == null || bytes.length==0)
			return "";
		if (offset<0)
			offset = 0;
		if (offset+length>bytes.length)
			length = bytes.length - offset;
		if (length<=0)
			return "";
		char[] output = new char[length*2];
		int j=0;
		for (int i=0; i < length; i++) {
			int value = bytes[offset+i] & 0xFF;
			output[j++] = hexArray[value>>>4];
			output[j++] = hexArray[value & 0xF];
		}
		return new String(output);
	}

	public static String toString(byte[] bytes){
		if (bytes == null || bytes.length==0)
			return "";
		return toString(bytes, 0, bytes.length);
	}
}
