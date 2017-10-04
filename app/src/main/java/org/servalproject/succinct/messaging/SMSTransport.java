package org.servalproject.succinct.messaging;


import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.Hex;


public class SMSTransport implements IMessaging{
	private final App appContext;
	private final MessageQueue queue;
	private final SmsManager smsManager;
	private final TelephonyManager telephonyManager;
	private boolean airplaneMode;
	private Fragment sending;
	private static final String TAG = "DummyTransport";
	private static final String ACTION_SENT = "org.servalproject.succinct.SMS_SENT";

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
				airplaneMode = intent.getBooleanExtra("state", false);
				Log.v(TAG, "airplaneMode = " + airplaneMode);
				queue.onStateChanged();
			}else if (action.equals(ACTION_SENT)){
				if (sending != null && getResultCode() == Activity.RESULT_OK){
					// TODO API to indicate success?
					Log.v(TAG, "Fragment sent");
					sending = null;
					queue.onStateChanged();
				}
			}
		}
	};

	public int getMTU(){
		int len = SmsMessage.MAX_USER_DATA_SEPTETS;
		if ((len% 4)!=0)
			len -= len % 4;
		return (len/4)*3;
	}

	public SMSTransport(MessageQueue queue, Context context){
		this.appContext = (App)context.getApplicationContext();
		this.queue = queue;
		telephonyManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
		smsManager = SmsManager.getDefault();

		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
		filter.addAction(ACTION_SENT);
		context.registerReceiver(receiver, filter);
		try {
			airplaneMode = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON) >0;
			Log.v(TAG, "airplaneMode = "+airplaneMode);
		} catch (Settings.SettingNotFoundException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	public int checkAvailable(){
		String destinationNumber = appContext.getPrefs().getString(App.SMS_DESTINATION, null);
		if (destinationNumber == null)
			return UNAVAILABLE;

		if (airplaneMode)
			return UNAVAILABLE;

		// TODO API26, check state of multiple sim cards?
		if (telephonyManager.getSimState() != TelephonyManager.SIM_STATE_READY)
			return UNAVAILABLE;

		if (sending != null)
			return BUSY;

		return SUCCESS;
	}

	@Override
	public int trySend(Fragment fragment) {
		int available = checkAvailable();
		if (available != SUCCESS)
			return available;

		String destinationNumber = appContext.getPrefs().getString(App.SMS_DESTINATION, null);
		if (destinationNumber == null)
			return UNAVAILABLE;

		sending = fragment;
		Log.v(TAG, "Sending "+ Hex.toString(fragment.bytes));

		// Do we need a broadcast receiver defined in our manifest?
		Intent sentIntent = new Intent(ACTION_SENT);
		sentIntent.putExtra("seq", fragment.seq);

		PendingIntent pi = PendingIntent.getBroadcast(appContext, 0, sentIntent, 0);

		String encoded = Base64.encodeToString(fragment.bytes, Base64.NO_WRAP | Base64.URL_SAFE);
		smsManager.sendTextMessage(destinationNumber, null, encoded, pi, null);

		return SUCCESS;
	}

	@Override
	public void done() {
		Log.v(TAG, "All done");
	}
}
