package org.servalproject.succinct;

import android.app.Application;
import android.os.Handler;

import org.servalproject.succinct.messaging.rock.RockMessaging;

public class App extends Application {
	public static Handler UIHandler;
	private RockMessaging rock;

	public RockMessaging getRock(){
		if (rock == null)
			rock = new RockMessaging(this);
		return rock;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		UIHandler = new Handler(this.getMainLooper());
	}

	@Override
	public void onTrimMemory(int level) {
		if (rock!=null)
			rock.onTrimMemory(level);
		super.onTrimMemory(level);
	}
}
