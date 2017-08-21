package org.servalproject.succinct.storage;

import org.servalproject.succinct.networking.Peer;

public class PeerTransfer {
	public final Peer peer;
	public final RecordStore file;
	public final String filename;
	public final long newLength;
	public final byte[] expectedHash;

	public PeerTransfer(Peer peer, RecordStore file, String filename, long newLength, byte[] expectedHash) {
		this.peer = peer;
		this.file = file;
		this.filename = filename;
		this.newLength = newLength;
		this.expectedHash = expectedHash;
	}
}
