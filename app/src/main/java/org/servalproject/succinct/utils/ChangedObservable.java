package org.servalproject.succinct.utils;

import java.util.Observable;

public class ChangedObservable extends Observable{
	@Override
	public void notifyObservers() {
		setChanged();
		super.notifyObservers();
	}

	@Override
	public void notifyObservers(Object arg) {
		setChanged();
		super.notifyObservers(arg);
	}
}
