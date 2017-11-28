package org.servalproject.succinct.messaging;

import org.servalproject.succinct.App;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;

import java.io.IOException;

/**
 * Created by jeremy on 28/11/17.
 */
class ChatQueueWatcher extends QueueWatcher<StoredChatMessage> {

	public ChatQueueWatcher(MessageQueue messageQueue, App app) {
		super(messageQueue, app, StoredChatMessage.factory);
	}

	@Override
	boolean findNext(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
		// don't echo EOC messages back at them....
		return !peer.equals(PeerId.EOC) && super.findNext(peer, records);
	}

	@Override
	boolean generateMessage(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
		StoredChatMessage msg = records.read();

		Serialiser serialiser = new Serialiser();
		serialiser.putByte((byte) (int) store.getMembers().getPosition(peer));
		serialiser.putTime(msg.time.getTime(), store.getTeam().epoc);
		serialiser.putString(msg.message);

		int delay = app.getPrefs().getInt(App.MESSAGE_DELAY, 60000);
		messageQueue.fragmentMessage(msg.time.getTime() + delay, MessageQueue.MESSAGE, serialiser.getResult());
		return true;
	}
}
