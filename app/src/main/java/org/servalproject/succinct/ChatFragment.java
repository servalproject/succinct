package org.servalproject.succinct;


import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Fragment;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.servalproject.succinct.chat.ChatAdapter;
import org.servalproject.succinct.chat.ChatDatabase;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessageCursor;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;


public class ChatFragment extends Fragment {
    private static final String TAG = "ChatFragment";

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
        ChatAdapter adapter = new ChatAdapter();
        recycler.setAdapter(adapter);
        adapter.enableStickyScroll(recycler);
        new ConnectChatDatabase().execute(adapter);
        return view;
    }

    private class ConnectChatDatabase extends AsyncTask<ChatAdapter, Void, ChatMessageCursor> {
        private ChatAdapter adapter;
        @Override
        protected ChatMessageCursor doInBackground(ChatAdapter... adapters) {
            adapter = adapters[0];
            ChatDatabase db = ChatDatabase.getInstance(getActivity().getApplicationContext());
            return db.getChatMessageCursor();
        }

        @Override
        protected void onPostExecute(ChatMessageCursor chatMessageCursor) {
            if (adapter != null) {
                adapter.setCursor(chatMessageCursor);
            } else {
                chatMessageCursor.close();
                Log.e(TAG, "ConnectChatDatabase called without valid adapter");
            }
        }
    }
}
