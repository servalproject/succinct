package org.servalproject.succinct.utils;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

public abstract class WakeAlarm {
	protected final Context context;
	private final PowerManager.WakeLock wakeLock;
	protected final Handler handler;
	protected final AlarmManager am;
	protected final String tag;
	protected long nextAlarm=-1;
	protected final Runnable onAlarm;
	protected static final String TAG = "WakeAlarm";

	protected WakeAlarm(Context context, String tag, Handler handler, Runnable onAlarm){
		this.context = context;
		this.handler = handler;
		this.tag = tag;
		this.onAlarm = onAlarm;
		am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
	}

	public static WakeAlarm getAlarm(Context context, String tag, Handler handler, Runnable onAlarm){
		if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)
			return new WakeAlarmHandler(context, tag, handler, onAlarm);
		return new WakeAlarmReceiver(context, tag, handler, onAlarm);
	}

	protected abstract void internalCancel();
	protected abstract void internalSet(long nextAlarm);

	public void setAlarm(long elapsedTime){
		cancel();
		if (elapsedTime <= SystemClock.elapsedRealtime()){
			acquire();
			//Log.v(TAG, "Running alarm "+tag+" now");
			handler.post(new Runnable() {
				@Override
				public void run() {
					onAlarm.run();
					release();
				}
			});
		}else {
			//Log.v(TAG, "Setting alarm "+tag+" for "+(elapsedTime - SystemClock.elapsedRealtime())+"ms");
			internalSet(elapsedTime);
			nextAlarm = elapsedTime;
		}
	}

	public void cancel(){
		if (nextAlarm !=-1) {
			//Log.v(TAG, "Cancelling alarm "+tag);
			internalCancel();
		}
		nextAlarm=-1;
	}

	protected void acquire(){
		nextAlarm = -1;
		wakeLock.acquire();
	}

	protected void release(){
		wakeLock.release();
	}

	public abstract void unRegister();
}
