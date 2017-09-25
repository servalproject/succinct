package org.servalproject.succinct.messaging;

public interface IMessaging {
	int UNAVAILABLE=0;
	int BUSY=1;
	int SUCCESS=2;

	int trySend(Fragment fragment);
	void done();
}
