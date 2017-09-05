package org.servalproject.succinct.forms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReceiveNewMagpiRecord extends BroadcastReceiver {
	private static final String ACTION="org.servalproject.succinctdata.ReceiveNewMagpiRecord";

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (ACTION.equals(action)){
			//String recordUUID =  intent.getStringExtra("recordUUID");
			String completedRecord = intent.getStringExtra("recordData");
			//String recordBundle = intent.getStringExtra("recordBundle");
			String formSpecification =  intent.getStringExtra("formSpecification");

			Form.compress(context, formSpecification, completedRecord);
		}
	}
}
