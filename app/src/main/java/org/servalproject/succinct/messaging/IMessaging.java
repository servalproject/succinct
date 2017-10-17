package org.servalproject.succinct.messaging;

public interface IMessaging {
	int UNAVAILABLE=0;
	int BUSY=1;
	int SUCCESS=2;

	int getMTU();
	int checkAvailable();
	int trySend(Fragment fragment);
	void done();
	void close();
}
