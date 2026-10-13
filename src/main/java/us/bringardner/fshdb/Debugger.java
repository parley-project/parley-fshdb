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

import us.bringardner.fsh.ide.core.Breakpoint;
import us.bringardner.fsh.ide.core.Completions;
import us.bringardner.fsh.ide.core.DebugVariables;
import us.bringardner.fsh.ide.core.ScriptText;
import us.bringardner.fsh.ide.core.Variable;

/**
 * The command-line debugger: reads commands (break, run, next, print ...), has a DebugEngine
 * run the script and reports where it stops.
 * <p>
 * Threads: commands are read and run on the thread that calls {@link #loop(CommandInput)}.
 * The script runs on its own thread; what the engine reports (stopped, ended) is queued and
 * handled by the command thread, which waits for it while the script runs.
 * {@link #interrupt()} (Ctrl-C) may be called on any thread.
 */
public class Debugger {

	public static final String PROMPT = "(fshdb) ";
	/** A second Ctrl-C within this many ms of the first stops the script. */
	static final long DOUBLE_INTERRUPT_MS = 2000;

	// ---- what the engine reports, handled on the command thread

	private interface Event {
	}

	private static final class Stopped implements Event {
		final int fileLine;
		final Breakpoints.Entry breakpoint;

		Stopped(int fileLine, Breakpoints.Entry breakpoint) {
			this.fileLine = fileLine;
			this.breakpoint = breakpoint;
		}
	}

	private static final class Ended implements Event {
		final int exitCode;
		final boolean canceled;
		final Exception error;

		Ended(int exitCode, boolean canceled, Exception error) {
			this.exitCode = exitCode;
			this.canceled = canceled;
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
	private final PrintStream scriptOut;
	private final PrintStream scriptErr;
	private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
	private final DebugEngine engine;
	private final Breakpoints breakpoints;
	private ExternalEditor editor = new ExternalEditor();

	// true from starting or resuming the script until it's reported stopped or ended
	private volatile boolean waiting;
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
		this(new DebugEngine(script, scriptIn, scriptOut, scriptErr), out, scriptOut, scriptErr);
	}

	public Debugger(DebugEngine engine, PrintStream out, PrintStream scriptOut, PrintStream scriptErr) {
		this.engine = engine;
		this.out = out;
		this.scriptOut = scriptOut;
		this.scriptErr = scriptErr;
		this.breakpoints = engine.getBreakpoints();
		engine.setListener(new DebugEngine.Listener() {
			@Override
			public void stopped(DebugEngine.StopReason reason, int fileLine, Breakpoints.Entry breakpoint) {
				events.add(new Stopped(fileLine, breakpoint));
			}

			@Override
			public void ended(int exitCode, boolean canceled, Exception error) {
				events.add(new Ended(exitCode, canceled, error));
			}

			@Override
			public void conditionFailed(int fileLine, Exception error) {
				Debugger.this.out.println("The condition of the breakpoint on line "+fileLine
					+" failed ("+message(error)+"); stopping there.");
				Debugger.this.out.flush();
			}

			@Override
			public void statement(int fileLine, String text) {
				Debugger.this.scriptErr.println("+ "+(fileLine > 0 ? fileLine : "?")+": "+text);
				Debugger.this.scriptErr.flush();
			}
		});
		defineCommands();
	}

	void setEditor(ExternalEditor editor) {
		this.editor = editor;
	}

	public DebugEngine getEngine() {
		return engine;
	}

	public void setArguments(String arguments) {
		engine.setArguments(arguments);
	}

	public ScriptFile getScript() {
		return engine.getScript();
	}

	private ScriptFile script() {
		return engine.getScript();
	}

	public Breakpoints getBreakpoints() {
		return breakpoints;
	}

	/** True while a run hasn't ended. */
	public boolean isRunning() {
		return engine.isRunning();
	}

	/** True while the script is stopped at a breakpoint or step. */
	public boolean isPaused() {
		return engine.isPaused();
	}

	/** The exit code of the last run that ended (0 if none has). */
	public int getLastExitCode() {
		return engine.getLastExitCode();
	}

	/** The variable names worth completing: the stopped script's (or last run's), and the ones the script sets. */
	public Collection<String> variableNames() {
		TreeSet<String> ret = new TreeSet<>();
		Map<String,Object> v = engine.variables();
		if( v != null ) {
			for(Variable var : DebugVariables.list(v, false)) {
				ret.add(var.getName());
			}
		}
		ret.addAll(Completions.variables(script().code()));
		return ret;
	}

	/** Command names, for completion. */
	public Collection<String> commandNames() {
		return new ArrayList<>(commands.keySet());
	}

	private static String terminalOr(String file) {
		return file == null || file.isEmpty() ? "terminal" : file;
	}

	// ---- the loop

	/**
	 * Reads and runs commands until quit or the end of input.
	 * @return the exit code of the last run of the script (0 if it didn't run)
	 */
	public int loop(CommandInput input) throws IOException {
		this.input = input;
		while( !quit ) {
			if( waiting ) {
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
		if( engine.isRunning()) {
			stopRun();
		}
		return engine.getLastExitCode();
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

	/** Waits a little for the script to stop or end, and reports it. */
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
		if( e instanceof Stopped ) {
			Stopped s = (Stopped) e;
			waiting = false;
			showStop(s.fileLine, s.breakpoint);
		} else if( e instanceof Ended ) {
			Ended f = (Ended) e;
			waiting = false;
			scriptOut.flush();
			scriptErr.flush();
			if( f.error != null ) {
				out.println("The script couldn't run: "+message(f.error));
			}
			out.println("[script "+(f.canceled ? "stopped" : "ended")+", exit code "+f.exitCode+"]");
		}
	}

	private void showStop(int fileLine, Breakpoints.Entry bp) {
		scriptOut.flush();
		scriptErr.flush();
		if( fileLine < 0 ) {
			out.println("Stopped outside "+script().getPath()+" (a sourced file?)");
			return;
		}
		if( bp != null ) {
			out.println("Breakpoint "+bp.number+", line "+fileLine);
		} else {
			out.println("Stopped at line "+fileLine);
		}
		printLine(fileLine);
		listNext = fileLine+1;
	}

	// ---- Ctrl-C

	/**
	 * Ctrl-C. While debugging, the script stops at its next statement; a second Ctrl-C (or one
	 * while just running) stops the script.
	 * @return true if there was a script to interrupt
	 */
	public boolean interrupt() {
		if( !engine.isRunning()) {
			return false;
		}
		if( engine.isPaused()) {
			return true;
		}
		long now = System.currentTimeMillis();
		if( engine.isDebugging() && now-lastInterrupt > DOUBLE_INTERRUPT_MS ) {
			lastInterrupt = now;
			if( engine.pause()) {
				out.println();
				out.println("[pausing at the next statement; Ctrl-C again to stop the script]");
			}
		} else {
			out.println();
			out.println("[stopping the script]");
			engine.kill();
		}
		out.flush();
		return true;
	}

	// ---- running

	private void start(boolean debug, boolean stopAtFirst, String args) {
		if( engine.isRunning()) {
			out.println("The script is already running. Use kill to stop it first.");
			return;
		}
		if( !args.isEmpty()) {
			engine.setArguments(args);
		}
		if( script().code().isBlank()) {
			out.println(script().getPath()+" has no code to run.");
			return;
		}
		lastInterrupt = 0;
		events.clear();
		String arguments = engine.getArguments();
		out.println((debug ? "Debugging " : "Running ")+script().getPath()+(arguments.isBlank() ? "" : " "+arguments));
		out.flush();
		waiting = true;
		try {
			engine.start(debug, stopAtFirst);
		} catch (RuntimeException e) {
			waiting = false;
			throw e;
		}
	}

	/** Runs a resume (continue, step ...) of the stopped script; then waits for it. */
	private void resume(Runnable r) {
		r.run();
		waiting = true;
	}

	private void stopRun() {
		if( !engine.isRunning()) {
			return;
		}
		// (until its end is reported, even if it was stopped at a breakpoint)
		waiting = true;
		if( !engine.killAndWait(5000)) {
			out.println("The script didn't stop.");
		}
		long until = System.currentTimeMillis()+2000;
		while( waiting && System.currentTimeMillis() < until ) {
			waitForScript();
		}
		waiting = false;
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
				args -> resume(engine::resume));
		define("step", "s", "step",
				"Runs the current statement and stops at the next one, going into functions.",
				args -> resume(engine::step));
		define("next", "n", "next",
				"Runs the current statement and stops at the next one here, stepping over function calls.",
				args -> resume(engine::next));
		define("finish", "fin", "finish",
				"Runs until the current function (or sourced file) returns.",
				args -> {
					if( !engine.finish()) {
						out.println("Not in a function; use continue.");
						return;
					}
					waiting = true;
				});
		define("kill", "k stop", "kill",
				"Stops the script.",
				args -> {
					if( !engine.isRunning()) {
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
					int line = lineArg(args, engine.getPausedLine());
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
					Map<String,Object> v = engine.variables();
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
						engine.setTrace(args.equalsIgnoreCase("on"));
					}
					out.println("Trace is "+(engine.isTrace() ? "on" : "off")+".");
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
					if( engine.isRunning()) {
						out.println("The script is running. Use kill to stop it first.");
						return;
					}
					ScriptFile f = new ScriptFile(args);
					f.load();
					engine.setScript(f);
					listNext = 1;
					out.println("Script "+f.getPath()+", "+f.lineCount()+" lines.");
				});
		define("help", "h ?", "help [COMMAND]",
				"Lists the commands, or describes one.",
				this::help);
		define("quit", "q exit", "quit",
				"Stops the script, if it's running, and leaves.",
				args -> {
					if( engine.isRunning()) {
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
		int line = lineArg(rest, engine.getPausedLine());
		if( line < 1 || line > script().lineCount()) {
			out.println("Line "+line+" is not in the script ("+script().lineCount()+" lines).");
			return;
		}
		int at = line;
		while( at <= script().lineCount() && !script().mayHaveCode(at)) {
			at++;
		}
		if( at > script().lineCount()) {
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
			String arguments = engine.getArguments();
			out.println(arguments.isBlank() ? "No arguments." : "Arguments: "+arguments);
		} else if( "redirects".startsWith(what) && what.startsWith("r")) {
			out.println("stdin:  "+terminalOr(engine.getStdIn()));
			out.println("stdout: "+terminalOr(engine.getStdOut()));
			out.println("stderr: "+terminalOr(engine.getStdErr()));
		} else if( "script".startsWith(what) && what.startsWith("s")) {
			out.println(script().scriptName()+", "+script().lineCount()+" lines"
				+(!engine.isRunning() ? "" : engine.isPaused() ? ", stopped at line "+engine.getPausedLine() : ", running"));
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
		} else if( previousCommand.equals("list") && listNext <= script().lineCount() ) {
			first = listNext;
			last = first+9;
		} else if( engine.getPausedLine() > 0 ) {
			first = Math.max(1, engine.getPausedLine()-5);
			last = first+9;
		} else {
			first = listNext;
			last = first+9;
		}
		if( first > script().lineCount()) {
			out.println("Line "+first+" is past the end ("+script().lineCount()+" lines).");
			return;
		}
		last = Math.min(last, script().lineCount());
		for(int l=first; l <= last; l++) {
			printLine(l);
		}
		listNext = last+1;
	}

	private void printLine(int fileLine) {
		String text = script().line(fileLine);
		if( text == null ) {
			return;
		}
		Breakpoints.Entry bp = breakpoints.atFileLine(fileLine);
		char b = bp == null ? ' ' : bp.breakpoint.isEnabled(false) ? '*' : 'o';
		char cur = fileLine == engine.getPausedLine() ? '>' : ' ';
		int width = Math.max(3, String.valueOf(script().lineCount()).length());
		out.println(String.format("%"+width+"d %c%c %s", fileLine, b, cur, text));
	}

	private void print(String args) {
		if( args.isEmpty()) {
			throw new UsageException("Print what?");
		}
		if( !engine.hasVariables()) {
			out.println(engine.isRunning() ? "The script isn't stopped." : "The script hasn't run yet.");
			return;
		}
		for(String name : args.split("\\s+")) {
			Object value = engine.variable(name);
			out.println(name+" = "+(value == null ? "(not set)" : format(value)));
		}
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
			engine.setArguments(rest);
			out.println(rest.isEmpty() ? "No arguments." : "Arguments: "+rest);
			return;
		case "stdin":
			engine.setRedirects(rest, null, null);
			out.println("stdin: "+(rest.isEmpty() ? "terminal" : rest));
			return;
		case "stdout":
			engine.setRedirects(null, rest, null);
			out.println("stdout: "+(rest.isEmpty() ? "terminal" : rest));
			return;
		case "stderr":
			engine.setRedirects(null, null, rest);
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
		name = engine.setVariable(name, value);
		out.println(name+" = "+format(value));
	}

	private static String unquote(String v) {
		if( v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
			return v.substring(1, v.length()-1);
		}
		return v;
	}

	private void where() {
		if( !engine.isPaused()) {
			throw new IllegalStateException(engine.isRunning() ? "The script isn't stopped." : "The script isn't running.");
		}
		int n = 0;
		for(DebugEngine.Frame f : engine.frames()) {
			String where = f.inScript || n == 0 ? "line "+f.line : f.file+":"+f.line;
			String calls = f.calls == null ? "" : f.calls.startsWith("source ")
					? " (sourcing "+f.calls.substring(7)+")" : " (calling "+f.calls+")";
			out.println("#"+n+"  "+where+" in "+f.name+calls);
			n++;
		}
	}

	private void edit(String args) throws IOException {
		ScriptFile script = script();
		int line = args.isEmpty() ? (engine.getPausedLine() > 0 ? engine.getPausedLine() : Math.max(1, listNext-1)) : number(args.trim());
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
		engine.reload();
		ScriptFile script = script();
		for(Breakpoints.Entry e : breakpoints.list()) {
			if( !script().mayHaveCode(e.fileLine)) {
				out.println("Breakpoint "+e.number+" is on line "+e.fileLine+", which has no code now.");
			}
		}
		out.println("Reloaded "+script.getPath()+", "+script().lineCount()+" lines."
				+(engine.isRunning() ? " The running script still has the old code; run it again to use the new." : ""));
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
