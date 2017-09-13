package org.servalproject.succinct.forms;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

class Stats {
	private AssetManager am;
	long ptr;
	private static native long openStats(AssetManager am, String filename);
	private static native void closeStats(long ptr);
	private static native byte[] compressString(long stats, String text);

	private static final String TAG = "Stats";

	Stats(Context context, String filename){
		am = context.getAssets();
		Log.v(TAG, "Open stats...");
		ptr = openStats(am, filename);
	}

	private static Stats instance;
	public static Stats getInstance(Context context){
		if (instance == null)
			instance = new Stats(context, "smac.dat");
		return instance;
	}

	void close(){
		Log.v(TAG, "Close stats");
		closeStats(ptr);
		ptr = 0;
	}

	public byte[] compress(String content){
		byte[] ret = compressString(this.ptr, content);
		if (ret == null)
			throw new IllegalStateException();
		return ret;
	}


}
