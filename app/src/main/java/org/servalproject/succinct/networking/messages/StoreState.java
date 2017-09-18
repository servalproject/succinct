package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.servalproject.succinct.networking.messages.Message.Type.StoreStateMessage;

public class StoreState extends Message<StoreState>{
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

	public static final Factory<StoreState> factory = new Factory<StoreState>() {
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public StoreState create(DeSerialiser serialiser) {
			byte[] key = serialiser.getFixedBytes(KEY_LEN);
			String team = new String(serialiser.getFixedBytes(DeSerialiser.REMAINING));
			return new StoreState(team, key);
		}

		@Override
		public void serialise(Serialiser serialiser, StoreState object) {
			serialiser.putFixedBytes(object.key);
			serialiser.putFixedBytes(object.team.getBytes());
		}
	};

	@Override
	public void process(Peer peer) {
		peer.setStoreState(this);
	}

	@Override
	public String toString() {
		return getClass().getName()+" "+team+" " + Hex.toString(key,0,20);
	}

	@Override
	protected Factory<StoreState> getFactory() {
		return factory;
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
