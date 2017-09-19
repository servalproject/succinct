package org.servalproject.succinct.networking;

import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public class PeerId {
	// How long does an ID need to be?
	public static final int LEN=8;
	private final byte[] id;

	public PeerId(String value){
		id = Hex.fromString(value);
		if (id.length!=LEN)
			throw new IllegalStateException("Unexpected length!");
	}

	PeerId(byte[] bytes){
		this.id = bytes;
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

	// team level content needs to be saved somewhere predictable
	public static final PeerId Team;

	static{
		byte[] id = new byte[LEN];
		id[0] = 1;
		Team = new PeerId(id);
	}

	@Override
	public String toString() {
		return Hex.toString(id);
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
