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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import us.bringardner.fsh.DebugContext.RunState;
import us.bringardner.fsh.ShellContext;
import us.bringardner.fsh.ide.core.Breakpoint;
import us.bringardner.fsh.ide.core.DebugSession;
import us.bringardner.fsh.ide.core.DebugVariables;
import us.bringardner.fsh.ide.core.ScriptRun;

/**
 * Runs and debugs one script: its breakpoints, the run, where it's stopped and its variables.
 * It has no user interface; the command line (Debugger) and the DAP adapter (FshdbAdapter) are
 * front ends to it.
 * <p>
 * Threads: what the script does is reported to the {@link Listener} on the script's threads.
 * The other methods may be called on any thread; the ones about the stopped script
 * (variables, setVariable, frames ...) only make sense while it's stopped.
 */
public class DebugEngine implements DebugTarget {

	/** Why the script stopped. */
	public enum StopReason {
		/** at a breakpoint */
		BREAKPOINT,
		/** after step, next or finish */
		STEP,
		/** pause() (Ctrl-C) */
		PAUSE,
		/** before its first statement (start) */
		ENTRY
	}

	public interface Listener {
		/**
		 * The script stopped. Called on the script's thread, which waits until the script is
		 * resumed (by another thread).
		 * @param fileLine the line (1-based) in the script, or -1 if it isn't in the script's file
		 * @param breakpoint the breakpoint it stopped at, or null
		 */
		void stopped(StopReason reason, int fileLine, Breakpoints.Entry breakpoint);

		/** The run ended. Called on the run's thread. */
		void ended(int exitCode, boolean canceled, Exception error);

		/** A breakpoint's condition couldn't be run (the script stops there anyway). */
		default void conditionFailed(int fileLine, Exception error) {
		}

		/** A statement ran (only while trace is on). */
		default void statement(int fileLine, String text) {
		}

		/** A message from the script's side for the user (a remote fshdb's output events). */
		default void message(String text) {
		}
	}

	/** A running function, sourced file or the main script, innermost first (see frames()). */
	public static final class Frame {
		/** function name, "main", or "source" for a sourced file */
		public final String name;
		/** the file it's in (the script's $0 for the script) */
		public final String file;
		/** where in it: the stopped line for the innermost frame, else the line of the call; -1 if unknown */
		public final int line;
		/** true if line is a line of the script being debugged (not of a sourced file) */
		public final boolean inScript;
		/** what it's calling: a function's name, "source FILE", or null for the innermost frame */
		public final String calls;

		Frame(String name, String file, int line, boolean inScript, String calls) {
			this.name = name;
			this.file = file;
			this.line = line;
			this.inScript = inScript;
			this.calls = calls;
		}
	}

	private ScriptFile script;
	private final Breakpoints breakpoints;
	private final ConsoleDebugSession session;
	private final InputStream scriptIn;
	private final PrintStream scriptOut;
	private final PrintStream scriptErr;
	private volatile Listener listener;

	private volatile String arguments = "";
	private volatile String stdIn = "";
	private volatile String stdOut = "";
	private volatile String stdErr = "";

	private volatile ScriptRun run;
	private volatile boolean debugging;
	private volatile ShellContext pausedCtx;
	private volatile Map<String,Object> pausedVariables;
	private volatile int pausedFileLine = -1;
	private volatile Map<String,Object> finalVariables;
	private volatile int lastExitCode;
	private volatile boolean trace;

	public DebugEngine(ScriptFile script, InputStream scriptIn, PrintStream scriptOut, PrintStream scriptErr) {
		this.script = script;
		this.scriptIn = scriptIn;
		this.scriptOut = scriptOut;
		this.scriptErr = scriptErr;
		this.breakpoints = new Breakpoints(script);
		this.session = new ConsoleDebugSession(breakpoints::forEditorLine, new DebugSession.Listener() {
			@Override
			public void paused(int line, ShellContext ctx, Map<String, Object> variables) {
				if( run == null ) {
					return;
				}
				session.clearStepping();
				int fl = DebugEngine.this.script.fileLine(line);
				pausedVariables = variables;
				pausedFileLine = fl;
				pausedCtx = ctx;
				StopReason reason = session.getStopReason();
				Breakpoints.Entry bp = fl > 0 ? breakpoints.atFileLine(fl) : null;
				if( bp != null && bp.breakpoint.getLine() != line ) {
					bp = null;
				}
				if( reason == StopReason.BREAKPOINT && bp == null ) {
					reason = StopReason.STEP;
				}
				Listener l = listener;
				if( l != null ) {
					l.stopped(reason, fl, reason == StopReason.BREAKPOINT ? bp : null);
				}
			}

			@Override
			public void statement(int line, String text) {
				Listener l = listener;
				if( trace && l != null ) {
					l.statement(DebugEngine.this.script.fileLine(line), text);
				}
			}

			@Override
			public void conditionFailed(Breakpoint bp, int line, Exception error) {
				Listener l = listener;
				if( l != null ) {
					l.conditionFailed(DebugEngine.this.script.fileLine(line), error);
				}
			}

			@Override
			public void resuming() {
			}

			@Override
			public void terminateRequested() {
				kill();
			}
		});
		useSettingsOf(script);
	}

	// ---- settings

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/** Arguments and redirects from the script's fsh-ide settings lines. */
	public void useSettingsOf(ScriptFile s) {
		arguments = s.document().arguments();
		stdIn = s.document().stdIn();
		stdOut = s.document().stdOut();
		stdErr = s.document().stdErr();
	}

	public ScriptFile getScript() {
		return script;
	}

	/** Debug another script (not while one is running). Its breakpoints start empty. */
	public void setScript(ScriptFile s) {
		if( run != null ) {
			throw new IllegalStateException("The script is running.");
		}
		script = s;
		breakpoints.clear();
		breakpoints.setScript(s);
		useSettingsOf(s);
		finalVariables = null;
	}

	/** Reads the script's file again; breakpoints stay on their line numbers. */
	public void reload() throws IOException {
		script.load();
		breakpoints.setScript(script);
	}

	public Breakpoints getBreakpoints() {
		return breakpoints;
	}

	public String getArguments() {
		return arguments;
	}

	public void setArguments(String arguments) {
		this.arguments = arguments == null ? "" : arguments;
	}

	public String getStdIn() {
		return stdIn;
	}

	public String getStdOut() {
		return stdOut;
	}

	public String getStdErr() {
		return stdErr;
	}

	/** A file for the script's standard input, output or error ("" for the console). */
	public void setRedirects(String in, String out, String err) {
		if( in != null ) {
			stdIn = in;
		}
		if( out != null ) {
			stdOut = out;
		}
		if( err != null ) {
			stdErr = err;
		}
	}

	public boolean isTrace() {
		return trace;
	}

	public void setTrace(boolean trace) {
		this.trace = trace;
	}

	// ---- state

	/** True while a run hasn't ended. */
	public boolean isRunning() {
		return run != null;
	}

	/** True if the run stops at breakpoints (run or start, not exec). */
	public boolean isDebugging() {
		return debugging;
	}

	/** True while the script is stopped at a breakpoint or step. */
	public boolean isPaused() {
		return pausedCtx != null;
	}

	/** The line (1-based) the script is stopped on, or -1. */
	public int getPausedLine() {
		return pausedCtx == null ? -1 : pausedFileLine;
	}

	/** The exit code of the last run that ended (0 if none has). */
	public int getLastExitCode() {
		return lastExitCode;
	}

	/** The variables the last run ended with, or null. */
	public Map<String,Object> getFinalVariables() {
		return finalVariables;
	}

	// ---- running

	/**
	 * Starts the script.
	 * @param debug stop at breakpoints and steps (false: just run it)
	 * @param stopAtFirst stop before its first statement
	 * @throws IllegalStateException if it's already running, or has no code
	 */
	public synchronized void start(boolean debug, boolean stopAtFirst) {
		if( run != null ) {
			throw new IllegalStateException("The script is already running.");
		}
		String code = script.code();
		if( code.isBlank()) {
			throw new IllegalStateException(script.getPath()+" has no code to run.");
		}
		breakpoints.resetForRun();
		session.clearStepping();
		session.setActive(debug);
		session.setFirstLine(0);
		session.setStopAtFirst(debug && stopAtFirst);
		debugging = debug;
		finalVariables = null;
		pausedCtx = null;
		ScriptRun r = new ScriptRun(code, this::finished);
		r.scriptName(script.scriptName())
			.arguments(arguments)
			.redirects(stdIn, stdOut, stdErr)
			.console(scriptIn, scriptOut, scriptErr)
			.debug(session);
		run = r;
		r.start();
	}

	private void finished(ScriptRun r, int exitCode, ShellContext ctx, Exception error) {
		synchronized (this) {
			if( run != r ) {
				return;
			}
			Map<String,Object> fin = null;
			if( ctx != null ) {
				try {
					fin = ctx.getVariables();
				} catch (RuntimeException e) {
					// none
				}
			}
			finalVariables = fin;
			lastExitCode = exitCode;
			pausedCtx = null;
			pausedVariables = null;
			pausedFileLine = -1;
			run = null;
			notifyAll();
		}
		scriptOut.flush();
		scriptErr.flush();
		Listener l = listener;
		if( l != null ) {
			l.ended(exitCode, r.isCanceled(), error);
		}
	}

	private ShellContext requirePaused() {
		ShellContext ctx = pausedCtx;
		if( run == null ) {
			throw new IllegalStateException("The script isn't running.");
		}
		if( ctx == null ) {
			throw new IllegalStateException("The script isn't stopped.");
		}
		return ctx;
	}

	private void resumed() {
		pausedCtx = null;
		pausedVariables = null;
	}

	/** The stopped script goes on until a breakpoint or its end. */
	public void resume() {
		requirePaused();
		resumed();
		session.resume();
	}

	/** The stopped script stops again at its next statement, going into functions. */
	public void step() {
		requirePaused();
		resumed();
		session.stepInto();
	}

	/** The stopped script stops again at its next statement here, stepping over function calls. */
	public void next() {
		int depth = requirePaused().callFrames.size();
		resumed();
		session.next(depth);
	}

	/**
	 * The stopped script stops again when the current function (or sourced file) returns.
	 * @return false if it isn't in a function (nothing is done)
	 */
	public boolean finish() {
		int depth = requirePaused().callFrames.size();
		if( depth == 0 ) {
			return false;
		}
		resumed();
		session.finish(depth);
		return true;
	}

	/**
	 * The running script stops at its next statement (while debugging).
	 * @return false if there's nothing to pause: not running, not debugging, or stopped already
	 */
	public boolean pause() {
		if( run == null || !debugging || pausedCtx != null || session.getCurrentState() == RunState.AtBreakpoint ) {
			return false;
		}
		session.clearStepping();
		session.suspend();
		return true;
	}

	/** Stops the script, without waiting. */
	public void kill() {
		ScriptRun r = run;
		if( r != null ) {
			resumed();
			r.cancel();
		}
	}

	/**
	 * Stops the script and waits (up to millis) for it to end.
	 * @return true if it ended
	 */
	public boolean killAndWait(long millis) {
		ScriptRun r = run;
		if( r == null ) {
			return true;
		}
		kill();
		long until = System.currentTimeMillis()+millis;
		synchronized (this) {
			while( run == r ) {
				long left = until-System.currentTimeMillis();
				if( left <= 0 ) {
					return false;
				}
				try {
					wait(left);
				} catch (InterruptedException e) {
					return run != r;
				}
			}
		}
		return true;
	}

	/** Waits (up to millis) for the run to end. @return true if nothing is running */
	public boolean waitForEnd(long millis) throws InterruptedException {
		long until = System.currentTimeMillis()+millis;
		synchronized (this) {
			while( run != null ) {
				long left = until-System.currentTimeMillis();
				if( left <= 0 ) {
					return false;
				}
				wait(left);
			}
		}
		return true;
	}

	/**
	 * Lets the script run on without stopping (a debugger detaches): it stops at no breakpoint
	 * or step from now on, and goes on if it's stopped.
	 */
	public void detach() {
		session.setActive(false);
		session.clearStepping();
		debugging = false;
		if( pausedCtx != null ) {
			resumed();
			session.resume();
		}
	}

	/** A debugger attaches to a running script: it stops at breakpoints again. */
	public void attach() {
		if( run != null ) {
			debugging = true;
			session.setActive(true);
		}
	}

	// ---- the stopped script

	/** The stopped script's variables as they are now (they may have been set), else the last run's final ones. */
	public Map<String,Object> variables() {
		ShellContext ctx = pausedCtx;
		if( ctx != null ) {
			try {
				// includes the running functions' local variables
				return new java.util.TreeMap<>(ctx.getVariables());
			} catch (RuntimeException e) {
				return pausedVariables;
			}
		}
		return run == null ? finalVariables : null;
	}

	@Override
	public Map<String,String> variableList(boolean all) {
		Map<String,Object> v = variables();
		if( v == null ) {
			return null;
		}
		Map<String,String> ret = new java.util.LinkedHashMap<>();
		for(us.bringardner.fsh.ide.core.Variable var : DebugVariables.list(v, all)) {
			ret.put(var.getName(), Debugger.format(var.getValue()));
		}
		return ret;
	}

	@Override
	public String formattedVariable(String name) {
		Object v = variable(name);
		return v == null ? null : Debugger.format(v);
	}

	/** True if variable() has variables to look in (stopped, or a run has ended). */
	public boolean hasVariables() {
		return variables() != null;
	}

	/**
	 * A variable of the stopped script (or of the last run): NAME, $NAME, ${NAME}, $1, 1, $@,
	 * $#, or a dynamic one (LINENO, RANDOM ...).
	 * @return its value, or null if it isn't set
	 */
	public Object variable(String name) {
		Map<String,Object> vars = variables();
		if( vars == null ) {
			return null;
		}
		String key = name;
		if( key.startsWith("${") && key.endsWith("}")) {
			key = key.substring(2, key.length()-1);
		} else if( key.startsWith("$") && key.length() > 1 && !Character.isDigit(key.charAt(1))
				&& key.charAt(1) != '@' && key.charAt(1) != '*' && key.charAt(1) != '#') {
			key = key.substring(1);
		}
		if( key.equals("$@") || key.equals("@") || key.equals("$*") || key.equals("*")) {
			return positional(vars);
		}
		if( key.equals("$#") || key.equals("#")) {
			return positional(vars).size();
		}
		if( vars.containsKey(key)) {
			return vars.get(key);
		}
		if( key.matches("\\d+") && vars.containsKey("$"+key)) {
			return vars.get("$"+key);
		}
		ShellContext ctx = pausedCtx;
		if( ctx != null ) {
			try {
				return ctx.getVariable(key);
			} catch (RuntimeException e) {
				return null;
			}
		}
		return null;
	}

	/** $1, $2 ... */
	public static List<Object> positional(Map<String,Object> vars) {
		List<Object> ret = new ArrayList<>();
		for(int i=1; vars.containsKey("$"+i); i++) {
			ret.add(vars.get("$"+i));
		}
		return ret;
	}

	/**
	 * Sets a variable of the stopped script: NAME, $NAME, or a positional parameter ($1 or 1).
	 * @return the name it was set as
	 */
	@Override
	public String setVariable(String name, String value) {
		ShellContext ctx = requirePaused();
		name = DebugTarget.variableName(name);
		DebugVariables.set(ctx, name, value);
		return name;
	}

	/** The running functions and sourced files of the stopped script, innermost first, ending with main. */
	public List<Frame> frames() {
		ShellContext ctx = pausedCtx;
		if( ctx == null ) {
			return Collections.emptyList();
		}
		List<String[]> calls = new ArrayList<>(ctx.callFrames);
		String me = script.scriptName();
		List<Frame> ret = new ArrayList<>();
		// innermost: where it's stopped
		String current = calls.isEmpty() ? "main" : calls.get(calls.size()-1)[0];
		String currentFile = calls.isEmpty() ? me : calls.get(calls.size()-1)[1];
		ret.add(new Frame(current, currentFile, pausedFileLine, pausedFileLine > 0, null));
		for(int i=calls.size()-1; i >= 0; i--) {
			String[] f = calls.get(i);
			String caller = i > 0 ? calls.get(i-1)[0] : "main";
			// the call is in the file that called: the previous frame's (the script for main)
			String file = i > 0 ? calls.get(i-1)[1] : me;
			String from = f.length > 2 ? f[2] : "";
			int line = -1;
			boolean inScript = false;
			if( from.matches("\\d+")) {
				int n = Integer.parseInt(from);
				if( me.equals(file)) {
					int fl = script.fileLine(session.editorLine(n));
					inScript = fl > 0;
					line = fl > 0 ? fl : n;
				} else {
					line = n;
				}
			}
			ret.add(new Frame(caller, file, line, inScript, "source".equals(f[0]) ? "source "+f[1] : f[0]));
		}
		return ret;
	}
}
