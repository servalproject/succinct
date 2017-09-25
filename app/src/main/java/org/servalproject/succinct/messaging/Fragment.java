package org.servalproject.succinct.messaging;

import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

public class Fragment  {
	public final PeerId teamId;
	public final int seq;
	public final boolean begin;
	public final boolean end;
	public final byte[] bytes;

	public static final int MTU = (200 - 4 - PeerId.LEN);

	public Fragment(PeerId teamId, int seq, byte[] message, int offset, int length){
		if (length > MTU)
			throw new IllegalStateException();

		this.teamId = teamId;
		this.seq = seq;
		this.begin = offset == 0;
		this.end = offset + length == message.length;

		bytes = new byte[length + 4 + PeerId.LEN];
		teamId.toBuffer(bytes, 0);
		int off=PeerId.LEN;
		bytes[off++] = (byte) (seq & 0xFF);
		bytes[off++] = (byte) ((seq >> 8) & 0xFF);
		bytes[off++] = (byte) ((seq >> 16) & 0xFF);
		bytes[off++] = (byte) ( (begin?0x80:0) | (end?0x40:0) | ((seq >> 24) & 0x3F));
		System.arraycopy(message, offset, bytes, off, length);
	}

	public Fragment(byte[] bytes){
		this.bytes = bytes;
		this.teamId = PeerId.fromBuffer(bytes, 0);
		int off=PeerId.LEN;
		this.seq =
				(bytes[off++] & 0xFF) |
				((bytes[off++]<<8) & 0xFF) |
				((bytes[off++]<<16) & 0xFF) |
				((bytes[off]<<24) & 0x3F);
		this.begin = (bytes[off] & 0x80) !=0;
		this.end = (bytes[off] & 0x40) !=0;
	}

	public static final Factory<Fragment> factory = new Factory<Fragment>() {
		@Override
		public String getFileName() {
			return "fragments";
		}

		@Override
		public Fragment create(DeSerialiser serialiser) {
			return new Fragment(serialiser.getFixedBytes(DeSerialiser.REMAINING));
		}

		@Override
		public void serialise(Serialiser serialiser, Fragment object) {
			serialiser.putFixedBytes(object.bytes);
		}
	};

}
