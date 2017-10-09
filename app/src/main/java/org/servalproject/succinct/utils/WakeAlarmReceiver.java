package org.servalproject.succinct.utils;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

public class WakeAlarmReceiver extends WakeAlarm {
	private final String actionName;
	private PendingIntent alarmIntent;

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			acquire();
			//Log.v(TAG, "Alarm "+tag+" firing now");
			alarmIntent = null;
			nextAlarm = -1;
			handler.post(new Runnable() {
				@Override
				public void run() {
					onAlarm.run();
					release();
				}
			});
		}
	};

	WakeAlarmReceiver(Context context, String tag, Handler handler, Runnable onAlarm) {
		super(context, tag, handler, onAlarm);
		actionName = "org.servalproject.succinct."+tag+"_ALARM";

		IntentFilter f = new IntentFilter();
		f.addAction(actionName);
		context.registerReceiver(receiver, f);
	}

	@Override
	public void unRegister() {
		context.unregisterReceiver(receiver);
	}

	@Override
	protected void internalCancel() {
		if (alarmIntent!=null)
			am.cancel(alarmIntent);
		alarmIntent = null;
	}

	@Override
	protected void internalSet(int flag, long nextAlarm) {
		alarmIntent = PendingIntent.getBroadcast(
				context,
				0,
				new Intent(actionName),
				PendingIntent.FLAG_UPDATE_CURRENT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			am.setExact(flag,
					nextAlarm,
					alarmIntent);
		}else{
			am.set(flag,
					nextAlarm,
					alarmIntent);
		}
	}
}
