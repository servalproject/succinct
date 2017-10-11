package org.servalproject.succinct.networking;

import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public class PeerId {
	// How long does an ID need to be?
	public static final int LEN=8;
	public final byte[] id;

	public PeerId(String value){
		this(Hex.fromString(value));
	}
	PeerId(byte[] bytes){
		this.id = bytes;
		if (id.length!=LEN)
			throw new IllegalStateException("Unexpected length!");
	}
	public PeerId(ByteBuffer buff){
		this.id = new byte[LEN];
		buff.get(id);
	}
	public PeerId(DeSerialiser serialiser){
		this.id = serialiser.getFixedBytes(LEN);
	}
	public PeerId(){
		this.id = new byte[LEN];
		new SecureRandom().nextBytes(id);
	}

	public static PeerId fromBuffer(byte[] buffer, int offset){
		byte[] id = new byte[LEN];
		System.arraycopy(buffer, offset, id, 0, LEN);
		return new PeerId(id);
	}

	@Override
	public String toString() {
		return Hex.toString(id);
	}

	public void toBuffer(byte[] buffer, int offset){
		System.arraycopy(id, 0, buffer, offset, LEN);
	}

	public void write(ByteBuffer buff){
		buff.put(id);
	}

	public void serialise(Serialiser serialiser){
		serialiser.putFixedBytes(id);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		PeerId peerId = (PeerId) o;

		return Arrays.equals(id, peerId.id);

	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(id);
	}
}
