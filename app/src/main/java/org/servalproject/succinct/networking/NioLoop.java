package org.servalproject.succinct.networking;


import android.util.Log;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

public class NioLoop implements Runnable{
	private Selector selector;
	private static final String TAG = "NioLoop";
	private Thread workingThread;

	public NioLoop() throws IOException {
		selector = Selector.open();
	}

	public void wakeUp(){
		if (workingThread != null && workingThread != Thread.currentThread())
			selector.wakeup();
	}

	public <T extends SelectableChannel> SelectionKey register(int ops, NioHandler<T> handler) throws IOException {
		handler.channel.configureBlocking(false);
		SelectionKey key = handler.channel.register(selector, ops & handler.channel.validOps(), handler);
		handler.setRegistration(this, key);
		wakeUp();
		return key;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		workingThread = Thread.currentThread();
		try {
			while(true){
				selector.select();
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
	}

}
