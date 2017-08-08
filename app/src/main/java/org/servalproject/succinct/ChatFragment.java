package org.servalproject.succinct;


import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Fragment;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import org.servalproject.succinct.chat.ChatAdapter;
import org.servalproject.succinct.chat.ChatCursorLoader;
import org.servalproject.succinct.chat.ChatDatabase;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessage;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessageCursor;
import org.servalproject.succinct.team.TeamMember;

import java.util.Date;


public class ChatFragment extends Fragment implements LoaderManager.LoaderCallbacks<ChatMessageCursor> {
    private static final String TAG = "ChatFragment";
    private ChatAdapter adapter;
    private EditText input;
    private Button sendButton;

    public ChatFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //noinspection StatementWithEmptyBody
        if (getArguments() != null) {
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_chat, container, false);
        RecyclerView recycler = (RecyclerView) view.findViewById(R.id.chatRecycler);
        adapter = new ChatAdapter();
        recycler.setAdapter(adapter);
        adapter.enableStickyScroll(recycler);
        input = (EditText) view.findViewById(R.id.sendEditText);
        input.addTextChangedListener(inputTextWatcher);
        sendButton = (Button) view.findViewById(R.id.sendButton);
        sendButton.setOnClickListener(sendClickListener);
        return view;
    }

    private OnClickListener sendClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            Log.d(TAG, "send: "+ input.getText());
            sendMessage(input.getText().toString());
            input.setText("");
            sendButton.setEnabled(false);
        }
    };

    private void sendMessage(final String s) {
        // todo new messages should probably get sent to the storage layer first?
        new AsyncTask<String, Void, Void>() {
            @Override
            protected Void doInBackground(String... strings) {
                ChatMessage msg = new ChatMessage();
                msg.type = ChatDatabase.TYPE_MESSAGE;
                msg.sender = TeamMember.myself().getName();
                msg.senderId = TeamMember.myself().getId();
                msg.message = s;
                msg.time = new Date();
                msg.isRead = true;
                ChatDatabase db = ChatDatabase.getInstance(getActivity().getApplicationContext());
                db.insert(msg);
                return null;
            }
        }.execute(s);
    }

    private TextWatcher inputTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            sendButton.setEnabled(s.length() > 0);
        }
    };

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        Log.d(TAG, "getLoaderManager().initLoader()");
        getLoaderManager().initLoader(0, null, this);
    }

    @Override
    public Loader<ChatMessageCursor> onCreateLoader(int i, Bundle args) {
        Log.d(TAG, "onCreateLoader");
        return new ChatCursorLoader(getActivity());
    }

    @Override
    public void onLoadFinished(Loader<ChatMessageCursor> loader, ChatMessageCursor cursor) {
        Log.d(TAG, "onLoadFinished");
        adapter.setCursor(cursor);
    }

    @Override
    public void onLoaderReset(Loader<ChatMessageCursor> loader) {
        Log.d(TAG, "onLoaderReset");
        adapter.setCursor(null);
    }
}
