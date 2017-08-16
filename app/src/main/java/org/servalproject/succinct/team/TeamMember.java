package org.servalproject.succinct.team;

/**
 * Created by kieran on 4/08/17.
 */

public class TeamMember {
    // fixme just a placeholder - will probably remove in future

    private String name;
    private int id;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    private static TeamMember me;
    public static TeamMember myself() {
        if (me == null) {
            me = new TeamMember();
            me.setName("Joe Bloggs");
            me.setId(24601);
        }
        return me;
    }
}
