package org.servalproject.succinct.networking;


import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import org.servalproject.succinct.App;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

public class NioLoop implements Runnable{
	private final PowerManager.WakeLock wakeLock;
	private boolean busy = false;
	private boolean locked = false;
	private Selector selector;
	private static final String TAG = "NioLoop";
	private Thread workingThread;

	public NioLoop(Context context) throws IOException {
		selector = Selector.open();
		PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
	}

	public void wakeUp(){
		if (workingThread != null && workingThread != Thread.currentThread()) {
			acquire();
			selector.wakeup();
		}
	}

	public <T extends SelectableChannel> SelectionKey register(int ops, NioHandler<T> handler) throws IOException {
		handler.channel.configureBlocking(false);
		SelectionKey key = handler.channel.register(selector, ops & handler.channel.validOps(), handler);
		handler.setRegistration(this, key);
		wakeUp();
		return key;
	}

	private final Runnable releaseLock = new Runnable() {
		@Override
		public void run() {
			release();
		}
	};

	private synchronized void release(){
		if (locked && !busy){
			locked = false;
			wakeLock.release();
		}
	}

	private synchronized void acquire(){
		busy = true;
		if (!locked){
			wakeLock.acquire();
			locked = true;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		workingThread = Thread.currentThread();
		try {
			while(true){
				busy = false;
				App.backgroundHandler.removeCallbacks(releaseLock);
				App.backgroundHandler.postDelayed(releaseLock,1);
				selector.select();
				acquire();

				Iterator<SelectionKey> i = selector.selectedKeys().iterator();
				while(i.hasNext()){
					SelectionKey key = i.next();

					NioHandler handler = (NioHandler) key.attachment();
					try {
						if (key.isAcceptable())
							handler.accept(this);
						else if (key.isConnectable())
							handler.connect();
						else if (key.isReadable())
							handler.read();
						else if (key.isWritable())
							handler.write();
						i.remove();
					}catch (IOException e){
						Log.e(TAG, e.getMessage(), e);
					}
				}
			}
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		workingThread = null;
		busy = false;
		release();
	}

}
