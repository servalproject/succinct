package org.servalproject.succinct.networking;

import java.util.HashMap;
import java.util.Map;

public class Peer {
	public final PeerId id;

	public final Map<Object, PeerLink> networkLinks = new HashMap<>();

	Peer(PeerId id){
		this.id = id;
	}
}
