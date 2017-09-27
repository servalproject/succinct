package org.servalproject.succinct.team;

import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MembershipList {
	private final Storage store;
	private final HashMap<PeerId, Integer> positions = new HashMap<>();
	private final Set<PeerId> removed = new HashSet<>();
	private final RecordIterator<Membership> iterator;
	private final List<TeamMember> members = new ArrayList<>();

	private MembershipList(Storage store) throws IOException {
		this.store = store;
		iterator = store.openIterator(Membership.factory, store.teamId);
		iterator.start();
		members.add(new TeamMember("","EOC"));
	}

	private static MembershipList instance;
	public static MembershipList getInstance(Storage store) throws IOException {
		if (instance==null)
			instance = new MembershipList(store);
		return instance;
	}

	public TeamMember getTeamMember(PeerId id) throws IOException {
		List<TeamMember> members = getMembers();
		if (!positions.containsKey(id))
			return null;
		return members.get(positions.get(id));
	}

	public boolean isActive(PeerId id) throws IOException {
		getMembers();
		return positions.containsKey(id) && !removed.contains(id);
	}

	public Integer getPosition(PeerId id) throws IOException {
		getMembers();
		return positions.get(id);
	}

	public synchronized List<TeamMember> getMembers() throws IOException {
		while(iterator.next()){
			Membership membership = iterator.read();
			if (membership.enroll){
				if (!positions.containsKey(membership.peerId)) {
					TeamMember member = store.getLastRecord(TeamMember.factory, membership.peerId);
					int pos = members.size();
					members.add(member);
					positions.put(membership.peerId, pos);
				}
			}else{
				removed.add(membership.peerId);
			}
		}
		return members;
	}

	// For now, called automatically by the team leader
	public void enroll(PeerId peer) throws IOException {
		iterator.append(new Membership(System.currentTimeMillis(), peer, true));
	}

	// Should only be called by the team leader!
	public void revoke(PeerId peer) throws IOException {
		iterator.append(new Membership(System.currentTimeMillis(), peer, false));
	}
}
