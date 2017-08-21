package org.servalproject.succinct.networking.messages;


import android.util.Log;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.storage.RecordStore;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

public class FileBlock extends Message{
	public RecordStore file;
	public final String filename;
	public long offset;
	public long length;
	public final byte[] data;
	private static final String TAG = "FileBlock";

	public FileBlock(String filename, long offset, long length, RecordStore file) {
		super(Type.FileBlockMessage);
		this.filename = filename;
		this.offset = offset;
		this.length = length;
		this.data = null;
		this.file = file;
	}

	FileBlock(ByteBuffer buffer) {
		super(Type.FileBlockMessage);
		byte[] nameBytes = new byte[buffer.get()&0xFF];
		buffer.get(nameBytes);
		filename = new String(nameBytes);
		offset = Message.getPackedLong(buffer);
		length = buffer.remaining();
		data = new byte[buffer.remaining()];
		buffer.get(data);
	}

	// abuse message write semantics, to both write some data and keep this message in the queue
	// until all the requested data has been written
	@Override
	public boolean write(ByteBuffer buff) {
		if (buff.remaining()<3)
			return false;

		try {
			buff.mark();
			buff.put((byte) type.ordinal());
			int lenOffset = buff.position();
			buff.putShort((short) 0);
			byte[] nameBytes = filename.getBytes();
			buff.put((byte) nameBytes.length);
			buff.put(filename.getBytes());
			Message.putPackedLong(buff, offset);

			if (length < buff.remaining())
				buff.limit((int) (buff.position()+length));

			int read = file.readBytes(offset, buff);

			int len = buff.position() - lenOffset - 2;
			buff.putShort(lenOffset, (short) len);

			length -= read;
			offset += read;

			return length == 0;
		}catch (BufferOverflowException e){
			buff.reset();
			return false;
		} catch (IOException e) {
			Log.v(TAG, e.getMessage(), e);
			buff.reset();
			return false;
		}
	}

	@Override
	protected boolean serialise(ByteBuffer buff) {
		return false;
	}

	@Override
	public void process(Peer peer) {
		peer.processData(this);
	}
}
