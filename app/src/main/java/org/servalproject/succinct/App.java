package org.servalproject.succinct;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;

import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.networking.Networks;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.Storage;

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

	@Override
	public void onCreate() {
		super.onCreate();
		UIHandler = new Handler(this.getMainLooper());
		HandlerThread backgroundThread = new HandlerThread("Background");
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());

		try {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			String idString = prefs.getString(MY_ID, null);
			PeerId myId;
			if (idString == null){
				myId = new PeerId();
				SharedPreferences.Editor ed = prefs.edit();
				ed.putString(MY_ID, myId.toString());
				ed.apply();
			}else{
				myId = new PeerId(idString);
			}

			// for now there is one team, and you are in it.
			teamStorage = new Storage(this, "team");
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
