package org.servalproject.succinct.team;

import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;


public class TeamMember {
    public final String employeeId;
    public final String name;

    public TeamMember(){
        this(null,null);
    }

    public TeamMember(String employeeId, String name){
        this.employeeId = employeeId;
        this.name = name;
    }

    public static final Factory<TeamMember> factory = new Factory<TeamMember>() {
        @Override
        public String getFileName() {
            return "id";
        }

        @Override
        public TeamMember create(DeSerialiser serialiser) {
            if (!serialiser.hasRemaining())
                return new TeamMember();
            String employeeId = serialiser.getString();
            String name = serialiser.getString();
            return new TeamMember(employeeId, name);
        }

        @Override
        public void serialise(Serialiser serialiser, TeamMember object) {
            if (object.name == null)
                return;
            serialiser.putString(object.employeeId);
            serialiser.putString(object.name);
        }
    };
}
