package org.servalproject.succinct.networking;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.servalproject.succinct.App;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

public class Networks {
	private static final int PORT = 4073;
	private static final int HEARTBEAT_MS = 5000; // Network heartbeat
	private static final String ALARM_ACTION = "org.servalproject.succinct.HEARTBEAT_ALARM";
	private static final String TAG = "Networks";

	private final Context context;
	private final DatagramSocket dgramSocket;
	private final AlarmManager am;
	private final PowerManager.WakeLock wakeLock;

	private native void beginPolling();

	// TODO, enabling will slowly drain battery...
	private boolean backgroundEnabled = false;

	private class Interface {
		final String name;
		final InetAddress address;
		final int prefixLength;
		final InetAddress broadcastAddress;

		Interface(String name, byte[] addr, byte[] broadcast, int prefixLength) throws UnknownHostException {
			this.name = name;
			this.address = InetAddress.getByAddress(addr);
			this.broadcastAddress = InetAddress.getByAddress(broadcast);
			this.prefixLength = prefixLength;
		}

		@Override
		public int hashCode() {
			return name.hashCode() ^ address.hashCode() ^ prefixLength;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Interface))
				return false;
			Interface other = (Interface)obj;
			return this.name.equals(other.name)
					&& this.address.equals(other.address)
					&& this.prefixLength == other.prefixLength;
		}

		@Override
		public String toString() {
			return name+", "+address.getHostAddress()+"/"+prefixLength+" ("+broadcastAddress.getHostAddress()+")";
		}
	}

	private Set<Interface> networks = new HashSet<>();

	private static Networks instance;
	public static Networks getInstance(){
		return instance;
	}

	public static void init(Context context) throws SocketException {
		if (instance != null)
			throw new IllegalStateException("Already created");

		DatagramSocket dgramSocket = new DatagramSocket(PORT);
		dgramSocket.setBroadcast(true);
		instance = new Networks(context, dgramSocket);
	}

	private Networks(Context context, DatagramSocket dgramSocket){
		this.dgramSocket = dgramSocket;
		this.context = context;
		am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			IntentFilter f = new IntentFilter();
			f.addAction(ALARM_ACTION);
			context.registerReceiver(new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					wakeLock.acquire();
					App.backgroundHandler.post(new Runnable() {
						@Override
						public void run() {
							onAlarm();
						}
					});
				}
			}, f);
		}
		App.backgroundHandler.post(new Runnable() {
			@Override
			public void run() {
				beginPolling();
			}
		});
	}

	private PendingIntent alarmIntent=null;
	private AlarmManager.OnAlarmListener listener=null;
	private void setAlarm(int delay){
		if (!backgroundEnabled)
			return;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			if (listener == null){
				listener = new AlarmManager.OnAlarmListener() {
					@Override
					public void onAlarm() {
						wakeLock.acquire();
						Networks.this.onAlarm();
					}
				};
			}
			am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime()+delay,
					"Heartbeat",
					listener,
					App.backgroundHandler);
		}else{
			alarmIntent = PendingIntent.getBroadcast(
					context,
					0,
					new Intent(ALARM_ACTION),
					PendingIntent.FLAG_UPDATE_CURRENT);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
						SystemClock.elapsedRealtime()+delay,
						alarmIntent);
			}else{
				am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
						SystemClock.elapsedRealtime()+delay,
						alarmIntent);
			}
		}
	}

	private void cancelAlarm(){
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			if (listener!=null)
				am.cancel(listener);
		}else{
			if (alarmIntent!=null)
				am.cancel(alarmIntent);
			alarmIntent = null;

		}
	}

	// called from JNI
	private void onAdd(String name, byte[] addr, byte[] broadcast, int prefixLen){
		try {
			Interface network = new Interface(name, addr, broadcast, prefixLen);
			Log.v(TAG, "Add "+network);
			networks.add(network);

			cancelAlarm();
			setAlarm(100);
		} catch (UnknownHostException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	// called from JNI
	private void onRemove(String name, byte[] addr, byte[] broadcast, int prefixLen){
		try {
			Interface network = new Interface(name, addr, broadcast, prefixLen);
			Log.v(TAG, "Remove "+network);
			networks.remove(network);
			if (networks.isEmpty())
				cancelAlarm();
		} catch (UnknownHostException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}


	public void onAlarm() {
		alarmIntent = null;
		if (backgroundEnabled && !networks.isEmpty()) {

			DatagramPacket packet = null;
			for (Interface i : networks) {
				Log.v(TAG, "Sending heartbeat to " + i);
				if (packet == null) {
					// TODO generate heartbeat packet data with name / team / sync state!
					packet = new DatagramPacket(new byte[0], 0, i.broadcastAddress, PORT);
				} else {
					packet.setAddress(i.broadcastAddress);
				}
				try {
					dgramSocket.send(packet);
				} catch (SecurityException se) {
					Log.e(TAG, se.getMessage(), se);
				} catch (IOException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}

			setAlarm(HEARTBEAT_MS);
		}
		wakeLock.release();
	}
}
