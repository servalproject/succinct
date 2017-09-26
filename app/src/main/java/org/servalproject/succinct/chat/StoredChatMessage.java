package org.servalproject.succinct.chat;

import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.Serialiser;

import java.util.Date;

/**
 * Created by kieran on 26/09/17.
 */

public class StoredChatMessage {

    public final int type;
    public final Date time;
    public final String message;

    public StoredChatMessage(int type, Date time, String message) {
        this.type = type;
        this.time = time;
        this.message = message;
    }

    public static final Factory<StoredChatMessage> factory = new Factory<StoredChatMessage>() {
        @Override
        public String getFileName() {
            return "chat";
        }

        @Override
        public StoredChatMessage create(DeSerialiser serialiser) {
            int type = (int) serialiser.getByte();
            Date time = new Date(serialiser.getRawLong());
            String message = serialiser.getEndString();
            return new StoredChatMessage(type, time, message);
        }

        @Override
        public void serialise(Serialiser serialiser, StoredChatMessage object) {
            serialiser.putByte((byte) object.type);
            serialiser.putRawLong(object.time.getTime());
            serialiser.putEndString(object.message);
        }
    };
}
