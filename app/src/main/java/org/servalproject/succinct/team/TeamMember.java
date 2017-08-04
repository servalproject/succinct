package org.servalproject.succinct.team;

/**
 * Created by kieran on 4/08/17.
 */

public class TeamMember {
    // fixme just a placeholder - will probably remove in future

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public static TeamMember myself() {
        TeamMember me = new TeamMember();
        me.setName("Joe Bloggs");
        return me;
    }
}
