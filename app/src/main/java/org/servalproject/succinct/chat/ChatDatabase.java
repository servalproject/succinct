package org.servalproject.succinct.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Environment;
import android.provider.BaseColumns;
import android.support.annotation.IntDef;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.BuildConfig;
import org.servalproject.succinct.networking.PeerId;
import org.servalproject.succinct.storage.RecordIterator;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;

/**
 * Created by kieran on 28/07/17.
 */

public class ChatDatabase extends SQLiteOpenHelper {
    private static final String TAG = "ChatDatabase";
    private static final String DATABASE_NAME = Environment.getExternalStorageDirectory() + "/succinct/chatlog.db"; // todo don't store on SD card?
    private static final int DATABASE_VERSION = 2;
    public static final Uri URI_CHAT_DATA = Uri.parse("sqlite://" + BuildConfig.APPLICATION_ID + "/chatlog");

    private static Context mContext;
    private static ChatDatabase instance;
    private static HashMap<String, Long> teamCache = new HashMap<>();
    private static HashMap<String, Long> senderCache = new HashMap<>();

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
        public int senderId;
        public String sender;
        public Date time;
        public String message;
        public boolean isRead;
        public boolean isFirstOnDay;
        public boolean sentByMe;

        public ChatMessage(ChatMessageCursor c) {
            // todo don't hard code
            id = c.getLong(0);
            //noinspection WrongConstant
            type = c.getInt(1);
            senderId = c.getInt(2);
            sender = c.getString(3);
            time = new Date(c.getLong(4));
            message = c.getString(5);
            isRead = (c.getInt(6) != 0);
            sentByMe = (c.getInt(7) != 0);

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
                cal.setTimeInMillis(c.getLong(4));
                prevDay = cal.get(Calendar.YEAR)*1000+cal.get(Calendar.DAY_OF_YEAR);
                isFirstOnDay = (day != prevDay);
            }
        }
    }

    public static class ChatMessageCursor extends CursorWrapper {
        // todo set up all fields
        public static final int ID = 0;
        public static final int SENT_BY_ME = 7;
        public ChatMessageCursor(Cursor c) {
            super(c);
        }
    }

    public ChatMessageCursor getChatMessageCursor() {
        SQLiteDatabase db = getReadableDatabase();
        final String SELECT_CHAT_MESSAGES = "SELECT "
                + ChatMessageTable._TABLE_NAME + "." + ChatMessageTable._ID + ", "
                + ChatMessageTable.TYPE + ", "
                + ChatMessageTable.SENDER + ", "
                + SenderTable.NAME + ", "
                + ChatMessageTable.TIME + ", "
                + ChatMessageTable.MESSAGE + ", "
                + ChatMessageTable.IS_READ + ", "
                + " 1 AS sent_by_me" + " FROM " // TODO FIXME
                + ChatMessageTable._TABLE_NAME + " LEFT JOIN "
                + SenderTable._TABLE_NAME + " ON "
                + ChatMessageTable.SENDER + " = " + SenderTable._TABLE_NAME + "." + SenderTable._ID;
        return new ChatMessageCursor(db.rawQuery(SELECT_CHAT_MESSAGES, null));
    }

    private static final class ChatMessageTable implements BaseColumns {
        private static final String _TABLE_NAME = "messages";
        private static final String TEAM = "team";
        private static final String TYPE = "type";
        private static final String TIME = "time";
        private static final String SENDER = "sender";
        private static final String MESSAGE = "message";
        private static final String IS_READ = "is_read";
    }

    private static final class SenderTable implements BaseColumns {
        private static final String _TABLE_NAME = "senders";
        private static final String TEAM = "team";
        private static final String PEER_ID = "peer_id";
        private static final String PEER_NUMBER = "peer_number";
        private static final String NAME = "name";
    }

    private static final class TeamTable implements BaseColumns {
        private static final String _TABLE_NAME = "teams";
        private static final String TEAM = "team";
    }

    private ChatDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    public static synchronized ChatDatabase getInstance (Context context) {
        if (instance == null) {
            instance = new ChatDatabase(context);
        }
        return instance;
    }

    private void notifyChange() {
        mContext.getContentResolver().notifyChange(URI_CHAT_DATA, null);
    }

    public void insert(PeerId team, PeerId sender, RecordIterator<StoredChatMessage> records) throws IOException {
        SQLiteDatabase db = getWritableDatabase();

        long teamId = getTeamId(db, team);
        long senderId = getSenderId(db, teamId, sender);

        db.beginTransaction();

        ContentValues v = new ContentValues();
        while (records.next()) {
            StoredChatMessage msg = records.read();
            if (msg.type != TYPE_MESSAGE) {
                Log.e(TAG, "Unexpected StoredChatMessage type");
                continue;
            }
            v.clear();
            v.put(ChatMessageTable.TEAM, teamId);
            v.put(ChatMessageTable.TYPE, msg.type);
            v.put(ChatMessageTable.TIME, msg.time.getTime());
            v.put(ChatMessageTable.SENDER, senderId);
            v.put(ChatMessageTable.MESSAGE, msg.message);
            v.put(ChatMessageTable.IS_READ, false); // todo true if self

            Log.d(TAG, "insert chat message: " + v);
            db.insert(ChatMessageTable._TABLE_NAME, null, v);
        }

        db.setTransactionSuccessful();
        db.endTransaction();
        db.close();

        notifyChange();
    }

    private long getTeamId(SQLiteDatabase db, PeerId team) {
        String key = team.toString();
        Long id = teamCache.get(key);
        if (id != null) return id;
        Cursor c = db.query(TeamTable._TABLE_NAME, new String[]{TeamTable._ID},
                TeamTable.TEAM + " = ?",
                new String[]{key}, null, null, "1");
        if (c.moveToFirst()) {
            id = c.getLong(0);
        } else {
            ContentValues v = new ContentValues();
            v.put(TeamTable.TEAM, key);
            id = db.insert(TeamTable._TABLE_NAME, null, v);
            if (id == -1) throw new IllegalArgumentException();
        }
        c.close();
        teamCache.put(key, id);
        return id;
    }

    private long getSenderId(SQLiteDatabase db, long team, PeerId sender) {
        String senderHex = sender.toString();
        String key = team + "/" + senderHex;
        Long id = senderCache.get(key);
        if (id != null) return id;
        Cursor c = db.query(SenderTable._TABLE_NAME, new String[]{SenderTable._ID},
                SenderTable.TEAM + " = " + team + " AND " + SenderTable.PEER_ID + " = ?",
                new String[]{senderHex}, null, null, "1");
        if (c.moveToFirst()) {
            id = c.getLong(0);
        } else {
            ContentValues v = new ContentValues();
            v.put(SenderTable.TEAM, team);
            v.put(SenderTable.PEER_ID, senderHex);
            id = db.insert(SenderTable._TABLE_NAME, null, v);
            if (id == -1) throw new IllegalArgumentException();
        }
        c.close();
        senderCache.put(key, id);
        return id;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "onCreate");
        String CREATE_CHAT_MESSAGE_TABLE = "CREATE TABLE " + ChatMessageTable._TABLE_NAME + " ("
                + ChatMessageTable._ID + " INTEGER PRIMARY KEY, "
                + ChatMessageTable.TEAM + " INTEGER NOT NULL, "
                + ChatMessageTable.TYPE + " INTEGER NOT NULL, "
                + ChatMessageTable.TIME + " INTEGER NOT NULL, "
                + ChatMessageTable.SENDER + " INTEGER, "
                + ChatMessageTable.MESSAGE + " TEXT, "
                + ChatMessageTable.IS_READ + " INTEGER NOT NULL )";

        String CREATE_CHAT_MESSAGE_TIME_INDEX = "CREATE INDEX idx_chatlog_time ON "
                + ChatMessageTable._TABLE_NAME + "(" + ChatMessageTable.TEAM + ", " + ChatMessageTable.TIME + ")";

        String CREATE_SENDER_TABLE = "CREATE TABLE " + SenderTable._TABLE_NAME + " ("
                + SenderTable._ID + " INTEGER PRIMARY KEY, "
                + SenderTable.TEAM + " INTEGER NOT NULL, "
                + SenderTable.PEER_ID + " TEXT NOT NULL, "
                + SenderTable.PEER_NUMBER + " INTEGER, "
                + SenderTable.NAME + " TEXT )";

        String CREATE_SENDER_PEER_ID_INDEX = "CREATE INDEX idx_sender_peer_id ON "
                + SenderTable._TABLE_NAME + "(" + SenderTable.PEER_ID + ")";

        String CREATE_TEAM_TABLE = "CREATE TABLE " + TeamTable._TABLE_NAME + " ("
                + TeamTable._ID + " INTEGER PRIMARY KEY, "
                + TeamTable.TEAM + " TEXT NOT NULL UNIQUE )";

        db.execSQL(CREATE_CHAT_MESSAGE_TABLE);
        db.execSQL(CREATE_CHAT_MESSAGE_TIME_INDEX);
        db.execSQL(CREATE_SENDER_TABLE);
        db.execSQL(CREATE_SENDER_PEER_ID_INDEX);
        db.execSQL(CREATE_TEAM_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + ChatMessageTable._TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + SenderTable._TABLE_NAME);
        db.execSQL("DROP TABLE IF EXISTS " + TeamTable._TABLE_NAME);
        onCreate(db);
    }
}
