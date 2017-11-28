package org.servalproject.succinct.messaging;

import android.location.Location;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.location.LocationFactory;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;

import java.io.IOException;

/**
 * Created by jeremy on 28/11/17.
 */
class LocationQueueWatcher extends QueueWatcher<Location> {
	private Serialiser serialiser;
	private long nextLocationMessage=-1;

	public LocationQueueWatcher(MessageQueue messageQueue, App app) {
		super(messageQueue, app, LocationFactory.factory);
	}

	@Override
	boolean findNext(PeerId peer, RecordIterator<Location> records) throws IOException {
		if (records.getOffset() == records.store.EOF)
			return false;
		records.end();
		if (!records.prev())
			return false;
		long delay = app.getPrefs().getLong(App.LOCATION_INTERVAL, App.DefaultLocationInterval);
		if (System.currentTimeMillis() - nextLocationMessage > delay) {
			nextLocationMessage = System.currentTimeMillis() + 1000;
		}
		return true;
	}

	long adjustAlarm(long alarmTime) {
		if (super.hasMessage() && alarmTime > nextLocationMessage)
			// fire this alarm earlier if we have a scheduled location message
			return nextLocationMessage;
		return alarmTime;
	}

	@Override
	public boolean hasMessage() {
		return super.hasMessage() && System.currentTimeMillis() >= nextLocationMessage;
	}

	@Override
	boolean generateMessage(PeerId peer, RecordIterator<Location> records) throws IOException {
		Location l = records.read();
		serialiser.putByte((byte) (int) store.getMembers().getPosition(peer));
		serialiser.putTime(l.getTime(), store.getTeam().epoc);
		serialiser.putFixedBytes(LocationFactory.packLatLngAcc(l));
		return false;
	}

	@Override
	public boolean nextMessage() throws IOException {
		if (!hasMessage())
			return false;
		serialiser = new Serialiser();
		super.nextMessage();
		long now = System.currentTimeMillis();
		byte[] message = serialiser.getResult();
		if (message.length > 0) {
			messageQueue.fragmentMessage(now, MessageQueue.LOCATION, message);
			long delay = app.getPrefs().getLong(App.LOCATION_INTERVAL, App.DefaultLocationInterval);
			nextLocationMessage = now + delay;
		}
		serialiser = null;
		return message.length > 0;
	}
}
