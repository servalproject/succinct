package org.servalproject.succinct.storage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;

public class RecordStore<T> {

	final Factory<T> factory;
	final RandomAccessFile file;
	final HashMap<String, Long> marks = new HashMap<>();
	long EOF=0;

	public interface Factory<T>{
		T create(byte[] bytes);
		byte[] serialise(T object);
	}

	public RecordStore(File file, Factory<T> factory) throws FileNotFoundException {
		this.factory = factory;
		this.file = new RandomAccessFile(file, "rw");
		// TODO serialise marks and store EOF in there...
		EOF = file.length();
	}

	public RecordIterator<T> iterator(){
		return new RecordIterator<>(this);
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

	public void append(T object) throws IOException {
		byte[] objectBytes = factory.serialise(object);
		byte[] lengthBytes = new byte[4];
		int len = objectBytes.length+8;
		lengthBytes[0]= (byte) (len<<24);
		lengthBytes[1]= (byte) (len<<16);
		lengthBytes[2]= (byte) (len<<8);
		lengthBytes[3]= (byte) (len);
		synchronized (this){
			file.seek(EOF);
			file.write(lengthBytes);
			file.write(objectBytes);
			file.write(lengthBytes);
			EOF += len;
		}
	}

	public void close() throws IOException {
		file.close();
	}
}
