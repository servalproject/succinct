package org.servalproject.succinct;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;

import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.networking.Networks;

public class App extends Application {
	public static Handler UIHandler;
	private RockMessaging rock;

	// a single background thread for short work tasks
	public static Handler backgroundHandler;

	static {
		// ensure our jni library has been loaded
		System.loadLibrary("native-lib");
	}

	public RockMessaging getRock(){
		if (rock == null)
			rock = new RockMessaging(this);
		return rock;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		UIHandler = new Handler(this.getMainLooper());
		HandlerThread backgroundThread = new HandlerThread("Background");
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());
		Networks.getInstance();
	}

	@Override
	public void onTrimMemory(int level) {
		if (rock!=null && level!=TRIM_MEMORY_UI_HIDDEN)
			rock.onTrimMemory(level);
		super.onTrimMemory(level);
	}
}
