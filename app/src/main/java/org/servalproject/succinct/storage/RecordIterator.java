package org.servalproject.succinct.storage;

import java.io.IOException;

public class RecordIterator<T> {
	private final RecordStore<T> store;
	private long offset;
	private int recordLength;

	RecordIterator(RecordStore<T> store) {
		this.store = store;
		start();
	}

	public void setMark(String name){
		store.marks.put(name, offset);
	}

	public boolean restoreMark(String name){
		if (!store.marks.containsKey(name))
			return false;
		offset = store.marks.get(name);
		return true;
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
		return store.factory.create(bytes);
	}
}
