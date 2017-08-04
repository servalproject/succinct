package org.servalproject.succinct.chat;

import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.provider.BaseColumns;
import android.support.annotation.IntDef;
import android.util.Log;

import org.servalproject.succinct.team.TeamMember;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Calendar;
import java.util.Date;

/**
 * Created by kieran on 28/07/17.
 */

public class ChatDatabase extends SQLiteOpenHelper {
    private static final String TAG = "ChatDatabase";
    private static final String DATABASE_NAME = Environment.getExternalStorageDirectory() + "/succinct/chatlog.db"; // todo don't store on SD card?
    private static final int DATABASE_VERSION = 1;

    private static ChatDatabase instance;

    @IntDef({TYPE_MESSAGE, TYPE_JOIN, TYPE_PART})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChatMessageType {}
    public static final int TYPE_MESSAGE = 0;
    public static final int TYPE_JOIN = 1;
    public static final int TYPE_PART = 2;

    public static class ChatMessage {
        public long id;
        @ChatMessageType
        public int type;
        public String sender;
        public Date time;
        public String message;
        public boolean isRead;
        public boolean isFirstOnDay;
        public boolean sentByMe;

        public ChatMessage(ChatMessageCursor c) {
            id = c.getLong(0);
            //noinspection WrongConstant
            type = c.getInt(1);
            sender = c.getString(2);
            time = new Date(c.getLong(3));
            message = c.getString(4);
            isRead = (c.getInt(5) != 0);
            sentByMe = (c.getInt(6) != 0);

            // check if we should show the full date with this message
            // fixme manipulating the cursor works but is not a good approach
            if (c.isFirst()) {
                isFirstOnDay = true;
            } else {
                long day, prevDay;
                Calendar cal = Calendar.getInstance();
                cal.setTime(time);
                day = cal.get(Calendar.YEAR)*1000+cal.get(Calendar.DAY_OF_YEAR);
                c.moveToPrevious();
                cal.setTimeInMillis(c.getLong(3));
                prevDay = cal.get(Calendar.YEAR)*1000+cal.get(Calendar.DAY_OF_YEAR);
                isFirstOnDay = (day != prevDay);
            }
        }
    }

    public static class ChatMessageCursor extends CursorWrapper {
        public static final int ID = 0;
        public static final int SENT_BY_ME = 6;
        public ChatMessageCursor(Cursor c) {
            super(c);
        }
    }

    public ChatMessageCursor getChatMessageCursor() {
        SQLiteDatabase db = getReadableDatabase();
        int mySenderId = TeamMember.myself().getId();
        final String SELECT_CHAT_MESSAGES = "SELECT "
                + ChatMessageTable._TABLE_NAME + "." + ChatMessageTable._ID + ", "
                + ChatMessageTable.TYPE + ", "
                + SenderTable.NAME + ", "
                + ChatMessageTable.TIME + ", "
                + ChatMessageTable.MESSAGE + ", "
                + ChatMessageTable.IS_READ + ", "
                + ChatMessageTable.SENDER + " = " + mySenderId + " AS sent_by_me" + " FROM "
                + ChatMessageTable._TABLE_NAME + " LEFT JOIN "
                + SenderTable._TABLE_NAME + " ON "
                + ChatMessageTable.SENDER + " = " + SenderTable._TABLE_NAME + "." + SenderTable._ID;
        return new ChatMessageCursor(db.rawQuery(SELECT_CHAT_MESSAGES, null));
    }

    private static final class ChatMessageTable implements BaseColumns {
        private static final String _TABLE_NAME = "messages";
        private static final String TYPE = "type";
        private static final String TIME = "time";
        private static final String SENDER = "sender";
        private static final String MESSAGE = "message";
        private static final String IS_READ = "is_read";
    }

    private static final class SenderTable implements BaseColumns {
        private static final String _TABLE_NAME = "senders";
        private static final String NAME = "name";
    }

    private ChatDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static synchronized ChatDatabase getInstance (Context context) {
        if (instance == null) {
            instance = new ChatDatabase(context);
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "onCreate");
        String CREATE_CHAT_MESSAGE_TABLE = "CREATE TABLE " + ChatMessageTable._TABLE_NAME + " ("
                + ChatMessageTable._ID + " INTEGER PRIMARY KEY, "
                + ChatMessageTable.TYPE + " INTEGER NOT NULL, "
                + ChatMessageTable.TIME + " INTEGER NOT NULL, "
                + ChatMessageTable.SENDER + " INTEGER, "
                + ChatMessageTable.MESSAGE + " TEXT, "
                + ChatMessageTable.IS_READ + " INTEGER NOT NULL )";

        String CREATE_CHAT_MESSAGE_TIME_INDEX = "CREATE INDEX idx_chatlog_time ON "
                + ChatMessageTable._TABLE_NAME + "(" + ChatMessageTable.TIME + ")";

        String CREATE_SENDER_TABLE = "CREATE TABLE " + SenderTable._TABLE_NAME + " ("
                + SenderTable._ID + " INTEGER PRIMARY KEY, "
                + SenderTable.NAME + " TEXT )";

        db.execSQL(CREATE_CHAT_MESSAGE_TABLE);
        db.execSQL(CREATE_CHAT_MESSAGE_TIME_INDEX);
        db.execSQL(CREATE_SENDER_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + ChatMessageTable._TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + SenderTable._TABLE_NAME);
        onCreate(db);
    }
}
