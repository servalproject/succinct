package org.servalproject.succinct.team;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.networking.messages.Message;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

public class Team extends Message<Team>{

    public final long epoc;
    public final PeerId id;
    public final PeerId leader;
    public final String name;

    public Team(long epoc, PeerId id, PeerId leader, String name) {
        super(Type.TeamMessage);
        this.epoc = epoc;
        this.id = id;
        this.leader = leader;
        this.name = name;
    }

    // team closure message
    public Team(long time){
        this(time, null, null, null);
    }

    public static final Factory<Team> factory = new Factory<Team>() {
        @Override
        public String getFileName() {
            return "team";
        }

        @Override
        public Team create(DeSerialiser serialiser) {
            long epoc = serialiser.getRawLong();
            if (!serialiser.hasRemaining())
                return new Team(epoc);
            PeerId id = new PeerId(serialiser);
            PeerId leader = new PeerId(serialiser);
            String name = serialiser.getString();
            return new Team(epoc, id, leader, name);
        }

        @Override
        public void serialise(Serialiser serialiser, Team object) {
            serialiser.putRawLong(object.epoc);
            if (object.id == null)
                return;
            object.id.serialise(serialiser);
            object.leader.serialise(serialiser);
            serialiser.putString(object.name);
        }
    };

    @Override
    protected Factory<Team> getFactory() {
        return factory;
    }

    @Override
    public void process(Peer peer) {
        peer.appContext.networks.processTeamMessage(this);
    }

    @Override
    public String toString(){
        return id.toString()+" "+name;
    }
}
