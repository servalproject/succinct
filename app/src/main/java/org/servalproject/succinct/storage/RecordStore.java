package org.servalproject.succinct.storage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;

public class RecordStore {
	private final Storage store;
	private final RandomAccessFile file;
	long EOF=0;
	private long appendOffset;
	private long ptr;

	private native long open(long storePtr, String relativePath);
	private native void append(long filePtr, byte[] bytes, int offset, int length);
	private native int flush(long storePtr, long filePtr);
	private native void close(long ptr);

	RecordStore(Storage storage, String relativePath) throws IOException {
		this.store = storage;
		File f = new File(storage.root, relativePath);
		f.getParentFile().mkdirs();
		this.file = new RandomAccessFile(f, "rw");
		ptr = open(storage.ptr, relativePath);
		if (ptr==0)
			throw new IllegalStateException("file open failed");
	}

	// called from JNI
	private void jniCallback(long length){
		appendOffset = EOF = length;
		// TODO observer callbacks after syncing changes?
	}

	synchronized void readBytes(long offset, byte[] bytes) throws IOException {
		file.seek(offset);
		file.readFully(bytes);
	}

	int readLength(long offset) throws IOException {
		byte[] lenBytes = new byte[4];
		readBytes(offset, lenBytes);
		return (lenBytes[0]&0xFF)>>24
				|(lenBytes[1]&0xFF)>>16
				|(lenBytes[2]&0xFF)>>8
				|(lenBytes[3]&0xFF);
	}

	public synchronized void append(byte[] bytes, int offset, int length) throws IOException{
		file.seek(appendOffset);
		file.write(bytes, offset, length);
		appendOffset+=length;
		append(ptr, bytes, offset, length);
	}

	public synchronized void flush(){
		if (flush(store.ptr, ptr)<0)
			throw new IllegalStateException("Unknown error");
	}

	public void appendRecord(byte[] record) throws IOException {
		int len = record.length+8;
		byte[] completeRecord = new byte[len];
		completeRecord[0]= completeRecord[len -4] = (byte) (len<<24);
		completeRecord[1]= completeRecord[len -3] = (byte) (len<<16);
		completeRecord[2]= completeRecord[len -2] = (byte) (len<<8);
		completeRecord[3]= completeRecord[len -1] = (byte) (len);
		System.arraycopy(record, 0, completeRecord, 4, record.length);
		synchronized (this) {
			append(completeRecord, 0, len);
			flush();
		}
	}

	public void close() throws IOException {
		file.close();
		close(ptr);
		ptr = 0;
	}
}
