package org.servalproject.succinct.team;

import org.servalproject.succinct.networking.Peer;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.networking.messages.Message;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

public class Team extends Message<Team>{

    public final PeerId id;
    public final String name;

    public Team(PeerId id, String name) {
        super(Type.TeamMessage);
        this.id = id;
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
            String name = serialiser.getEndString();
            return new Team(id, name);
        }

        @Override
        public void serialise(Serialiser serialiser, Team object) {
            object.id.serialise(serialiser);
            serialiser.putEndString(object.name);
        }
    };

    @Override
    protected Factory<Team> getFactory() {
        return factory;
    }

    @Override
    public void process(Peer peer) {
        // cache set of known teams?
    }
}
