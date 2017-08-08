package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.servalproject.succinct.networking.messages.Message.Type.StoreStateMessage;

public class StoreState extends Message{
	private static final int KEY_LEN=8;
	final String team;
	final byte[] key;

	public StoreState(String team, byte[] key) {
		super(StoreStateMessage);
		this.team = team;
		this.key = key;
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
	protected void serialise(ByteBuffer buff) {
		buff.put(key);
		buff.put(team.getBytes());
	}

	@Override
	public void process(Peer peer) {
		peer.setStoreState(this);
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
