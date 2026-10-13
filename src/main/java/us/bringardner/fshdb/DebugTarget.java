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
import java.util.List;
import java.util.Map;

/**
 * What the command line (Debugger) debugs: a script run here (DebugEngine) or one running in
 * another fshdb, attached to over DAP (RemoteTarget).
 * <p>
 * What a target can't do throws IllegalStateException with a message for the user (a remote
 * script's arguments, for example, were given where it runs).
 */
public interface DebugTarget {

	void setListener(DebugEngine.Listener listener);

	/** The script (for a remote one, a copy of its text). */
	ScriptFile getScript();

	/** The breakpoints; a remote target sends them to the script as they change. */
	Breakpoints getBreakpoints();

	/** True if the script runs elsewhere (fshdb --attach). */
	default boolean isAttached() {
		return false;
	}

	// ---- settings for the next run

	String getArguments();

	void setArguments(String arguments);

	String getStdIn();

	String getStdOut();

	String getStdErr();

	/** A file for the script's standard input, output or error ("" for the console; null: unchanged). */
	void setRedirects(String in, String out, String err);

	boolean isTrace();

	void setTrace(boolean trace);

	/** Debug another script (not while one is running). */
	void setScript(ScriptFile s);

	/** Reads the script's file again. */
	void reload() throws IOException;

	// ---- state

	/** True while a run hasn't ended. */
	boolean isRunning();

	/** True if the run stops at breakpoints. */
	boolean isDebugging();

	/** True while the script is stopped at a breakpoint or step. */
	boolean isPaused();

	/** The line (1-based) the script is stopped on, or -1. */
	int getPausedLine();

	/** The exit code of the last run that ended (0 if none has). */
	int getLastExitCode();

	// ---- running

	/**
	 * Starts the script.
	 * @param debug stop at breakpoints and steps
	 * @param stopAtFirst stop before its first statement
	 */
	void start(boolean debug, boolean stopAtFirst);

	void resume();

	void step();

	void next();

	/** @return false if it isn't in a function (nothing is done) */
	boolean finish();

	/** @return false if there's nothing to pause */
	boolean pause();

	/** Stops the script, without waiting. */
	void kill();

	/** Stops the script and waits (up to millis) for it to end. @return true if it ended */
	boolean killAndWait(long millis);

	/** The debugger leaves: the script goes on without stopping (and goes on if it's stopped). */
	void detach();

	// ---- the stopped script

	/**
	 * The stopped script's variables (or the last run's), by name, with their values as
	 * Debugger.format shows them; null if there are none.
	 * @param all include the ones inherited unchanged from the environment and the shell's defaults
	 */
	Map<String,String> variableList(boolean all);

	/** True if variableList and formattedVariable have variables to look in. */
	boolean hasVariables();

	/**
	 * A variable (NAME, $NAME, ${NAME}, $1, $@, $# ...) as Debugger.format shows it.
	 * @return null if it isn't set
	 */
	String formattedVariable(String name);

	/** Sets a variable of the stopped script. @return the name it was set as */
	String setVariable(String name, String value);

	/** The running functions and sourced files of the stopped script, innermost first. */
	List<DebugEngine.Frame> frames();

	/** NAME, $NAME or a positional parameter ($1 or 1), as setVariable sets it. */
	static String variableName(String name) {
		if( name.matches("\\$?\\d+")) {
			return name.startsWith("$") ? name : "$"+name;
		}
		return name.startsWith("$") ? name.substring(1) : name;
	}
}
