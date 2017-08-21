package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.Peer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.servalproject.succinct.networking.messages.Message.Type.StoreStateMessage;

public class StoreState extends Message{
	private static final int KEY_LEN=32;
	public final String team;
	public final byte[] key;

	public StoreState(String team, byte[] key) {
		super(StoreStateMessage);
		this.team = team;
		this.key = key;
		if (key.length != KEY_LEN)
			throw new IllegalStateException("Expected len "+KEY_LEN+", got "+ key.length);
	}

	StoreState(ByteBuffer buff){
		super(StoreStateMessage);
		this.key = new byte[KEY_LEN];
		buff.get(this.key);
		byte[] tmp = new byte[buff.remaining()];
		buff.get(tmp);
		this.team = new String(tmp);
	}

	@Override
	protected boolean serialise(ByteBuffer buff) {
		byte[] teamName = team.getBytes();
		if (buff.remaining() < teamName.length + key.length)
			return false;
		buff.put(key);
		buff.put(teamName);
		return true;
	}

	@Override
	public void process(Peer peer) {
		peer.setStoreState(this);
	}

	@Override
	public String toString() {
		return getClass().getName()+" "+team+" " + Hex.toString(key,0,20);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		StoreState that = (StoreState) o;

		if (!team.equals(that.team)) return false;
		return Arrays.equals(key, that.key);
	}

	@Override
	public int hashCode() {
		int result = team.hashCode();
		result = 31 * result + Arrays.hashCode(key);
		return result;
	}
}
