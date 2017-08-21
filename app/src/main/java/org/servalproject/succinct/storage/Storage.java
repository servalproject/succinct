package org.servalproject.succinct.storage;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.messages.StoreState;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public class Storage {
	private final App appContext;
	public final String team;
	private StoreState state;
	final File root;
	public long ptr;

	private native long open(String path);
	private native void close(long ptr);

	private static final String TAG = "Storage";

	public Storage(App appContext, String team){
		this.appContext = appContext;
		this.team = team;
		this.root = appContext.getExternalFilesDir(team);
		ptr = open(root.getAbsolutePath());
		if (ptr==0)
			throw new IllegalStateException("storage open failed");
	}

	public StoreState getState(){
		return state;
	}

	private void jniCallback(byte[] rootHash){
		state = new StoreState(team, rootHash);
		if (appContext.networks!=null)
			appContext.networks.setAlarm(10);
	}

	private Map<String, WeakReference<RecordStore>> files = new HashMap<>();
	public RecordStore openFile(String relativePath) throws IOException {
		RecordStore file = null;
		WeakReference<RecordStore> ref = files.get(relativePath);
		if (ref != null)
			file = ref.get();
		if (file == null){
			file = new RecordStore(this, relativePath);
			files.put(relativePath, new WeakReference<>(file));
		}
		return file;
	}

	public void close(){
		close(ptr);
		ptr = 0;
	}
}
