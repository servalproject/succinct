package org.servalproject.succinct.networking;

import android.os.SystemClock;

import java.net.SocketAddress;

public class PeerSocketLink extends PeerLink {
	public final IPInterface network;
	public final SocketAddress addr;
	public long lastHeard = -1;
	public int lastHeardSeq = -1;
	public long lastHeardUnicast =-1;
	public long lastHeardBroadcast =-1;

	// assume broadcasts are filtered until we hear an ack
	public long lastAckTime=-1;
	public int lastAckSeq = -1;
	public int ackUnicastCount=0;
	public boolean ackedUnicast=false;
	public int ackBroadcastCount=0;
	public boolean ackedBroadcast=false;
	public int unicastPackets=0;
	public int broadcastPackets=0;

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
		return heardBroadcast(SystemClock.elapsedRealtime());
	}

	public boolean heardBroadcast(long elapsedTime){
		return (elapsedTime - lastHeardBroadcast) < Networks.HEARTBEAT_MS *3;
	}

	public boolean heardUnicast(){
		return heardUnicast(SystemClock.elapsedRealtime());
	}
	public boolean heardUnicast(long elapsedTime){
		return (elapsedTime - lastHeardUnicast) < Networks.HEARTBEAT_MS *6;
	}

	public boolean isDead(){
		return isDead(SystemClock.elapsedRealtime());
	}
	public boolean isDead(long elapsedTime){
		return (elapsedTime - Math.max(lastHeardBroadcast, lastHeardUnicast)) > Networks.HEARTBEAT_MS *6;
	}

	public boolean theyAckedUnicast(){
		return theyAckedUnicast(SystemClock.elapsedRealtime());
	}
	public boolean theyAckedUnicast(long elapsedTime) {
		return ackedUnicast && (elapsedTime - lastAckTime) < Networks.HEARTBEAT_MS *6;
	}

	public boolean theyAckedBroadcast(){
		return theyAckedBroadcast(SystemClock.elapsedRealtime());
	}
	public boolean theyAckedBroadcast(long elapsedTime) {
		return ackedBroadcast && (elapsedTime - lastAckTime) < Networks.HEARTBEAT_MS *6;
	}

	@Override
	public String toString() {
		long elapsedTime = SystemClock.elapsedRealtime();
		StringBuilder sb = new StringBuilder();
		sb
			.append(addr.toString())
			.append(" IN: U")
			.append(unicastPackets)
			.append(heardUnicast(elapsedTime)?"*":"")
			.append(" B")
			.append(broadcastPackets)
			.append(heardBroadcast(elapsedTime)?"*":"")
			.append(" ACK:")
			.append(theyAckedUnicast(elapsedTime)?'U':' ')
			.append(theyAckedBroadcast(elapsedTime)?'B':' ')
		;
		return sb.toString();
	}
}
