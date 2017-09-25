package org.servalproject.succinct.storage;

import android.util.Log;

import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.utils.ChangedObservable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Observable;

public class RecordStore {
	public final File filename;
	private final Storage store;
	private final RandomAccessFile file;
	public long EOF=-1;
	private long appendOffset=0;
	private long ptr;
	public PeerTransfer activeTransfer;
	public final Observable observable = new ChangedObservable();
	private static final String TAG = "RecordStore";

	private native long open(long storePtr, String relativePath);
	private native void append(long filePtr, byte[] bytes, int offset, int length);
	private native int flush(long storePtr, long filePtr, byte[] expectedHash);
	private native void close(long ptr);

	RecordStore(Storage storage, String relativePath) throws IOException {
		this.store = storage;
		this.filename = new File(storage.root, relativePath);
		filename.getParentFile().mkdirs();
		this.file = new RandomAccessFile(filename, "rw");
		ptr = open(storage.ptr, relativePath);
		if (ptr==0)
			throw new IllegalStateException("file open failed");
	}

	public synchronized boolean setTranfer(PeerTransfer transfer){
		if (activeTransfer != null)
			return false;
		activeTransfer = transfer;
		return true;
	}

	public synchronized void cancel(PeerTransfer transfer){
		if (activeTransfer == transfer)
			activeTransfer = null;
	}

	// called from JNI on open or flush success / failure
	private void jniCallback(long length){
		boolean notify = EOF == -1;
		if (appendOffset > length) {
			try {
				file.setLength(length);
			} catch (IOException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
		appendOffset = EOF = length;
		if (notify) {
			observable.notifyObservers();
			store.fileFlushed(this);
		}
	}

	public synchronized void readBytes(long offset, byte[] bytes) throws IOException {
		file.seek(offset);
		file.readFully(bytes);
	}

	public synchronized int readBytes(long offset, ByteBuffer buffer) throws IOException {
		file.seek(offset);
		return file.getChannel().read(buffer);
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
		if (length <= 0)
			return;
		file.seek(appendOffset);
		file.write(bytes, offset, length);
		appendOffset+=length;
		append(ptr, bytes, offset, length);
	}

	public synchronized void appendAt(long fileOffset, byte[] bytes) throws IOException{
		if (fileOffset > appendOffset)
			throw new ProtocolException("Cannot append beyond the current end of file");
		int offset = (int) (appendOffset - fileOffset);
		append(bytes, offset, bytes.length - offset);

		if (activeTransfer!=null && activeTransfer.newLength == appendOffset) {
			flush(activeTransfer.expectedHash);
			activeTransfer = null;
		}
	}

	public synchronized void flush(byte[] expectedHash) throws ProtocolException {
		if (flush(store.ptr, ptr, expectedHash)<0)
			throw new ProtocolException("Unknown error flushing file "+filename+ " "+ Hex.toString(expectedHash));
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
			flush(null);
		}
	}

	public void close() throws IOException {
		file.close();
		close(ptr);
		ptr = 0;
	}
}
