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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.fsh.Console;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.FileNameCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * fshdb: debug a shell script in a terminal.
 * <pre>
 * fshdb [-e COMMAND]... [-x FILE] SCRIPT [ARGUMENTS...]
 * </pre>
 * The commands given with -e (and those in FILE) run first, as if typed; then commands are
 * read from the terminal (with history, line editing and Tab completion).
 */
public class Fshdb {

	public static final String VERSION = "1.0.0";

	private static final String USAGE =
			"Usage: fshdb [-e COMMAND]... [-x FILE] SCRIPT [ARGUMENTS...]\n"
			+ "Debug a shell script with fsh, in the terminal.\n"
			+ "\n"
			+ "  -e COMMAND   run a debugger command first (may be repeated), e.g. -e 'break 10' -e run\n"
			+ "  -x FILE      run the debugger commands in FILE first\n"
			+ "  -h, --help   show this help\n"
			+ "  --version    show the version\n"
			+ "\n"
			+ "SCRIPT may be a local file or a URL a parley-files file system understands\n"
			+ "(sftp://user@host/path/script.sh ...). Type help at the (fshdb) prompt for the commands.\n";

	public static void main(String[] args) {
		int code;
		try {
			code = run(args);
		} catch (IOException e) {
			System.err.println("fshdb: "+Debugger.message(e));
			code = 2;
		}
		System.out.flush();
		System.exit(code);
	}

	static int run(String[] args) throws IOException {
		List<String> first = new ArrayList<>();
		int i = 0;
		for(; i < args.length; i++) {
			String a = args[i];
			if( a.equals("-h") || a.equals("--help")) {
				System.out.print(USAGE);
				return 0;
			} else if( a.equals("--version")) {
				System.out.println("fshdb "+VERSION);
				return 0;
			} else if( a.equals("-e") && i+1 < args.length) {
				first.add(args[++i]);
			} else if( a.equals("-x") && i+1 < args.length) {
				first.addAll(Files.readAllLines(Paths.get(args[++i]), StandardCharsets.UTF_8));
			} else if( a.equals("--")) {
				i++;
				break;
			} else if( a.startsWith("-") && !a.equals("-")) {
				System.err.print("fshdb: unknown option "+a+"\n"+USAGE);
				return 2;
			} else {
				break;
			}
		}
		if( i >= args.length ) {
			System.err.print(USAGE);
			return 2;
		}
		ScriptFile script = new ScriptFile(args[i]);
		script.load();
		StringBuilder scriptArgs = new StringBuilder();
		for(int j=i+1; j < args.length; j++) {
			if( scriptArgs.length() > 0 ) {
				scriptArgs.append(' ');
			}
			scriptArgs.append(args[j]);
		}

		Terminal terminal = TerminalBuilder.builder().system(true).nativeSignals(true)
				.signalHandler(Terminal.SignalHandler.SIG_IGN).build();
		try {
			PrintStream out = System.out;
			// the script reads the terminal through JLine's stream, so nothing typed for the
			// script is taken by JLine (or the other way round). fsh treats that stream as the
			// keyboard, as it does System.in when it runs a script itself (read -p shows its
			// prompt, read -s doesn't echo ...).
			java.io.InputStream keyboard = terminal.input();
			System.setIn(keyboard);
			Console.System_in = keyboard;
			Debugger debugger = new Debugger(script, out, keyboard, System.out, System.err);
			if( scriptArgs.length() > 0 ) {
				debugger.setArguments(scriptArgs.toString());
			}
			terminal.handle(Terminal.Signal.INT, sig -> debugger.interrupt());

			LineReader reader = LineReaderBuilder.builder()
					.terminal(terminal)
					.appName("fshdb")
					.completer(new DebuggerCompleter(debugger))
					.variable(LineReader.HISTORY_FILE, new File(System.getProperty("user.home"), ".fshdb_history").toPath())
					.variable(LineReader.HISTORY_SIZE, 1000)
					.build();

			out.println("fshdb "+VERSION+": "+script.getPath()+", "+script.lineCount()+" lines. Type help for the commands.");
			CommandInput input = new JLineInput(reader, terminal, first);
			return debugger.loop(input);
		} finally {
			terminal.close();
		}
	}

	/** Commands from -e/-x first, then from the terminal. */
	static final class JLineInput implements CommandInput {
		private final LineReader reader;
		private final Terminal terminal;
		private final List<String> first;

		JLineInput(LineReader reader, Terminal terminal, List<String> first) {
			this.reader = reader;
			this.terminal = terminal;
			this.first = new ArrayList<>(first);
		}

		@Override
		public String readLine(String prompt) {
			if( !first.isEmpty()) {
				String cmd = first.remove(0);
				terminal.writer().println(prompt+cmd);
				terminal.writer().flush();
				return cmd;
			}
			try {
				return reader.readLine(prompt);
			} catch (UserInterruptException e) {
				return "";
			} catch (EndOfFileException e) {
				return null;
			}
		}

		@Override
		public void pause() {
			terminal.pause();
		}

		@Override
		public void resume() {
			terminal.resume();
		}
	}

	/** Tab completion: command names, then variable names (print, set) or file names (file). */
	static final class DebuggerCompleter implements Completer {
		private final Debugger debugger;
		private final FileNameCompleter files = new FileNameCompleter();

		DebuggerCompleter(Debugger debugger) {
			this.debugger = debugger;
		}

		@Override
		public void complete(LineReader reader, org.jline.reader.ParsedLine line, List<Candidate> candidates) {
			if( line.wordIndex() == 0 ) {
				for(String c : debugger.commandNames()) {
					candidates.add(new Candidate(c));
				}
				return;
			}
			String cmd = line.words().get(0);
			switch (cmd) {
			case "print": case "p": case "set":
				for(String v : debugger.variableNames()) {
					candidates.add(new Candidate(v));
				}
				if( cmd.equals("set") && line.wordIndex() == 1 ) {
					for(String w : new String[] {"args", "stdin", "stdout", "stderr", "var"}) {
						candidates.add(new Candidate(w));
					}
				}
				break;
			case "file":
				files.complete(reader, line, candidates);
				break;
			case "info": case "i":
				for(String w : new String[] {"breakpoints", "args", "redirects", "script"}) {
					candidates.add(new Candidate(w));
				}
				break;
			case "trace":
				candidates.add(new Candidate("on"));
				candidates.add(new Candidate("off"));
				break;
			case "help": case "h":
				for(String c : debugger.commandNames()) {
					candidates.add(new Candidate(c));
				}
				break;
			default:
			}
		}
	}
}
