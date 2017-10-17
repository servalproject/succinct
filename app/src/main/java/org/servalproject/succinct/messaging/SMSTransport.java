package org.servalproject.succinct.messaging;


import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.BuildConfig;
import org.servalproject.succinct.networking.Hex;

import java.util.ArrayList;


public class SMSTransport implements IMessaging{
	private final App appContext;
	private final MessageQueue queue;
	private final SmsManager smsManager;
	private final TelephonyManager telephonyManager;
	private boolean airplaneMode;
	private Fragment sending;
	private static final String TAG = "SMSTransport";
	private static final String ACTION_SENT = "org.servalproject.succinct.SMS_SENT";

	private static final String EXTRA_SEQ = "seq";
	private static final String EXTRA_PART = "part";
	private static final String EXTRA_PARTS = "parts";

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
				airplaneMode = intent.getBooleanExtra("state", false);
				Log.v(TAG, "airplaneMode = " + airplaneMode);
				queue.onStateChanged();
			}else if (action.equals(ACTION_SENT)){
				int seq = intent.getIntExtra(EXTRA_SEQ, -1);
				int part = intent.getIntExtra(EXTRA_PART, -1);
				int parts = intent.getIntExtra(EXTRA_PARTS, -1);
				// TODO track each part separately?
				if (sending != null && seq == sending.seq && part == parts && getResultCode() == Activity.RESULT_OK){
					// TODO API to indicate success / failure?
					Log.v(TAG, "Fragment sent");
					sending = null;
					queue.onStateChanged();
				}
			}
		}
	};

	private static final int MAX_PARTS = 2;
	public int getMTU(){
		int len = (MAX_PARTS == 1) ? SmsMessage.MAX_USER_DATA_SEPTETS :
				SmsMessage.MAX_USER_DATA_SEPTETS_WITH_HEADER * MAX_PARTS;
		if ((len% 4)!=0)
			len -= len % 4;
		return (len/4)*3;
	}

	public SMSTransport(MessageQueue queue, Context context){
		this.appContext = (App)context.getApplicationContext();
		this.queue = queue;
		telephonyManager = (TelephonyManager)appContext.getSystemService(Context.TELEPHONY_SERVICE);
		smsManager = SmsManager.getDefault();

		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
		filter.addAction(ACTION_SENT);
		appContext.registerReceiver(receiver, filter);
		try {
			airplaneMode = Settings.Global.getInt(appContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON) >0;
			Log.v(TAG, "airplaneMode = "+airplaneMode);
		} catch (Settings.SettingNotFoundException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	public int checkAvailable(){
		String destinationNumber = appContext.getPrefs().getString(App.SMS_DESTINATION, BuildConfig.smsDestination);
		if (destinationNumber == null || "".equals(destinationNumber))
			return UNAVAILABLE;

		if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
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

		sending = fragment;
		Log.v(TAG, "Sending "+ Hex.toString(fragment.bytes));

		String encoded = Base64.encodeToString(fragment.bytes, Base64.NO_WRAP);

		// this should handle a single part just fine.
		ArrayList<String> parts = smsManager.divideMessage(encoded);
		ArrayList<PendingIntent> send = new ArrayList<>();

		for(int i=0;i<parts.size();i++){

			// Do we need a broadcast receiver defined in our manifest?
			Intent sentIntent = new Intent(ACTION_SENT);
			sentIntent.putExtra(EXTRA_SEQ, fragment.seq);
			sentIntent.putExtra(EXTRA_PART, i);
			sentIntent.putExtra(EXTRA_PARTS, parts.size());

			send.add(PendingIntent.getBroadcast(appContext, 0, sentIntent, 0));
		}

		String destinationNumber = appContext.getPrefs().getString(App.SMS_DESTINATION, BuildConfig.smsDestination);
		smsManager.sendMultipartTextMessage(destinationNumber, null, parts, send, null);

		return SUCCESS;
	}

	@Override
	public void done() {
		Log.v(TAG, "All done");
	}

	@Override
	public void close() {
		done();
		appContext.unregisterReceiver(receiver);
	}
}
