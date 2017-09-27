/*
 * License applies to this file:
 *
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.servalproject.succinct.chat;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.chat.ChatDatabase.ChatMessageCursor;

/**
 * Based on CursorLoader from the android support library
 * Handles Loader tasks without having to have a ContentProvider
 */
public class ChatCursorLoader extends AsyncTaskLoader<ChatMessageCursor> {
    private static final String TAG = "ChatCursorLoader";
    private final ForceLoadContentObserver mObserver;
    private final App mApp;
    private ChatMessageCursor mCursor;

    /* Runs on a worker thread */
    @Override
    public ChatMessageCursor loadInBackground() {
        Log.d(TAG, "loadInBackground");
        ChatDatabase db = ChatDatabase.getInstance(mApp);
        ChatMessageCursor c = db.getChatMessageCursor();
        // preload cursor count
        c.getCount();
        // register observer
        c.registerContentObserver(mObserver);
        c.setNotificationUri(getContext().getContentResolver(), ChatDatabase.URI_CHAT_DATA);
        return c;
    }

    /* Runs on the UI thread */
    @Override
    public void deliverResult(ChatMessageCursor cursor) {
        if (isReset()) {
            // An async query came in while the loader is stopped
            if (cursor != null) {
                cursor.close();
            }
            return;
        }
        Cursor oldCursor = mCursor;
        mCursor = cursor;
        if (isStarted()) {
            Log.d(TAG, "super.deliverResult(cursor)");
            super.deliverResult(cursor);
        }
        if (oldCursor != null && oldCursor != cursor && !oldCursor.isClosed()) {
            oldCursor.close();
        }
    }
    public ChatCursorLoader(App app) {
        super(app);
        mApp = app;
        mObserver = new ForceLoadContentObserver();
    }
    /**
     * When the result is ready the callbacks
     * will be called on the UI thread. If a previous load has been completed and is still valid
     * the result may be passed to the callbacks immediately.
     *
     * Must be called from the UI thread
     */
    @Override
    protected void onStartLoading() {
        if (mCursor != null) {
            deliverResult(mCursor);
        }
        if (takeContentChanged() || mCursor == null) {
            forceLoad();
        }
    }
    /**
     * Must be called from the UI thread
     */
    @Override
    protected void onStopLoading() {
        // Attempt to cancel the current load task if possible.
        cancelLoad();
    }
    @Override
    public void onCanceled(ChatMessageCursor cursor) {
        if (cursor != null && !cursor.isClosed()) {
            cursor.close();
        }
    }
    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();
        if (mCursor != null && !mCursor.isClosed()) {
            mCursor.close();
        }
        mCursor = null;
    }
}
