package org.servalproject.succinct.messaging;


import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.RecordStore;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;
import org.servalproject.succinct.utils.AndroidObserver;

import java.io.IOException;
import java.util.Observable;

// Manage the queue of outgoing messages / fragments
public class MessageQueue {
	private final Storage store;
	public final RecordIterator<Fragment> fragments;

	public MessageQueue(App app) throws IOException {
		store = app.teamStorage;

		this.fragments = store.openIterator(Fragment.factory, "messaging");

		store.observable.addObserver(new AndroidObserver(App.backgroundHandler){
			@Override
			public void observe(Observable observable, Object o) {
				onFileChanged((RecordStore)o);
			}
		});
	}

	private void onFileChanged(RecordStore file){
		try {
			String name = file.filename.getName();
			String parentFolder = file.filename.getParentFile().getName();
			if (Hex.isHex(parentFolder)) {
				PeerId peer = new PeerId(parentFolder);
				if (name.equals(TeamMember.factory.getFileName())) {
					MembershipList.getInstance(store).enroll(peer);
				}
			}
		}catch (Exception e){
			throw new IllegalStateException(e);
		}
	}

	private static MessageQueue instance=null;
	// start fragmenting and queuing messages, if this is the app instance with that role
	public static void init(App app){
		try {
			if (instance!=null)
				throw new IllegalStateException();

			Team myTeam = app.teamStorage.getLastRecord(Team.factory, app.teamStorage.teamId);
			if (myTeam.leader.equals(app.networks.myId))
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
