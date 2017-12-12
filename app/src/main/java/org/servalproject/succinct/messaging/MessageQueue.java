package org.servalproject.succinct.messaging;


import android.app.AlarmManager;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.chat.ChatDatabase;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.StorageWatcher;
import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;
import org.servalproject.succinct.utils.SeqTracker;
import org.servalproject.succinct.utils.WakeAlarm;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
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
	final SeqTracker incomingTracker;
	private final StorageWatcher<TeamMember> memberWatcher;
	final RecordIterator<Fragment> fragments;
	private final IMessaging[] services;
	private final HttpTransport httpTransport;
	private final int MTU;
	private static final int HEADER = 13;
	int nextFragmentSeq;
	private static final String TAG = "MessageQueue";
	private final WakeAlarm alarm;

	private final LocationQueueWatcher locationWatcher;
	private final IMessageSource[] messageSources;

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

	public MessageQueue(App appContext, TeamStorage store) throws IOException {
		this.app = appContext;
		this.store = store;
		app.getPrefs().registerOnSharedPreferenceChangeListener(prefsChanged);

		incomingFragments = store.openIterator(Fragment.factory, PeerId.EOC);
		incomingTracker = new SeqTracker(incomingFragments.store.getProperty("received"));

		eocMessages = store.openIterator(StoredChatMessage.factory, PeerId.EOC);

		fragments = store.openIterator(Fragment.factory, "messaging");
		Fragment last = fragments.readLast();
		if (last != null)
			nextFragmentSeq = last.seq+1;

		httpTransport = new HttpTransport(this, store, appContext);

		alarm = WakeAlarm.getAlarm(app, "Queue", App.backgroundHandler, new Runnable() {
			@Override
			public void run() {
				if (closed)
					return;
				checkMonitoring();
				httpTransport.sendViaHttp();
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
				if (records.getOffset() != records.store.EOF) {
					records.end();
					if (records.prev()) {
						TeamMember member = records.read();
						if (member.name == null) {
							Log.v(TAG, "Removing " + peer + " from the team list");
							store.getMembers().revoke(peer);
						} else {
							Log.v(TAG, "Enrolling " + peer + " in the team list");
							store.getMembers().enroll(peer);
						}
						records.next();
						records.mark("enrolled");
						alarm.setAlarm(AlarmManager.ELAPSED_REALTIME, WakeAlarm.NOW);
					}
				}
			}
		};

		messageSources = new IMessageSource[]{
				new TeamMessages(this, app, store),
				new MemberEnrolments(this, store),
				(locationWatcher = new LocationQueueWatcher(this, this.app)),
				new ChatQueueWatcher(this, this.app),
				new FormQueueWatcher(this, this.app)
		};

		alarm.setAlarm(AlarmManager.ELAPSED_REALTIME, WakeAlarm.NOW);
	}

	static final byte CREATE_TEAM = 0;
	static final byte DESTROY_TEAM = 1;
	static final byte ENROLL = 2;
	static final byte LEAVE = 3;
	static final byte LOCATION = 4;
	static final byte MESSAGE = 5;
	static final byte FORM = 6;

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
			for(int i=0;i<messageSources.length;i++){
				messageSources[i].activate();
			}
		}else{
			// stop listening for storage changes
			memberWatcher.deactivate();
			for(int i=0;i<messageSources.length;i++){
				messageSources[i].deactivate();
			}
		}
		httpTransport.monitor(monitoringActive);
	}

	void fragmentMessage(long deadline, byte messageType, byte[] messageBytes) throws IOException {
		fragmentMessage(deadline, messageType, messageBytes, messageBytes.length);
	}

	private void fragmentMessage(long deadline, byte messageType, byte[] messageBytes, int length) throws IOException {
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

	public boolean hasUnsent() throws IOException {
		if (fragmentBuff.position()>4)
			return true;
		fragments.reset("sending");
		if (fragments.next())
			return true;

		for (int i=0; i<messageSources.length; i++){
			if (messageSources[i].hasMessage())
				return true;
		}
		return false;
	}

	boolean nextMessage(boolean flushNow) throws IOException {
		// Look for something to send in priority order;
		int seq = nextFragmentSeq;
		if (fragmentBuff.position()<=4)
			seq++;

		// keep trying to pack more data into the current fragment, before ending it
		for (int i=0; nextFragmentSeq <= seq && i<messageSources.length; i++){
			messageSources[i].nextMessage();
		}

		if (nextFragmentSeq > seq)
			return true;

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

	boolean storeFragment(Fragment fragment) throws IOException {
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

	void processFragments() throws IOException {
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
