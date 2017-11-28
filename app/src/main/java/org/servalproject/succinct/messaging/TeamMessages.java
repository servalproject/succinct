package org.servalproject.succinct.messaging;

import org.servalproject.succinct.App;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.team.Team;

import java.io.IOException;

/**
 * Created by jeremy on 28/11/17.
 */
class TeamMessages implements IMessageSource {
	private final MessageQueue messageQueue;
	private final App app;
	private final RecordIterator<Team> team;

	public TeamMessages(MessageQueue messageQueue, App app, TeamStorage store) throws IOException {
		this.messageQueue = messageQueue;
		this.app = app;
		team = store.openIterator(Team.factory, store.teamId);
		team.reset("sent");
	}

	@Override
	public boolean hasMessage() throws IOException {
		team.reset("sent");
		return team.next();
	}

	@Override
	public boolean nextMessage() throws IOException {
		boolean ret = false;
		team.reset("sent");
		while (team.next()) {
			Serialiser serialiser = new Serialiser();
			Team record = team.read();
			if (record.id == null) {
				serialiser.putRawLong(record.epoc);
				messageQueue.fragmentMessage(record.epoc, MessageQueue.DESTROY_TEAM, serialiser.getResult());
			} else {
				serialiser.putRawLong(record.epoc);
				serialiser.putString(record.name);
				int delay = app.getPrefs().getInt(App.MESSAGE_DELAY, 60000);
				messageQueue.fragmentMessage(record.epoc + delay, MessageQueue.CREATE_TEAM, serialiser.getResult());
			}
			Team.factory.serialise(serialiser, record);
			ret = true;
		}
		team.mark("sent");
		return ret;
	}

	@Override
	public void activate() {

	}

	@Override
	public void deactivate() {

	}
}
