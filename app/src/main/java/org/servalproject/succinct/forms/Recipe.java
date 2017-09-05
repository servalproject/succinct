package org.servalproject.succinct.forms;

import android.util.Log;

class Recipe {
	long ptr;
	String recipe;
	byte[] hash;
	String formName;

	private native void buildRecipe(String content);
	private static native void closeRecipe(long ptr);
	private static String TAG = "Recipe";

	Recipe(String content){
		Log.v(TAG, "buildRecipe");
		buildRecipe(content);
	}

	private void callback(String formName, byte[] hash, String recipe, long ptr){
		Log.v(TAG, "Callback "+formName+", "+recipe);
		this.formName = formName;
		this.hash = hash;
		this.recipe = recipe;
		this.ptr = ptr;
	}

	void close(){
		Log.v(TAG, "closeRecipe");
		closeRecipe(ptr);
		ptr = 0;
	}
}
