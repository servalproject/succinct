package org.servalproject.succinct.messaging;

import java.io.IOException;

/**
 * Created by jeremy on 28/11/17.
 */

public interface IMessageSource {
	boolean hasMessage() throws IOException;
	boolean nextMessage() throws IOException;
	void activate();
	void deactivate();
}
