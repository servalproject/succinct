package org.servalproject.succinct.forms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.servalproject.succinct.App;

public class ReceiveNewMagpiRecord extends BroadcastReceiver {
	private static final String ACTION="org.servalproject.succinctdata.ReceiveNewMagpiRecord";
	private static final String TAG="ReceiveNewMagpiRecord";

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (ACTION.equals(action)){
			//String recordUUID =  intent.getStringExtra("recordUUID");
			final String completedRecord = intent.getStringExtra("recordData");
			//String recordBundle = intent.getStringExtra("recordBundle");
			final String formSpecification =  intent.getStringExtra("formSpecification");
			final Context appContext = context.getApplicationContext();

			final PendingResult result = goAsync();

			App.backgroundHandler.post(new Runnable() {
				@Override
				public void run() {
					Form.compress(appContext, formSpecification, completedRecord);
					result.finish();
				}
			});
		}
	}
}
