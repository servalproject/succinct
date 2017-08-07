package org.servalproject.succinct.networking.messages;

import org.servalproject.succinct.networking.PeerId;

import java.nio.ByteBuffer;

public class Header extends Message{
	public final PeerId id;
	public final boolean unicast;

	Header(ByteBuffer buffer){
		super(Type.DGramHeader);
		id = new PeerId(buffer);
		unicast = buffer.get()>0;
	}

	public Header(PeerId id, boolean unicast){
		super(Type.DGramHeader);
		this.id = id;
		this.unicast = unicast;
	}

	@Override
	protected void serialise(ByteBuffer buff) {
		id.write(buff);
		buff.put((byte)(unicast?1:0));
	}
}
