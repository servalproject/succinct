package org.servalproject.succinct.team;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.networking.messages.Message;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

public class Team extends Message<Team>{

    public final PeerId id;
    public final PeerId leader;
    public final String name;

    public Team(PeerId id, PeerId leader, String name) {
        super(Type.TeamMessage);
        this.id = id;
        this.leader = leader;
        this.name = name;
    }

    public static final Factory<Team> factory = new Factory<Team>() {
        @Override
        public String getFileName() {
            return "team";
        }

        @Override
        public Team create(DeSerialiser serialiser) {
            PeerId id = new PeerId(serialiser);
            PeerId leader = new PeerId(serialiser);
            String name = serialiser.getString();
            return new Team(id, leader, name);
        }

        @Override
        public void serialise(Serialiser serialiser, Team object) {
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
    }

    @Override
    public String toString(){
        return id.toString()+" "+name;
    }
}
