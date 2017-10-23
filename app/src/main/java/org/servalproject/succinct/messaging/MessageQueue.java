package org.servalproject.succinct.messaging;


import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.BuildConfig;
import org.servalproject.succinct.chat.ChatDatabase;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.forms.Form;
import org.servalproject.succinct.location.LocationFactory;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;
import org.servalproject.succinct.storage.StorageWatcher;
import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.team.Membership;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;
import org.servalproject.succinct.utils.SeqTracker;
import org.servalproject.succinct.utils.WakeAlarm;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Date;
import java.util.List;

import static org.servalproject.succinct.messaging.Fragment.TYPE_PARTIAL;

// Manage the queue of outgoing messages / fragments
public class MessageQueue {
	private final App app;
	private final TeamStorage store;
	private final RecordIterator<StoredChatMessage> eocMessages;
	private final RecordIterator<Fragment> incomingFragments;
	private final SeqTracker incomingTracker;
	private long nextHttpCheck;
	private final RecordIterator<Fragment> fragments;
	private final RecordIterator<Membership> members;
	private final RecordIterator<Team> team;
	public final IMessaging[] services;
	private final int MTU;
	private static final int HEADER = 13;
	private final ConnectivityManager connectivityManager;
	private int nextFragmentSeq;
	private static final String TAG = "MessageQueue";
	private final WakeAlarm alarm;
	private final WakeAlarm httpRecvAlarm;

	private final StorageWatcher<TeamMember> memberWatcher;
	private final QueueWatcher<Form> formWatcher;
	private final QueueWatcher<StoredChatMessage> chatWatcher;

	private final LocationQueueWatcher locationWatcher;
	private long nextLocationMessage=-1;

	private final MappedByteBuffer fragmentBuff;
	private long fragmentDeadline;

	private boolean closed = false;
	private boolean monitoringActive = false;

	private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = new SharedPreferences.OnSharedPreferenceChangeListener() {
		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
			onStateChanged();
		}
	};

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)){
				httpRecvAlarm.setAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextHttpCheck);
			}
		}
	};

	public MessageQueue(App appContext, TeamStorage store) throws IOException {
		this.app = appContext;
		this.store = store;
		app.getPrefs().registerOnSharedPreferenceChangeListener(prefsChanged);
		connectivityManager = (ConnectivityManager)appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
		members = store.openIterator(Membership.factory, store.teamId);
		team = store.openIterator(Team.factory, store.teamId);
		team.reset("sent");

		incomingFragments = store.openIterator(Fragment.factory, PeerId.EOC);
		incomingTracker = new SeqTracker(incomingFragments.store.getProperty("received"));
		httpRecvAlarm = WakeAlarm.getAlarm(appContext, "HttpReceive", App.backgroundHandler, new Runnable() {
			@Override
			public void run() {
				receiveHttpFragments();
			}
		});

		eocMessages = store.openIterator(StoredChatMessage.factory, PeerId.EOC);

		fragments = store.openIterator(Fragment.factory, "messaging");
		Fragment last = fragments.readLast();
		if (last != null)
			nextFragmentSeq = last.seq+1;

		alarm = WakeAlarm.getAlarm(app, "Queue", App.backgroundHandler, new Runnable() {
			@Override
			public void run() {
				if (closed)
					return;
				checkMonitoring();
				sendViaHttp();
				sendNextFragment();
			}
		});

		services = new IMessaging[]{
				new SMSTransport(this, app),
				new RockTransport(this, app)
		};

		// find the smallest mtu we must support
		int mtu = 0x7FFFFFFF;
		for (int i=0;i<services.length;i++){
			mtu = Math.min(services[i].getMTU(), mtu);
		}

		// round down to nearest 50 bytes to limit wasted iridium credits
		if ((mtu % 50)!=0)
			mtu -= mtu % 50;

		MTU = mtu;
		Log.v(TAG, "Using MTU = "+mtu);

		RandomAccessFile f = new RandomAccessFile(new File(store.root,"partial_fragment"), "rw");
		boolean empty = f.length() == 0;

		f.setLength(MTU+4);
		fragmentBuff = f.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, MTU+4);
		f.close();
		fragmentBuff.order(ByteOrder.BIG_ENDIAN);

		if (empty) {
			fragmentBuff.putInt(0, 0);
		}else{
			// recover pre-quit fragment?
			int size = fragmentBuff.getInt();
			fragmentBuff.position(size+fragmentBuff.position());
			if (size>0) {
				nextFragmentSeq = fragmentBuff.getInt(4 + PeerId.LEN) + 1;
				// since we're restarting, assume we should send any partial fragment now?
				fragmentDeadline = SystemClock.elapsedRealtime();
			}
			Log.v(TAG, "Reset fragment buffer to "+size);
		}

		memberWatcher = new StorageWatcher<TeamMember>(App.backgroundHandler, store, TeamMember.factory) {
			@Override
			protected void Visit(PeerId peer, RecordIterator<TeamMember> records) throws IOException {
				records.reset("enrolled");
				if (records.getOffset() != records.store.EOF){
					records.end();
					if (records.prev()){
						TeamMember member = records.read();
						if (member.name == null){
							Log.v(TAG, "Removing "+peer+" from the team list");
							store.getMembers().revoke(peer);
						}else{
							Log.v(TAG, "Enrolling "+peer+" in the team list");
							store.getMembers().enroll(peer);
						}
						records.next();
						records.mark("enrolled");
						alarm.setAlarm(AlarmManager.ELAPSED_REALTIME, WakeAlarm.NOW);
					}
				}
			}
		};

		formWatcher = new QueueWatcher<Form>(this, app, Form.factory) {
			@Override
			boolean generateMessage(PeerId peer, RecordIterator<Form> records) throws IOException {
				int pos = store.getMembers().getPosition(peer);
				Form form = records.read();
				Serialiser serialiser = new Serialiser();
				serialiser.putByte((byte) pos);
				serialiser.putTime(form.time, store.getTeam().epoc);
				Log.v(TAG, "Sending form from "+records.getOffset());
				int delay = MessageQueue.this.app.getPrefs().getInt(App.FORM_DELAY, 60000);
				fragmentMessage(form.time+delay, FORM, serialiser.getResult());
				return true;
			}
		};

		chatWatcher = new QueueWatcher<StoredChatMessage>(this, app, StoredChatMessage.factory) {
			@Override
			boolean findNext(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
				// don't echo EOC messages back at them....
				return !peer.equals(PeerId.EOC) && super.findNext(peer, records);
			}

			@Override
			boolean generateMessage(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
				StoredChatMessage msg = records.read();

				Serialiser serialiser = new Serialiser();
				serialiser.putByte((byte)(int)store.getMembers().getPosition(peer));
				serialiser.putTime(msg.time.getTime(), store.getTeam().epoc);
				serialiser.putString(msg.message);

				Log.v(TAG, "Sending chat message from "+records.getOffset());
				int delay = MessageQueue.this.app.getPrefs().getInt(App.MESSAGE_DELAY, 60000);
				fragmentMessage(msg.time.getTime()+delay, MESSAGE, serialiser.getResult());
				return true;
			}
		};

		locationWatcher = new LocationQueueWatcher();

		IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
		appContext.registerReceiver(receiver, filter);

		alarm.setAlarm(AlarmManager.ELAPSED_REALTIME, WakeAlarm.NOW);
		httpRecvAlarm.setAlarm(AlarmManager.ELAPSED_REALTIME, WakeAlarm.NOW);
	}

	private static final byte CREATE_TEAM = 0;
	private static final byte DESTROY_TEAM = 1;
	private static final byte ENROLL = 2;
	private static final byte LEAVE = 3;
	private static final byte LOCATION = 4;
	private static final byte MESSAGE = 5;
	private static final byte FORM = 6;

	private boolean endFragment() throws IOException {
		if (fragmentBuff.position()<=4)
			return false;
		fragmentBuff.flip();
		fragmentBuff.position(4);
		byte[] fragmentBytes = new byte[fragmentBuff.remaining()];
		fragmentBuff.get(fragmentBytes);
		fragments.append(new Fragment(System.currentTimeMillis(), fragmentBytes));
		fragmentBuff.clear();
		fragmentBuff.putInt(0, 0);
		return true;
	}

	private void beginFragment(int pieceLen) throws IOException {
		int seq = nextFragmentSeq++;
		fragmentBuff.clear();
		fragmentBuff.position(4);
		store.teamId.write(fragmentBuff);
		fragmentBuff.putInt(seq);
		fragmentBuff.put((byte) (pieceLen > 255 ? 255 : pieceLen));
		fragmentBuff.putInt(0, fragmentBuff.position());
	}

	private void checkMonitoring(){
		if (monitoringActive == (!closed && store.isTeamActive()))
			return;

		monitoringActive = (!closed && store.isTeamActive());
		if (monitoringActive){
			memberWatcher.activate();
			chatWatcher.activate();
			locationWatcher.activate();
			formWatcher.activate();
		}else{
			// stop listening for storage changes
			memberWatcher.deactivate();
			formWatcher.deactivate();
			chatWatcher.deactivate();
			locationWatcher.deactivate();
		}
	}

	public void fragmentMessage(long deadline, byte messageType, byte[] messageBytes) throws IOException {
		fragmentMessage(deadline, messageType, messageBytes, messageBytes.length);
	}

	public void fragmentMessage(long deadline, byte messageType, byte[] messageBytes, int length) throws IOException {
		int offset = -3;

		Log.v(TAG, "Fragmenting "+messageType+" message, deadline in "+(deadline - System.currentTimeMillis())+"ms "+Hex.toString(messageBytes, 0, length));

		while (offset < length) {
			int len = length - offset;
			if (len > MTU - HEADER)
				len = MTU - HEADER;


			if (fragmentBuff.position() <= 4) {
				fragmentDeadline = deadline;
				beginFragment((offset == -3) ? 0 : len);
			}else if(deadline < fragmentDeadline){
				fragmentDeadline = deadline;
			}

			if (len > fragmentBuff.remaining())
				len = fragmentBuff.remaining();

			if (offset == -3) {
				fragmentBuff.put(messageType);
				fragmentBuff.putShort((short) length);
				offset += 3;
				len -= 3;
			}
			fragmentBuff.put(messageBytes, offset, len);
			offset += len;
			fragmentBuff.putInt(0, fragmentBuff.position());

			if (len > 254 || fragmentBuff.remaining()<3)
				endFragment();
		}
	}

	private boolean teamState() throws IOException{
		boolean ret = false;
		team.reset("sent");
		while (team.next()){
			Serialiser serialiser = new Serialiser();
			Team record = team.read();
			if (record.id == null) {
				serialiser.putRawLong(record.epoc);
				fragmentMessage(record.epoc, DESTROY_TEAM, serialiser.getResult());
			}else{
				serialiser.putRawLong(record.epoc);
				serialiser.putString(record.name);
				int delay = app.getPrefs().getInt(App.MESSAGE_DELAY, 60000);
				fragmentMessage(record.epoc+delay, CREATE_TEAM, serialiser.getResult());
			}
			Team.factory.serialise(serialiser, record);
			ret = true;
		}
		team.mark("sent");
		return ret;
	}

	private boolean memberEnrollments() throws IOException{
		members.reset("sent");
		Serialiser serialiser = new Serialiser();
		boolean sent = false;

		while(members.next()){
			Membership m = members.read();
			int pos = store.getMembers().getPosition(m.peerId);
			if (pos > 255)
				continue;

			TeamMember member = store.getMembers().getTeamMember(m.peerId);
			if (member == null)
				break;

			serialiser.putByte((byte) pos);
			serialiser.putTime(m.time, store.getTeam().epoc);
			if (m.enroll) {
				serialiser.putString(member.name);
				serialiser.putString(member.employeeId);

				fragmentMessage(m.time, ENROLL, serialiser.getResult());
			}else{
				fragmentMessage(m.time, LEAVE, serialiser.getResult());
			}
			sent = true;
		}
		members.mark("sent");
		return sent;
	}

	public boolean hasUnsent() throws IOException {
		if (fragmentBuff.position()>4)
			return true;
		fragments.reset("sending");
		if (fragments.next())
			return true;
		team.reset("sent");
		if (team.next())
			return true;

		if (!store.isTeamActive())
			return false;

		members.reset("sent");
		if (members.next())
			return true;
		if (locationWatcher.hasMessage())
			return true;
		if (chatWatcher.hasMessage())
			return true;
		if (formWatcher.hasMessage())
			return true;
		return false;
	}

	private boolean nextMessage(boolean flushNow) throws IOException {
		// Look for something to send in priority order;
		int seq = nextFragmentSeq;
		if (fragmentBuff.position()<=4)
			seq++;

		// keep trying to pack more data into the current fragment, before ending it
		while(nextFragmentSeq <= seq){
			if (teamState())
				continue;

			if (store.isTeamActive()) {
				if (memberEnrollments())
					continue;
				if (locationWatcher.nextMessage())
					continue;
				if (chatWatcher.nextMessage())
					continue;
				if (formWatcher.nextMessage())
					continue;
			}

			if (fragmentBuff.position()<=4) {
				Log.v(TAG, "Message queue is empty");
				return false;
			}

			long alarmTime = locationWatcher.adjustAlarm(fragmentDeadline);
			if (alarmTime > System.currentTimeMillis() && !flushNow){
				// Delay closing this fragment until the deadline of any message within it has elapsed
				fragmentBuff.force();
				Log.v(TAG, "Delaying close of last fragment");
				alarm.setAlarm(AlarmManager.RTC_WAKEUP, alarmTime);
				return false;
			}

			Log.v(TAG, "Deadline expired, closing last fragment");
			return endFragment();
		}
		return true;
	}

	private void markAck(int seq) throws IOException {
		if (seq <-1 || seq >= nextFragmentSeq)
			throw new IllegalStateException("Sequence out of range ("+seq+", "+nextFragmentSeq+")");

		if (seq <0){
			fragments.start();
			fragments.next();
			fragments.mark("http_acked");
			return;
		}

		int first = 0;
		int last = nextFragmentSeq -1;
		{
			Fragment current = fragments.read();
			if (current != null) {
				int currentSeq = current.seq;
				if (seq >= currentSeq)
					first = currentSeq;
				else
					last = currentSeq;
			}
		}
		boolean forwards = (seq - first) < (last - seq);
		if (forwards && first == 0) {
			fragments.start();
			if (!fragments.next())
				throw new IllegalStateException("Seq "+seq+" not found!");
		}
		if (!forwards && last == nextFragmentSeq -1) {
			fragments.end();
			if (!fragments.prev())
				throw new IllegalStateException("Seq "+seq+" not found!");
		}
		while(true){
			Fragment fragment = fragments.read();
			if (fragment.seq == seq) {
				fragments.next();
				fragments.mark("http_acked");
				return;
			}
			if (!(forwards ? fragments.next() : fragments.prev()))
				break;
		}
		throw new IllegalStateException("Seq "+seq+" not found!");
	}

	private String readString(URLConnection connection) throws IOException {
		final char[] buffer = new char[512];
		final StringBuilder out = new StringBuilder();
		Reader in = new InputStreamReader(connection.getInputStream(), "UTF-8");
		while(true) {
			int rsz = in.read(buffer, 0, buffer.length);
			if (rsz < 0)
				break;
			out.append(buffer, 0, rsz);
		}
		in.close();
		return out.toString();
	}

	private byte[] readBytes(URLConnection connection) throws IOException {
		int len = connection.getContentLength();
		if (len <=0)
			return null;

		byte[] ret = new byte[len];
		int offset=0;
		InputStream stream = connection.getInputStream();
		while(offset < ret.length){
			int r = stream.read(ret, offset, ret.length - offset);
			if (r<0)
				throw new EOFException();
			offset+=r;
		}
		return ret;
	}

	private String getBaseUrl(){
		if (!app.getPrefs().getBoolean(App.ENABLE_HTTP, true))
			return null;
		String baseUrl = app.getPrefs().getString(App.BASE_SERVER_URL,
				BuildConfig.directApiUrl);
		if (baseUrl == null || "".equals(baseUrl))
			return null;

		NetworkInfo network = connectivityManager.getActiveNetworkInfo();
		if (network == null || !network.isConnected())
			return null;
		return baseUrl;
	}

	private void sendViaHttp(){
		String baseUrl = getBaseUrl();
		if (baseUrl == null)
			return;

		try {
			fragments.reset("http_acked");
			// If we've already acked them all, skip the connection to the server
			if (fragments.next()){
				// double check the latest ack sequence with the server
				URL url = new URL(baseUrl+"/succinct/api/v1/ack/"+store.teamId+"?key="+BuildConfig.directApiKey);
				Log.v(TAG, "Connecting to "+url);
				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
				try {
					connection.setRequestProperty("Connection", "keep-alive");
					connection.connect();
					int response = connection.getResponseCode();
					if (response == 404) {
						markAck(-1);
					} else if (response == 200) {
						markAck(Integer.parseInt(readString(connection)));
					} else {
						Log.e(TAG, "Unexpected http response code " + response);
						return;
					}
				} finally {
					connection.disconnect();
				}
			}

			while(true){
				Fragment sendFragment = fragments.read();
				if (sendFragment == null){
					// If we reach the end of the fragment list, we can avoid other transports
					fragments.mark("sending");
					if (!(nextMessage(true) && fragments.next())) {
						break;
					}
					continue;
				}

				URL url = new URL(baseUrl+"/succinct/api/v1/uploadFragment/"+store.teamId+"?key="+BuildConfig.directApiKey);
				Log.v(TAG, "Connecting to "+url);
				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
				try {
					connection.setRequestMethod("POST");
					connection.setRequestProperty("Connection", "keep-alive");
					connection.setRequestProperty("Content-Type", "application/octet-stream");
					connection.setFixedLengthStreamingMode(sendFragment.bytes.length);
					connection.connect();
					OutputStream out = connection.getOutputStream();
					out.write(sendFragment.bytes);
					out.close();
					int response = connection.getResponseCode();
					if (response != 200) {
						Log.e(TAG, "Unexpected http response code " + response);
						return;
					}

					markAck(Integer.parseInt(readString(connection)));
				}finally{
					connection.disconnect();
				}
			}

		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	private void receiveHttpFragments() {
		if (SystemClock.elapsedRealtime() < nextLocationMessage)
			return;

		String baseUrl = getBaseUrl();
		if (baseUrl == null)
			return;

		try {
			while (true) {
				int nextSeq = incomingTracker.nextMissing();
				URL url = new URL(baseUrl + "/succinct/api/v1/receiveFragment/" + store.teamId + "/" + nextSeq + "?key=" + BuildConfig.directApiKey);
				Log.v(TAG, "Connecting to "+url);
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				try {
					connection.setRequestProperty("Connection", "keep-alive");
					connection.connect();
					int response = connection.getResponseCode();
					Log.v(TAG, "Status code "+response);
					nextHttpCheck = SystemClock.elapsedRealtime() + 60000;
					if (response == 404)
						break;
					if (response != 200) {
						Log.e(TAG, "Unexpected http response " + response);
						break;
					}

					byte[] message = readBytes(connection);
					Fragment fragment = new Fragment(System.currentTimeMillis(), message);
					storeFragment(fragment);
				}finally{
					connection.disconnect();
				}
			}
			// look for incoming content every 60s, unless we run into a networking problem.
			httpRecvAlarm.setAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextHttpCheck);
		}catch (Exception e){
			Log.e(TAG, e.getMessage(), e);
		}

		try {
			processFragments();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private void sendNextFragment(){
		try {
			// Check if we have or can build a new fragment
			if (!hasUnsent()){
				// set the alarm for the next outgoing location message
				long nextAlarm = locationWatcher.adjustAlarm(Long.MAX_VALUE);
				if (nextAlarm != Long.MAX_VALUE)
					alarm.setAlarm(AlarmManager.RTC_WAKEUP, nextAlarm);
				return;
			}

			// Check that we have at least one service that is ready to deliver a fragment
			int status = IMessaging.UNAVAILABLE;
			for (int i = 0; i < services.length && status == IMessaging.UNAVAILABLE; i++){
				status = services[i].checkAvailable();
			}
			if (status != IMessaging.SUCCESS) {
				Log.v(TAG, "All services are busy or unavailable");
				return;
			}

			// Now we can actually fragment the next message
			// and try to send fragments

			fragments.reset("sending");
			while (true) {
				if (!fragments.next()){
					if (!(nextMessage(false) && fragments.next())) {
						done();
						break;
					}
				}
				status = IMessaging.UNAVAILABLE;

				Fragment send = fragments.read();
				Log.v(TAG, "Attempting to send fragment @"+fragments.getOffset());

				for (int i = 0; i < services.length && status == IMessaging.UNAVAILABLE; i++) {
					status = services[i].trySend(send);
					Log.v(TAG, "Service "+i+" returned "+status);
				}

				if (status != IMessaging.SUCCESS)
					break;
			}
			fragments.mark("sending");
		}catch (Exception e){
			throw new IllegalStateException(e);
		}
	}

	private void done() {
		// tell each service they can teardown their connection now.
		// since we have run out of fragments
		for (int i = 0; i < services.length; i++)
			services[i].done();

		if (!store.isTeamActive()){
			close();
			return;
		}

		// set the alarm for the next outgoing location message
		long nextAlarm = locationWatcher.adjustAlarm(Long.MAX_VALUE);
		if (nextAlarm != Long.MAX_VALUE)
			alarm.setAlarm(AlarmManager.RTC_WAKEUP, nextAlarm);
	}

	// one of our services might be ready for a new fragment
	public void onStateChanged(){
		if (closed)
			return;
		alarm.setAlarm(AlarmManager.ELAPSED_REALTIME_WAKEUP, WakeAlarm.NOW);
	}

	private boolean storeFragment(Fragment fragment) throws IOException {
		if (fragment.team == null)
			return false;

		if (!store.teamId.equals(fragment.team))
			return false;

		// ignore duplicate seq numbers
		if (!incomingTracker.received(fragment.seq))
			return false;

		incomingFragments.append(fragment);
		incomingFragments.store.putProperty("received", incomingTracker.toString());
		return true;
	}

	private void processFragments() throws IOException {
		Team myTeam = store.getTeam();
		if (myTeam == null)
			return;

		incomingFragments.reset("processed");

		while(incomingFragments.next()){
			try {
				Fragment f = incomingFragments.read();

				List<Fragment.Piece> pieces = f.getPieces();
				for(int i=0;i<pieces.size();i++){
					Fragment.Piece piece = pieces.get(i);

					// TODO fragment reassembly!
					if (piece.type == TYPE_PARTIAL || piece.payload.remaining()!=piece.len) {
						Log.v(TAG, "Skip, fragmented message!");
						continue;
					}

					DeSerialiser serialiser = new DeSerialiser(piece.payload);
					switch (piece.type){
						case MESSAGE:
							int position = serialiser.getByte() & 0xFF;
							if (position != 0) {
								Log.v(TAG, "Skip, team member should be 0");
								continue;
							}
							long time = serialiser.getTime(myTeam.epoc);
							String content = serialiser.getString();

							eocMessages.append(
									new StoredChatMessage(
											ChatDatabase.TYPE_MESSAGE,
											new Date(time), content));
							break;
						default:
							Log.v(TAG, "Skip, unsupported message type "+piece.type);
					}
				}
			}catch (Exception e){
				Log.e(TAG, e.getMessage());
			}
		}
		incomingFragments.mark("processed");

	}

	public void receiveFragment(byte[] bytes) {
		try {
			Fragment newFragment = new Fragment(System.currentTimeMillis(), bytes);
			storeFragment(newFragment);

			processFragments();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private class LocationQueueWatcher extends QueueWatcher<Location> {
		private Serialiser serialiser;

		public LocationQueueWatcher() {
			super(MessageQueue.this, app, LocationFactory.factory);
		}

		@Override
		boolean findNext(PeerId peer, RecordIterator<Location> records) throws IOException {
			if (records.getOffset() == records.store.EOF)
				return false;
			records.end();
			if (!records.prev())
				return false;
			long delay = app.getPrefs().getLong(App.LOCATION_INTERVAL, App.DefaultLocationInterval);
			if (System.currentTimeMillis() - nextLocationMessage > delay) {
				Log.v(TAG, "First location update, forcing message in 1s");
				nextLocationMessage = System.currentTimeMillis() + 1000;
			}
			return true;
		}

		long adjustAlarm(long alarmTime){
			if (super.hasMessage() && alarmTime > nextLocationMessage)
				// fire this alarm earlier if we have a scheduled location message
				return nextLocationMessage;
			return alarmTime;
		}

		@Override
		boolean hasMessage() {
			return super.hasMessage() && System.currentTimeMillis() >= nextLocationMessage;
		}

		@Override
		boolean generateMessage(PeerId peer, RecordIterator<Location> records) throws IOException {
			Location l = records.read();
			serialiser.putByte((byte)(int)store.getMembers().getPosition(peer));
			serialiser.putTime(l.getTime(), store.getTeam().epoc);
			serialiser.putFixedBytes(LocationFactory.packLatLngAcc(l));
			return false;
		}

		@Override
		boolean nextMessage() throws IOException {
			if (!hasMessage())
				return false;
			serialiser = new Serialiser();
			super.nextMessage();
			long now = System.currentTimeMillis();
			byte[] message = serialiser.getResult();
			if (message.length>0) {
				fragmentMessage(now, LOCATION, message);
				long delay = app.getPrefs().getLong(App.LOCATION_INTERVAL, App.DefaultLocationInterval);
				nextLocationMessage = now + delay;
			}
			serialiser = null;
			return message.length>0;
		}
	}

	public void close(){
		if (closed)
			return;
		closed = true;
		app.getPrefs().unregisterOnSharedPreferenceChangeListener(prefsChanged);
		checkMonitoring();
		for (int i = 0;i<services.length;i++){
			services[i].close();
		}
	}
}
