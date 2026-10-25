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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * fshdb --attach: the command line debugging a script that runs in a fshdb --listen (here a
 * DapHost on a thread), over DAP on a loopback socket.
 */
public class TestAttach {

	private DapHost host;
	private Thread hostThread;
	private volatile int hostExit = -999;
	private final ByteArrayOutputStream scriptOut = new ByteArrayOutputStream();
	private RemoteTarget target;

	@AfterEach
	public void stop() throws Exception {
		if( target != null ) {
			target.close();
		}
		if( host != null ) {
			host.close();
		}
		if( hostThread != null ) {
			hostThread.join(5000);
		}
	}

	/** Starts fshdb --listen on a free port. @return the port */
	private int listen(String code, boolean suspend, String args) throws Exception {
		ScriptFile script = new ScriptFile("/tmp/attach-test.sh", code);
		PrintStream out = new PrintStream(scriptOut, true, StandardCharsets.UTF_8);
		DebugEngine engine = new DebugEngine(script, new ByteArrayInputStream(new byte[0]), out, out);
		if( args != null ) {
			engine.setArguments(args);
		}
		host = new DapHost(engine, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), suspend, null);
		int port = host.bind();
		hostThread = new Thread(()->{
			try {
				hostExit = host.run();
			} catch (Exception e) {
				hostExit = -1;
			}
		}, "test host");
		hostThread.setDaemon(true);
		hostThread.start();
		return port;
	}

	private String scriptOutput() {
		return new String(scriptOut.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
	}

	/**
	 * The debugger attached to the listening fshdb, its commands and what it printed. A command
	 * "@wait TEXT" waits until TEXT has been printed (a stop reported above the prompt).
	 */
	final class Session implements CommandInput {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
		final Debugger debugger;
		final List<String> commands = new ArrayList<>();
		final boolean above;
		volatile boolean woken;
		int exitCode;

		Session(int port, boolean above) throws IOException {
			target = RemoteTarget.connect(String.valueOf(port));
			debugger = new Debugger(target, out, out, out);
			this.above = above;
		}

		String run(String ... list) {
			commands.addAll(Arrays.asList(list));
			assertTimeoutPreemptively(Duration.ofSeconds(30), ()->{
				exitCode = debugger.loop(this);
			});
			return text();
		}

		String text() {
			out.flush();
			return new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
		}

		@Override
		public String readLine(String prompt) {
			while( !commands.isEmpty() && commands.get(0).startsWith("@wait ")) {
				String what = commands.remove(0).substring(6);
				long until = System.currentTimeMillis()+20000;
				while( !text().contains(what) && !woken ) {
					assertTrue(System.currentTimeMillis() < until, "never printed: "+what+"\n"+text());
					sleep(20);
				}
			}
			if( woken ) {
				woken = false;
				return "";
			}
			return commands.isEmpty() ? null : commands.remove(0);
		}

		@Override
		public boolean printAbove(String text) {
			if( above ) {
				out.println(text);
			}
			return above;
		}

		@Override
		public void wake() {
			woken = true;
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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

	private void waitForHost() throws InterruptedException {
		hostThread.join(20000);
		assertFalse(hostThread.isAlive(), "the listening fshdb didn't end");
	}

	@Test
	public void debugsAWaitingScriptLikeALocalOne() throws Exception {
		int port = listen(TestDebugger.DEMO, true, "a b");
		Session s = new Session(port, false);
		assertTrue(target.isWaitingToStart());
		assertEquals(TestDebugger.DEMO.split("\n").length, target.getScript().lineCount());
		String out = s.run("break 11 if [ $i -eq 3 ]", "list 9,12", "info breakpoints", "run", "print i total $@ $#",
				"where", "vars", "set total=100", "print total", "continue");
		assertTrue(out.contains(" 11 *    total=$((total + i))"), out);
		assertTrue(out.contains("1    11    yes      if [ $i -eq 3 ]"), out);
		assertEquals(Arrays.asList(11), stops(out));
		assertTrue(out.contains("Breakpoint 1, line 11"), out);
		assertTrue(out.contains("i = 3\ntotal = 3\n$@ = (a b)\n$# = 2\n"), out);
		assertTrue(out.contains("#0  line 11 in main"), out);
		assertTrue(out.contains("$1 = a\n$2 = b\n"), out);
		assertTrue(out.contains("total = 100\n"), out);
		// the script ended there, so the debugger leaves with its exit code
		assertTrue(out.contains("[script ended, exit code 0]"), out);
		waitForHost();
		assertTrue(scriptOutput().contains("hello world\ntotal=107 args=a b"), scriptOutput());
		assertEquals(0, s.exitCode);
		assertEquals(0, hostExit);
	}

	@Test
	public void stepsIntoAndOutOfFunctions() throws Exception {
		int port = listen(TestDebugger.DEMO, true, null);
		Session s = new Session(port, false);
		String out = s.run("break 13", "run", "step", "where", "print who", "finish", "continue");
		// 13, into greet (4), finish: back after the call (14)
		assertEquals(Arrays.asList(13, 4, 14), stops(out));
		assertTrue(out.contains("#0  line 4 in greet\n#1  line 13 in main (calling greet)"), out);
		assertTrue(out.contains("who = (not set)") || out.contains("who = world"), out);
		waitForHost();
	}

	@Test
	public void startStopsAtTheFirstStatement() throws Exception {
		int port = listen(TestDebugger.DEMO, true, null);
		Session s = new Session(port, false);
		String out = s.run("start", "next", "continue");
		// as for a script run here: the function definition (3), then total=0 (9)
		assertEquals(Arrays.asList(3, 9), stops(out));
		waitForHost();
	}

	@Test
	public void attachesToARunningScriptAndDetaches() throws Exception {
		File flag = File.createTempFile("fshdb-attach", ".flag");
		flag.delete();
		String code =
				"n=0\n"                               // 1
				+ "while [ ! -f \""+flag.getPath().replace('\\', '/')+"\" ]; do\n" // 2
				+ "  n=$((n+1))\n"                    // 3
				+ "  sleep 0.05\n"                    // 4
				+ "done\n"                            // 5
				+ "echo \"done $n\"\n";               // 6
		try {
			int port = listen(code, false, null);
			Thread.sleep(300);
			Session s = new Session(port, true);
			assertFalse(target.isWaitingToStart());
			assertTrue(target.isRunning());
			// the stop comes while the prompt is up: it's shown above it
			String out = s.run("break 3", "@wait Breakpoint 1, line 3", "print n", "where", "next", "delete", "quit");
			assertTrue(out.contains("Breakpoint 1 at line 3."), out);
			assertTrue(out.contains("Breakpoint 1, line 3\n  3 *>   n=$((n+1))"), out);
			assertTrue(Pattern.compile("n = \\d+").matcher(out).find(), out);
			assertTrue(out.contains("Stopped at line 4"), out);
			assertTrue(out.contains("Detached; the script goes on."), out);
			// it goes on without the debugger, and ends when told to
			assertTrue(hostThread.isAlive());
			Files.write(flag.toPath(), new byte[0]);
			waitForHost();
			assertTrue(scriptOutput().matches("(?s).*done \\d+\\n.*"), scriptOutput());
			assertEquals(0, hostExit);
		} finally {
			flag.delete();
		}
	}

	@Test
	public void anotherDebuggerCanAttachAfterOneLeaves() throws Exception {
		File flag = File.createTempFile("fshdb-attach", ".flag");
		flag.delete();
		String code = "while [ ! -f \""+flag.getPath().replace('\\', '/')+"\" ]; do\n  sleep 0.05\ndone\necho over\n";
		try {
			int port = listen(code, false, null);
			Session first = new Session(port, true);
			String out = first.run("break 2", "@wait Breakpoint 1, line 2", "quit");
			assertTrue(out.contains("Detached"), out);
			target.close();
			Session second = new Session(port, true);
			out = second.run("info breakpoints", "break 2", "@wait Breakpoint 1, line 2", "kill");
			// the first one's breakpoints went with it
			assertTrue(out.contains("No breakpoints."), out);
			assertTrue(out.contains("[script stopped"), out);
			waitForHost();
			assertFalse(scriptOutput().contains("over"), scriptOutput());
		} finally {
			flag.delete();
		}
	}

	@Test
	public void leavesWhenTheScriptEndsWhileThePromptIsUp() throws Exception {
		int port = listen("sleep 0.5\necho bye\nexit 3\n", false, null);
		Session s = new Session(port, true);
		// waits at the prompt; the end is shown above it and the prompt goes
		String out = s.run("@wait [script ended", "print never");
		assertTrue(out.contains("[script ended, exit code 3]"), out);
		assertFalse(out.contains("never"), out);
		assertEquals(3, s.exitCode);
		waitForHost();
		assertEquals(3, hostExit);
	}

	@Test
	public void whatOnlyTheListeningFshdbCanDo() throws Exception {
		int port = listen(TestDebugger.DEMO, true, "x");
		Session s = new Session(port, false);
		String out = s.run("set args y", "set stdout /tmp/x", "trace on", "edit", "reload", "exec", "info args",
				"continue", "kill", "quit");
		assertTrue(out.contains("The script's arguments are given to the fshdb you attached to (fshdb --listen)."), out);
		assertTrue(out.contains("The script's input and output are set in the fshdb you attached to"), out);
		assertTrue(out.contains("trace isn't available when attached"), out);
		assertTrue(out.contains("edit it there"), out);
		assertTrue(out.contains("The script runs (and is edited) in the fshdb you attached to"), out);
		assertTrue(out.contains("exec isn't available when attached; use run."), out);
		assertTrue(out.contains("print $@ shows the arguments"), out);
		assertTrue(out.contains("The script isn't running."), out);
		// quit before it started: it still waits for a debugger
		assertTrue(hostThread.isAlive());
		target.close();
		Session again = new Session(port, false);
		out = again.run("run");
		assertTrue(out.contains("[script ended, exit code 0]"), out);
		waitForHost();
		assertTrue(scriptOutput().contains("args=x"), scriptOutput());
	}

	@Test
	public void refusesWhatIsntFshdb() throws Exception {
		try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread t = new Thread(()->{
				try {
					ss.accept().close();
				} catch (IOException e) {
					// closed
				}
			});
			t.setDaemon(true);
			t.start();
			IOException e = assertThrows(IOException.class, ()->RemoteTarget.connect(String.valueOf(ss.getLocalPort())));
			assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
		}
		IOException e = assertThrows(IOException.class, ()->RemoteTarget.connect("127.0.0.1:1"));
		assertTrue(e.getMessage().startsWith("Can't connect to 127.0.0.1:1"), e.getMessage());
	}
}
