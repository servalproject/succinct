package org.servalproject.succinct.messaging.rock;

import uk.rock7.connect.enums.R7MessageStatus;

public class RockMessage {
	public final short id;
	public boolean completed=false;
	public R7MessageStatus status;
	public int part=-1;
	public int total=-1;

	public RockMessage(short id){
		this.id = id;
	}
}
