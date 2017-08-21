package org.servalproject.succinct.networking;

import android.util.Log;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

public abstract class NioHandler<T extends SelectableChannel> {
	public final T channel;
	protected NioLoop loop;
	protected SelectionKey key;
	private static final String TAG = "NioHandler";

	protected NioHandler(T channel) {
		this.channel = channel;
	}

	public void accept(NioLoop loop) throws IOException{
	}

	public void read() throws IOException {
	}

	public void write() throws IOException {
	}

	public void connect() throws IOException {
	}

	public void setInterest(int ops){
		if (key == null || ops == key.interestOps())
			return;
		key.interestOps(ops);
		loop.wakeUp();
	}

	void setRegistration(NioLoop loop, SelectionKey key) {
		this.loop = loop;
		this.key = key;
	}

	public void close(){
		try {
			channel.close();
		} catch (IOException e) {
			Log.v(TAG, e.getMessage(), e);
		}
	}
}
