package org.servalproject.succinct.messaging;


import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.messaging.rock.Device;
import org.servalproject.succinct.messaging.rock.RockMessage;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.utils.AndroidObserver;

import java.util.Observable;

import uk.rock7.connect.enums.R7DeviceError;
import uk.rock7.connect.enums.R7LockState;

import static android.content.ContentValues.TAG;

public class RockTransport extends AndroidObserver implements IMessaging{
	private final SharedPreferences prefs;
	private final RockMessaging messaging;
	private final MessageQueue messageQueue;
	private boolean messagingRequired=false;

	public RockTransport(MessageQueue messageQueue, Context context){
		this.messageQueue = messageQueue;
		App app = (App)context.getApplicationContext();
		messaging = app.getRock();
		messaging.observable.addObserver(this);

		prefs = PreferenceManager.getDefaultSharedPreferences(context);
	}

	private RockMessage sendingMsg;
	private Fragment sendingFragment;

	@Override
	public int getMTU() {
		return 338;
	}

	public int checkAvailable(){
		String deviceId = prefs.getString(App.PAIRED_ROCK, null);
		if (deviceId==null)
			return UNAVAILABLE;

		messagingRequired = true;
		if (!messaging.isEnabled()){
			Log.v(TAG, "Enabling bluetooth");
			messaging.enable();
			return BUSY;
		}

		if (!messaging.isConnected()){
			R7DeviceError error = messaging.getLastError();
			if (error != null){
				Log.e(TAG, "Rock API returned error "+error);
				return UNAVAILABLE;
			}
			if (messaging.canConnect()) {
				Log.v(TAG, "Initiating connection");
				messaging.connect(deviceId);
			}else{
				Log.v(TAG, "Can't connect (again?) right now");
			}
			return BUSY;
		}

		R7LockState lockState = messaging.getLockState();
		if (lockState == null)
			return BUSY;

		if (lockState == R7LockState.R7LockStateLocked){
			Log.v(TAG, "Entering PIN");
			messaging.enterPin((short) 1234);
			return BUSY;
		}else if(lockState != R7LockState.R7LockStateUnlocked){
			Log.v(TAG, "Device is not locked or unlocked "+lockState);
			return UNAVAILABLE;
		}

		if (!messaging.canSendRawMessage()) {
			Log.v(TAG, "Raw messaging is not available!");
			return UNAVAILABLE;
		}

		if (sendingMsg != null)
			return BUSY;

		return SUCCESS;
	}

	@Override
	public int trySend(Fragment fragment) {
		int available = checkAvailable();
		if (available != SUCCESS)
			return available;

		Log.v(TAG, "Sending message");
		sendingMsg = messaging.sendRawMessage((short)fragment.seq, fragment.bytes);
		sendingFragment = fragment;
		return SUCCESS;
	}

	private void tearDown(){
		if (messaging.canDisconnect()){
			messaging.disconnect();
			return;
		}

		// TODO disable bluetooth too?
	}

	@Override
	public void done() {
		messagingRequired = false;
		tearDown();
	}

	@Override
	public void observe(Observable observable, Object obj) {
		if (obj != null && obj instanceof RockMessage) {
			RockMessage m = (RockMessage) obj;
			if (m.completed && m == sendingMsg) {
				// TODO, callback to indicate success / failure of delivery && state changed
				// Lets annoy everyone by beeping every time we sent something
				messaging.requestBeep();
				sendingMsg = null;
				sendingFragment = null;
				messageQueue.onStateChanged();
			}
		} else if (messagingRequired) {
			messageQueue.onStateChanged();
		} else {
			// TODO settings dialog to ask the user explicitly to use this device
			String deviceId = prefs.getString(App.PAIRED_ROCK, null);
			if (deviceId == null) {
				Device connected = messaging.getConnectedDevice();
				if (connected != null) {
					Log.v(TAG, "Remembering connection to "+connected.id+" for automatic messaging");
					deviceId = connected.id;
					SharedPreferences.Editor ed = prefs.edit();
					ed.putString(App.PAIRED_ROCK, deviceId);
					ed.apply();
					messageQueue.onStateChanged();
				}
			}
		}
	}
}
