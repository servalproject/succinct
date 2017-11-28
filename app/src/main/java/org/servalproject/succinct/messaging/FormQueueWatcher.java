package org.servalproject.succinct.messaging;

import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.forms.Form;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;

import java.io.IOException;

/**
 * Created by jeremy on 28/11/17.
 */
class FormQueueWatcher extends QueueWatcher<Form> {

	public FormQueueWatcher(MessageQueue messageQueue, App app) {
		super(messageQueue, app, Form.factory);
	}

	@Override
	boolean generateMessage(PeerId peer, RecordIterator<Form> records) throws IOException {
		int pos = store.getMembers().getPosition(peer);
		Form form = records.read();
		Serialiser serialiser = new Serialiser();
		serialiser.putByte((byte) pos);
		serialiser.putTime(form.time, store.getTeam().epoc);
		serialiser.putFixedBytes(form.record);
		int delay = app.getPrefs().getInt(App.FORM_DELAY, 60000);
		messageQueue.fragmentMessage(form.time + delay, MessageQueue.FORM, serialiser.getResult());
		return true;
	}
}
