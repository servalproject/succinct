package org.servalproject.succinct.networking;

import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.messages.StoreState;
import org.servalproject.succinct.networking.messages.SyncMsg;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Peer {
	private final App appContext;
	public final PeerId id;
	private StoreState storeState;
	private long syncState=0;
	PeerConnection connection;

	private static final String TAG = "Peer";
	public final Map<Object, PeerLink> networkLinks = new HashMap<>();

	Peer(App appContext, PeerId id){
		this.appContext = appContext;
		this.id = id;
	}

	private native void queueRootMessage(long ptr);

	public void setStoreState(StoreState state){
		if (!appContext.teamStorage.team.equals(state.team))
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
	private void peerHas(String filename, long length){
		Log.v(TAG, "Peer has "+filename+", "+length);
	}

	private native long processSyncMessage(long ptr, long syncState, byte[] message);

	public void processSyncMessage(byte[] message){
		syncState = processSyncMessage(appContext.teamStorage.ptr, syncState, message);
	}

	public void linksDied() {
		if (syncState!=0)
			processSyncMessage(null);
	}
}
