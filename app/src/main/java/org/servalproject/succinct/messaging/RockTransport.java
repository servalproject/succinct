package org.servalproject.succinct.messaging;


import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.messaging.rock.RockMessage;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.utils.AndroidObserver;

import java.util.Observable;

import uk.rock7.connect.enums.R7DeviceError;
import uk.rock7.connect.enums.R7LockState;

public class RockTransport extends AndroidObserver implements IMessaging{
	private final SharedPreferences prefs;
	private final RockMessaging messaging;
	private final MessageQueue messageQueue;
	private boolean messagingRequired=false;
	private static final String TAG = "RockTransport";
	private boolean closed = false;

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
		messaging.checkState();

		if (!messaging.isConnected()){
			R7DeviceError error = messaging.getLastError();
			if (error != null){
				Log.e(TAG, "Rock API returned error "+error);
				return UNAVAILABLE;
			}
			if (messaging.isScanning())
				return BUSY;
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
			Log.v(TAG, "Raw messaging is not available, forgetting configured device!");
			SharedPreferences.Editor e = prefs.edit();
			e.putString(App.PAIRED_ROCK, null);
			e.apply();
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

		if (prefs.getBoolean(App.PRETEND_ROCK, false)) {
			Log.v(TAG, "Faking send of fragment "+ Hex.toString(fragment.bytes));
		}else{
			messaging.disableTimeout();

			Log.v(TAG, "Sending message");
			sendingMsg = messaging.sendRawMessage((short) fragment.seq, fragment.bytes);
			sendingFragment = fragment;
		}
		return SUCCESS;
	}

	@Override
	public void done() {
		messagingRequired = false;
		if (sendingMsg == null)
			messaging.enableTimeout();
	}

	@Override
	public void close() {
		done();
		closed = true;
		if (sendingMsg == null)
			messaging.observable.deleteObserver(this);
	}

	@Override
	public void observe(Observable observable, Object obj) {
		boolean callback = messagingRequired;

		if (obj != null && obj instanceof RockMessage) {
			RockMessage m = (RockMessage) obj;
			if (m.incoming){
				messageQueue.receiveFragment(m.bytes);
			} else if (m.status!=null) {
				switch (m.status) {
					case R7MessageStatusReceived:
						// incoming??
						break;
					case R7MessageStatusReceivedByDevice:
					case R7MessageStatusQueuedForTransmission:
					case R7MessageStatusPending:
					case R7MessageStatusTransmitting:
						break;
					case R7MessageStatusTransmitted:
						// Lets annoy everyone by beeping every time we sent something
						messaging.requestBeep();

						// TODO remember fragment send state across restarts
						// (and between team members...)!
						if (m.id == sendingMsg.id) {
							// TODO, callback to indicate success / failure of delivery && state changed
							sendingMsg = null;
							sendingFragment = null;
							messaging.enableTimeout();
							if (closed)
								messaging.observable.deleteObserver(this);
							callback = true;
						}
						break;
					default:
						// errors...
						if (m.id == sendingMsg.id) {
							sendingMsg = null;
							sendingFragment = null;
							callback = true;
						}
				}
			}
		}

		if (callback && !closed)
			messageQueue.onStateChanged();
	}
}
