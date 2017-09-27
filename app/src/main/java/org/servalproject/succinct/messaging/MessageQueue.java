package org.servalproject.succinct.messaging;


import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.chat.StoredChatMessage;
import org.servalproject.succinct.forms.Form;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.storage.StorageWatcher;
import org.servalproject.succinct.team.Membership;
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamMember;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// Manage the queue of outgoing messages / fragments
public class MessageQueue {
	private final Team myTeam;
	private final Storage store;
	private final RecordIterator<Fragment> fragments;
	private final RecordIterator<Membership> members;
	private final RecordIterator<Team> team;
	public final IMessaging[] services;
	private final MembershipList membershipList;
	private int nextFragmentSeq;
	private static final String TAG = "MessageQueue";

	private final StorageWatcher<TeamMember> memberWatcher;
	private final QueueWatcher<Form> formWatcher;
	private final QueueWatcher<StoredChatMessage> chatWatcher;

	private final ByteBuffer fragmentBuff;

	abstract class QueueWatcher<T> extends StorageWatcher<T>{
		private final HashMap<PeerId, RecordIterator<T>> queue = new HashMap<>();

		public QueueWatcher(Storage store, Factory<T> factory) {
			super(App.backgroundHandler, store, factory);
			activate();
		}

		boolean findNext(PeerId peer, RecordIterator<T> records) throws IOException {
			return records.next();
		}
		abstract void generateMessage(PeerId peer, RecordIterator<T> records) throws IOException;

		@Override
		protected void Visit(PeerId peer, RecordIterator<T> records) throws IOException {
			records.mark("queue");
			if (findNext(peer, records)){
				if (queue.containsKey(peer))
					return;
				queue.put(peer, records);
				App.backgroundHandler.removeCallbacks(sendRunner);
				App.backgroundHandler.postDelayed(sendRunner, 500);
			}
		}

		boolean nextMessage() throws IOException {
			Iterator<Map.Entry<PeerId, RecordIterator<T>>> i = queue.entrySet().iterator();
			while(i.hasNext()) {
				Map.Entry<PeerId, RecordIterator<T>> e = i.next();
				PeerId peerId = e.getKey();

				// always skip peers if they aren't enrolled
				if (!membershipList.isActive(peerId))
					continue;

				if (membershipList.getPosition(peerId)>255)
					continue;

				RecordIterator<T> iterator = e.getValue();
				iterator.mark("queue");

				if (!findNext(peerId, iterator)) {
					i.remove();
					continue;
				}

				generateMessage(peerId, iterator);

				if (!findNext(peerId, iterator))
					i.remove();
				iterator.mark("sent");
				return true;
			}
			return false;
		}
	}

	private static final int FRAGMENT_MTU=200;
	private static final int PAYLOAD_MTU=200 - 13;

	public MessageQueue(App app, final Team myTeam) throws IOException {
		store = app.teamStorage;
		this.myTeam = myTeam;
		membershipList = app.membershipList;
		members = store.openIterator(Membership.factory, store.teamId);
		team = store.openIterator(Team.factory, store.teamId);
		team.reset("sent");

		fragments = store.openIterator(Fragment.factory, "messaging");
		Fragment last = fragments.readLast();
		if (last != null){
			ByteBuffer b = ByteBuffer.wrap(last.bytes);
			b.order(ByteOrder.BIG_ENDIAN);
			nextFragmentSeq = b.getInt(8)+1;
		}

		RandomAccessFile f = new RandomAccessFile(new File(store.root,"partial_fragment"), "rw");
		boolean empty = f.length() == 0;

		f.setLength(FRAGMENT_MTU+4);
		fragmentBuff = f.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, FRAGMENT_MTU+4);

		if (empty) {
			fragmentBuff.putInt(0, 0);
		}else{
			// recover pre-quit fragment?
			int size = fragmentBuff.getInt();
			fragmentBuff.position(size+fragmentBuff.position());
			Log.v(TAG, "Reset fragment buffer to "+size);
		}

		memberWatcher = new StorageWatcher<TeamMember>(App.backgroundHandler, store, TeamMember.factory) {
			@Override
			protected void Visit(PeerId peer, RecordIterator<TeamMember> records) throws IOException {
				records.reset("enrolled");
				if (records.getOffset()==0 && records.next()) {
					Log.v(TAG, "Enrolling "+peer+" in the team list");
					membershipList.enroll(peer);
					records.next();
					records.mark("enrolled");
					App.backgroundHandler.removeCallbacks(sendRunner);
					App.backgroundHandler.postDelayed(sendRunner, 500);
				}
			}
		};
		memberWatcher.activate();

		formWatcher = new QueueWatcher<Form>(store, Form.factory) {
			@Override
			void generateMessage(PeerId peer, RecordIterator<Form> records) throws IOException {
				int pos = membershipList.getPosition(peer);
				Form form = records.read();
				Serialiser serialiser = new Serialiser();
				serialiser.putByte((byte) pos);
				serialiser.putTime(form.time, myTeam.epoc);
				Log.v(TAG, "Sending form from "+records.getOffset());
				fragmentMessage(FORM, serialiser.getResult());
			}
		};

		chatWatcher = new QueueWatcher<StoredChatMessage>(store, StoredChatMessage.factory) {
			@Override
			void generateMessage(PeerId peer, RecordIterator<StoredChatMessage> records) throws IOException {
				StoredChatMessage msg = records.read();

				Serialiser serialiser = new Serialiser();
				serialiser.putByte((byte)(int)membershipList.getPosition(peer));
				serialiser.putTime(msg.time.getTime(), myTeam.epoc);
				serialiser.putString(msg.message);

				Log.v(TAG, "Sending chat message  from "+records.getOffset());
				fragmentMessage(MESSAGE, serialiser.getResult());
			}
		};

		services = new IMessaging[]{
				//new RockTransport(this, app),
				new DummySMSTransport(this, app)
		};

		App.backgroundHandler.removeCallbacks(sendRunner);
		App.backgroundHandler.postDelayed(sendRunner, 500);
	}

	private static MessageQueue instance=null;
	// start fragmenting and queuing messages, if this is the app instance with that role
	public static void init(App app){
		try {
			if (instance!=null)
				throw new IllegalStateException();
			Team myTeam = app.teamStorage.getLastRecord(Team.factory, app.teamStorage.teamId);
			if (myTeam!=null && myTeam.leader.equals(app.networks.myId))
				instance = new MessageQueue(app, myTeam);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final byte CREATE_TEAM = 0;
	private static final byte DESTROY_TEAM = 1;
	private static final byte ENROLL=2;
	private static final byte LEAVE=3;
	private static final byte LOCATION=4;
	private static final byte MESSAGE=5;
	private static final byte FORM=6;

	public void fragmentMessage(byte messageType, byte[] messageBytes) throws IOException {
		fragmentMessage(messageType, messageBytes, messageBytes.length);
	}

	private boolean endFragment() throws IOException {
		if (fragmentBuff.position()<=4)
			return false;
		Log.v(TAG, "End fragment "+(nextFragmentSeq -1)+" "+fragmentBuff.position()+"?");
		fragmentBuff.flip();
		fragmentBuff.position(4);
		byte[] fragmentBytes = new byte[fragmentBuff.remaining()];
		fragmentBuff.get(fragmentBytes);
		fragments.append(new Fragment(fragmentBytes));
		fragmentBuff.clear();
		fragmentBuff.putInt(0, 0);
		return true;
	}

	private void beginFragment(int pieceLen) throws IOException {
		int seq = nextFragmentSeq++;
		Log.v(TAG, "Begin fragment "+seq);
		fragmentBuff.clear();
		fragmentBuff.position(4);
		store.teamId.write(fragmentBuff);
		fragmentBuff.putInt(seq);
		fragmentBuff.put((byte) (pieceLen > 255 ? 255 : pieceLen));
		fragmentBuff.putInt(0, fragmentBuff.position());
	}

	public void fragmentMessage(byte messageType, byte[] messageBytes, int length) throws IOException {
		int offset = -3;
		while (offset < length) {
			int len = length - offset;

			if (len > PAYLOAD_MTU)
				len = PAYLOAD_MTU;
			if (len > fragmentBuff.remaining())
				len = fragmentBuff.remaining();

			if (fragmentBuff.position() <= 4)
				beginFragment((offset == -3) ? 0 : len + 1);

			Log.v(TAG, "Adding "+messageType+" to seq "+(nextFragmentSeq-1)+" @"+fragmentBuff.position()+", offset "+offset+" len "+len+"/"+length);
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
		while (team.next()){
			Serialiser serialiser = new Serialiser();
			Team record = team.read();
			if (record.id == null) {
				serialiser.putRawLong(record.epoc);
				fragmentMessage(DESTROY_TEAM, serialiser.getResult());
			}else{
				serialiser.putRawLong(record.epoc);
				serialiser.putString(record.name);
				fragmentMessage(CREATE_TEAM, serialiser.getResult());
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
			int pos = membershipList.getPosition(m.peerId);
			if (pos > 255)
				continue;

			TeamMember member = membershipList.getTeamMember(m.peerId);
			if (member == null)
				break;

			serialiser.putByte((byte) pos);
			serialiser.putTime(m.time, myTeam.epoc);
			if (m.enroll) {
				serialiser.putString(member.employeeId);
				serialiser.putString(member.name);
				fragmentMessage(ENROLL, serialiser.getResult());
			}else{
				fragmentMessage(LEAVE, serialiser.getResult());
			}
			sent = true;
		}
		members.mark("sent");
		return sent;
	}

	private boolean nextMessage() throws IOException {
		// Look for something to send in priority order;
		int seq = nextFragmentSeq;
		if (fragmentBuff.position()<=4)
			seq++;

		// keep trying to pack more data into the current fragment, before ending it
		while(nextFragmentSeq <= seq){
			if (teamState())
				continue;
			if (memberEnrollments())
				continue;
			// TODO locations HERE!?
			if (chatWatcher.nextMessage())
				continue;
			if (formWatcher.nextMessage())
				continue;
			// TODO delay closing the last fragment on a timer?
			Log.v(TAG, "Message queue ran out");
			return endFragment();
		}
		return true;
	}

	public void sendNext(){
		Log.v(TAG, "sendNext");
		try {
			fragments.reset("sending");
			while (true) {
				if (!fragments.next()){
					if (!(nextMessage() && fragments.next())) {
						// tell each service they can teardown their connection now.
						for (int i = 0; i < services.length; i++)
							services[i].done();
						break;
					}
				}
				int status = IMessaging.UNAVAILABLE;

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

	private final Runnable sendRunner = new Runnable() {
		@Override
		public void run() {
			sendNext();
		}
	};

	// one of our services might be ready for a new fragment
	public void onStateChanged(){
		App.backgroundHandler.removeCallbacks(sendRunner);
		App.backgroundHandler.post(sendRunner);
	}
}
