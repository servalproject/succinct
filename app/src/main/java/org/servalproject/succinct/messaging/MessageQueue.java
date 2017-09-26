package org.servalproject.succinct.messaging;


import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.storage.StorageWatcher;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;

import java.io.IOException;

// Manage the queue of outgoing messages / fragments
public class MessageQueue {
	private final Storage store;
	public final RecordIterator<Fragment> fragments;
	private static final String TAG = "MessageQueue";

	private final StorageWatcher<TeamMember> teamMembers;

	public MessageQueue(App app) throws IOException {
		store = app.teamStorage;

		teamMembers = new StorageWatcher<TeamMember>(App.backgroundHandler, store, TeamMember.factory) {
			@Override
			protected void Visit(PeerId peer, RecordIterator<TeamMember> records) throws IOException {
				records.reset("enrolled");
				if (records.getOffset()==0 && records.next()) {
					Log.v(TAG, "Enrolling "+peer+" in the team list");
					MembershipList.getInstance(store).enroll(peer);
					records.next();
					records.mark("enrolled");
				}
			}
		};
		teamMembers.activate();

		this.fragments = store.openIterator(Fragment.factory, "messaging");
	}

	private static MessageQueue instance=null;
	// start fragmenting and queuing messages, if this is the app instance with that role
	public static void init(App app){
		try {
			if (instance!=null)
				throw new IllegalStateException();
			Team myTeam = app.teamStorage.getLastRecord(Team.factory, app.teamStorage.teamId);
			if (myTeam!=null && myTeam.leader.equals(app.networks.myId))
				instance = new MessageQueue(app);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public void fragmentMessage(byte[] messageBytes) throws IOException {
		int offset=0;
		while(offset<messageBytes.length){
			int len = messageBytes.length - offset;
			if (len > Fragment.MTU)
				len = Fragment.MTU;

			Fragment f = new Fragment(store.teamId, queueState.nextFragmentSeq++, messageBytes, offset, len);
			fragments.append(f);
			offset+=len;
		}
	}
}
