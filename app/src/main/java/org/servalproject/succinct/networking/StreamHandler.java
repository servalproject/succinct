package org.servalproject.succinct.networking;

import android.os.SystemClock;

import org.servalproject.succinct.networking.messages.Message;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public abstract class StreamHandler extends NioHandler<SocketChannel> {
	private ByteBuffer readBuffer;
	private ByteBuffer writeBuffer;
	protected long lastRead =-1;
	protected long lastWrite =-1;

	protected StreamHandler(SocketChannel channel) {
		super(channel);
		readBuffer = ByteBuffer.allocate(Message.MTU);
		writeBuffer = ByteBuffer.allocate(Message.MTU);
		writeBuffer.flip();
	}

	public int getInterest(){
		int ops = 0;
		if (channel.isConnectionPending())
			ops|=SelectionKey.OP_CONNECT;
		else if(channel.isConnected()){
			if (readBuffer.hasRemaining())
				ops|=SelectionKey.OP_READ;
			if (writeBuffer.hasRemaining())
				ops|=SelectionKey.OP_WRITE;
		}
		return ops;
	}

	private void setInterest(){
		setInterest(getInterest());
	}

	@Override
	public void read() throws IOException {
		int read = channel.read(readBuffer);
		if (read == -1) {
			close();
			return;
		}
		lastRead = SystemClock.elapsedRealtime();
		readBuffer.flip();
		emptyReadBuffer(readBuffer);
		readBuffer.compact();
		setInterest();
	}

	protected abstract void emptyReadBuffer(ByteBuffer readBuffer) throws ProtocolException;

	@Override
	public void write() throws IOException {
		synchronized (writeBuffer) {
			int wrote = channel.write(writeBuffer);
			if (wrote == -1) {
				close();
				return;
			}
			lastWrite = SystemClock.elapsedRealtime();
		}
		tryFill();
	}

	public void tryFill(){
		synchronized (writeBuffer) {
			writeBuffer.compact();
			fillWriteBuffer(writeBuffer);
			writeBuffer.flip();
		}
		setInterest();
	}

	protected abstract void fillWriteBuffer(ByteBuffer writeBuffer);

	@Override
	public void connect() throws IOException {
		if (channel.finishConnect())
			setInterest();
	}
}
