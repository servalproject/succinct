package org.servalproject.succinct.networking.messages;


import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordStore;
import org.servalproject.succinct.storage.Serialiser;

import java.io.IOException;
import java.nio.ByteBuffer;

public class FileBlock extends Message<FileBlock>{
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

	public FileBlock(String filename, long offset, long length, byte[] data) {
		super(Type.FileBlockMessage);
		this.filename = filename;
		this.offset = offset;
		this.length = length;
		this.data = data;
		this.file = null;
	}

	public static final Factory<FileBlock> factory = new Factory<FileBlock>() {
		@Override
		public String getFileName() {
			return null;
		}

		@Override
		public FileBlock create(DeSerialiser serialiser) {
			String filename = serialiser.getString();
			long offset = serialiser.getLong();
			byte[] data = serialiser.getFixedBytes(DeSerialiser.REMAINING);
			return new FileBlock(filename, offset, data.length, data);
		}

		@Override
		public void serialise(Serialiser serialiser, FileBlock object) {
			serialiser.putString(object.filename);
			serialiser.putLong(object.offset);
			int len = serialiser.remaining();
			if (len > object.length)
				len = (int) object.length;

			ByteBuffer buff = serialiser.slice(len);

			try {
				int read = object.file.readBytes(object.offset, buff);
				serialiser.skip(read);
				object.length -= read;
				object.offset += read;
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
	};

	@Override
	protected Factory<FileBlock> getFactory() {
		return factory;
	}

	@Override
	protected boolean isComplete() {
		return length==0;
	}

	@Override
	public void process(Peer peer) {
		peer.processData(this);
	}
}
