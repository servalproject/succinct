package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.servalproject.succinct.networking.messages.Message.Type.StoreStateMessage;

public class StoreState extends Message<StoreState>{
	private static final int KEY_LEN=32;
	public final PeerId teamId;
	public final byte[] key;

	public StoreState(PeerId teamId, byte[] key) {
		super(StoreStateMessage);
		this.teamId = teamId;
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
			PeerId team = new PeerId(serialiser);
			byte[] key = serialiser.getFixedBytes(KEY_LEN);
			return new StoreState(team, key);
		}

		@Override
		public void serialise(Serialiser serialiser, StoreState object) {
			object.teamId.serialise(serialiser);
			serialiser.putFixedBytes(object.key);
		}
	};

	@Override
	public void process(Peer peer) {
		peer.setStoreState(this);
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

		if (!teamId.equals(that.teamId)) return false;
		return Arrays.equals(key, that.key);
	}

	@Override
	public int hashCode() {
		int result = teamId.hashCode();
		result = 31 * result + Arrays.hashCode(key);
		return result;
	}

	@Override
	public String toString() {
		return "StoreState; "+teamId.toString()+","+Hex.toString(key);
	}
}
