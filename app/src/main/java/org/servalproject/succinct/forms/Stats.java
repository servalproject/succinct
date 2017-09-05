package org.servalproject.succinct.forms;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

class Stats {
	private AssetManager am;
	long ptr;
	private static native long openStats(AssetManager am, String filename);
	private static native void closeStats(long ptr);
	private static final String TAG = "Stats";

	Stats(Context context, String filename){
		am = context.getAssets();
		Log.v(TAG, "Open stats...");
		ptr = openStats(am, filename);
	}

	void close(){
		Log.v(TAG, "Close stats");
		closeStats(ptr);
		ptr = 0;
	}
}
