package org.servalproject.succinct.forms;

import android.content.Context;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordStore;
import org.servalproject.succinct.storage.Serialiser;

import java.io.IOException;

public class Form {

	public final long time;
	public final byte[] record;
	private Form(long time, byte[] record){
		this.time = time;
		this.record = record;
	}

	public static final Factory<Form> factory = new Factory<Form>(){
		@Override
		public String getFileName() {
			return "forms";
		}

		@Override
		public Form create(DeSerialiser serialiser) {
			long time = serialiser.getRawLong();
			return new Form(time, serialiser.getFixedBytes(DeSerialiser.REMAINING));
		}

		@Override
		public void serialise(Serialiser serialiser, Form object) {
			serialiser.putRawLong(object.time);
			serialiser.putFixedBytes(object.record);
		}
	};

	private static final String TAG = "Forms";

	public static void compress(Context context, String formSpecification, String completedRecord){
		long time = System.currentTimeMillis();
		Stats stats = Stats.getInstance(context);
		try{
			Recipe recipe = new Recipe(formSpecification);
			try{
				byte[] compressed = recipe.compress(stats, completedRecord);
				App app = (App)context.getApplicationContext();
				app.teamStorage.appendRecord(factory, app.networks.myId, new Form(time, compressed));

				// store a copy of the form definition in a file
				// (shouldn't matter who writes it first, the content should be the same)
				RecordStore storeDefinition = app.teamStorage.openFile("forms/"+ Hex.toString(recipe.hash));
				if (storeDefinition.EOF == 0){
					storeDefinition.appendAt(0, formSpecification.getBytes("UTF-8"));
					storeDefinition.flush(null);
				}
			}finally {
				recipe.close();
			}
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
}
