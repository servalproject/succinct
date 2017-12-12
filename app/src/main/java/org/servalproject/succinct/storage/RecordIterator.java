package org.servalproject.succinct.storage;

import java.io.IOException;

public class RecordIterator<T> {
	private final Factory<T> factory;
	public final RecordStore store;
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

	public long getOffset(){
		return offset;
	}

	public long mark(String markName) throws IOException {
		store.setMark(markName, offset);
		return offset;
	}

	public long reset(String markName) throws IOException {
		// slightly quirky if you mark between records
		long offset = store.getMark(markName);
		if (offset<0 || offset >store.EOF)
			throw new IllegalStateException();
		this.offset = offset;
		this.recordLength = 0;
		return offset;
	}

	public boolean next() throws IOException {
		offset+=recordLength;
		if (offset>=store.EOF) {
			recordLength = 0;
			return false;
		}
		recordLength = store.readLength(offset);
		return offset+recordLength<=store.EOF;
	}

	public boolean prev() throws IOException {
		if (offset<=0) {
			recordLength = 0;
			return false;
		}
		recordLength = store.readLength(offset -4);
		offset-=recordLength;
		return offset>=0;
	}

	private long currentOffset = 0;
	private T current = null;
	public T read() throws IOException {
		if (recordLength==0)
			return null;
		if (currentOffset == offset && current!=null)
			return current;
		byte[] bytes = new byte[recordLength - 8];
		store.readBytes(offset+4, bytes);
		current = factory.create(bytes);
		currentOffset = offset;
		return current;
	}

	public T readLast() throws IOException {
		if (recordLength==0 || offset + recordLength != store.EOF){
			end();
			if (!prev())
				return null;
		}
		return read();
	}

	public void append(T object) throws IOException {
		byte[] bytes = factory.serialise(object);
		store.appendRecord(bytes);
	}

	public Factory<T> getFactory() {
		return factory;
	}
}
