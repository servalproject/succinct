package org.servalproject.succinct.storage;

import android.content.SharedPreferences;

import org.servalproject.succinct.App;
import org.servalproject.succinct.chat.ChatDatabase;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.messaging.MessageQueue;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TeamStorage extends Storage {
	public final PeerId peerId;
	private final StorageWatcher<StoredChatMessage> chatWatcher;
	private MessageQueue messageQueue;

	private TeamStorage(App app, PeerId id, PeerId peerId) {
		super(app, id);
		this.peerId = peerId;

		chatWatcher = new StorageWatcher<StoredChatMessage>(App.backgroundHandler, this, StoredChatMessage.factory) {
			@Override
			protected void Visit(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
				// todo wait until we have peer's name from id file
				records.reset("imported");
				ChatDatabase db = ChatDatabase.getInstance(appContext);
				// todo handle duplicate records in case previous process inserted without saving mark?
				db.insert(teamId, peer, records);
				records.mark("imported");
			}
		};
	}

	private final Runnable postInit = new Runnable() {
		@Override
		public void run() {
			try {
				Team team = getTeam();
				if (team != null && team.leader.equals(peerId))
					messageQueue = new MessageQueue(appContext, TeamStorage.this);
			}catch (IOException e){
				throw new IllegalStateException(e);
			}
			chatWatcher.activate();
		}
	};

	public static void createTeam(App appContext, String teamName, PeerId peerId) throws IOException {
		if (appContext.teamStorage!=null) {
			appContext.teamStorage.close();
			appContext.teamStorage = null;
		}
		SharedPreferences prefs = appContext.getPrefs();
		String name = prefs.getString(App.MY_NAME, null);
		String employeeId = prefs.getString(App.MY_EMPLOYEE_ID, null);

		TeamMember me = new TeamMember(employeeId, name);
		PeerId teamId = new PeerId();
		TeamStorage storage = new TeamStorage(appContext, teamId, peerId);
		Team team = new Team(System.currentTimeMillis(), teamId, peerId, teamName);
		storage.appendRecord(Team.factory, teamId, team);
		storage.appendRecord(TeamMember.factory, peerId, me);
		storage.myself = me;
		storage.myTeam = team;

		SharedPreferences.Editor ed = prefs.edit();
		ed.putString(App.TEAM_ID, teamId.toString());
		ed.apply();

		App.backgroundHandler.post(storage.postInit);
		appContext.teamStorage = storage;
	}

	public static void joinTeam(App appContext, Team team, PeerId peerId) throws IOException {
		if (appContext.teamStorage!=null) {
			appContext.teamStorage.close();
			appContext.teamStorage = null;
		}
		SharedPreferences prefs = appContext.getPrefs();
		String name = prefs.getString(App.MY_NAME, null);
		String employeeId = prefs.getString(App.MY_EMPLOYEE_ID, null);

		TeamMember me = new TeamMember(employeeId, name);
		TeamStorage storage = new TeamStorage(appContext, team.id, peerId);
		storage.appendRecord(TeamMember.factory, peerId, me);
		storage.myself = me;
		storage.myTeam = team;

		SharedPreferences.Editor ed = prefs.edit();
		ed.putString(App.TEAM_ID, team.id.toString());
		ed.apply();

		appContext.teamStorage = storage;
	}

	public static void reloadTeam(App appContext, PeerId teamId, PeerId peerId){
		TeamStorage storage = new TeamStorage(appContext, teamId, peerId);
		App.backgroundHandler.post(storage.postInit);
		appContext.teamStorage = storage;
	}

	private TeamMember myself;
	public TeamMember getMyself() throws IOException {
		if (myself == null)
			myself = getLastRecord(TeamMember.factory, peerId);
		return myself;
	}

	private Team myTeam;
	public Team getTeam() throws IOException {
		if (myTeam == null)
			myTeam = getLastRecord(Team.factory, teamId);
		return myTeam;
	}

	private MembershipList membershipList;
	public MembershipList getMembers() throws IOException {
		if (membershipList == null)
			membershipList = new MembershipList(this);
		return membershipList;
	}

	@Override
	public void close() throws IOException {
		// About to join a new team, really stop tracking this one.
		if (messageQueue!=null)
			messageQueue.shutdown();
		super.close();
	}

	public void leave() throws IOException {
		// Record that we are leaving / shutting down the team
		// But keep syncing and sending message fragments
		Team team = getTeam();
		if (team!=null && team.leader.equals(peerId)){
			// close the whole team
			myTeam = new Team(System.currentTimeMillis());
			appendRecord(Team.factory, teamId, myTeam);
			if (messageQueue!=null)
				messageQueue.onStateChanged();
		} else {
			// just remove myself
			myself = new TeamMember();
			appendRecord(TeamMember.factory, peerId, myself);
		}
		chatWatcher.deactivate();
	}

	public List<PeerId> getDevices(){
		List<PeerId> devices = new ArrayList<>();
		for(File f : root.listFiles()){
			if (!f.isDirectory() || !Hex.isHex(f.getName()))
				continue;
			devices.add(new PeerId(f.getName()));
		}
		return devices;
	}

	public <T> void appendRecord(Factory<T> factory, PeerId peer, T record) throws IOException {
		RecordIterator<T> iterator = openIterator(factory, peer);
		iterator.append(record);
	}

	public <T> T getLastRecord(Factory<T> factory, PeerId peer) throws IOException {
		RecordIterator<T> iterator = openIterator(factory, peer);
		return iterator.readLast();
	}

	public <T> boolean exists(Factory<T> factory, PeerId peer){
		return new File(root, peer.toString()+"/"+factory.getFileName()).exists();
	}

	public <T> RecordIterator<T> openIterator(Factory<T> factory, PeerId peer) throws IOException {
		return openIterator(factory, peer.toString());
	}
}
