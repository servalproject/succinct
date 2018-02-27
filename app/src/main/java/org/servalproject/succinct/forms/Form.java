package org.servalproject.succinct.forms;

import android.content.Context;
import android.util.Log;

import org.servalproject.succinct.App;
import org.servalproject.succinct.networking.Hex;
import org.servalproject.succinct.storage.DeSerialiser;
import org.servalproject.succinct.storage.Factory;
import org.servalproject.succinct.storage.RecordIterator;
import org.servalproject.succinct.storage.RecordStore;
import org.servalproject.succinct.storage.Serialiser;
import org.servalproject.succinct.storage.TeamStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

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


	private static String readFile(File file) throws IOException{
		StringBuilder sb = new StringBuilder();
		InputStreamReader in = new InputStreamReader(new FileInputStream(file), "UTF-8");
		char[] buff = new char[1024];
		while(true) {
			int len = in.read(buff);
			if (len<0)
				break;
			sb.append(buff, 0, len);
		}
		return sb.toString();
	}

	public static void compress(Context context, File formSpecification, File completedRecord) throws IOException {
		compress(context, readFile(formSpecification), readFile(completedRecord));
	}

	public static void compress(Context context, String formSpecification, String completedRecord){
		App app = (App)context.getApplicationContext();
		TeamStorage store = app.teamStorage;
		if (store == null || !store.isTeamActive()){
			Log.w(TAG, "***** THROWING AWAY FORM, NOT IN AN ACTIVE TEAM *****");
			return;
		}

		long time = System.currentTimeMillis();
		String stripped = Recipe.stripForm(completedRecord);
		Map<String, String> fields = Recipe.parse(stripped);
		String uuid = fields.get("uuid");

		try{
			RecordIterator<Form> iterator = store.openIterator(factory, app.networks.myId);
			if (iterator.store.getProperty(uuid) == null){
				Log.v(TAG, "Compressing record "+uuid);
				Recipe recipe = new Recipe(formSpecification);
				try{
					Stats stats = Stats.getInstance(context);
					byte[] compressed = recipe.compress(stats, stripped);
					iterator.append(new Form(time, compressed));
					iterator.store.putProperty(uuid, "submitted");
					// store a copy of the form definition in a file
					// (shouldn't matter who writes it first, the content should be the same)
					RecordStore storeDefinition = store.openFile("forms/"+ Hex.toString(recipe.hash));
					if (storeDefinition.EOF == 0){
						storeDefinition.appendAt(0, formSpecification.getBytes("UTF-8"));
						storeDefinition.flush(null);
					}
				}finally {
					recipe.close();
				}
			}else{
				Log.v(TAG, "Ignored record "+uuid+", already sent");
			}
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
}
