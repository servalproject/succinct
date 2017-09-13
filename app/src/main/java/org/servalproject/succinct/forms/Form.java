package org.servalproject.succinct.forms;

import android.content.Context;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.storage.BlobFactory;
import org.servalproject.succinct.storage.RecordIterator;

import java.io.IOException;

public class Form {
	private Form(){}

	public static final BlobFactory factory = new BlobFactory("forms");
	private static final String TAG = "Forms";

	public static void compress(Context context, String formSpecification, String completedRecord){
		Stats stats = Stats.getInstance(context);
		try{
			byte[] compressed = Recipe.compress(stats, formSpecification, completedRecord);
			App app = (App)context.getApplicationContext();
			RecordIterator<byte[]> records = app.teamStorage.openIterator(factory, app.networks.myId);
			records.append(compressed);
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
}
