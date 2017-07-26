package org.servalproject.succinct.storage;

import java.io.IOException;

public class RecordIterator<T> {
	private final Factory<T> factory;
	private final RecordStore store;
	private long offset;
	private int recordLength;

	public RecordIterator(RecordStore store, Factory<T> factory) {
		this.store = store;
		this.factory = factory;
		start();
	}

	public void start() {
		offset = 0;
		recordLength = 0;
	}

	public void end() {
		offset = store.EOF;
		recordLength = 0;
	}

	public boolean next() throws IOException {
		if (offset+recordLength>=store.EOF)
			return false;
		offset+=recordLength;
		recordLength = store.readLength(offset);
		return offset+recordLength<=store.EOF;
	}

	public boolean prev() throws IOException {
		if (offset<=0)
			return false;
		recordLength = store.readLength(offset -4);
		offset-=recordLength;
		return offset>=0;
	}

	public T read() throws IOException {
		if (recordLength==0)
			return null;
		byte[] bytes = new byte[recordLength - 8];
		store.readBytes(offset+4, bytes);
		return factory.create(bytes);
	}

	public void append(T object) throws IOException {
		byte[] bytes = factory.serialise(object);
		store.appendRecord(bytes);
	}
}
