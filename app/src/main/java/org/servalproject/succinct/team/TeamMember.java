package org.servalproject.succinct.team;

import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;


public class TeamMember {
    public final int employeeId;
    public final String name;

    public TeamMember(int employeeId, String name){
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
            int employeeId = (int)serialiser.getLong();
            String name = serialiser.getString();
            return new TeamMember(employeeId, name);
        }

        @Override
        public void serialise(Serialiser serialiser, TeamMember object) {
            serialiser.putLong(object.employeeId);
            serialiser.putString(object.name);
        }
    };
}
