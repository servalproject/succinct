package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;

import java.nio.ByteBuffer;

public class SyncMsg extends BlobMessage{
	public SyncMsg(ByteBuffer buff) {
		super(Type.SyncMsgMessage, buff);
	}

	public SyncMsg(byte[] message){
		super(Type.SyncMsgMessage, message);
	}

	@Override
	public void process(Peer peer) {
		peer.processSyncMessage(blob);
	}
}
