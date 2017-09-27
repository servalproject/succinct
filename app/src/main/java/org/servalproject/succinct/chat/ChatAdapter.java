package org.servalproject.succinct.chat;

import android.database.Cursor;
import android.support.annotation.IntDef;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.servalproject.succinct.R;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessage;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessageCursor;
import org.servalproject.succinct.team.MembershipList;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DateFormat;

/**
 * Created by kieran on 1/08/17.
 */

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {
    private static final String TAG = "ChatAdapter";
    private DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);
    private DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.LONG);
    private boolean atBottom = false;
    private ChatMessageCursor cursor;
    private final MembershipList membershipList;

    @IntDef({TYPE_MESSAGE_RECEIVED, TYPE_MESSAGE_SENT})
    @Retention(RetentionPolicy.SOURCE)
    private @interface ChatViewType {}
    private static final int TYPE_MESSAGE_RECEIVED = 0;
    private static final int TYPE_MESSAGE_SENT = 1;

    public ChatAdapter(MembershipList members) {
        setHasStableIds(true);
        membershipList = members;
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
    public MessageViewHolder onCreateViewHolder(ViewGroup parent, @ChatViewType int viewType) {
        int layout;
        switch (viewType) {
            case TYPE_MESSAGE_RECEIVED:
                layout = R.layout.chat_item;
                break;
            case TYPE_MESSAGE_SENT:
                layout = R.layout.chat_item_sent;
                break;
            default:
                throw new IllegalArgumentException();
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new MessageViewHolder(viewType, view);
    }

    @Override
    public void onBindViewHolder(MessageViewHolder holder, int position) {
        cursor.moveToPosition(position);
        ChatMessage msg = new ChatMessage(cursor, membershipList);

        if (msg.isFirstOnDay) {
            holder.horizontalRule.setVisibility(View.VISIBLE);
            holder.date.setText(dateFormat.format(msg.time));
            holder.date.setVisibility(View.VISIBLE);
        } else {
            holder.horizontalRule.setVisibility(View.GONE);
            holder.date.setVisibility(View.GONE);
        }

        holder.message.setText(msg.message);
        holder.sender.setText(msg.sender == null ? "???" : msg.sender.name);
        holder.time.setText(timeFormat.format(msg.time));
    }

    @Override
    public int getItemCount() {
        return (cursor == null ? 0 : cursor.getCount());
    }

    @Override
    public long getItemId(int position) {
        if (cursor != null && cursor.moveToPosition(position)) {
            return cursor.getId();
        } else {
            return -1;
        }
    }

    @Override
    public @ChatViewType int getItemViewType(int position) {
        if (cursor == null || !cursor.moveToPosition(position)) {
            //noinspection WrongConstant
            return -1;
        }
        if (cursor.getIsSentByMe()) {
            return TYPE_MESSAGE_SENT;
        } else {
            return TYPE_MESSAGE_RECEIVED;
        }
    }

    public void enableStickyScroll(RecyclerView recycler) {
        recycler.scrollToPosition(getItemCount()-1);
        atBottom = true;
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(final RecyclerView v, int dx, int dy) {
                boolean canScrollDown = v.canScrollVertically(1);
                if (atBottom && dx == 0 && dy == 0 && canScrollDown) {
                    // layout change will be handled by OnLayoutChangeListener
                    return;
                }
                atBottom = !canScrollDown;
            }
        });
        recycler.addOnLayoutChangeListener(new RecyclerView.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(final View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                boolean canScrollDown = v.canScrollVertically(1);
                if (!atBottom || !canScrollDown) return;
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ((RecyclerView) v).scrollToPosition(getItemCount() - 1);
                    }
                }, 100);
            }
        });
    }

    public class MessageViewHolder extends RecyclerView.ViewHolder {
        protected @ChatViewType int type;
        protected View horizontalRule;
        protected TextView date;
        protected TextView sender;
        protected TextView message;
        protected TextView time;

        public MessageViewHolder(@ChatViewType int type, View view) {
            super(view);
            this.type = type;
            horizontalRule = view.findViewById(R.id.horizontal_rule);
            date = (TextView) view.findViewById(R.id.dateText);
            sender = (TextView) view.findViewById(R.id.senderText);
            message = (TextView) view.findViewById(R.id.messageText);
            time = (TextView) view.findViewById(R.id.messageTime);
        }
    }
}
