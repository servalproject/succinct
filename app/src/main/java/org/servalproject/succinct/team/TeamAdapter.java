package org.servalproject.succinct.team;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.servalproject.succinct.R;
import org.servalproject.succinct.TeamFragment;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Created by kieran on 3/10/17.
 */

public class TeamAdapter extends RecyclerView.Adapter<TeamAdapter.TeamViewHolder> {

    private ArrayList<Team> teams;
    private int selected = -1;
    private final TeamFragment fragment;

    public TeamAdapter(TeamFragment f) {
        super();
        teams = new ArrayList<>();
        fragment = f;
    }

    public void clear() {
        teams.clear();
        notifyDataSetChanged();
    }

    public boolean addTeam(Team team) {
        int index = teams.indexOf(team);
        if (index < 0) {
            teams.add(team);
        } else {
            teams.set(index, team);
        }
        notifyDataSetChanged();
        return (index >= 0);
    }

    public void setTeams(Collection<Team> teams) {
        this.teams.clear();
        this.teams.addAll(teams);
        notifyDataSetChanged();
    }

    @Override
    public TeamViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.team_item, parent, false);
        return new TeamViewHolder(view);
    }

    @Override
    public void onBindViewHolder(TeamViewHolder holder, int position) {
        holder.itemView.setSelected(position == selected);
        Team team = teams.get(position);
        holder.name.setText(team.name);
        // holder.leader.setText(team.leader.getName());
    }

    @Override
    public int getItemCount() {
        return teams.size();
    }

    private void setSelected(int pos) {
        if (pos >= getItemCount()) pos = -1;
        if (selected == pos) return;
        if (selected >= 0) notifyItemChanged(selected);
        selected = pos;
        if (selected >= 0) notifyItemChanged(selected);
        fragment.onTeamSelectedChange();
    }

    public Team getSelected() {
        if (selected < 0) return null;
        return teams.get(selected);
    }

    public class TeamViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        protected TextView name;
        // protected TextView leader;
        public TeamViewHolder(View view) {
            super(view);
            name = (TextView) view.findViewById(R.id.team_name);
            // leader = (TextView) view.findViewById(R.id.team_leader);
            view.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            int pos = getAdapterPosition();
            setSelected(pos);
        }
    }
}
