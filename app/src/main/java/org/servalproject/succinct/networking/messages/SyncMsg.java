package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.nio.ByteBuffer;

public class SyncMsg extends BlobMessage<SyncMsg>{

	public static final Factory<SyncMsg> factory = new Factory<SyncMsg>() {
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public SyncMsg create(DeSerialiser serialiser) {
			return new SyncMsg(serialiser.getFixedBytes(DeSerialiser.REMAINING));
		}

		@Override
		public void serialise(Serialiser serialiser, SyncMsg object) {
			serialiser.putFixedBytes(object.blob);
		}
	};

	public SyncMsg(byte[] message){
		super(Type.SyncMsgMessage, message);
	}

	@Override
	protected Factory<SyncMsg> getFactory() {
		return factory;
	}

	@Override
	public void process(Peer peer) {
		peer.processSyncMessage(blob);
	}
}
