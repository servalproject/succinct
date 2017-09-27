package org.servalproject.succinct.chat;

import android.content.ContentValues;
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
import org.servalproject.succinct.team.MembershipList;
import org.servalproject.succinct.team.TeamMember;

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

    private final App app;
    private static ChatDatabase instance;
    private static HashMap<String, Long> teamCache = new HashMap<>();
    private static HashMap<String, Long> senderCache = new HashMap<>();

    @IntDef({TYPE_MESSAGE, TYPE_JOIN, TYPE_PART, TYPE_UNKNOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChatMessageType {}
    public static final int TYPE_MESSAGE = 0;
    public static final int TYPE_JOIN = 1;
    public static final int TYPE_PART = 2;
    public static final int TYPE_UNKNOWN = -1;

    public static class ChatMessage {
        public final long id;
        @ChatMessageType
        public final int type;
        public final TeamMember sender;
        public final Date time;
        public final String message;
        public final boolean isRead;
        public final boolean isFirstOnDay;
        public final boolean sentByMe;

        public ChatMessage(ChatMessageCursor c, MembershipList members) {
            id = c.getId();
            int msgType = c.getMessageType();
            switch (msgType) {
                case TYPE_MESSAGE:
                case TYPE_JOIN:
                case TYPE_PART:
                    // noinspection WrongConstant
                    type = msgType;
                    break;
                default:
                    type = TYPE_UNKNOWN;
            }
            try {
                sender = members.getTeamMember(c.getPeerId());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            time = c.getTime();
            message = c.getMessage();
            isRead = c.getIsRead();
            isFirstOnDay = c.getIsFirstOnDay();
            sentByMe = c.getIsSentByMe();
        }
    }

    public static class ChatMessageCursor extends CursorWrapper {
        public static final int ID = 0;
        public static final int MESSAGE_TYPE = 1;
        public static final int PEER_ID = 2;
        public static final int TIME = 3;
        public static final int MESSAGE = 4;
        public static final int IS_READ = 5;
        public static final int IS_SENT_BY_ME = 6;

        private ChatMessageCursor(Cursor c) {
            super(c);
        }

        public long getId() { return getLong(ID); }
        public int getMessageType() { return getInt(MESSAGE_TYPE); }
        public PeerId getPeerId() { return new PeerId(getString(PEER_ID)); }
        public Date getTime() { return new Date(getLong(TIME)); }
        public String getMessage() { return getString(MESSAGE); }
        public boolean getIsRead() { return getInt(IS_READ) != 0; }
        public boolean getIsSentByMe() { return getInt(IS_SENT_BY_ME) != 0; }

        public boolean getIsFirstOnDay() {
            if (isFirst()) return true;
            long day, prevDay;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(getLong(TIME));
            day = cal.get(Calendar.YEAR)*1000+cal.get(Calendar.DAY_OF_YEAR);
            moveToPrevious();
            cal.setTimeInMillis(getLong(TIME));
            prevDay = cal.get(Calendar.YEAR)*1000+cal.get(Calendar.DAY_OF_YEAR);
            moveToNext();
            return (day != prevDay);
        }

        public static ChatMessageCursor getCursor(SQLiteDatabase db, long teamId, long mySenderId) {
            final String SELECT_CHAT_MESSAGES = "SELECT "
                    + ChatMessageTable._TABLE_NAME + "." + ChatMessageTable._ID + ", "
                    + ChatMessageTable.TYPE + ", "
                    + SenderTable.PEER_ID + ", "
                    + ChatMessageTable.TIME + ", "
                    + ChatMessageTable.MESSAGE + ", "
                    + ChatMessageTable.IS_READ + ", "
                    + "(" + ChatMessageTable.SENDER + " = " + mySenderId + ") AS sent_by_me"
                    + " FROM " + ChatMessageTable._TABLE_NAME + " LEFT JOIN " + SenderTable._TABLE_NAME
                    + " ON " + ChatMessageTable.SENDER + " = " + SenderTable._TABLE_NAME + "." + SenderTable._ID
                    + " AND " + ChatMessageTable._TABLE_NAME + "." + ChatMessageTable.TEAM + " = " + SenderTable._TABLE_NAME + "." + SenderTable.TEAM
                    + " WHERE " + ChatMessageTable._TABLE_NAME + "." + ChatMessageTable.TEAM + " = " + teamId
                    + " ORDER BY " + ChatMessageTable.TIME + ", " + ChatMessageTable._TABLE_NAME + "." + ChatMessageTable._ID;
            return new ChatMessageCursor(db.rawQuery(SELECT_CHAT_MESSAGES, null));
        }
    }

    public ChatMessageCursor getChatMessageCursor() {
        SQLiteDatabase db = getReadableDatabase();
        long team = getTeamId(db, app.teamStorage.teamId);
        long me = getSenderId(db, team, app.networks.myId);
        return ChatMessageCursor.getCursor(db, team, me);
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

    private ChatDatabase(App app) {
        super(app, DATABASE_NAME, null, DATABASE_VERSION);
        this.app = app;
    }

    public static synchronized ChatDatabase getInstance (App app) {
        if (instance == null) {
            instance = new ChatDatabase(app);
        }
        return instance;
    }

    private void notifyChange() {
        app.getContentResolver().notifyChange(URI_CHAT_DATA, null);
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
        } else if (db.isReadOnly()) {
            c.close();
            return -1;
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
