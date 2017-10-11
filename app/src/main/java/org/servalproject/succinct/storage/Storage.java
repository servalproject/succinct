package org.servalproject.succinct.storage;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.networking.messages.StoreState;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;
import org.servalproject.succinct.utils.ChangedObservable;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Observable;

public class Storage {
	protected final App appContext;
	public final PeerId teamId;
	private StoreState state;
	public final File root;
	public long ptr;
	public final Observable observable = new ChangedObservable();

	private native long open(String path);
	private native void close(long ptr);

	private static final String TAG = "Storage";

	public Storage(App appContext, PeerId teamId){
		this.appContext = appContext;
		this.teamId = teamId;
		this.root = appContext.getExternalFilesDir(teamId.toString());
		ptr = open(root.getAbsolutePath());
		if (ptr==0)
			throw new IllegalStateException("storage open failed");
	}

	public StoreState getState(){
		return state;
	}

	private void jniCallback(byte[] rootHash){
		state = new StoreState(teamId, rootHash);
		if (appContext.networks!=null)
			appContext.networks.setAlarm(10);
	}

	private Map<String, RecordStore> files = new HashMap<>();
	public RecordStore openFile(String relativePath) throws IOException{
		RecordStore file = files.get(relativePath);
		if (file == null){
			file = new RecordStore(this, relativePath);
			files.put(relativePath, file);
		}
		return file;
	}

	public <T> RecordIterator<T> openIterator(Factory<T> factory, String folder) throws IOException {
		RecordStore file = openFile(folder+"/"+factory.getFileName());
		return new RecordIterator<>(file, factory);
	}

	public <T> T getLastRecord(Factory<T> factory, String folder) throws IOException {
		RecordIterator<T> iterator = openIterator(factory, folder);
		return iterator.readLast();
	}

	public void close() throws IOException {
		// TODO throw if being observed?
		observable.deleteObservers();
		for(RecordStore file : files.values()){
			file.close();
		}
		files.clear();
		close(ptr);
		ptr = 0;
	}

	void fileFlushed(RecordStore file) {
		observable.notifyObservers(file);
	}
}
