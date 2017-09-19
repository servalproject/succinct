package org.servalproject.succinct;


import android.os.Bundle;
import android.app.Fragment;
import android.support.annotation.IntDef;
import android.support.design.widget.TextInputEditText;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.servalproject.succinct.team.TeamMember;
import org.servalproject.succinct.utils.HtmlCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


public class TeamFragment extends Fragment {
    private static final String TAG = "TeamFragment";

    @IntDef({TEAM_STATE_EDITING_ID, TEAM_STATE_SCANNING, TEAM_STATE_JOINING, TEAM_STATE_ACTIVE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TeamState {}

    private static final int TEAM_STATE_EDITING_ID = 0;
    private static final int TEAM_STATE_SCANNING = 1;
    private static final int TEAM_STATE_JOINING = 2;
    private static final int TEAM_STATE_ACTIVE = 3;

    private TextInputEditText editName;
    private TextInputEditText editID;
    private Button saveButton;
    private Button editButton;

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
        TeamMember me = TeamMember.getMyself();
        if (me.isValid()) {
            state = TEAM_STATE_SCANNING;
        } else {
            state = TEAM_STATE_EDITING_ID;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_team, container, false);
        TeamMember me = TeamMember.getMyself();
        String name = me.getName();
        int id = me.getId();
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
            if (id > 0) {
                String idText = "" + id;
                editID.setText(idText);
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
                        int id;
                        try {
                            name = editName.getText().toString().trim();
                            id = Integer.parseInt(editID.getText().toString().trim());
                        } catch (NullPointerException | NumberFormatException e) {
                            return;
                        }
                        if (!isValidIdentity(name, id)) {
                            return;
                        }
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
                break;
            case TEAM_STATE_SCANNING:
            case TEAM_STATE_JOINING: // todo handle currently-joining case
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
                identity.setText(HtmlCompat.fromHtml(getResources().getString(R.string.identity_summary, escapedName, id)));
                card.findViewById(R.id.identity_name_layout).setVisibility(View.GONE);
                card.findViewById(R.id.identity_id_layout).setVisibility(View.GONE);
                card.setVisibility(View.VISIBLE);
                // team selection card
                card = view.findViewById(R.id.team_select_card);
                card.setVisibility(View.VISIBLE);
                ProgressBar progress = (ProgressBar) card.findViewById(R.id.team_list_progress_bar);
                progress.setVisibility(View.VISIBLE);
                Button join = (Button) card.findViewById(R.id.join_team_button);
                join.setEnabled(false);
                break;
            case TEAM_STATE_ACTIVE:
                TextView status = (TextView) view.findViewById(R.id.team_status_text);
                status.setText(getResources().getString(R.string.team_status_in_team, me.getTeam().name));
                card = view.findViewById(R.id.team_card);
                card.setVisibility(View.VISIBLE);
                TextView teamName = (TextView) card.findViewById(R.id.team_name);
                teamName.setText(me.getTeam().name);
        }
        return view;
    }

    private void redraw() {
        getFragmentManager()
                .beginTransaction()
                .detach(this)
                .attach(this)
                .commit();
    }

    private boolean isValidIdentity(String name, int id) {
        return (name != null && name.length() == name.trim().length() && name.trim().length() > 0 && id > 0);
    }

    private void setIdentity(String name, int id) {
        if (!isValidIdentity(name, id))
            throw new IllegalArgumentException();
        Log.d(TAG, "saving new identity; name=\""+name+"\", id="+id);
        TeamMember me = TeamMember.getMyself();
        me.setName(name);
        me.setId(id);
        MainActivity activity = (MainActivity) getActivity();
        activity.updateIdentity();
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
            int id;
            try {
                name = editName.getText().toString().trim();
                id = Integer.parseInt(editID.getText().toString().trim());
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
