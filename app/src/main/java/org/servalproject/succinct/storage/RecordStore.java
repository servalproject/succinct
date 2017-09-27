package org.servalproject.succinct.storage;

import android.util.Log;

import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.utils.ChangedObservable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
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
	private final File markFile;
	final HashMap<String, Long> marks = new HashMap<>();

	private native long open(long storePtr, String relativePath);
	private native void append(long filePtr, byte[] bytes, int offset, int length);
	private native int flush(long storePtr, long filePtr, byte[] expectedHash);
	private native void close(long ptr);

	RecordStore(Storage storage, String relativePath) throws IOException {
		this.store = storage;
		this.filename = new File(storage.root, relativePath);
		markFile = new File(storage.root, relativePath+".marks");
		filename.getParentFile().mkdirs();
		this.file = new RandomAccessFile(filename, "rw");
		ptr = open(storage.ptr, relativePath);
		if (ptr==0)
			throw new IllegalStateException("file open failed");
		readMarks();
	}

	private void readMarks() throws IOException{
		if (!markFile.exists())
			return;
		if (EOF == 0) {
			markFile.delete();
			return;
		}
		BufferedReader r = new BufferedReader(new FileReader(markFile));
		try {
			String line;
			while((line = r.readLine())!=null){
				int i = line.indexOf("=");
				if (i<0)
					continue;
				String name = line.substring(0,i);
				String value = line.substring(i+1);
				Log.v(TAG, "'"+name+"' = '"+value+"'");
				marks.put(name, Long.parseLong(value));
			}
		}finally {
			r.close();
		}
	}

	private void flushMarks() throws IOException {
		if (marks.isEmpty()){
			Log.v(TAG, "Delete marks");
			markFile.delete();
			return;
		}

		FileWriter w = new FileWriter(markFile);
		try {
			for (Map.Entry<String, Long> e : marks.entrySet()) {
				//Log.v(TAG, "writing marks; "+e.getKey() + "=" + e.getValue() );
				w.write(e.getKey() + "=" + e.getValue() + "\n");
			}
		} finally {
			w.close();
		}
	}

	void setMark(String name, long offset) throws IOException {
		Long v = marks.get(name);
		// Noop
		if ((v==null && offset==0) || (v!=null && v == offset))
			return;
		if (offset==0){
			marks.remove(name);
		} else {
			marks.put(name, offset);
		}
		flushMarks();
	}

	long getMark(String name){
		Long v = marks.get(name);
		return (v==null) ? 0 : v;
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
		boolean notify = (EOF != -1 && length!=EOF);
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
		if (offset+bytes.length>EOF)
			throw new IllegalStateException();
		file.seek(offset);
		file.readFully(bytes);
	}

	public synchronized int readBytes(long offset, ByteBuffer buffer) throws IOException {
		if (offset+buffer.remaining()>EOF)
			throw new IllegalStateException();
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
