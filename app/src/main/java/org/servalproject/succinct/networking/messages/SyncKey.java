package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.Peer;

import java.nio.ByteBuffer;

public class SyncKey extends Message{
	public SyncKey(ByteBuffer buff) {
		super(Type.SyncKeyMessage);
	}

	@Override
	protected void serialise(ByteBuffer buff) {

	}

	@Override
	public void process(Peer peer) {

	}
}
