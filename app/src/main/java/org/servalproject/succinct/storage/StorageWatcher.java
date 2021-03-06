package org.servalproject.succinct.storage;

import android.os.Handler;
import android.util.Log;

import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.utils.AndroidObserver;

import java.io.IOException;
import java.util.Observable;

public abstract class StorageWatcher<T> extends AndroidObserver{
	protected final TeamStorage store;
	private final Factory<T> factory;
	private boolean active = false;
	private static final String TAG = "StorageWatcher";

	public StorageWatcher(Handler handler, TeamStorage store, Factory<T> factory){
		super(handler);
		this.store = store;
		this.factory = factory;
	}
	public StorageWatcher(TeamStorage store, Factory<T> factory){
		super();
		this.store = store;
		this.factory = factory;
	}

	public void activate(){
		if (active)
			return;
		active = true;
		store.observable.addObserver(this);
		for(PeerId peer : store.getDevices()){
			try {
				if (store.exists(factory, peer))
					Visit(peer, store.openIterator(factory, peer));
			} catch (IOException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}

	public boolean isActive(){
		return active;
	}

	public void deactivate(){
		if (!active)
			return;
		active = false;
		store.observable.deleteObserver(this);
	}

	@Override
	public void observe(Observable observable, Object o) {
		RecordStore file = (RecordStore)o;
		// TODO, this only handles per-person files...
		if (!file.filename.getName().equals(factory.getFileName()))
			return;
		try {
			PeerId peer = new PeerId(file.filename.getParentFile().getName());
			RecordIterator<T> records = new RecordIterator<>(file, factory);
			Updated(peer, records);
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	// Called for each existing file, and by default for each updated file too
	protected abstract void Visit(PeerId peer, RecordIterator<T> records) throws IOException;

	// Override if you only care about changes
	protected void Updated(PeerId peer, RecordIterator<T> records) throws IOException{
		Visit(peer, records);
	}
}
