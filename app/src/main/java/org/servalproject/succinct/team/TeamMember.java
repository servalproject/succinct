package org.servalproject.succinct.team;

import org.servalproject.succinct.networking.PeerId;

/**
 * Created by kieran on 4/08/17.
 */

public class TeamMember {
    // fixme just a placeholder - will probably remove in future

    static TeamMember myself;
    static{
        PeerId team = new PeerId();
        PeerId me = new PeerId();
        myself = new TeamMember("Joe Bloggs", 24601, new Team(team, me, "Team A"));
    }

    private Team team;
    private String name;
    private int id;

    public TeamMember() {
    }

    private TeamMember(String name, int id, Team team) {
        this.name = name;
        this.id = id;
        this.team = team;
    }

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

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public boolean isValid() {
        return (name != null && !name.isEmpty() && id > 0);
    }

    public static TeamMember getMyself() {
        return myself;
    }
}
