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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Debugger sessions driven by commands, as typed at the prompt, with scripts run by fsh. */
public class TestDebugger {

	static final String DEMO =
			"#!/bin/bash\n"          // 1
			+ "# demo\n"             // 2
			+ "greet() {\n"          // 3
			+ "  local who=$1\n"     // 4
			+ "  echo \"hello $who\"\n" // 5
			+ "  return 0\n"         // 6
			+ "}\n"                  // 7
			+ "\n"                   // 8
			+ "total=0\n"            // 9
			+ "for i in 1 2 3 4; do\n" // 10
			+ "  total=$((total + i))\n" // 11
			+ "done\n"               // 12
			+ "greet world\n"        // 13
			+ "echo \"total=$total args=$*\"\n"; // 14

	/** A debugger, the commands it reads and everything it (and the script) printed. */
	static final class Session {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
		final Debugger debugger;
		int exitCode;

		Session(ScriptFile script, String stdin) {
			debugger = new Debugger(script, out, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), out, out);
		}

		Session(String text) {
			this(new ScriptFile("test.sh", text), "");
		}

		/** Runs the commands (then quit) and returns the output. */
		String run(String ... commands) {
			List<String> list = new ArrayList<>(Arrays.asList(commands));
			list.add("quit");
			assertTimeoutPreemptively(Duration.ofSeconds(30), ()->{
				exitCode = debugger.loop(prompt -> list.isEmpty() ? null : list.remove(0));
			});
			return text();
		}

		String text() {
			out.flush();
			return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static List<Integer> stops(String output) {
		List<Integer> ret = new ArrayList<>();
		Matcher m = Pattern.compile("(?:Stopped at line|Breakpoint \\d+, line) (\\d+)").matcher(output);
		while( m.find()) {
			ret.add(Integer.parseInt(m.group(1)));
		}
		return ret;
	}

	@Test
	public void stopsAtABreakpointAndShowsVariables() {
		Session s = new Session(DEMO);
		String out = s.run("break 11", "run a b", "print i total", "continue", "print i", "delete", "continue");
		assertTrue(out.contains("Breakpoint 1 at line 11."), out);
		assertEquals(Arrays.asList(11, 11), stops(out));
		assertTrue(out.contains("i = 1\ntotal = 0"), out);
		assertTrue(out.contains("i = 2"), out);
		assertTrue(out.contains("total=10 args=a b"), out);
		assertTrue(out.contains("[script ended, exit code 0]"), out);
		assertEquals(0, s.exitCode);
	}

	@Test
	public void conditionAndHitCount() {
		Session s = new Session(DEMO);
		String out = s.run("break 11 if [ $i -eq 3 ]", "run", "print i", "continue");
		assertEquals(Arrays.asList(11), stops(out));
		assertTrue(out.contains("i = 3"), out);

		s = new Session(DEMO);
		out = s.run("break 11 hits 2", "run", "print i", "continue");
		assertEquals(Arrays.asList(11), stops(out));
		assertTrue(out.contains("i = 2"), out);
	}

	@Test
	public void stepNextAndFinish() {
		Session s = new Session(DEMO);
		String out = s.run("break 13", "run", "step", "step", "print who", "finish", "continue");
		// step goes into greet (to its first command, not its { body), finish comes back out
		assertEquals(Arrays.asList(13, 4, 5, 14), stops(out));
		assertTrue(out.contains("who = world"), out);

		s = new Session(DEMO);
		out = s.run("break 13", "run", "next", "continue");
		// next steps over the call
		assertEquals(Arrays.asList(13, 14), stops(out));
		assertTrue(out.indexOf("hello world") < out.indexOf("Stopped at line 14"), out);
	}

	@Test
	public void aBlankLineRepeatsTheLastStep() {
		Session s = new Session("a=1\nb=2\nc=3\nd=4\n");
		String out = s.run("start", "next", "", "", "continue");
		assertEquals(Arrays.asList(1, 2, 3, 4), stops(out));
	}

	@Test
	public void inAFunctionDollarZeroIsTheScriptsAndDollarOneTheFunctions() {
		Session s = new Session(DEMO);
		// stopped before "local who=$1" in greet
		String out = s.run("break 4", "run a b", "print $0 $1 $#", "set $1=changed", "print $1", "set $0=x", "continue");
		assertTrue(out.contains("$0 = test.sh\n$1 = world\n$# = 1\n"), out);
		assertTrue(out.contains("$1 = changed\n"), out);
		assertTrue(out.contains("$0 can't be set"), out);
		// the function's $1 changed; the script's arguments didn't
		assertTrue(out.contains("hello changed\ntotal=10 args=a b"), out);
	}

	@Test
	public void lineNumbersAreTheFiles() {
		Session s = new Session("#!/bin/bash\necho \"line $LINENO\"\nf() { echo \"called from $BASH_LINENO\"; }\nf\n");
		String out = s.run("exec");
		assertTrue(out.contains("line 2\ncalled from 4\n"), out);
	}

	@Test
	public void startStopsAtTheFirstStatement() {
		Session s = new Session(DEMO);
		String out = s.run("start", "continue");
		// the function definition on line 3 is the first statement
		assertEquals(Arrays.asList(3), stops(out));
	}

	@Test
	public void execDoesntStopAtBreakpoints() {
		Session s = new Session(DEMO);
		String out = s.run("break 11", "exec x");
		assertTrue(stops(out).isEmpty(), out);
		assertTrue(out.contains("total=10 args=x"), out);
	}

	@Test
	public void setChangesTheScriptsVariables() {
		Session s = new Session("x=1\necho \"x is $x\"\n");
		String out = s.run("break 2", "run", "set x=42", "continue");
		assertTrue(out.contains("x is 42"), out);

		s = new Session("echo \"first is $1\"\n");
		out = s.run("start one", "set $1=uno", "print $1 $@ $#", "continue");
		assertTrue(out.contains("first is uno"), out);
		assertTrue(out.contains("$# = 1"), out);
	}

	@Test
	public void killStopsAPausedScript() {
		Session s = new Session("x=1\necho reached\n");
		String out = s.run("break 2", "run", "kill", "info script");
		assertFalse(Pattern.compile("(?m)^reached$").matcher(out).find(), out);
		assertTrue(out.contains("[script stopped"), out);
		assertTrue(out.contains("lines") && !out.contains("stopped at line"), out);
	}

	@Test
	public void ctrlCPausesARunningScript() throws Exception {
		Session s = new Session("i=0\nwhile true; do\n  i=$((i+1))\ndone\n");
		List<String> commands = new ArrayList<>(List.of("run"));
		Thread interrupter = new Thread(()->{
			try {
				// once it's running
				while( !s.debugger.isRunning()) {
					Thread.sleep(10);
				}
				Thread.sleep(300);
				s.debugger.interrupt();
			} catch (InterruptedException e) {
			}
		});
		interrupter.start();
		List<String> after = new ArrayList<>(List.of("print i", "kill"));
		assertTimeoutPreemptively(Duration.ofSeconds(30), ()->{
			s.debugger.loop(prompt -> {
				if( !commands.isEmpty()) {
					return commands.remove(0);
				}
				if( !after.isEmpty()) {
					return after.remove(0);
				}
				return null;
			});
		});
		String out = s.text();
		assertTrue(out.contains("[pausing at the next statement"), out);
		assertFalse(stops(out).isEmpty(), out);
		assertTrue(out.contains("[script stopped"), out);
	}

	@Test
	public void ctrlCTwiceStopsAScriptThatDoesntReachAStatement() throws Exception {
		// sleep doesn't reach another statement for a long time: the second Ctrl-C stops it
		Session s = new Session("sleep 30\necho never\n");
		List<String> commands = new ArrayList<>(List.of("run"));
		Thread interrupter = new Thread(()->{
			try {
				while( !s.debugger.isRunning()) {
					Thread.sleep(10);
				}
				Thread.sleep(300);
				s.debugger.interrupt();
				Thread.sleep(100);
				s.debugger.interrupt();
			} catch (InterruptedException e) {
			}
		});
		interrupter.start();
		assertTimeoutPreemptively(Duration.ofSeconds(20), ()->{
			s.debugger.loop(prompt -> commands.isEmpty() ? null : commands.remove(0));
		});
		String out = s.text();
		assertTrue(out.contains("[stopping the script]"), out);
		assertTrue(out.contains("[script stopped"), out);
		assertFalse(Pattern.compile("(?m)^never$").matcher(out).find(), out);
	}

	@Test
	public void settingsLinesOfAnEarlierFshIdeKeepFileLineNumbers() {
		// an earlier fsh-ide kept a script's arguments in a comment line: read, and run as a blank line
		String text = "#BjlIdeScriptArgs=alpha beta\n"
				+ "x=1\n"
				+ "echo \"args $* line $LINENO\"\n";
		Session s = new Session(text);
		String out = s.run("break 3", "info args", "run", "list 3", "continue");
		assertEquals(Arrays.asList(3), stops(out), out);
		assertTrue(out.contains("Arguments: alpha beta"), out);
		assertTrue(out.contains("  3 *> echo \"args $* line $LINENO\""), out);
		assertTrue(out.contains("args alpha beta line 3"), out);
	}

	@Test
	public void runsWithTheArgumentsFshIdeKeepsForTheScript() throws Exception {
		java.nio.file.Path dir = Files.createTempDirectory("fshdb-settings");
		try {
			File script = dir.resolve("kept.sh").toFile();
			Files.write(script.toPath(), "echo \"args $*\" >&2\necho out\n".getBytes(StandardCharsets.UTF_8));
			us.bringardner.fsh.ide.core.RunSettingsStore store = new us.bringardner.fsh.ide.core.RunSettingsStore(
					dir.resolve("run-settings").toFile());
			File outFile = dir.resolve("out.txt").toFile();
			store.put(script.getCanonicalPath(), new us.bringardner.fsh.ide.core.RunSettings("one two", "", outFile.getPath(), ""));
			ScriptFile f = new ScriptFile(script.getPath(), store);
			f.load();
			Session s = new Session(f, "");
			String out = s.run("info args", "info redirects", "run");
			assertTrue(out.contains("Arguments: one two"), out);
			assertTrue(out.contains("stdout: "+outFile.getPath()), out);
			assertTrue(out.contains("args one two"), out);
			assertEquals("out\n", new String(Files.readAllBytes(outFile.toPath()), StandardCharsets.UTF_8));
			// and the script's own text runs as it is
			assertFalse(f.code().contains("Bjl"));
		} finally {
			for(File x : dir.toFile().listFiles()) {
				x.delete();
			}
			dir.toFile().delete();
		}
	}

	@Test
	public void breakOnALineWithoutCodeMovesToTheNext() {
		Session s = new Session(DEMO);
		String out = s.run("break 8", "break 99", "info breakpoints");
		assertTrue(out.contains("Breakpoint 1 at line 9 (line 8 has no code)."), out);
		assertTrue(out.contains("Line 99 is not in the script (14 lines)."), out);
		assertTrue(out.matches("(?s).*1\\s+9\\s+yes.*"), out);
	}

	@Test
	public void disabledBreakpointsDontStop() {
		Session s = new Session(DEMO);
		String out = s.run("break 11", "disable 1", "run", "enable", "info b");
		assertTrue(stops(out).isEmpty(), out);
		assertTrue(out.contains("Enabled breakpoint 1."), out);
	}

	@Test
	public void whereShowsTheCallers() {
		String text = "inner() {\n  echo in\n}\nouter() {\n  inner\n}\nouter\n";
		Session s = new Session(text);
		String out = s.run("break 2", "run", "where", "continue");
		assertTrue(out.contains("#0  line 2 in inner"), out);
		assertTrue(out.contains("#1  line 5 in outer (calling inner)"), out);
		assertTrue(out.contains("#2  line 7 in main (calling outer)"), out);
	}

	@Test
	public void traceShowsEachStatement() {
		Session s = new Session("a=1\nb=2\n");
		String out = s.run("trace on", "run");
		assertTrue(out.contains("+ 1: a=1\n+ 2: b=2"), out);
	}

	@Test
	public void listMarksBreakpointsAndTheCurrentLine() {
		Session s = new Session(DEMO);
		String out = s.run("break 11", "run", "list", "kill");
		assertTrue(out.contains(" 10    for i in 1 2 3 4; do"), out);
		assertTrue(out.contains(" 11 *>   total=$((total + i))"), out);
	}

	@Test
	public void scriptInputComesFromItsStdin() {
		ScriptFile f = new ScriptFile("in.sh", "read name\necho \"got $name\"\n");
		Session s = new Session(f, "Ada\n");
		String out = s.run("run");
		assertTrue(out.contains("got Ada"), out);
	}

	@Test
	public void exitCodeOfTheLastRunIsReturned() {
		Session s = new Session("exit 3\n");
		s.run("run");
		assertEquals(3, s.exitCode);
	}

	@Test
	public void unknownCommandsAndBadArguments() {
		Session s = new Session(DEMO);
		String out = s.run("frobnicate", "break x", "continue", "print x");
		assertTrue(out.contains("Unknown command \"frobnicate\""), out);
		assertTrue(out.contains("\"x\" is not a number."), out);
		assertTrue(out.contains("The script isn't running."), out);
		assertTrue(out.contains("The script hasn't run yet."), out);
	}

	@Test
	public void editRunsTheEditorAndReloads() throws Exception {
		File dir = Files.createTempDirectory("fshdb").toFile();
		File script = new File(dir, "edit.sh");
		Files.write(script.toPath(), "echo before\n".getBytes(StandardCharsets.UTF_8));
		// an "editor" that rewrites the file and records its arguments
		File log = new File(dir, "editor.log");
		File fake = new File(dir, "fake-editor");
		Files.write(fake.toPath(), ("#!/bin/sh\necho \"$@\" > '"+log+"'\n"
				+ "for f; do :; done\nprintf 'echo after\\necho more\\n' > \"$f\"\n").getBytes(StandardCharsets.UTF_8));
		fake.setExecutable(true);

		ScriptFile sf = new ScriptFile(script.getAbsolutePath());
		sf.load();
		Session s = new Session(sf, "");
		s.debugger.setEditor(new ExternalEditor(java.util.Map.of("EDITOR", fake.getAbsolutePath(), "PATH", "/usr/bin:/bin")));
		String out = s.run("edit 1", "run");
		assertTrue(out.contains("Reloaded"), out);
		assertTrue(out.contains("after\nmore"), out);
		String args = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).trim();
		// not a known editor: no +LINE
		assertEquals(script.getAbsolutePath(), args);
	}

	@Test
	public void editorCommandLines() throws Exception {
		File f = new File("/tmp/x.sh");
		assertEquals(List.of("vim", "+7", f.getAbsolutePath()),
				new ExternalEditor(java.util.Map.of("EDITOR", "vim")).commandLine(f, 7));
		assertEquals(List.of("emacs", "-nw", "+2", f.getAbsolutePath()),
				new ExternalEditor(java.util.Map.of("VISUAL", "emacs -nw", "EDITOR", "vi")).commandLine(f, 2));
		assertEquals(List.of("code", "-w", f.getAbsolutePath()),
				new ExternalEditor(java.util.Map.of("EDITOR", "code -w")).commandLine(f, 2));
	}

	@Test
	public void formatsValuesLikeDeclare() {
		assertEquals("abc", Debugger.format("abc"));
		assertEquals("\"a b\"", Debugger.format("a b"));
		assertEquals("(1 \"two words\")", Debugger.format(List.of("1", "two words")));
		assertEquals("\"line\\nnext\"", Debugger.format("line\nnext"));
	}
}
