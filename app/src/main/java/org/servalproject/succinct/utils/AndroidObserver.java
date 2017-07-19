package org.servalproject.succinct.utils;

import android.os.Handler;

import org.servalproject.succinct.App;

import java.util.Observable;
import java.util.Observer;

/**
 * Created by jeremy on 19/07/17.
 */

public abstract class AndroidObserver implements Observer{
	private final Handler handler;

	public AndroidObserver(Handler handler){
		this.handler = handler;
	}
	public AndroidObserver(){
		this(App.UIHandler);
	}

	@Override
	public void update(final Observable observable, final Object o) {
		if (handler.getLooper().getThread() == Thread.currentThread())
			observe(observable, o);
		else{
			handler.post(new Runnable() {
				@Override
				public void run() {
					observe(observable, o);
				}
			});
		}
	}

	public abstract void observe(Observable observable, Object o);
}
