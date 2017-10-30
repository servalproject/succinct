package org.servalproject.succinct.team;

import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Storage;
import org.servalproject.succinct.storage.TeamStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MembershipList {
	private final TeamStorage store;
	private final HashMap<PeerId, Integer> positions = new HashMap<>();
	private final Set<PeerId> removed = new HashSet<>();
	private final RecordIterator<Membership> iterator;
	private final List<PeerId> peerIds = new ArrayList<>();
	private final List<TeamMember> members = new ArrayList<>();

	public MembershipList(TeamStorage store) throws IOException {
		this.store = store;
		iterator = store.openIterator(Membership.factory, store.teamId);
		iterator.start();
		members.add(new TeamMember("","EOC"));
		peerIds.add(PeerId.EOC);
		positions.put(PeerId.EOC, 0);
	}

	public TeamMember getTeamMember(PeerId id) throws IOException {
		List<TeamMember> members = getMembers();
		if (!positions.containsKey(id))
			return null;
		int position = positions.get(id);
		TeamMember member = members.get(position);
		if (member == null){
			RecordIterator<TeamMember> recordIterator = store.openIterator(TeamMember.factory, id);
			recordIterator.end();
			while(recordIterator.prev()){
				member = recordIterator.read();
				if (member.name!=null)
					break;
			}
			members.set(position, member);
		}
		return member;
	}

	public TeamMember getTeamMember(int position) throws IOException{
		List<TeamMember> members = getMembers();
		if (position<0 || position>=members.size())
			return null;
		TeamMember member = members.get(position);
		if (member == null){
			PeerId id = peerIds.get(position);
			RecordIterator<TeamMember> recordIterator = store.openIterator(TeamMember.factory, id);
			recordIterator.end();
			while(recordIterator.prev()){
				member = recordIterator.read();
				if (member.name!=null)
					break;
			}
			members.set(position, member);
		}
		return member;
	}

	public boolean isActive(PeerId id) throws IOException {
		getMembers();
		return positions.containsKey(id) && !removed.contains(id);
	}

	public boolean isActive(int position) throws IOException {
		getMembers();
		PeerId id = peerIds.get(position);
		return positions.containsKey(id) && !removed.contains(id) && positions.get(id)==position;
	}

	public Integer getPosition(PeerId id) throws IOException {
		getMembers();
		return positions.get(id);
	}

	public PeerId getPeerId(int position) throws IOException {
		getMembers();
		if (position<0 || position>=peerIds.size())
			return null;
		return peerIds.get(position);
	}

	public synchronized List<TeamMember> getMembers() throws IOException {
		while(iterator.next()){
			Membership membership = iterator.read();
			if (membership.enroll){
				if (!positions.containsKey(membership.peerId)) {
					RecordIterator<TeamMember> recordIterator = store.openIterator(TeamMember.factory, membership.peerId);
					recordIterator.end();
					TeamMember member = null;
					while(recordIterator.prev()){
						member = recordIterator.read();
						if (member.name!=null)
							break;
					}
					int pos = members.size();
					members.add(member);
					peerIds.add(membership.peerId);
					positions.put(membership.peerId, pos);
					removed.remove(membership.peerId);
				}
			}else{
				removed.add(membership.peerId);
				positions.remove(membership.peerId);
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
