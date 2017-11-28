package org.servalproject.succinct.messaging;

import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.Serialiser;
import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.team.Membership;
import org.servalproject.succinct.team.TeamMember;

import java.io.IOException;

/**
 * Created by jeremy on 28/11/17.
 */
class MemberEnrolments implements IMessageSource {
	private MessageQueue messageQueue;
	private final TeamStorage store;
	private final RecordIterator<Membership> members;

	public MemberEnrolments(MessageQueue messageQueue, TeamStorage store) throws IOException {
		this.messageQueue = messageQueue;
		this.store = store;
		members = store.openIterator(Membership.factory, store.teamId);
	}

	@Override
	public boolean hasMessage() throws IOException {
		members.reset("sent");
		return members.next();
	}

	@Override
	public boolean nextMessage() throws IOException {
		members.reset("sent");
		Serialiser serialiser = new Serialiser();
		boolean sent = false;

		while (members.next()) {
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

				messageQueue.fragmentMessage(m.time, MessageQueue.ENROLL, serialiser.getResult());
			} else {
				messageQueue.fragmentMessage(m.time, MessageQueue.LEAVE, serialiser.getResult());
			}
			sent = true;
		}
		members.mark("sent");
		return sent;
	}

	@Override
	public void activate() {

	}

	@Override
	public void deactivate() {

	}
}
