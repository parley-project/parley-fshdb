/**
 *	Copyright 2026 Tony Bringardner
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *	You may obtain a copy of the License at
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 *	the specific language governing permissions and limitations under the License.
 */
package us.bringardner.fshdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.fsh.ide.core.Breakpoint;

/**
 * The breakpoints, numbered 1, 2 ... as they're made, on file lines (1-based).
 * <p>
 * fsh-ide's DebugSession looks breakpoints up by editor line on the script's thread; that
 * lookup (forEditorLine) reads a concurrent map that is rebuilt when breakpoints or the
 * script's lines change.
 * <p>
 * A change listener (see setChangeListener) hears of each change, after it's made, on the
 * thread that made it; an exception it throws goes to that caller (the change stays made).
 */
public class Breakpoints {

	/** A breakpoint: fsh-ide's Breakpoint (condition, hit count, enabled) plus its number and file line. */
	public static final class Entry {
		public final int number;
		public final int fileLine;
		public final Breakpoint breakpoint;

		Entry(int number, int fileLine, Breakpoint breakpoint) {
			this.number = number;
			this.fileLine = fileLine;
			this.breakpoint = breakpoint;
		}
	}

	private final Map<Integer,Entry> byNumber = new TreeMap<>();
	private final Map<Integer,Breakpoint> byEditorLine = new ConcurrentHashMap<>();
	private int next = 1;
	private ScriptFile script;
	private volatile Runnable changeListener;

	public Breakpoints(ScriptFile script) {
		this.script = script;
	}

	/** Told after each change (add, remove, clear, condition, enable); null for none. */
	public void setChangeListener(Runnable listener) {
		this.changeListener = listener;
	}

	private void changed() {
		Runnable r = changeListener;
		if( r != null ) {
			r.run();
		}
	}

	/** The script's lines changed (it was edited or another one was opened). */
	public synchronized void setScript(ScriptFile script) {
		this.script = script;
		reindex();
	}

	/** The breakpoint on an editor line (0-based), or null. Safe on any thread. */
	public Breakpoint forEditorLine(int editorLine) {
		return byEditorLine.get(editorLine);
	}

	/** The breakpoint on a file line (1-based), or null. */
	public synchronized Entry atFileLine(int fileLine) {
		for(Entry e : byNumber.values()) {
			if( e.fileLine == fileLine ) {
				return e;
			}
		}
		return null;
	}

	/**
	 * Adds a breakpoint, or returns the one already on the line (its condition and hit count are
	 * replaced by these).
	 * @param condition a command (such as [ $i -gt 3 ]): stop only if it succeeds; null for none
	 * @param hitCount stop only on this hit; 0 for every hit
	 */
	public Entry add(int fileLine, String condition, int hitCount) {
		Entry e;
		synchronized (this) {
			e = atFileLine(fileLine);
			if( e == null ) {
				Breakpoint bp = new Breakpoint();
				e = new Entry(next++, fileLine, bp);
				byNumber.put(e.number, e);
			}
			applyCondition(e, condition);
			e.breakpoint.setHitCount(hitCount > 0 ? hitCount : -1);
			e.breakpoint.setHitCount(hitCount > 0);
			e.breakpoint.reset();
			reindex();
		}
		changed();
		return e;
	}

	/** Sets (or, with null or "", removes) a breakpoint's condition. */
	public void setCondition(Entry e, String condition) {
		synchronized (this) {
			applyCondition(e, condition);
		}
		changed();
	}

	private static void applyCondition(Entry e, String condition) {
		boolean has = condition != null && !condition.isBlank();
		e.breakpoint.setConditional(has);
		e.breakpoint.setCondition(has ? condition.trim() : "");
	}

	/** Enables or disables a breakpoint. */
	public void setEnabled(Entry e, boolean enabled) {
		synchronized (this) {
			e.breakpoint.setEnabled(enabled);
		}
		changed();
	}

	public synchronized Entry get(int number) {
		return byNumber.get(number);
	}

	public boolean remove(int number) {
		boolean ret;
		synchronized (this) {
			ret = byNumber.remove(number) != null;
			reindex();
		}
		if( ret ) {
			changed();
		}
		return ret;
	}

	public void clear() {
		boolean had;
		synchronized (this) {
			had = !byNumber.isEmpty();
			byNumber.clear();
			reindex();
		}
		if( had ) {
			changed();
		}
	}

	/** In number order. */
	public synchronized List<Entry> list() {
		return new ArrayList<>(byNumber.values());
	}

	public synchronized boolean isEmpty() {
		return byNumber.isEmpty();
	}

	/** Before a run: hit counts start again and a bad condition is reported again. */
	public synchronized void resetForRun() {
		for(Entry e : byNumber.values()) {
			e.breakpoint.reset();
		}
	}

	private void reindex() {
		Map<Integer,Breakpoint> m = new ConcurrentHashMap<>();
		for(Entry e : byNumber.values()) {
			int line = script == null ? -1 : script.editorLine(e.fileLine);
			e.breakpoint.setLine(line);
			if( line >= 0 ) {
				m.put(line, e.breakpoint);
			}
		}
		byEditorLine.keySet().retainAll(m.keySet());
		byEditorLine.putAll(m);
	}
}
