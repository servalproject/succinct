package org.servalproject.succinct.messaging;

import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.StorageWatcher;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


abstract class QueueWatcher<T> extends StorageWatcher<T> {
	private final App app;
	private final MessageQueue messageQueue;
	private final HashMap<PeerId, RecordIterator<T>> queue = new HashMap<>();
	private static final String TAG = "QueueWatcher";

	public QueueWatcher(MessageQueue messageQueue, App app, Factory<T> factory) {
		super(App.backgroundHandler, app.teamStorage, factory);
		this.messageQueue = messageQueue;
		this.app = app;
	}

	@Override
	public void deactivate() {
		super.deactivate();
		queue.clear();
	}

	boolean findNext(PeerId peer, RecordIterator<T> records) throws IOException {
		return records.next();
	}

	boolean generateMessage(PeerId peer, RecordIterator<T> records) throws IOException {
		return true;
	}

	@Override
	protected void Visit(PeerId peer, RecordIterator<T> records) throws IOException {
		records.reset("sent");
		if (findNext(peer, records)) {
			if (!queue.containsKey(peer)) {
				Log.v(TAG, "Remembering " + peer + "/" + records.getFactory().getFileName());
				queue.put(peer, records);
			}
			messageQueue.onStateChanged();
		}
	}

	boolean hasMessage() {
		return !queue.isEmpty();
	}

	boolean nextMessage() throws IOException {
		Iterator<Map.Entry<PeerId, RecordIterator<T>>> i = queue.entrySet().iterator();
		while (i.hasNext()) {
			Map.Entry<PeerId, RecordIterator<T>> e = i.next();
			PeerId peerId = e.getKey();

			// always skip peers if they aren't enrolled
			if (!store.getMembers().isActive(peerId)) {
				Log.v(TAG, "Skipping " + peerId + ", not enrolled?");
				continue;
			}

			if (store.getMembers().getPosition(peerId) > 255) {
				Log.v(TAG, "Skipping " + peerId + ", too many?");
				continue;
			}

			RecordIterator<T> iterator = e.getValue();
			iterator.reset("sent");

			if (!findNext(peerId, iterator)) {
				Log.v(TAG, "Skipping " + peerId + ", no interesting records?");
				i.remove();
				continue;
			}

			boolean done = generateMessage(peerId, iterator);

			if (!findNext(peerId, iterator))
				i.remove();
			iterator.mark("sent");
			if (done)
				return true;
		}
		return false;
	}
}
