package org.servalproject.succinct.storage;

import android.content.SharedPreferences;
import android.util.Log;

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
	private boolean sendMessages = false;
	private boolean teamActive = true;
	private static final String TAG = "TeamStorage";

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
				if (sendMessages)
					messageQueue = new MessageQueue(appContext, TeamStorage.this);
				if (teamActive)
					chatWatcher.activate();
			}catch (IOException e){
				throw new IllegalStateException(e);
			}
		}
	};

	public boolean isTeamActive(){
		return teamActive;
	}

	public boolean isMembershipUpToDate() throws IOException {
		TeamMember me = getMyself();
		MembershipList list = getMembers();

		return list.getPosition(peerId)>0
				&& (me.name != null || !list.isActive(peerId));
	}

	public boolean messagesPending() throws IOException {
		if (messageQueue==null)
			return false;
		return messageQueue.hasUnsent();
	}

	public static boolean canCreateOrJoin(App appContext) {
		try {
			TeamStorage store = appContext.teamStorage;
			if (store == null)
				return true;
			if (store.messagesPending())
				return false;
			// TODO do we add a timer or something?
			/*if (!store.isMembershipUpToDate())
				return false;
			*/
			return !store.isTeamActive();
		}catch (Exception e) {
			Log.e(TAG, e.getMessage(), e);
			return false;
		}
	}

	public static void createTeam(App appContext, String teamName, PeerId peerId) throws IOException {
		if (!canCreateOrJoin(appContext))
			throw new IllegalStateException();
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
		storage.sendMessages = true;

		SharedPreferences.Editor ed = prefs.edit();
		ed.putString(App.TEAM_ID, teamId.toString());
		ed.apply();

		appContext.teamStorage = storage;
		App.backgroundHandler.post(storage.postInit);
	}

	public static void joinTeam(App appContext, Team team, PeerId peerId) throws IOException {
		if (!canCreateOrJoin(appContext))
			throw new IllegalStateException();
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
		App.backgroundHandler.post(storage.postInit);
	}

	public static void reloadTeam(App appContext, PeerId teamId, PeerId peerId) throws IOException {
		if (appContext.teamStorage != null)
			throw new IllegalStateException();

		TeamStorage storage = new TeamStorage(appContext, teamId, peerId);

		RecordIterator<Team> iterator = storage.openIterator(Team.factory, teamId);
		iterator.end();
		while(iterator.prev()){
			Team team = iterator.read();
			// set myTeam to the last record
			if (storage.myTeam == null)
				storage.myTeam = team;
			// check if we are / were the team leader
			if (peerId.equals(team.leader))
				storage.sendMessages = true;

			if (team.leader==null)
				storage.teamActive = false;
			else
				// stop when we hit a team create record
				break;
		}

		TeamMember me = storage.getMyself();
		if (me!=null && me.name == null){
			// We have left this team
			storage.teamActive = false;
		}

		appContext.teamStorage = storage;
		App.backgroundHandler.post(storage.postInit);
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
			messageQueue.close();
		super.close();
	}

	public void leave() throws IOException {
		// Record that we are leaving / shutting down the team
		// But keep syncing and sending message fragments
		teamActive = false;
		Team team = getTeam();
		if (team!=null && team.leader.equals(peerId)){
			// close the whole team
			myTeam = new Team(System.currentTimeMillis());
			appendRecord(Team.factory, teamId, myTeam);
		} else {
			// just remove myself
			myself = new TeamMember();
			appendRecord(TeamMember.factory, peerId, myself);
		}
		if (messageQueue!=null)
			messageQueue.onStateChanged();
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
