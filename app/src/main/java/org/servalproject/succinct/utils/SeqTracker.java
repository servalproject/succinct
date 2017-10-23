package org.servalproject.succinct.utils;


import android.util.Log;

// Track which sequence numbers we have seen as a linked list of ranges.
public class SeqTracker {
	private Range head;
	private Range tail;
	private static final String TAG = "SeqTracker";

	private class Range{
		Range next;
		Range prev;
		int first;
		int last;
		Range(int f, int l){
			first = f;
			last = l;
		}
	}

	public SeqTracker() {
	}

	public SeqTracker(String seq){
		if (seq == null || "".equals(seq) || "{}".equals(seq))
			return;

		int mark=-1;
		int i=0;
		int first=-1;
		char c=seq.charAt(i++);
		if (c != '{')
			throw new IllegalArgumentException();

		mark=i;
		while(c!='}'){
			c=seq.charAt(i++);
			switch (c){
				case '-':
					first = Integer.parseInt(seq.substring(mark, i-1),10);
					mark = i;
					break;
				case ',':
				case '}':
					int last = Integer.parseInt(seq.substring(mark, i-1),10);
					mark = i;
					if (first==-1)
						first = last;
					Range r = new Range(first, last);
					if (tail == null){
						head = tail = r;
					}else{
						r.prev = tail;
						tail.next = r;
						tail = r;
					}
					first=-1;
					break;
			}
		}
	}

	public static void testSeq(){
		SeqTracker x = new SeqTracker();
		Log.v(TAG, "blank "+x+", "+x.nextMissing()+", "+new SeqTracker(x.toString()));
		int[] seq = new int[]{1, 2, 2, 0, 5, 4, 7, 3, 4, 6};
		for (int i=0;i<seq.length;i++){
			boolean r = x.received(seq[i]);
			Log.v(TAG, i+", "+seq[i]+" "+x+", "+r+", "+x.nextMissing()+", "+new SeqTracker(x.toString()));
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("{");
		Range n = head;
		while(n!=null){
			if(sb.length()>1)
				sb.append(',');
			sb.append(n.first);
			if (n.first!=n.last){
				sb.append('-');
				sb.append(n.last);
			}
			n=n.next;
		}
		sb.append("}");
		return sb.toString();
	}

	public int nextMissing(){
		if (head == null || head.first!=0)
			return 0;
		return head.last+1;
	}

	private void insert(int seq, Range prev, Range next){
		Range r = new Range(seq, seq);
		if (prev == null){
			head = r;
		}else{
			prev.next = r;
			r.prev = prev;
		}
		if (next == null){
			tail = r;
		}else{
			next.prev = r;
			r.next = next;
		}
	}

	private void merge(Range prev, Range next){
		if (prev == null || next == null)
			return;
		if (prev.last+1 != next.first)
			return;
		prev.last = next.last;
		prev.next = next.next;
		if (next.next == null){
			tail = prev;
		}else{
			next.next.prev = prev;
		}
	}

	// returns true if this is a new seq
	public synchronized boolean received(int seq){
		Range n = head;
		while(n!=null){
			if (seq >= n.first && seq <= n.last)
				return false;

			if (seq == n.first -1){
				n.first--;
				merge(n.prev, n);
				return true;
			}else if (seq < n.first){
				insert(seq, n.prev, n);
				return true;
			}else if (seq == n.last +1){
				n.last++;
				merge(n, n.next);
				return true;
			}
			n=n.next;
		}
		insert(seq, tail, null);
		return true;
	}
}
