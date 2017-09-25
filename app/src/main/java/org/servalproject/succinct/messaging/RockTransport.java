package org.servalproject.succinct.messaging;


import android.content.Context;

import org.servalproject.succinct.App;
import org.servalproject.succinct.messaging.rock.RockMessage;
import org.servalproject.succinct.messaging.rock.RockMessaging;
import org.servalproject.succinct.utils.AndroidObserver;

import java.util.Observable;

import uk.rock7.connect.enums.R7LockState;

public class RockTransport extends AndroidObserver implements IMessaging{
	private final RockMessaging messaging;
	private boolean messagingRequired=false;
	private String deviceId;

	public RockTransport(Context context){
		App app = (App)context.getApplicationContext();
		messaging = app.getRock();
		messaging.observable.addObserver(this);
	}

	private boolean getReady(){
		if (!messaging.isEnabled()){
			messaging.enable();
			return false;
		}

		if (!messaging.isConnected()){
			if (messaging.canConnect())
				messaging.connect(deviceId);
			return false;
		}

		if (messaging.getLockState() != R7LockState.R7LockStateUnlocked){
			messaging.enterPin((short) 1234);
			return false;
		}

		return true;
	}

	private RockMessage sendingMsg;
	private Fragment sendingFragment;

	@Override
	public int trySend(Fragment fragment) {
		if (deviceId==null)
			return UNAVAILABLE;

		messagingRequired = true;
		if (!getReady())
			return BUSY;

		if (!messaging.canSendRawMessage())
			return UNAVAILABLE;

		if (sendingMsg != null)
			return BUSY;

		sendingMsg = messaging.sendRawMessage(fragment.bytes);
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
		if (obj!=null && obj instanceof RockMessage){
			RockMessage m = (RockMessage)obj;
			if (m.completed && m == sendingMsg){
				// TODO, callback to indicate success / failure of delivery && state changed
				sendingMsg = null;
				sendingFragment = null;
			}
		}
		if (messagingRequired) {
			if (getReady()){
				// TODO, callback to indicate state changed
			}
		}else{
			tearDown();
		}
	}
}
