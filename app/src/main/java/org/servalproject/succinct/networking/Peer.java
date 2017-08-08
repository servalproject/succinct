package org.servalproject.succinct.networking;

import org.servalproject.succinct.networking.messages.StoreState;

import java.util.HashMap;
import java.util.Map;

public class Peer {
	public final PeerId id;
	private StoreState storeState;

	public final Map<Object, PeerLink> networkLinks = new HashMap<>();

	Peer(PeerId id){
		this.id = id;
	}

	public void setStoreState(StoreState state){
		// TODO filter on team?
		if (storeState!=null && storeState.equals(state))
			return;

		storeState = state;
	}
}
