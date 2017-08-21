package org.servalproject.succinct.networking;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public class PeerId {
	// How long does an ID need to be?
	public static final int LEN=4;
	private final byte[] id;

	public PeerId(String value){
		id = Hex.fromString(value);
	}

	PeerId(byte[] bytes){
		this.id = bytes;
	}
	public PeerId(ByteBuffer buff){
		this.id = new byte[LEN];
		buff.get(id);
	}
	public PeerId(){
		this.id = new byte[LEN];
		new SecureRandom().nextBytes(id);
	}

	@Override
	public String toString() {
		return Hex.toString(id);
	}

	public void write(ByteBuffer buff){
		buff.put(id);
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
