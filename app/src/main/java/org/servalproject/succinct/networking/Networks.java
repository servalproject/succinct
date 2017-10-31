package org.servalproject.succinct.networking;


import android.app.AlarmManager;
import android.os.SystemClock;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.messages.Ack;
import org.servalproject.succinct.networking.messages.Header;
import org.servalproject.succinct.networking.messages.Message;
import org.servalproject.succinct.networking.messages.RequestTeam;
import org.servalproject.succinct.networking.messages.StoreState;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.utils.ChangedObservable;
import org.servalproject.succinct.utils.WakeAlarm;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Observable;

public class Networks {
	private static final int PORT = 4043;
	public static final int HEARTBEAT_MS = 5000; // Network heartbeat
	private static final String ALARM_ACTION = "org.servalproject.succinct.HEARTBEAT_ALARM";
	private static final String TAG = "Networks";
	private static final int MTU = 1400;

	private final App appContext;
	private final DatagramChannel dgram;
	private final WakeAlarm alarm;
	public final PeerId myId;
	private final NioLoop nioLoop;

	public final Map<String, IPInterface> networks = new HashMap<>();
	private final Map<PeerId, Peer> peers = new HashMap<>();
	public final Observable observePeers = new ChangedObservable();

	// track the set of known team id's, and which peer we should contact to ask about it
	private final HashMap<PeerId, Team> knownTeams = new HashMap<>();
	public final Observable teams = new ChangedObservable();

	private native void beginPolling();

	private boolean backgroundEnabled = true;

	private int seq=0;

	private static Networks instance;
	public static Networks getInstance(){
		return instance;
	}

	public static Networks init(App appContext, PeerId myId) throws IOException {
		if (instance != null)
			throw new IllegalStateException("Already created");

		return instance = new Networks(appContext, myId);
	}

	private Networks(App context, PeerId myId) throws IOException {
		this.appContext = context;
		this.nioLoop = new NioLoop(context);
		this.myId = myId;

		InetSocketAddress addr = new InetSocketAddress(PORT);

		this.dgram = DatagramChannel.open();
		dgram.socket().bind(addr);
		dgram.socket().setBroadcast(true);
		NioHandler<DatagramChannel> dgramHandler = new NioHandler<DatagramChannel>(dgram){
			ByteBuffer buff = ByteBuffer.allocate(MTU);
			@Override
			public void read() throws IOException{
				buff.clear();
				InetSocketAddress addr = (InetSocketAddress)dgram.receive(buff);
				buff.flip();

				// TODO protocol versioning by source port?
				if (addr.getPort()!=PORT)
					return;

				IPInterface receiveInterface=null;
				for(IPInterface i : networks.values()){
					if (i.isInSubnet(addr.getAddress())) {
						receiveInterface = i;
						break;
					}
				}
				if (receiveInterface == null) {
					Log.w(TAG, "Could not find valid interface for "+addr.getAddress());
					return;
				}

				process(receiveInterface, addr, buff);
			}
		};
		nioLoop.register(SelectionKey.OP_READ, dgramHandler);

		ServerSocketChannel channel = ServerSocketChannel.open();
		channel.socket().bind(addr);
		NioHandler<ServerSocketChannel> acceptHandler = new NioHandler<ServerSocketChannel>(channel) {
			@Override
			public void accept(NioLoop loop) throws IOException {
				SocketChannel client = channel.accept();
				PeerConnection connection = new PeerConnection(Networks.this, client);
				loop.register(connection.getInterest(), connection);
			}
		};
		nioLoop.register(SelectionKey.OP_ACCEPT, acceptHandler);

		alarm = WakeAlarm.getAlarm(context, "Heartbeat", App.backgroundHandler, onAlarm);

		new Thread(nioLoop, "Networking").start();

		App.backgroundHandler.post(new Runnable() {
			@Override
			public void run() {
				beginPolling();
			}
		});
	}

	public Peer getPeer(PeerId id){
		if (myId.equals(id))
			return null;
		return peers.get(id);
	}

	public Peer createPeer(PeerId id){
		if (myId.equals(id))
			return null;
		Peer peer = peers.get(id);
		if (peer == null){
			peer = new Peer(appContext, id);
			peers.put(id, peer);
			observePeers.notifyObservers(peer);
			Log.v(TAG, "New peer");
			setAlarm(10);
		}
		return peer;
	}

	public Collection<Peer> getPeers(){
		return peers.values();
	}

	private void process(IPInterface network, SocketAddress addr, ByteBuffer buff) {
		// first message should always be a Header
		Header hdr = (Header)Message.parseMessage(buff);
		if (hdr == null) {
			Log.w(TAG, "Unable to parse header!");
			return;
		}
		Peer peer = createPeer(hdr.id);
		if (peer == null) {
			// TODO double check that the packet came from one of our interfaces?
			return;
		}

		PeerSocketLink link = peer.processHeader(network, addr, hdr);

		while(buff.hasRemaining()){
			Message msg = Message.parseMessage(buff);
			if (msg == null)
				break;

			switch (msg.type){
				case AckMessage:
					peer.processAck(myId, link, (Ack)msg);
					break;
				case StoreStateMessage:
					processStoreState(peer, (StoreState)msg);
					break;
			}
			msg.process(peer);
		}
	}

	public Collection<Team> getTeams(){
		return knownTeams.values();
	}

	public void processTeamMessage(Team team){
		Log.v(TAG, "Storing info about team "+team.id+" "+team.name);
		knownTeams.put(team.id, team);
		teams.notifyObservers(team);
	}

	private void processStoreState(Peer peer, StoreState state){
		if (knownTeams.containsKey(state.teamId))
			return;
		Log.v(TAG, "Asking for information about "+state.teamId);
		peer.getConnection().queue(new RequestTeam(state.teamId));
	}

	public void setAlarm(int delay){
		if (networks.isEmpty() || !backgroundEnabled)
			return;

		alarm.setAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime()+delay);
	}

	// called from JNI
	private void onAdd(String name, byte[] addr, byte[] broadcast, int prefixLen){
		try {
			IPInterface network = new IPInterface(name, addr, broadcast, prefixLen);
			Log.v(TAG, "Add "+network);

			// Only send broadcasts on 80211 interfaces
			if (!new File("/sys/class/net/"+name+"/phy80211").exists())
				return;

			networks.put(name, network);

			// wait a little while for the kernel to finish bringing the interface up
			setAlarm(10);
		} catch (UnknownHostException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	// called from JNI
	private void onRemove(String name, byte[] addr, byte[] broadcast, int prefixLen){
		if (!networks.containsKey(name))
			return;

		IPInterface network = networks.get(name);
		network.up = false;
		Log.v(TAG, "Remove "+network);

		networks.remove(name);
		if (networks.isEmpty())
			alarm.cancel();
		trimDead();
	}

	private void trimDead(){
		Iterator<Map.Entry<PeerId, Peer>> pi = peers.entrySet().iterator();
		while(pi.hasNext()){
			Map.Entry<PeerId, Peer> ep = pi.next();
			Peer p = ep.getValue();

			p.checkLinks();

			if (!p.isAlive()) {
				p.linksDied();
				pi.remove();
				observePeers.notifyObservers(p);
			}
		}
	}

	private PeerSocketLink sendUnicastAck(Peer peer){
		PeerSocketLink unicastLink = null;
		for(PeerLink l:peer.networkLinks.values()){
			if (l instanceof PeerSocketLink){
				PeerSocketLink link = (PeerSocketLink)l;
				if (link.theyAckedBroadcast())
					return null;
				unicastLink = link;
			}
		}
		return unicastLink;
	}

	private Runnable onAlarm = new Runnable() {
		@Override
		public void run() {

			if (backgroundEnabled && !networks.isEmpty()) {
				trimDead();
				int seq = Networks.this.seq++;

				Header hdr = new Header(myId, false, seq & 0xFFFF);
				Header unicastHdr = new Header(myId, true, seq & 0xFFFF);

				// assemble a broadcast heartbeat packet
				ByteBuffer buff = ByteBuffer.allocate(MTU);
				hdr.write(buff);

				// TODO in a crowded network, all link acks might not fit in a single packet
				Ack ack = new Ack();
				for(Peer p : peers.values()){
					for(PeerLink l : p.networkLinks.values()){
						if (l instanceof PeerSocketLink){
							PeerSocketLink link = (PeerSocketLink)l;
							ack.add(p, link);
						}
					}
				}
				if (!ack.links.isEmpty())
					ack.write(buff);

				StoreState state = null;
				if (appContext.teamStorage != null)
					state = appContext.teamStorage.getState();
				if (state != null)
					state.write(buff);

				buff.flip();
				for (IPInterface i : networks.values()) {
					try {
						//Log.v(TAG, "Heartbeat B "+i.broadcastAddress);
						dgram.send(buff, new InetSocketAddress(i.broadcastAddress, PORT));
					} catch (SecurityException se) {
						Log.e(TAG, se.getMessage(), se);
					} catch (IOException e) {
						Log.e(TAG, e.getMessage(), e);
					}
				}

				// Send unicast heartbeats when we haven't heard recent confirmation of broadcast reception
				for(Peer p:peers.values()){
					PeerSocketLink link = sendUnicastAck(p);
					if (link == null)
						continue;

					buff.clear();
					unicastHdr.write(buff);
					ack = new Ack();
					ack.add(p, link);
					ack.write(buff);
					if (state != null)
						state.write(buff);
					buff.flip();

					try {
						//Log.v(TAG, "Heartbeat U "+link.addr);
						dgram.send(buff, link.addr);
					} catch (SecurityException se) {
						Log.e(TAG, se.getMessage(), se);
					} catch (IOException e) {
						Log.e(TAG, e.getMessage(), e);
					}
				}

				// send one unicast probe per interface
				buff.clear();
				unicastHdr.write(buff);
				buff.flip();

				for (IPInterface i : networks.values()) {
					try {
						// TODO skip addresses for peers we already know about
						InetAddress addr = i.nextAddress();
						//Log.v(TAG, "Probe U "+addr);
						dgram.send(buff, new InetSocketAddress(addr, PORT));
					} catch (SecurityException se) {
						Log.e(TAG, se.getMessage(), se);
					} catch (IOException e) {
						Log.e(TAG, e.getMessage(), e);
					}
				}

				setAlarm(HEARTBEAT_MS);
			}
		}
	};

	public PeerConnection connectLink(Peer peer, PeerSocketLink link) throws IOException {
		SocketChannel channel = SocketChannel.open();
		PeerConnection connection = new PeerConnection(this, channel, peer);
		nioLoop.register(connection.getInterest(), connection);
		channel.connect(link.addr);
		return connection;
	}
}
