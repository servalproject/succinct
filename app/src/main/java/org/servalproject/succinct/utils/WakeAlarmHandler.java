package org.servalproject.succinct.utils;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

public class WakeAlarmHandler extends WakeAlarm{
	private AlarmManager.OnAlarmListener listener=new AlarmManager.OnAlarmListener() {
		@Override
		public void onAlarm() {
			acquire();
			//Log.v(TAG, "Alarm "+tag+" firing now");
			nextAlarm = -1;
			onAlarm.run();
			release();
		}
	};

	public WakeAlarmHandler(Context context, String tag, Handler handler, Runnable onAlarm) {
		super(context, tag, handler, onAlarm);
	}

	@TargetApi(Build.VERSION_CODES.N)
	@Override
	protected void internalSet(int flag, long nextAlarm) {
		am.setExact(flag,
				nextAlarm,
				tag,
				listener,
				handler);
	}

	@Override
	public void unRegister() {
		internalCancel();
	}

	@TargetApi(Build.VERSION_CODES.N)
	@Override
	protected void internalCancel() {
		am.cancel(listener);
	}
}
