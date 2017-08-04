package org.servalproject.succinct.chat;

import android.database.Cursor;
import android.provider.BaseColumns;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.servalproject.succinct.R;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessage;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessageCursor;

import java.text.DateFormat;
import java.util.List;

/**
 * Created by kieran on 1/08/17.
 */

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {
    private static final String TAG = "ChatAdapter";
    private DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);
    private DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.LONG);
    private boolean atBottom = false;
    private ChatMessageCursor cursor;

    public ChatAdapter() {
        setHasStableIds(true);
    }

    public void setCursor(ChatMessageCursor c) {
        if (c == cursor) {
            return;
        }
        Cursor old = cursor;
        cursor = c;
        if (old != null) {
            old.close();
        }
        notifyDataSetChanged();
    }

    public Cursor getCursor() {
        return cursor;
    }

    @Override
    public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.chat_item, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(MessageViewHolder holder, int position) {
        cursor.moveToPosition(position);
        ChatMessage msg = new ChatMessage(cursor);

        if (msg.isFirstOnDay) {
            holder.horizontalRule.setVisibility(View.VISIBLE);
            holder.date.setText(dateFormat.format(msg.time));
            holder.date.setVisibility(View.VISIBLE);
        } else {
            holder.horizontalRule.setVisibility(View.GONE);
            holder.date.setVisibility(View.GONE);
        }

        holder.message.setText(msg.message);
        holder.sender.setText(msg.sender);
        holder.time.setText(timeFormat.format(msg.time));
    }

    @Override
    public int getItemCount() {
        return (cursor == null ? 0 : cursor.getCount());
    }

    @Override
    public long getItemId(int position) {
        if (cursor != null && cursor.moveToPosition(position)) {
            return cursor.getLong(ChatMessageCursor.ID);
        } else {
            return -1;
        }
    }

    public void enableStickyScroll(RecyclerView recycler) {
        recycler.scrollToPosition(getItemCount()-1);
        atBottom = true;
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(final RecyclerView v, int dx, int dy) {
                super.onScrolled(v, dx, dy);
                boolean canScrollDown = v.canScrollVertically(1);
                if (atBottom && dx == 0 && dy == 0 && canScrollDown) {
                    // layout changed, need to scroll
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            v.scrollToPosition(getItemCount() - 1);
                        }
                    }, 100);
                } else {
                    atBottom = !canScrollDown;
                }
            }
        });
    }

    public class MessageViewHolder extends RecyclerView.ViewHolder {
        protected View horizontalRule;
        protected TextView date;
        protected TextView sender;
        protected TextView message;
        protected TextView time;
        public MessageViewHolder(View view) {
            super(view);
            horizontalRule = view.findViewById(R.id.horizontal_rule);
            date = (TextView) view.findViewById(R.id.dateText);
            sender = (TextView) view.findViewById(R.id.senderText);
            message = (TextView) view.findViewById(R.id.messageText);
            time = (TextView) view.findViewById(R.id.messageTime);
        }
    }
}
