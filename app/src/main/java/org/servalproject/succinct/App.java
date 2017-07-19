package org.servalproject.succinct;

import android.app.Application;
import android.os.Handler;

/**
 * Created by jeremy on 12/07/17.
 */

public class App extends Application {
	public static Handler UIHandler;

	@Override
	public void onCreate() {
		UIHandler = new Handler(this.getMainLooper());
		super.onCreate();
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
	}
}
