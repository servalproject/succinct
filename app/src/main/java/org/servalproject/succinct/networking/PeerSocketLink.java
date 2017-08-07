package org.servalproject.succinct.networking;

import android.os.SystemClock;

import java.net.SocketAddress;

public class PeerSocketLink extends PeerLink {
	public final IPInterface network;
	public final SocketAddress addr;
	public long lastHeard = -1;
	public long lastHeardUnicast =-1;
	public long lastHeardBroadcast =-1;

	// assume broadcasts are filtered until we hear an ack
	public long lastAckTime=-1;
	public boolean ackedUnicast=false;
	public boolean ackedBroadcast=false;

	PeerSocketLink(IPInterface network, SocketAddress addr){
		this.network = network;
		this.addr = addr;
	}

	// Expected lifecycle;
	// on device without packet filter, receive first broadcast from peer

	// send ack in broadcast heartbeat
	// send ack in unicast too

	// receive packet containing ack from above (even if broadcast filtered)
	// reply with ack (as above)

	// two way link established

	// if device enables broadcast filter;
	// fall back to sending unicast ack until timeout
	// keep unicast link working

	// if device disables filter;
	// revert to broadcast again.

	public boolean heardBroadcast(){
		return (SystemClock.elapsedRealtime() - lastHeardBroadcast) < Networks.HEARTBEAT_MS *3;
	}

	public boolean heardUnicast(){
		return (SystemClock.elapsedRealtime() - lastHeardUnicast) < Networks.HEARTBEAT_MS *6;
	}

	public boolean isDead(){
		return (SystemClock.elapsedRealtime() - Math.max(lastHeardBroadcast, lastHeardUnicast)) > Networks.HEARTBEAT_MS *6;
	}

	public boolean theyAckedUnicast() {
		return ackedUnicast && (SystemClock.elapsedRealtime() - lastAckTime) < Networks.HEARTBEAT_MS *6;
	}

	public boolean theyAckedBroadcast() {
		return ackedBroadcast && (SystemClock.elapsedRealtime() - lastAckTime) < Networks.HEARTBEAT_MS *6;
	}
}
