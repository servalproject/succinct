package org.servalproject.succinct;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;

import org.servalproject.succinct.chat.ChatDatabase;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.messaging.MessageQueue;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.storage.StorageWatcher;
import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;

import java.io.IOException;

public class App extends Application {
	public static Handler UIHandler;
	private RockMessaging rock;
	private SharedPreferences prefs;
	public TeamStorage teamStorage;
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

	// All the preference names we're using anywhere in the app
	public static final String MY_ID = "my_id";
	public static final String TEAM_ID = "team_id";
	public static final String MY_NAME = "my_name";
	public static final String MY_EMPLOYEE_ID = "my_employee_id";
	public static final String PAIRED_ROCK = "paired_rock";
	public static final String SMS_DESTINATION = "sms_destination";
	public static final String BASE_SERVER_URL = "base_server_url";

	// Maximum delay (in ms) before flushing message fragments;
	// to send a form
	public static final String FORM_DELAY = "form_delay";
	// to send a text message
	public static final String MESSAGE_DELAY = "message_delay";
	// minimum delay before sending a location update
	public static final String LOCATION_INTERVAL = "location_interval";

	private PeerId fromPreference(SharedPreferences prefs, String pref){
		String id = prefs.getString(pref, null);
		if (id==null || id.length() != PeerId.LEN*2)
			return null;
		return new PeerId(id);
	}

	public SharedPreferences getPrefs(){
		return prefs;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		UIHandler = new Handler(this.getMainLooper());
		HandlerThread backgroundThread = new HandlerThread("Background");
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());

		try {
			prefs = PreferenceManager.getDefaultSharedPreferences(this);
			PeerId myId = fromPreference(prefs, MY_ID);
			if (myId == null){
				myId = new PeerId();
				SharedPreferences.Editor ed = prefs.edit();
				ed.putString(MY_ID, myId.toString());
				ed.apply();
			}
			PeerId teamId = fromPreference(prefs, TEAM_ID);
			if (teamId!=null)
				TeamStorage.reloadTeam(this, teamId, myId);
			networks = Networks.init(this, myId);

		} catch (java.io.IOException e) {
			throw new IllegalStateException("");
		}
	}

	@Override
	public void onTrimMemory(int level) {
		if (rock!=null && level!=TRIM_MEMORY_UI_HIDDEN)
			rock.onTrimMemory(level);
		super.onTrimMemory(level);
	}
}
