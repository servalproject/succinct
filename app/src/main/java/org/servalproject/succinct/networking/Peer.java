package org.servalproject.succinct.networking;

import android.os.SystemClock;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.messages.Ack;
import org.servalproject.succinct.networking.messages.FileBlock;
import org.servalproject.succinct.networking.messages.Header;
import org.servalproject.succinct.networking.messages.Message;
import org.servalproject.succinct.networking.messages.RequestBlock;
import org.servalproject.succinct.networking.messages.StoreState;
import org.servalproject.succinct.networking.messages.SyncMsg;
import org.servalproject.succinct.storage.PeerTransfer;
import org.servalproject.succinct.storage.RecordStore;
import org.servalproject.succinct.utils.ChangedObservable;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Observable;

public class Peer {
	public final App appContext;
	public final PeerId id;
	private StoreState storeState;
	private long syncState=0;
	PeerConnection connection;
	private long requested;
	private long received;
	private long transmitting;
	private long transmitted;
	private static final long maxRequest = 1024*256;
	private List<PeerTransfer> possibleTransfers = new ArrayList<>();

	private static final String TAG = "Peer";
	final Map<Object, PeerLink> networkLinks = new HashMap<>();
	public final Observable observable = new ChangedObservable();

	Peer(App appContext, PeerId id){
		this.appContext = appContext;
		this.id = id;
	}

	public Collection<PeerLink> getLinks(){
		return networkLinks.values();
	}

	private native void queueRootMessage(long ptr);

	public void setStoreState(StoreState state){
		// ignore store's from other teams
		if (appContext.teamStorage == null)
			return;
		if (!appContext.teamStorage.teamId.equals(state.teamId))
			return;

		if (storeState!=null && storeState.equals(state))
			return;

		// TODO should we avoid sending if changed to equal?
		boolean sendRoot = (storeState != null || !state.equals(appContext.teamStorage.getState()));

		storeState = state;
		if (sendRoot)
			queueRootMessage(appContext.teamStorage.ptr);
	}

	public PeerConnection getConnection(){
		if (connection == null || connection.shutdown){
			// TODO initiate connection
			for(PeerLink l:networkLinks.values()){
				try {
					if (l instanceof PeerSocketLink){
						PeerSocketLink link = (PeerSocketLink)l;
						Log.v(TAG, "Initiating connection");
						connection = appContext.networks.connectLink(this, link);
						break;
					}
				} catch (IOException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
		}
		return connection;
	}

	public synchronized void setConnection(PeerConnection connection){
		if (this.connection == connection)
			return;

		if (this.connection!=null) {
			if (connection!=null && connection.initiated != this.connection.initiated && !this.connection.shutdown) {
				int cmp = appContext.networks.myId.compare(id);
				if (connection.initiated)
					cmp = -cmp;
				if (cmp<0) {
					Log.v(TAG, "Shutdown due to *not* replacing connection");
					connection.shutdown();
					return;
				}
			}
			Log.v(TAG, "Shutdown due to replacing connection");
			this.connection.shutdown();
		}
		this.connection = connection;
	}

	void processAck(PeerId myId, PeerSocketLink link, Ack msg) {
		for(Ack.LinkAck linkAck : msg.links){
			if (!linkAck.id.equals(myId))
				continue;

			link.lastAckTime = SystemClock.elapsedRealtime();
			link.lastAckSeq = linkAck.seq;
			if (linkAck.unicast)
				link.ackUnicastCount++;
			if (linkAck.broadcast)
				link.ackBroadcastCount++;
			link.ackedUnicast = linkAck.unicast;
			link.ackedBroadcast = linkAck.broadcast;
			observable.notifyObservers(link);
		}
	}

	public static final int BROADCAST = 1;
	public static final int RECENT_BROADCAST = 2;
	public static final int RECENT_UNICAST = 4;
	public static final int HEARD = 0x7;
	public static final int ACK_BROADCAST = 0x10;
	public static final int RECENT_ACK_BROADCAST = 0x20;
	public static final int RECENT_ACK_UNICAST = 0x40;
	public static final int ACKED = 0x70;

	public int getLinkState(){
		int ret = 0;
		long elapsed = SystemClock.elapsedRealtime();
		for(PeerLink l : networkLinks.values()){
			if (l instanceof PeerSocketLink){
				PeerSocketLink link = (PeerSocketLink)l;
				if (link.broadcastPackets>0)
					ret|=BROADCAST;
				if (link.heardBroadcast(elapsed))
					ret|=RECENT_BROADCAST;
				if (link.heardUnicast(elapsed))
					ret|=RECENT_UNICAST;

				if (link.ackBroadcastCount>0)
					ret|=ACK_BROADCAST;
				if (link.theyAckedBroadcast(elapsed))
					ret|=RECENT_ACK_BROADCAST;
				if (link.theyAckedUnicast(elapsed))
					ret|=RECENT_ACK_UNICAST;

			}
		}
		return ret;
	}

	// from JNI, send these bytes to this peer so they can process them
	private void syncMessage(byte[] message){
		Log.v(TAG, "Queue "+Hex.toString(message));
		getConnection().queue(new SyncMsg(message));
	}

	// from JNI, peer has this version of this file
	// could be older, newer or the same as ours
	private void peerHas(byte[] hash, String filename, long length){
		Log.v(TAG, "Peer has "+filename+", "+length);
		try{
			RecordStore file = appContext.teamStorage.openFile(filename);
			if (length <= file.EOF)
				return;
			PeerTransfer active = file.activeTransfer;
			if (active!=null && length <= active.newLength)
				return;
			possibleTransfers.add(new PeerTransfer(this, file, filename, length, hash));
			observable.notifyObservers();
			nextTransfer();
		}catch (IOException e){
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private void nextTransfer(){
		PeerConnection connection = getConnection();
		int i=0;
		while (requested - received <= maxRequest && i < possibleTransfers.size()){
			PeerTransfer transfer = possibleTransfers.get(i);
			long EOF = transfer.file.EOF;
			if (transfer.newLength <= EOF) {
				possibleTransfers.remove(i);
				observable.notifyObservers();
				continue;
			}

			if (transfer.file.setTranfer(transfer)){
				long length = transfer.newLength - EOF;
				Log.v(TAG, "Requesting "+transfer.filename+" "+EOF+" +"+length);
				connection.queue(new RequestBlock(transfer.filename, EOF, length));
				requested+=length;
				observable.notifyObservers();
			}
			i++;
		}
	}

	private native long processSyncMessage(long ptr, long syncState, byte[] message);

	public void processSyncMessage(byte[] message){
		Log.v(TAG, "Process "+Hex.toString(message));
		if (appContext.teamStorage!=null)
			syncState = processSyncMessage(appContext.teamStorage.ptr, syncState, message);
	}

	public void processRequest(RequestBlock request){
		try {
			RecordStore file = appContext.teamStorage.openFile(request.filename);
			String filename = request.filename;
			long offset = request.offset;
			long length = request.length;

			// sanity checks...
			if (offset >= file.EOF)
				return;

			if (offset + length >= file.EOF)
				length = file.EOF - offset;

			if (length<=0)
				return;

			Log.v(TAG, "Sending "+filename+" "+offset+" +"+length);
			transmitting+=length;
			getConnection().queue(new FileBlock(filename, offset, length, file));
		} catch (IOException e) {
			Log.v(TAG, e.getMessage(), e);
		}
	}

	public void processData(FileBlock fileBlock){
		received+=fileBlock.length;
		try{
			RecordStore file = appContext.teamStorage.openFile(fileBlock.filename);
			file.appendAt(fileBlock.offset, fileBlock.data);
		} catch (IOException e) {
			Log.v(TAG, e.getMessage(), e);
		}
		observable.notifyObservers();
		nextTransfer();
	}

	public boolean isAlive(){
		return connection!=null || !networkLinks.isEmpty();
	}

	public void checkLinks(){
		Iterator<Map.Entry<Object, PeerLink>> li = networkLinks.entrySet().iterator();
		boolean died=false;
		while(li.hasNext()){
			Map.Entry<Object, PeerLink> el = li.next();
			PeerLink l = el.getValue();

			if (l instanceof PeerSocketLink) {
				PeerSocketLink link = (PeerSocketLink) l;
				if (link.isDead() || networkLinks.isEmpty() || (link.network!=null && !link.network.up)) {
					died = true;
					Log.v(TAG, "Dead peer link from "+link.addr+
							" ("+(link.network == null ? "null" : link.network.up)+
							", "+link.heardBroadcast()+", "+link.heardUnicast()+")");
					li.remove();
					observable.notifyObservers(link);
				}
			}
		}
		if (died && networkLinks.isEmpty() && this.connection!=null){
			Log.v(TAG, "Shutdown due to dead link");
			this.connection.shutdown();
			this.connection = null;
		}
	}

	public void linksDied() {
		if (syncState!=0)
			processSyncMessage(null);

		while(!possibleTransfers.isEmpty()){
			PeerTransfer transfer = possibleTransfers.get(0);
			possibleTransfers.remove(0);
			transfer.file.cancel(transfer);
		}
		requested=0;
		received=0;
		transmitting=0;
		observable.notifyObservers();
	}

	PeerSocketLink processHeader(IPInterface network, SocketAddress addr, Header hdr){
		PeerSocketLink link = (PeerSocketLink) networkLinks.get(addr);
		long now = SystemClock.elapsedRealtime();

		if (link == null){
			Log.v(TAG, "New peer link from "+addr);
			link = new PeerSocketLink(network, addr);
			networkLinks.put(addr, link);
		}

		link.lastHeard = now;
		link.lastHeardSeq = hdr.seq;
		if (hdr.unicast) {
			link.unicastPackets++;
			link.lastHeardUnicast = now;
		}else {
			link.broadcastPackets++;
			link.lastHeardBroadcast = now;
		}
		// TODO detect if link state actually changed?
		observable.notifyObservers(link);
		return link;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(id.toString());

		if (requested>received || transmitting>transmitted)
			sb.append(" Syncing");

		if (!isAlive())
			sb.append(" DEAD");

		return sb.toString();
	}

	public void wrote(Message msg) {
		if (msg instanceof FileBlock){
			FileBlock req = (FileBlock)msg;
			transmitted += req.wrote;
			observable.notifyObservers();
		}
	}
}
