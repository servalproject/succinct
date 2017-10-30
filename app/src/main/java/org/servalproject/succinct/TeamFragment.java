package org.servalproject.succinct;


import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.app.Fragment;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.support.design.widget.TextInputEditText;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.servalproject.succinct.storage.TeamStorage;
import org.servalproject.succinct.team.MemberAdapter;
import org.servalproject.succinct.team.Team;
import org.servalproject.succinct.team.TeamAdapter;
import org.servalproject.succinct.utils.AndroidObserver;
import org.servalproject.succinct.utils.HtmlCompat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Observable;

public class TeamFragment extends Fragment {
    private static final String TAG = "TeamFragment";

    @IntDef({TEAM_STATE_EDITING_ID, TEAM_STATE_SCANNING, TEAM_STATE_ACTIVE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TeamState {}

    private static final int TEAM_STATE_EDITING_ID = 0;
    private static final int TEAM_STATE_SCANNING = 1;
    private static final int TEAM_STATE_ACTIVE = 2;

    private TextInputEditText editName;
    private TextInputEditText editID;
    private Button saveButton;
    private Button editButton;
    private Button joinButton;
    private App app;
    private SharedPreferences prefs;
    private TeamAdapter teamAdapter = new TeamAdapter(this);
    private MemberAdapter memberAdapter;
    private ProgressBar progress;

    @TeamState int state;

    public TeamFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: "+savedInstanceState);
        super.onCreate(savedInstanceState);
        //noinspection StatementWithEmptyBody
        if (getArguments() != null) {
        }

        app = (App)getActivity().getApplication();
        memberAdapter = new MemberAdapter(app, this);
        prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String name = prefs.getString(App.MY_NAME, null);
        String employeeId = prefs.getString(App.MY_EMPLOYEE_ID, null);

        if (app.teamStorage!=null && app.teamStorage.isTeamActive()){
            state = TEAM_STATE_ACTIVE;
        }else if (!isValidIdentity(name, employeeId)){
            state = TEAM_STATE_EDITING_ID;
        } else {
            state = TEAM_STATE_SCANNING;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_team, container, false);

        String name = prefs.getString(App.MY_NAME, null);
        String employeeId = prefs.getString(App.MY_EMPLOYEE_ID, null);

        View card;

        // need to initialise EditText fields regardless of state, because views can save state between fragment detach/attach
        card = view.findViewById(R.id.identity_card);
        TextInputEditText localEditName = (TextInputEditText) card.findViewById(R.id.identity_name_text);
        TextInputEditText localEditID = (TextInputEditText) card.findViewById(R.id.identity_id_text);
        if (localEditName != editName) {
            editName = localEditName;
            if (name != null) {
                editName.setText(name);
            }
            editName.addTextChangedListener(textWatcherIdentity);
        }
        if (localEditID != editID) {
            editID = localEditID;
            if (employeeId != null) {
                editID.setText(employeeId);
            }
            editID.addTextChangedListener(textWatcherIdentity);
        }
        saveButton = (Button) card.findViewById(R.id.identity_save_button);
        editButton = (Button) card.findViewById(R.id.identity_edit_button);

        switch (state) {
            case TEAM_STATE_EDITING_ID:
                card = view.findViewById(R.id.identity_card);
                saveButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Log.d(TAG, "Save pressed");
                        String name;
                        String id;
                        try {
                            name = editName.getText().toString().trim();
                            id = editID.getText().toString().trim();
                        } catch (NullPointerException e) {
                            return;
                        }
                        if (!isValidIdentity(name, id)) {
                            return;
                        }
                        hideKeyboard();
                        setIdentity(name, id);
                        state = TEAM_STATE_SCANNING;
                        redraw();
                    }
                });
                // enable/disable save button as appropriate
                textWatcherIdentity.afterTextChanged(null);
                editButton.setVisibility(View.GONE);
                saveButton.setVisibility(View.VISIBLE);
                card.setVisibility(View.VISIBLE);
                progress = null;
                break;
            case TEAM_STATE_SCANNING:
                card = view.findViewById(R.id.identity_card);
                editButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Log.d(TAG, "Edit pressed");
                        state = TEAM_STATE_EDITING_ID;
                        redraw();
                    }
                });
                editButton.setVisibility(View.VISIBLE);
                saveButton.setVisibility(View.GONE);
                TextView identity = (TextView) card.findViewById(R.id.identity_card_text);
                String escapedName = TextUtils.htmlEncode(name);
                String escapedId = TextUtils.htmlEncode(employeeId);
                identity.setText(HtmlCompat.fromHtml(getResources().getString(R.string.identity_summary, escapedName, escapedId)));
                card.findViewById(R.id.identity_name_layout).setVisibility(View.GONE);
                card.findViewById(R.id.identity_id_layout).setVisibility(View.GONE);
                card.setVisibility(View.VISIBLE);
                // team selection card
                card = view.findViewById(R.id.team_select_card);
                card.setVisibility(View.VISIBLE);
                progress = (ProgressBar) card.findViewById(R.id.team_list_progress_bar);
                RecyclerView teamList = (RecyclerView) card.findViewById(R.id.team_list);
                teamList.setAdapter(teamAdapter);
                progress.setVisibility(teamAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                joinButton = (Button) card.findViewById(R.id.join_team_button);
                joinButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        try {
                            Team t = teamAdapter.getSelected();
                            if (t == null) return;
                            TeamStorage.joinTeam(app, t, app.networks.myId);
                            state = TEAM_STATE_ACTIVE;
                            redraw();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                });
                joinButton.setEnabled(false);
                Button start = (Button) card.findViewById(R.id.start_new_team_button);
                start.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTeamCreateDialog(v.getContext());
                    }
                });
                start.setEnabled(TeamStorage.canCreateOrJoin(app));
                break;
            case TEAM_STATE_ACTIVE:
                TextView status = (TextView) view.findViewById(R.id.team_status_text);
                String myTeamName = null;
                boolean leader = false;
                try {
                    Team myTeam = app.teamStorage.getTeam();
                    if (myTeam != null) {
                        myTeamName = myTeam.name;
                        leader = myTeam.leader.equals(app.teamStorage.peerId);
                    } else
                        myTeamName = app.teamStorage.teamId.toString();
                }catch (IOException e){
                    Log.e(TAG, e.getMessage(), e);
                }
                status.setText(getResources().getString(R.string.team_status_in_team, myTeamName));
                card = view.findViewById(R.id.team_card);
                card.setVisibility(View.VISIBLE);
                TextView teamName = (TextView) card.findViewById(R.id.team_name);
                teamName.setText(myTeamName);

                RecyclerView teamMembers = (RecyclerView) card.findViewById(R.id.team_members);
                teamMembers.setAdapter(memberAdapter);

                Button leave = (Button)card.findViewById(R.id.leave_team_button);
                leave.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        try {
                            app.teamStorage.leave();
                            state = TEAM_STATE_SCANNING;
                            redraw();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                });
                leave.setText(leader ? R.string.end_team : R.string.leave_team);
                progress = null;
        }
        return view;
    }

    public void onTeamSelectedChange() {
        if (state != TEAM_STATE_SCANNING || joinButton == null) return;
        joinButton.setEnabled(TeamStorage.canCreateOrJoin(app) && teamAdapter.getSelected() != null);
    }

    private final AndroidObserver teamObserver = new AndroidObserver() {
        @Override
        public void observe(Observable observable, Object o) {
            Team t = (Team)o;
            Log.v(TAG, "Team observed; "+t.toString());
            if (progress != null && teamAdapter.getItemCount() == 0) {
                progress.setVisibility(View.GONE);
            }
            teamAdapter.addTeam(t);
        }
    };

    private boolean observing = false;

    @Override
    public void onStart() {
        super.onStart();
        switch (state){
            case TEAM_STATE_SCANNING:
                app.networks.teams.addObserver(teamObserver);
                Collection<Team> teams = app.networks.getTeams();
                for(Team t:teams)
                    Log.v(TAG, "Team; "+t.toString());
                teamAdapter.setTeams(teams);
                if (progress != null && teams.size() > 0) {
                    progress.setVisibility(View.GONE);
                }
                observing = true;
                break;

            case TEAM_STATE_ACTIVE:
                memberAdapter.onStart();
                break;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (observing)
            app.networks.teams.deleteObserver(teamObserver);
        memberAdapter.onStop();
    }

    private void redraw() {
        getFragmentManager()
                .beginTransaction()
                .detach(this)
                .attach(this)
                .commit();
    }

    private boolean isValidIdentity(String name, String id) {
        return (name != null && name.length() == name.trim().length() && name.trim().length() > 0 && id != null);
    }

    private void setIdentity(String name, String id) {
        if (!isValidIdentity(name, id))
            throw new IllegalArgumentException();
        Log.d(TAG, "saving new identity; name=\""+name+"\", id="+id);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(App.MY_NAME, name);
        ed.putString(App.MY_EMPLOYEE_ID, id);
        ed.apply();
        MainActivity activity = (MainActivity) getActivity();
        activity.updateIdentity();
    }

    private void showTeamCreateDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.start_new_team);

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.team_name);
        builder.setView(input);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                try {
                    TeamStorage.createTeam(app,
                            input.getText().toString(),
                            app.networks.myId);
                    state = TEAM_STATE_ACTIVE;
                    redraw();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        AlertDialog dialog = builder.show();
        final Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setEnabled(false);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (input.getText().length() > 0) {
                    ok.setEnabled(true);
                } else {
                    ok.setEnabled(false);
                }
            }
        });
    }

    private void hideKeyboard() {
        Activity activity = getActivity();
        InputMethodManager inputMethodManager =
                (InputMethodManager) activity.getSystemService(
                        Activity.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(
                activity.getCurrentFocus().getWindowToken(), 0);
    }

    private TextWatcher textWatcherIdentity = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        }

        @Override
        public void afterTextChanged(Editable editable) {
            String name;
            String id;
            try {
                name = editName.getText().toString().trim();
                id = editID.getText().toString().trim();
            } catch (NullPointerException | NumberFormatException e) {
                if (state == TEAM_STATE_EDITING_ID) {
                    saveButton.setEnabled(false);
                }
                return;
            }
            if (state == TEAM_STATE_EDITING_ID) {
                saveButton.setEnabled(isValidIdentity(name, id));
            }
        }
    };
}
