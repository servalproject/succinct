package org.servalproject.succinct.networking;


import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class Networks {
	// TODO refactor if / when other jni polling needs to occur
	private native void poll();
	private static final String TAG = "Networks";

	private static Networks instance;
	public static Networks getInstance(){
		if (instance == null)
			instance = new Networks();
		return instance;
	}


	private Networks(){
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				poll();
				throw new IllegalStateException("Unreachable");
			}
		}, "Networks");
		thread.start();
	}

	private void onAdd(String name, byte[] addr, byte[] broadcast, int prefixLen){
		try {
			InetAddress interfaceAddress = InetAddress.getByAddress(addr);
			InetAddress broadcastAddress = InetAddress.getByAddress(addr);
			Log.v(TAG, "Add interface "+name+", "+
					interfaceAddress.getHostAddress()+"/"+prefixLen+" ("+
					broadcastAddress.getHostAddress()+")");
		} catch (UnknownHostException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private void onRemove(String name, byte[] addr, byte[] broadcast, int prefixLen){
		try {
			InetAddress interfaceAddress = InetAddress.getByAddress(addr);
			InetAddress broadcastAddress = InetAddress.getByAddress(addr);
			Log.v(TAG, "Remove interface "+name+", "+
					interfaceAddress.getHostAddress()+"/"+prefixLen+" ("+
					broadcastAddress.getHostAddress()+")");
		} catch (UnknownHostException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
}
