package org.servalproject.succinct.networking;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.messages.Ack;
import org.servalproject.succinct.networking.messages.Header;
import org.servalproject.succinct.networking.messages.Message;
import org.servalproject.succinct.networking.messages.StoreState;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class Networks {
	private static final int PORT = 4042;
	public static final int HEARTBEAT_MS = 5000; // Network heartbeat
	private static final String ALARM_ACTION = "org.servalproject.succinct.HEARTBEAT_ALARM";
	private static final String TAG = "Networks";
	private static final int MTU = 1400;

	private final App appContext;
	private final DatagramChannel dgram;
	private final AlarmManager am;
	private final PowerManager.WakeLock wakeLock;
	public final PeerId myId;
	private final NioLoop nioLoop;

	public final Set<IPInterface> networks = new HashSet<>();
	private final Map<PeerId, Peer> peers = new HashMap<>();
	private native void beginPolling();

	// TODO, enabling will slowly drain battery...
	private boolean backgroundEnabled = true;

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
		this.nioLoop = new NioLoop();
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
				for(IPInterface i : networks){
					if (i.isInSubnet(addr.getAddress())) {
						receiveInterface = i;
						break;
					}
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

		am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			IntentFilter f = new IntentFilter();
			f.addAction(ALARM_ACTION);
			context.registerReceiver(new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					wakeLock.acquire();
					alarmIntent = null;
					App.backgroundHandler.post(new Runnable() {
						@Override
						public void run() {
							onAlarm();
						}
					});
				}
			}, f);
		}

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
		Peer peer = peers.get(id);
		if (peer == null){
			peer = new Peer(appContext, id);
			peers.put(id, peer);
			Log.v(TAG, "New peer");
			setAlarm(10);
		}
		return peer;
	}

	private void process(IPInterface network, SocketAddress addr, ByteBuffer buff) {
		// first message should always be a Header
		Header hdr = (Header)Message.parseMessage(buff);
		if (hdr == null)
			throw new IllegalStateException("Must have a header?");
		Peer peer = getPeer(hdr.id);
		if (peer == null) {
			// TODO double check that the packet came from one of our interfaces?
			return;
		}

		PeerSocketLink link = (PeerSocketLink) peer.networkLinks.get(addr);
		if (link == null){
			Log.v(TAG, "New peer link from "+addr);
			link = new PeerSocketLink(network, addr);
			peer.networkLinks.put(addr, link);
		}

		if (hdr.unicast)
			link.lastHeardUnicast = SystemClock.elapsedRealtime();
		else
			link.lastHeardBroadcast = SystemClock.elapsedRealtime();

		while(buff.hasRemaining()){
			Message msg = Message.parseMessage(buff);
			if (msg == null)
				break;

			if (msg.type == Message.Type.AckMessage)
				processAck(peer, link, (Ack)msg);
			else
				msg.process(peer);
		}
	}

	private void processAck(Peer peer, PeerSocketLink link, Ack msg) {
		for(Ack.LinkAck linkAck : msg.links){
			if (!linkAck.id.equals(myId))
				continue;

			link.lastAckTime = SystemClock.elapsedRealtime();
			link.ackedUnicast = linkAck.unicast;
			link.ackedBroadcast = linkAck.broadcast;
		}
	}

	private PendingIntent alarmIntent=null;
	private AlarmManager.OnAlarmListener listener=null;
	private long nextAlarm=-1;
	public void setAlarm(int delay){
		if (networks.isEmpty() || !backgroundEnabled)
			return;

		long newAlarm = SystemClock.elapsedRealtime()+delay;
		// Don't delay an alarm that has already been set
		if (nextAlarm!=-1 && newAlarm > nextAlarm)
			return;

		nextAlarm = newAlarm;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			if (listener == null){
				listener = new AlarmManager.OnAlarmListener() {
					@Override
					public void onAlarm() {
						wakeLock.acquire();
						Networks.this.onAlarm();
					}
				};
			}
			am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					newAlarm,
					"Heartbeat",
					listener,
					App.backgroundHandler);
		}else{
			alarmIntent = PendingIntent.getBroadcast(
					appContext,
					0,
					new Intent(ALARM_ACTION),
					PendingIntent.FLAG_UPDATE_CURRENT);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
						newAlarm,
						alarmIntent);
			}else{
				am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
						newAlarm,
						alarmIntent);
			}
		}
	}

	private void cancelAlarm(){
		nextAlarm = -1;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			if (listener!=null)
				am.cancel(listener);
		}else{
			if (alarmIntent!=null)
				am.cancel(alarmIntent);
			alarmIntent = null;
		}
	}

	// called from JNI
	private void onAdd(String name, byte[] addr, byte[] broadcast, int prefixLen){
		try {
			IPInterface network = new IPInterface(name, addr, broadcast, prefixLen);
			Log.v(TAG, "Add "+network);

			// Only send broadcasts on 80211 interfaces
			if (!new File("/sys/class/net/"+name+"/phy80211").exists())
				return;

			networks.add(network);

			// wait a little while for the kernel to finish bringing the interface up
			setAlarm(10);
		} catch (UnknownHostException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	// called from JNI
	private void onRemove(String name, byte[] addr, byte[] broadcast, int prefixLen){
		try {
			IPInterface network = new IPInterface(name, addr, broadcast, prefixLen);
			Log.v(TAG, "Remove "+network);
			networks.remove(network);
			if (networks.isEmpty())
				cancelAlarm();
			trimDead();
		} catch (UnknownHostException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private void trimDead(){
		Iterator<Map.Entry<PeerId, Peer>> pi = peers.entrySet().iterator();
		while(pi.hasNext()){
			Map.Entry<PeerId, Peer> ep = pi.next();
			Peer p = ep.getValue();

			Iterator<Map.Entry<Object, PeerLink>> li = p.networkLinks.entrySet().iterator();
			while(li.hasNext()){
				Map.Entry<Object, PeerLink> el = li.next();
				PeerLink l = el.getValue();

				if (l instanceof PeerSocketLink) {
					PeerSocketLink link = (PeerSocketLink) l;
					if (!networks.contains(link.network) || link.isDead()) {
						Log.v(TAG, "Dead peer link from "+link.addr+
								" ("+networks.contains(link.network)+
								", "+link.heardBroadcast()+", "+link.heardUnicast()+")");
						li.remove();
					}
				}
			}

			if (!p.isAlive()) {
				p.linksDied();
				pi.remove();
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

	public void onAlarm() {
		nextAlarm = -1;
		if (backgroundEnabled && !networks.isEmpty()) {
			trimDead();

			// assemble a broadcast heartbeat packet
			ByteBuffer buff = ByteBuffer.allocate(MTU);
			Header hdr = new Header(myId, false);
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
			for (IPInterface i : networks) {
				try {
					dgram.send(buff, new InetSocketAddress(i.broadcastAddress, PORT));
				} catch (SecurityException se) {
					Log.e(TAG, se.getMessage(), se);
				} catch (IOException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}

			hdr = new Header(myId, true);
			// Send unicast heartbeats when we haven't heard recent confirmation of broadcast reception
			for(Peer p:peers.values()){
				PeerSocketLink link = sendUnicastAck(p);
				if (link == null)
					continue;

				buff.clear();
				hdr.write(buff);
				ack = new Ack();
				ack.add(p, link);
				ack.write(buff);
				if (state != null)
					state.write(buff);

				buff.flip();

				try {
					dgram.send(buff, link.addr);
				} catch (SecurityException se) {
					Log.e(TAG, se.getMessage(), se);
				} catch (IOException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}

			// TODO, send any required unicast packets to bypass packet filters

			setAlarm(HEARTBEAT_MS);
		}
		wakeLock.release();
	}

	public PeerConnection connectLink(Peer peer, PeerSocketLink link) throws IOException {
		SocketChannel channel = SocketChannel.open();
		PeerConnection connection = new PeerConnection(this, channel, peer);
		nioLoop.register(connection.getInterest(), connection);
		channel.connect(link.addr);
		return connection;
	}
}
