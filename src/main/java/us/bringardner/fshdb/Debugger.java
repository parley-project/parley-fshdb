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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import us.bringardner.fsh.ShellContext;
import us.bringardner.fsh.ide.core.Breakpoint;
import us.bringardner.fsh.ide.core.Completions;
import us.bringardner.fsh.ide.core.DebugSession;
import us.bringardner.fsh.ide.core.DebugVariables;
import us.bringardner.fsh.ide.core.ScriptRun;
import us.bringardner.fsh.ide.core.ScriptText;
import us.bringardner.fsh.ide.core.Variable;

/**
 * The command-line debugger: reads commands (break, run, next, print ...), runs the script with
 * fsh-ide's core (ScriptRun and a DebugSession) and reports where it stops.
 * <p>
 * Threads: commands are read and run on the thread that calls {@link #loop(CommandInput)}.
 * The script runs on its own thread; what it reports (stopped, finished) is queued and handled
 * by the command thread, which waits for it while the script runs. {@link #interrupt()} (Ctrl-C)
 * may be called on any thread.
 */
public class Debugger {

	public static final String PROMPT = "(fshdb) ";
	/** A second Ctrl-C within this many ms of the first stops the script. */
	static final long DOUBLE_INTERRUPT_MS = 2000;

	// ---- what the script's thread reports

	private interface Event {
	}

	private static final class Paused implements Event {
		final int line;
		final ShellContext ctx;
		final Map<String,Object> variables;

		Paused(int line, ShellContext ctx, Map<String,Object> variables) {
			this.line = line;
			this.ctx = ctx;
			this.variables = variables;
		}
	}

	private static final class Finished implements Event {
		final ScriptRun run;
		final int exitCode;
		final ShellContext ctx;
		final Exception error;

		Finished(ScriptRun run, int exitCode, ShellContext ctx, Exception error) {
			this.run = run;
			this.exitCode = exitCode;
			this.ctx = ctx;
			this.error = error;
		}
	}

	// ---- commands

	private interface Action {
		void run(String args) throws Exception;
	}

	private static final class Command {
		final String name;
		final String[] aliases;
		final String usage;
		final String help;
		final Action action;

		Command(String name, String[] aliases, String usage, String help, Action action) {
			this.name = name;
			this.aliases = aliases;
			this.usage = usage;
			this.help = help;
			this.action = action;
		}
	}

	private final Map<String,Command> commands = new LinkedHashMap<>();
	private final Map<String,Command> byName = new LinkedHashMap<>();

	// ---- state

	private final PrintStream out;
	private final InputStream scriptIn;
	private final PrintStream scriptOut;
	private final PrintStream scriptErr;
	private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
	private final ConsoleDebugSession session;
	private ScriptFile script;
	private final Breakpoints breakpoints;
	private ExternalEditor editor = new ExternalEditor();

	private String arguments = "";
	private String stdIn = "";
	private String stdOut = "";
	private String stdErr = "";

	private volatile ScriptRun run;
	private volatile boolean debugging;
	// set while the script is stopped (only on the command thread)
	private volatile ShellContext pausedCtx;
	private Map<String,Object> pausedVariables;
	private int pausedFileLine = -1;
	// the variables the last run ended with
	private Map<String,Object> finalVariables;
	private int lastExitCode;
	private volatile boolean trace;
	private volatile long lastInterrupt;
	private int listNext = 1;
	private String lastCommand = "";
	// the command before the one running
	private String previousCommand = "";
	private boolean quit;
	private CommandInput input;

	/**
	 * @param out where the debugger writes (it may be the script's standard output too)
	 * @param scriptIn, scriptOut, scriptErr the script's standard streams
	 */
	public Debugger(ScriptFile script, PrintStream out, InputStream scriptIn, PrintStream scriptOut, PrintStream scriptErr) {
		this.script = script;
		this.out = out;
		this.scriptIn = scriptIn;
		this.scriptOut = scriptOut;
		this.scriptErr = scriptErr;
		this.breakpoints = new Breakpoints(script);
		this.session = new ConsoleDebugSession(breakpoints::forEditorLine, new DebugSession.Listener() {
			@Override
			public void paused(int line, ShellContext ctx, Map<String, Object> variables) {
				events.add(new Paused(line, ctx, variables));
			}

			@Override
			public void statement(int line, String text) {
				if( trace ) {
					int fl = Debugger.this.script.fileLine(line);
					Debugger.this.scriptErr.println("+ "+(fl > 0 ? fl : "?")+": "+text);
					Debugger.this.scriptErr.flush();
				}
			}

			@Override
			public void conditionFailed(Breakpoint bp, int line, Exception error) {
				Debugger.this.out.println("The condition of the breakpoint on line "+Debugger.this.script.fileLine(line)
					+" failed ("+message(error)+"); stopping there.");
				Debugger.this.out.flush();
			}

			@Override
			public void resuming() {
			}

			@Override
			public void terminateRequested() {
				ScriptRun r = run;
				if( r != null ) {
					r.cancel();
				}
			}
		});
		useSettingsOf(script);
		defineCommands();
	}

	private void useSettingsOf(ScriptFile s) {
		arguments = s.document().arguments();
		stdIn = s.document().stdIn();
		stdOut = s.document().stdOut();
		stdErr = s.document().stdErr();
	}

	void setEditor(ExternalEditor editor) {
		this.editor = editor;
	}

	public void setArguments(String arguments) {
		this.arguments = arguments == null ? "" : arguments;
	}

	public ScriptFile getScript() {
		return script;
	}

	public Breakpoints getBreakpoints() {
		return breakpoints;
	}

	/** True while a run hasn't ended. */
	public boolean isRunning() {
		return run != null;
	}

	/** True while the script is stopped at a breakpoint or step. */
	public boolean isPaused() {
		return pausedCtx != null;
	}

	/** The exit code of the last run that ended (0 if none has). */
	public int getLastExitCode() {
		return lastExitCode;
	}

	/** The variable names worth completing: the paused script's, else the ones the script sets. */
	public Collection<String> variableNames() {
		TreeSet<String> ret = new TreeSet<>();
		Map<String,Object> v = pausedVariables != null ? pausedVariables : finalVariables;
		if( v != null ) {
			for(Variable var : DebugVariables.list(v, false)) {
				ret.add(var.getName());
			}
		}
		ret.addAll(Completions.variables(script.code()));
		return ret;
	}

	/** Command names, for completion. */
	public Collection<String> commandNames() {
		return new ArrayList<>(commands.keySet());
	}

	// ---- the loop

	/**
	 * Reads and runs commands until quit or the end of input.
	 * @return the exit code of the last run of the script (0 if it didn't run)
	 */
	public int loop(CommandInput input) throws IOException {
		this.input = input;
		while( !quit ) {
			if( run != null && pausedCtx == null ) {
				waitForScript();
				continue;
			}
			out.flush();
			scriptOut.flush();
			String line = input.readLine(PROMPT);
			if( line == null ) {
				out.println();
				break;
			}
			execute(line);
		}
		if( run != null ) {
			stopRun();
		}
		return lastExitCode;
	}

	/** Runs one command line (a blank line repeats a step, next, continue or list). */
	public void execute(String line) {
		String text = line.trim();
		if( text.isEmpty()) {
			if( lastCommand.isEmpty()) {
				return;
			}
			text = lastCommand;
		}
		if( text.startsWith("#")) {
			return;
		}
		int sp = indexOfSpace(text);
		String name = sp < 0 ? text : text.substring(0, sp);
		String args = sp < 0 ? "" : text.substring(sp+1).trim();
		Command c = byName.get(name);
		if( c == null ) {
			out.println("Unknown command \""+name+"\". Type help for the list.");
			lastCommand = "";
			return;
		}
		previousCommand = lastCommand;
		lastCommand = isRepeatable(c) ? (c.name.equals("list") ? "list" : text) : "";
		try {
			c.action.run(args);
		} catch (UsageException e) {
			out.println(e.getMessage());
			out.println("Usage: "+c.usage);
		} catch (Exception e) {
			out.println(message(e));
		}
	}

	private static boolean isRepeatable(Command c) {
		switch (c.name) {
		case "step": case "next": case "continue": case "finish": case "list":
			return true;
		default:
			return false;
		}
	}

	private static int indexOfSpace(String s) {
		for(int i=0; i < s.length(); i++) {
			if( Character.isWhitespace(s.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	/** Waits until the script stops or ends, and reports it. */
	void waitForScript() {
		try {
			Event e = events.poll(250, TimeUnit.MILLISECONDS);
			if( e != null ) {
				handle(e);
			}
		} catch (InterruptedException ex) {
			// keep waiting; Ctrl-C comes through interrupt()
		}
	}

	private void handle(Event e) {
		if( e instanceof Paused ) {
			Paused p = (Paused) e;
			if( run == null ) {
				return;
			}
			session.clearStepping();
			pausedCtx = p.ctx;
			pausedVariables = p.variables;
			pausedFileLine = script.fileLine(p.line);
			showStop(p.line);
		} else if( e instanceof Finished ) {
			Finished f = (Finished) e;
			if( f.run != run ) {
				return;
			}
			run = null;
			pausedCtx = null;
			pausedVariables = null;
			pausedFileLine = -1;
			scriptOut.flush();
			scriptErr.flush();
			if( f.ctx != null ) {
				try {
					finalVariables = f.ctx.getVariables();
				} catch (RuntimeException ex) {
					finalVariables = null;
				}
			}
			lastExitCode = f.exitCode;
			if( f.error != null ) {
				out.println("The script couldn't run: "+message(f.error));
			}
			out.println("[script "+(f.run.isCanceled() ? "stopped" : "ended")+", exit code "+f.exitCode+"]");
		}
	}

	private void showStop(int editorLine) {
		scriptOut.flush();
		scriptErr.flush();
		int fl = script.fileLine(editorLine);
		if( fl < 0 ) {
			out.println("Stopped outside "+script.getPath()+" (a sourced file?)");
			return;
		}
		Breakpoints.Entry bp = breakpoints.atFileLine(fl);
		if( bp != null && bp.breakpoint.getLine() == editorLine ) {
			out.println("Breakpoint "+bp.number+", line "+fl);
		} else {
			out.println("Stopped at line "+fl);
		}
		printLine(fl);
		listNext = fl+1;
	}

	// ---- Ctrl-C

	/**
	 * Ctrl-C. While debugging, the script stops at its next statement; a second Ctrl-C (or one
	 * while just running) stops the script.
	 * @return true if there was a script to interrupt
	 */
	public boolean interrupt() {
		ScriptRun r = run;
		if( r == null ) {
			return false;
		}
		if( pausedCtx != null || session.getCurrentState() == us.bringardner.fsh.DebugContext.RunState.AtBreakpoint ) {
			// stopped already (and suspending would let it go on to the next statement)
			return true;
		}
		long now = System.currentTimeMillis();
		if( debugging && now-lastInterrupt > DOUBLE_INTERRUPT_MS ) {
			lastInterrupt = now;
			session.clearStepping();
			session.suspend();
			out.println();
			out.println("[pausing at the next statement; Ctrl-C again to stop the script]");
		} else {
			out.println();
			out.println("[stopping the script]");
			r.cancel();
		}
		out.flush();
		return true;
	}

	// ---- running

	private void start(boolean debug, boolean stopAtFirst, String args) {
		if( run != null ) {
			out.println("The script is already running. Use kill to stop it first.");
			return;
		}
		if( !args.isEmpty()) {
			arguments = args;
		}
		String code = script.code();
		if( code.isBlank()) {
			out.println(script.getPath()+" has no code to run.");
			return;
		}
		breakpoints.resetForRun();
		session.clearStepping();
		session.setActive(debug);
		session.setFirstLine(0);
		session.setStopAtFirst(debug && stopAtFirst);
		debugging = debug;
		lastInterrupt = 0;
		finalVariables = null;
		events.clear();
		ScriptRun r = new ScriptRun(code, (sr, exitCode, ctx, error)->events.add(new Finished(sr, exitCode, ctx, error)));
		r.scriptName(script.scriptName())
			.arguments(arguments)
			.redirects(stdIn, stdOut, stdErr)
			.console(scriptIn, scriptOut, scriptErr)
			.debug(session);
		run = r;
		out.println((debug ? "Debugging " : "Running ")+script.getPath()+(arguments.isBlank() ? "" : " "+arguments));
		out.flush();
		r.start();
	}

	private void requirePaused() {
		if( run == null ) {
			throw new IllegalStateException("The script isn't running.");
		}
		if( pausedCtx == null ) {
			throw new IllegalStateException("The script isn't stopped.");
		}
	}

	/** The paused script goes on (continue, step ...): it's no longer stopped. */
	private void resumed() {
		pausedCtx = null;
		pausedVariables = null;
	}

	private void stopRun() {
		ScriptRun r = run;
		if( r == null ) {
			return;
		}
		r.cancel();
		resumed();
		try {
			if( !r.join(5000)) {
				out.println("The script didn't stop.");
			}
		} catch (InterruptedException e) {
			// go on
		}
		// report its end
		long until = System.currentTimeMillis()+2000;
		while( run == r && System.currentTimeMillis() < until ) {
			waitForScript();
		}
		if( run == r ) {
			run = null;
		}
	}

	// ---- commands

	private void define(String name, String aliases, String usage, String help, Action action) {
		String[] al = aliases.isEmpty() ? new String[0] : aliases.split(" ");
		Command c = new Command(name, al, usage, help, action);
		commands.put(name, c);
		byName.put(name, c);
		for(String a : al) {
			byName.put(a, c);
		}
	}

	private void defineCommands() {
		define("run", "r", "run [ARGUMENTS]",
				"Runs the script, stopping at breakpoints. ARGUMENTS replace the script's arguments.",
				args -> start(true, false, args));
		define("start", "", "start [ARGUMENTS]",
				"Runs the script and stops before its first statement.",
				args -> start(true, true, args));
		define("exec", "", "exec [ARGUMENTS]",
				"Runs the script without stopping (no breakpoints). Ctrl-C stops it.",
				args -> start(false, false, args));
		define("continue", "c cont", "continue",
				"Lets the stopped script go on until a breakpoint or its end.",
				args -> {
					requirePaused();
					resumed();
					session.resume();
				});
		define("step", "s", "step",
				"Runs the current statement and stops at the next one, going into functions.",
				args -> {
					requirePaused();
					resumed();
					session.stepInto();
				});
		define("next", "n", "next",
				"Runs the current statement and stops at the next one here, stepping over function calls.",
				args -> {
					requirePaused();
					int depth = pausedCtx.callFrames.size();
					resumed();
					session.next(depth);
				});
		define("finish", "fin", "finish",
				"Runs until the current function (or sourced file) returns.",
				args -> {
					requirePaused();
					int depth = pausedCtx.callFrames.size();
					if( depth == 0 ) {
						out.println("Not in a function; use continue.");
						return;
					}
					resumed();
					session.finish(depth);
				});
		define("kill", "k stop", "kill",
				"Stops the script.",
				args -> {
					if( run == null ) {
						out.println("The script isn't running.");
						return;
					}
					stopRun();
				});
		define("break", "b", "break [LINE] [hits N] [if COMMAND]",
				"Sets a breakpoint on a line (default: the current line). With hits N it stops only on the Nth "
				+ "time; with if COMMAND only when the command succeeds, for example: break 12 if [ $i -gt 3 ]",
				this::breakCommand);
		define("delete", "d", "delete [NUMBER...]",
				"Deletes breakpoints by number, or all of them.",
				args -> {
					if( args.isEmpty()) {
						breakpoints.clear();
						out.println("Deleted all breakpoints.");
						return;
					}
					for(int n : numbers(args)) {
						out.println(breakpoints.remove(n) ? "Deleted breakpoint "+n+"." : "No breakpoint "+n+".");
					}
				});
		define("clear", "", "clear LINE",
				"Deletes the breakpoint on a line.",
				args -> {
					int line = lineArg(args, pausedFileLine);
					Breakpoints.Entry e = breakpoints.atFileLine(line);
					if( e == null ) {
						out.println("No breakpoint on line "+line+".");
					} else {
						breakpoints.remove(e.number);
						out.println("Deleted breakpoint "+e.number+".");
					}
				});
		define("disable", "", "disable [NUMBER...]",
				"Disables breakpoints (all if no numbers are given).",
				args -> enable(args, false));
		define("enable", "", "enable [NUMBER...]",
				"Enables breakpoints (all if no numbers are given).",
				args -> enable(args, true));
		define("condition", "cond", "condition NUMBER [COMMAND]",
				"Sets a breakpoint's condition (a command such as [ $x = abc ]), or removes it.",
				args -> {
					String[] parts = args.split("\\s+", 2);
					if( args.isEmpty()) {
						throw new UsageException("Which breakpoint?");
					}
					Breakpoints.Entry e = breakpoints.get(number(parts[0]));
					if( e == null ) {
						out.println("No breakpoint "+parts[0]+".");
						return;
					}
					breakpoints.setCondition(e, parts.length > 1 ? parts[1] : null);
					out.println(parts.length > 1 ? "Breakpoint "+e.number+" stops if: "+parts[1]
						: "Breakpoint "+e.number+" stops every time.");
				});
		define("info", "i", "info breakpoints|args|redirects|script",
				"Shows the breakpoints, the script's arguments, its redirects or which script this is.",
				this::info);
		define("list", "l", "list [LINE | FIRST,LAST | -]",
				"Shows lines of the script: around LINE, FIRST to LAST, the 10 before the last shown (-), "
				+ "or the next 10. * marks a breakpoint (o a disabled one), > the line the script is stopped on.",
				this::list);
		define("print", "p", "print NAME...",
				"Shows variables of the stopped script ($1, $@, arrays ...).",
				this::print);
		define("vars", "locals", "vars [all]",
				"Shows the stopped script's variables. Without all, the ones inherited unchanged from the "
				+ "environment and the shell's own defaults are left out.",
				args -> {
					Map<String,Object> v = variablesNow();
					if( v == null ) {
						out.println("The script isn't stopped.");
						return;
					}
					List<Variable> list = DebugVariables.list(v, args.trim().equals("all"));
					if( list.isEmpty()) {
						out.println("No variables.");
					}
					for(Variable var : list) {
						out.println(var.getName()+" = "+format(var.getValue()));
					}
				});
		define("set", "", "set NAME=VALUE | set args [ARGUMENTS] | set stdin|stdout|stderr [FILE]",
				"Sets a variable of the stopped script, the arguments for the next run, or a file to redirect "
				+ "the script's standard input, output or error to (no FILE: the terminal).",
				this::set);
		define("where", "bt backtrace", "where",
				"Shows the running functions and sourced files, innermost first.",
				args -> where());
		define("trace", "", "trace [on|off]",
				"Prints each statement as it runs (+ LINE: statement), while debugging.",
				args -> {
					if( !args.isEmpty()) {
						trace = args.equalsIgnoreCase("on");
					}
					out.println("Trace is "+(trace ? "on" : "off")+".");
				});
		define("edit", "e", "edit [LINE]",
				"Edits the script with $VISUAL or $EDITOR (at LINE, default the current line), then reloads it.",
				this::edit);
		define("reload", "", "reload",
				"Reads the script from its file again (breakpoints stay on their line numbers).",
				args -> reload());
		define("file", "", "file SCRIPT",
				"Debugs another script (its breakpoints start empty).",
				args -> {
					if( args.isEmpty()) {
						throw new UsageException("Which script?");
					}
					if( run != null ) {
						out.println("The script is running. Use kill to stop it first.");
						return;
					}
					ScriptFile f = new ScriptFile(args);
					f.load();
					script = f;
					breakpoints.clear();
					breakpoints.setScript(f);
					useSettingsOf(f);
					listNext = 1;
					finalVariables = null;
					out.println("Script "+f.getPath()+", "+f.lineCount()+" lines.");
				});
		define("help", "h ?", "help [COMMAND]",
				"Lists the commands, or describes one.",
				this::help);
		define("quit", "q exit", "quit",
				"Stops the script, if it's running, and leaves.",
				args -> {
					if( run != null ) {
						stopRun();
					}
					quit = true;
				});
	}

	private void breakCommand(String args) {
		String rest = args;
		String condition = null;
		int ifAt = findWord(rest, "if");
		if( ifAt >= 0 ) {
			condition = rest.substring(ifAt+2).trim();
			rest = rest.substring(0, ifAt).trim();
			if( condition.isEmpty()) {
				throw new UsageException("if needs a command.");
			}
		}
		int hits = 0;
		int hitsAt = findWord(rest, "hits");
		if( hitsAt >= 0 ) {
			String n = rest.substring(hitsAt+4).trim();
			rest = rest.substring(0, hitsAt).trim();
			hits = number(n);
			if( hits < 1 ) {
				throw new UsageException("hits needs a number of 1 or more.");
			}
		}
		int line = lineArg(rest, pausedFileLine);
		if( line < 1 || line > script.lineCount()) {
			out.println("Line "+line+" is not in the script ("+script.lineCount()+" lines).");
			return;
		}
		int at = line;
		while( at <= script.lineCount() && !script.mayHaveCode(at)) {
			at++;
		}
		if( at > script.lineCount()) {
			out.println("There's no code on or after line "+line+".");
			return;
		}
		Breakpoints.Entry e = breakpoints.add(at, condition, hits);
		StringBuilder msg = new StringBuilder("Breakpoint "+e.number+" at line "+at);
		if( at != line ) {
			msg.append(" (line ").append(line).append(" has no code)");
		}
		if( hits > 0 ) {
			msg.append(", on hit ").append(hits);
		}
		if( condition != null ) {
			msg.append(", if ").append(condition);
		}
		out.println(msg.append('.'));
	}

	/** Where word starts in s as a word of its own (at the start or after white space), or -1. */
	private static int findWord(String s, String word) {
		int from = 0;
		while( true ) {
			int i = s.indexOf(word, from);
			if( i < 0 ) {
				return -1;
			}
			boolean startOk = i == 0 || Character.isWhitespace(s.charAt(i-1));
			int end = i+word.length();
			boolean endOk = end == s.length() || Character.isWhitespace(s.charAt(end));
			if( startOk && endOk ) {
				return i;
			}
			from = i+1;
		}
	}

	private void enable(String args, boolean on) {
		List<Breakpoints.Entry> list = new ArrayList<>();
		if( args.isEmpty()) {
			list.addAll(breakpoints.list());
		} else {
			for(int n : numbers(args)) {
				Breakpoints.Entry e = breakpoints.get(n);
				if( e == null ) {
					out.println("No breakpoint "+n+".");
				} else {
					list.add(e);
				}
			}
		}
		for(Breakpoints.Entry e : list) {
			e.breakpoint.setEnabled(on);
			out.println((on ? "Enabled" : "Disabled")+" breakpoint "+e.number+".");
		}
	}

	private void info(String args) {
		String what = args.trim();
		if( what.isEmpty() || "breakpoints".startsWith(what) && what.startsWith("b")) {
			if( breakpoints.isEmpty()) {
				out.println("No breakpoints.");
				return;
			}
			out.println("Num  Line  Enabled  Condition");
			for(Breakpoints.Entry e : breakpoints.list()) {
				Breakpoint b = e.breakpoint;
				StringBuilder cond = new StringBuilder();
				if( b.isHitCount() && b.getHitCount() > 0 ) {
					cond.append("hits ").append(b.getHitCount()).append(' ');
				}
				if( b.isConditional()) {
					cond.append("if ").append(b.getCondition());
				}
				out.println(String.format("%-4d %-5d %-8s %s", e.number, e.fileLine, b.isEnabled(false) ? "yes" : "no",
						cond.toString().trim()));
			}
		} else if( "args".startsWith(what) && what.startsWith("a")) {
			out.println(arguments.isBlank() ? "No arguments." : "Arguments: "+arguments);
		} else if( "redirects".startsWith(what) && what.startsWith("r")) {
			out.println("stdin:  "+(stdIn.isEmpty() ? "terminal" : stdIn));
			out.println("stdout: "+(stdOut.isEmpty() ? "terminal" : stdOut));
			out.println("stderr: "+(stdErr.isEmpty() ? "terminal" : stdErr));
		} else if( "script".startsWith(what) && what.startsWith("s")) {
			out.println(script.scriptName()+", "+script.lineCount()+" lines"
				+(run == null ? "" : pausedCtx != null ? ", stopped at line "+pausedFileLine : ", running"));
		} else {
			throw new UsageException("info what?");
		}
	}

	private void list(String args) {
		int first;
		int last;
		String a = args.trim();
		if( a.equals("-")) {
			last = Math.max(0, listNext-11);
			first = Math.max(1, last-9);
		} else if( a.contains(",")) {
			String[] p = a.split(",", 2);
			first = number(p[0].trim());
			last = p[1].isBlank() ? first+9 : number(p[1].trim());
		} else if( !a.isEmpty()) {
			int center = number(a);
			first = Math.max(1, center-5);
			last = first+9;
		} else if( previousCommand.equals("list") && listNext <= script.lineCount() ) {
			first = listNext;
			last = first+9;
		} else if( pausedFileLine > 0 ) {
			first = Math.max(1, pausedFileLine-5);
			last = first+9;
		} else {
			first = listNext;
			last = first+9;
		}
		if( first > script.lineCount()) {
			out.println("Line "+first+" is past the end ("+script.lineCount()+" lines).");
			return;
		}
		last = Math.min(last, script.lineCount());
		for(int l=first; l <= last; l++) {
			printLine(l);
		}
		listNext = last+1;
	}

	private void printLine(int fileLine) {
		String text = script.line(fileLine);
		if( text == null ) {
			return;
		}
		Breakpoints.Entry bp = breakpoints.atFileLine(fileLine);
		char b = bp == null ? ' ' : bp.breakpoint.isEnabled(false) ? '*' : 'o';
		char cur = fileLine == pausedFileLine && pausedCtx != null ? '>' : ' ';
		int width = Math.max(3, String.valueOf(script.lineCount()).length());
		out.println(String.format("%"+width+"d %c%c %s", fileLine, b, cur, text));
	}

	/** The stopped script's variables as they are now (they may have been set), or its final ones. */
	private Map<String,Object> variablesNow() {
		if( pausedCtx != null ) {
			try {
				return pausedCtx.getVariables();
			} catch (RuntimeException e) {
				return pausedVariables;
			}
		}
		return run == null ? finalVariables : null;
	}

	private void print(String args) {
		if( args.isEmpty()) {
			throw new UsageException("Print what?");
		}
		Map<String,Object> vars = variablesNow();
		if( vars == null ) {
			out.println(run == null ? "The script hasn't run yet." : "The script isn't stopped.");
			return;
		}
		for(String name : args.split("\\s+")) {
			String key = name;
			Object value;
			if( key.startsWith("${") && key.endsWith("}")) {
				key = key.substring(2, key.length()-1);
			} else if( key.startsWith("$") && key.length() > 1 && !Character.isDigit(key.charAt(1))
					&& key.charAt(1) != '@' && key.charAt(1) != '*' && key.charAt(1) != '#') {
				key = key.substring(1);
			}
			if( key.equals("$@") || key.equals("@") || key.equals("$*") || key.equals("*")) {
				value = positional(vars);
			} else if( key.equals("$#") || key.equals("#")) {
				value = positional(vars).size();
			} else if( vars.containsKey(key)) {
				value = vars.get(key);
			} else if( key.matches("\\d+") && vars.containsKey("$"+key)) {
				value = vars.get("$"+key);
			} else if( pausedCtx != null ) {
				// a dynamic one: LINENO, RANDOM, SECONDS ...
				try {
					value = pausedCtx.getVariable(key);
				} catch (RuntimeException e) {
					value = null;
				}
			} else {
				value = null;
			}
			out.println(name+" = "+(value == null ? "(not set)" : format(value)));
		}
	}

	private static List<Object> positional(Map<String,Object> vars) {
		List<Object> ret = new ArrayList<>();
		for(int i=1; vars.containsKey("$"+i); i++) {
			ret.add(vars.get("$"+i));
		}
		return ret;
	}

	/** A value as bash's declare -p would show it, roughly: arrays as (a b c). */
	static String format(Object value) {
		if( value instanceof Map ) {
			StringBuilder b = new StringBuilder("(");
			for(Map.Entry<?,?> e : ((Map<?,?>) value).entrySet()) {
				if( b.length() > 1 ) {
					b.append(' ');
				}
				b.append('[').append(e.getKey()).append("]=").append(quote(String.valueOf(e.getValue())));
			}
			return b.append(')').toString();
		}
		if( value instanceof List ) {
			StringBuilder b = new StringBuilder("(");
			for(Object o : (List<?>) value) {
				if( b.length() > 1 ) {
					b.append(' ');
				}
				b.append(quote(String.valueOf(o)));
			}
			return b.append(')').toString();
		}
		return quote(String.valueOf(value));
	}

	private static String quote(String s) {
		if( !s.isEmpty() && s.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'' || c == '\\' || c < ' ')) {
			return s;
		}
		StringBuilder b = new StringBuilder("\"");
		for(char c : s.toCharArray()) {
			switch (c) {
			case '"': b.append("\\\""); break;
			case '\\': b.append("\\\\"); break;
			case '\n': b.append("\\n"); break;
			case '\t': b.append("\\t"); break;
			case '\r': b.append("\\r"); break;
			default:
				if( c < ' ') {
					b.append(String.format("\\x%02x", (int) c));
				} else {
					b.append(c);
				}
			}
		}
		return b.append('"').toString();
	}

	private void set(String args) {
		if( args.isEmpty()) {
			throw new UsageException("Set what?");
		}
		int sp = indexOfSpace(args);
		String first = sp < 0 ? args : args.substring(0, sp);
		String rest = sp < 0 ? "" : args.substring(sp+1).trim();
		switch (first) {
		case "args":
			arguments = rest;
			out.println(rest.isEmpty() ? "No arguments." : "Arguments: "+rest);
			return;
		case "stdin":
			stdIn = rest;
			out.println("stdin: "+(rest.isEmpty() ? "terminal" : rest));
			return;
		case "stdout":
			stdOut = rest;
			out.println("stdout: "+(rest.isEmpty() ? "terminal" : rest));
			return;
		case "stderr":
			stdErr = rest;
			out.println("stderr: "+(rest.isEmpty() ? "terminal" : rest));
			return;
		case "var":
			args = rest;
			break;
		default:
		}
		int eq = args.indexOf('=');
		String name;
		String value;
		if( eq > 0 ) {
			name = args.substring(0, eq).trim();
			value = args.substring(eq+1).trim();
		} else {
			int s = indexOfSpace(args);
			if( s < 0 ) {
				throw new UsageException("Set "+args+" to what?");
			}
			name = args.substring(0, s);
			value = args.substring(s+1).trim();
		}
		value = unquote(value);
		requirePaused();
		if( name.matches("\\$?\\d+")) {
			name = name.startsWith("$") ? name : "$"+name;
		} else if( name.startsWith("$")) {
			name = name.substring(1);
		}
		DebugVariables.set(pausedCtx, name, value);
		out.println(name+" = "+format(value));
	}

	private static String unquote(String v) {
		if( v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
			return v.substring(1, v.length()-1);
		}
		return v;
	}

	private void where() {
		requirePaused();
		List<String[]> frames = new ArrayList<>(pausedCtx.callFrames);
		String me = script.scriptName();
		String current = frames.isEmpty() ? "main" : frames.get(frames.size()-1)[0];
		out.println("#0  line "+pausedFileLine+" in "+current);
		int n = 1;
		for(int i=frames.size()-1; i >= 0; i--, n++) {
			String[] f = frames.get(i);
			String caller = i > 0 ? frames.get(i-1)[0] : "main";
			String from = f.length > 2 ? f[2] : "?";
			String where;
			// the line it was called from is in the file that called it: the previous frame's
			String file = i > 0 ? frames.get(i-1)[1] : me;
			if( me.equals(file) && from.matches("\\d+")) {
				int fl = script.fileLine(session.editorLine(Integer.parseInt(from)));
				where = "line "+(fl > 0 ? fl : from);
			} else {
				where = file+":"+from;
			}
			out.println("#"+n+"  "+where+" in "+caller+("source".equals(f[0]) ? " (sourcing "+f[1]+")" : " (calling "+f[0]+")"));
		}
	}

	private void edit(String args) throws IOException {
		int line = args.isEmpty() ? (pausedFileLine > 0 ? pausedFileLine : Math.max(1, listNext-1)) : number(args.trim());
		File local = script.localFile();
		File toEdit = local;
		String before = null;
		if( local == null ) {
			if( script.getFile() == null ) {
				out.println("This script isn't in a file.");
				return;
			}
			// a remote file: edit a copy, then write it back
			String name = script.getFile().getName();
			int dot = name.lastIndexOf('.');
			toEdit = File.createTempFile("fshdb-", dot > 0 ? name.substring(dot) : ".sh");
			toEdit.deleteOnExit();
			before = ScriptText.read(script.getFile());
			Files.write(toEdit.toPath(), before.getBytes(StandardCharsets.UTF_8));
		}
		if( input != null ) {
			input.pause();
		}
		int code;
		try {
			code = editor.edit(toEdit, line);
		} finally {
			if( input != null ) {
				input.resume();
			}
		}
		if( code != 0 ) {
			out.println("The editor exited with code "+code+".");
		}
		if( local == null ) {
			String after = new String(Files.readAllBytes(toEdit.toPath()), StandardCharsets.UTF_8);
			toEdit.delete();
			if( after.equals(before)) {
				out.println("No changes.");
				return;
			}
			ScriptText.write(script.getFile(), after);
		}
		reload();
	}

	private void reload() throws IOException {
		script.load();
		breakpoints.setScript(script);
		for(Breakpoints.Entry e : breakpoints.list()) {
			if( !script.mayHaveCode(e.fileLine)) {
				out.println("Breakpoint "+e.number+" is on line "+e.fileLine+", which has no code now.");
			}
		}
		out.println("Reloaded "+script.getPath()+", "+script.lineCount()+" lines."
				+(run != null ? " The running script still has the old code; run it again to use the new." : ""));
	}

	private void help(String args) {
		if( !args.isEmpty()) {
			Command c = byName.get(args.trim());
			if( c == null ) {
				out.println("No command "+args.trim()+".");
				return;
			}
			out.println(c.usage+(c.aliases.length == 0 ? "" : "   (also: "+String.join(", ", c.aliases)+")"));
			out.println("  "+c.help);
			return;
		}
		out.println("Commands (help COMMAND for more; a blank line repeats step, next, continue or list):");
		for(Command c : commands.values()) {
			String names = c.name+(c.aliases.length == 0 ? "" : " ("+String.join(", ", c.aliases)+")");
			String help = c.help;
			int dot = help.indexOf(". ");
			if( dot > 0 ) {
				help = help.substring(0, dot+1);
			}
			out.println(String.format("  %-22s %s", names, help));
		}
		out.println("Ctrl-C while the script runs stops it at its next statement (twice: stops the script).");
	}

	// ---- arguments

	/** Thrown for a command typed wrongly: its usage is shown. */
	static final class UsageException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		UsageException(String msg) {
			super(msg);
		}
	}

	private static int number(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			throw new UsageException("\""+s.trim()+"\" is not a number.");
		}
	}

	private static List<Integer> numbers(String s) {
		List<Integer> ret = new ArrayList<>();
		for(String p : s.trim().split("[\\s,]+")) {
			if( !p.isEmpty()) {
				ret.add(number(p));
			}
		}
		return ret;
	}

	private int lineArg(String s, int dflt) {
		if( s.isBlank()) {
			if( dflt < 1 ) {
				throw new UsageException("Which line?");
			}
			return dflt;
		}
		return number(s);
	}

	static String message(Throwable e) {
		String m = e.getMessage();
		return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
	}
}
