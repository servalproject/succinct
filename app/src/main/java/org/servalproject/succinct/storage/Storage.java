package org.servalproject.succinct.storage;

import java.io.File;
import java.io.IOException;

public class Storage {
	final File root;
	long ptr;

	private native long open(String path);
	private native void close(long ptr);

	private static final String TAG = "Storage";

	public Storage(File root){
		this.root = root;
		ptr = open(new File(root, "db").getAbsolutePath());
		if (ptr==0)
			throw new IllegalStateException("storage open failed");
	}

	static {
		System.loadLibrary("native-lib");
	}

	public RecordStore openFile(String relativePath) throws IOException {
		// TODO cache objects?
		return new RecordStore(this, relativePath);
	}

	public void close(){
		close(ptr);
		ptr = 0;
	}
}
