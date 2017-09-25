package org.servalproject.succinct.team;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MembershipList {
	private final Storage store;
	private final HashMap<PeerId, Integer> positions = new HashMap<>();
	private final RecordIterator<Membership> iterator;
	private final List<TeamMember> members = new ArrayList<>();

	private MembershipList(Storage store) throws IOException {
		this.store = store;
		iterator = store.openIterator(Membership.factory, store.teamId);
		iterator.start();
	}

	private static MembershipList instance;
	public static MembershipList getInstance(Storage store) throws IOException {
		if (instance==null)
			instance = new MembershipList(store);
		return instance;
	}

	public synchronized List<TeamMember> getMembers() throws IOException {
		while(iterator.next()){
			Membership membership = iterator.read();
			if (membership.enroll){
				TeamMember member = store.getLastRecord(TeamMember.factory, membership.peerId);
				int pos = members.size();
				members.add(member);
				positions.put(membership.peerId, pos);
			}else{
				// null out anyone who has left
				int pos = positions.get(membership.peerId);
				members.remove(pos);
				members.add(pos, null);
			}
		}
		return members;
	}

	// For now, called automatically by the team leader
	public void enroll(PeerId peer) throws IOException {
		iterator.append(new Membership(peer, true));
	}

	// Should only be called by the team leader!
	public void revoke(PeerId peer) throws IOException {
		iterator.append(new Membership(peer, false));
	}
}
