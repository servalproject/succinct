package org.servalproject.succinct;

import android.app.Application;
import android.os.Handler;

import org.servalproject.succinct.messaging.rock.RockMessaging;

public class App extends Application {
	public static Handler UIHandler;
	private RockMessaging rock;

	@Override
	public void onCreate() {
		UIHandler = new Handler(this.getMainLooper());
		//rock = new RockMessaging(this, this.getSharedPreferences("",0));
		super.onCreate();
	}

	@Override
	public void onTrimMemory(int level) {
		if (rock!=null)
			rock.onTrimMemory(level);
		super.onTrimMemory(level);
	}
}
