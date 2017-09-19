package org.servalproject.succinct.networking;

import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.messages.FileBlock;
import org.servalproject.succinct.networking.messages.RequestBlock;
import org.servalproject.succinct.networking.messages.StoreState;
import org.servalproject.succinct.networking.messages.SyncMsg;
import org.servalproject.succinct.storage.PeerTransfer;
import org.servalproject.succinct.storage.RecordStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Peer {
	public final App appContext;
	public final PeerId id;
	private StoreState storeState;
	private long syncState=0;
	PeerConnection connection;
	private long requested;
	private long received;
	private static final long maxRequest = 1024*256;
	private List<PeerTransfer> possibleTransfers = new ArrayList<>();

	private static final String TAG = "Peer";
	public final Map<Object, PeerLink> networkLinks = new HashMap<>();

	Peer(App appContext, PeerId id){
		this.appContext = appContext;
		this.id = id;
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
		if (connection == null){
			// TODO initiate connection
			for(PeerLink l:networkLinks.values()){
				try {
					if (l instanceof PeerSocketLink){
						PeerSocketLink link = (PeerSocketLink)l;
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

	public void setConnection(PeerConnection connection){
		// TODO try to keep only one active connection?
		this.connection = connection;
	}

	// from JNI, send these bytes to this peer so they can process them
	private void syncMessage(byte[] message){
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
			possibleTransfers.add(new PeerTransfer(this, file, filename, length, hash));
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
				continue;
			}

			if (transfer.file.setTranfer(transfer)){
				long length = transfer.newLength - EOF;
				Log.v(TAG, "Requesting "+transfer.filename+" "+EOF+" +"+length);
				connection.queue(new RequestBlock(transfer.filename, EOF, length));
				requested+=length;
			}
			i++;
		}
	}

	private native long processSyncMessage(long ptr, long syncState, byte[] message);

	public void processSyncMessage(byte[] message){
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
		nextTransfer();
	}

	public boolean isAlive(){
		return connection!=null || !networkLinks.isEmpty();
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
	}
}
