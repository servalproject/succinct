package org.servalproject.succinct;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;

import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.team.Team;

import java.io.IOException;

public class App extends Application {
	public static Handler UIHandler;
	private RockMessaging rock;
	public Storage teamStorage;
	public Networks networks;

	// a single background thread for short work tasks
	public static Handler backgroundHandler;

	public RockMessaging getRock(){
		if (rock == null)
			rock = new RockMessaging(this);
		return rock;
	}

	static {
		// ensure our jni library has been loaded
		System.loadLibrary("native-lib");
	}

	private static final String MY_ID = "my_id";
	private static final String TEAM_ID = "team_id";

	private PeerId fromPreference(SharedPreferences prefs, String pref){
		String id = prefs.getString(pref, null);
		if (id==null || id.length() != PeerId.LEN*2)
			return null;
		return new PeerId(id);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		UIHandler = new Handler(this.getMainLooper());
		HandlerThread backgroundThread = new HandlerThread("Background");
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());

		try {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			PeerId myId = fromPreference(prefs, MY_ID);
			if (myId == null){
				myId = new PeerId();
				SharedPreferences.Editor ed = prefs.edit();
				ed.putString(MY_ID, myId.toString());
				ed.apply();
			}
			PeerId teamId = fromPreference(prefs, TEAM_ID);
			// TODO for now there is a default team, and you are in it.
			if (teamId == null){
				teamId = PeerId.Team;
			}

			if (teamId!=null)
				teamStorage = new Storage(this, teamId);
			networks = Networks.init(this, myId);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("");
		}
	}

	public void createTeam(String name) throws IOException {
		PeerId teamId = new PeerId();
		Storage storage = new Storage(this, teamId);
		storage.appendRecord(Team.factory, teamId, new Team(teamId, networks.myId, name));
		teamStorage = storage;
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		SharedPreferences.Editor ed = prefs.edit();
		ed.putString(TEAM_ID, teamId.toString());
		ed.apply();
	}

	public void joinTeam(PeerId teamId){
		if (teamStorage!=null)
			throw new IllegalStateException("Already in a team");
		teamStorage = new Storage(this, teamId);
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		SharedPreferences.Editor ed = prefs.edit();
		ed.putString(TEAM_ID, teamId.toString());
		ed.apply();
		// trigger a heartbeat now, which should start syncing with existing peers almost immediately
		networks.setAlarm(0);
	}

	@Override
	public void onTrimMemory(int level) {
		if (rock!=null && level!=TRIM_MEMORY_UI_HIDDEN)
			rock.onTrimMemory(level);
		super.onTrimMemory(level);
	}
}
