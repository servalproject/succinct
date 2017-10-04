package org.servalproject.succinct.messaging;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.Hex;

// placeholder to test messaging
public class DummyTransport implements IMessaging{
	private final MessageQueue queue;
	// use flight mode as a proxy for availability
	private boolean airplaneMode;
	private Fragment sending;
	private static final String TAG = "DummyTransport";

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			airplaneMode = intent.getBooleanExtra("state", false);
			Log.v(TAG, "airplaneMode = "+airplaneMode);
			queue.onStateChanged();
		}
	};

	public DummyTransport(MessageQueue queue, Context context){
		this.queue = queue;
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
		context.registerReceiver(receiver, filter);
		try {
			airplaneMode = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON) >0;
			Log.v(TAG, "airplaneMode = "+airplaneMode);
		} catch (Settings.SettingNotFoundException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	@Override
	public int getMTU() {
		return 200;
	}

	@Override
	public int checkAvailable() {
		if (airplaneMode)
			return UNAVAILABLE;

		if (sending != null)
			return BUSY;

		return SUCCESS;
	}

	@Override
	public int trySend(Fragment fragment) {
		int available = checkAvailable();
		if (available!=SUCCESS)
			return available;

		sending = fragment;
		Log.v(TAG, "Sending "+ Hex.toString(fragment.bytes));

		App.backgroundHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				sending = null;
				queue.onStateChanged();
			}
		}, 1000);

		return SUCCESS;
	}

	@Override
	public void done() {
		Log.v(TAG, "All done");
	}
}
