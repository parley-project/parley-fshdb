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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.dap.DapConnection;
import us.bringardner.parley.dap.DapException;
import us.bringardner.parley.dap.protocol.AttachRequestArguments;
import us.bringardner.parley.dap.protocol.Breakpoint;
import us.bringardner.parley.dap.protocol.ConfigurationDoneArguments;
import us.bringardner.parley.dap.protocol.ContinueArguments;
import us.bringardner.parley.dap.protocol.DisconnectArguments;
import us.bringardner.parley.dap.protocol.EvaluateArguments;
import us.bringardner.parley.dap.protocol.ExitedEventBody;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.IDebugClient;
import us.bringardner.parley.dap.protocol.InitializeRequestArguments;
import us.bringardner.parley.dap.protocol.NextArguments;
import us.bringardner.parley.dap.protocol.OutputEventBody;
import us.bringardner.parley.dap.protocol.PauseArguments;
import us.bringardner.parley.dap.protocol.Scope;
import us.bringardner.parley.dap.protocol.ScopesArguments;
import us.bringardner.parley.dap.protocol.SetBreakpointsArguments;
import us.bringardner.parley.dap.protocol.SetVariableArguments;
import us.bringardner.parley.dap.protocol.Source;
import us.bringardner.parley.dap.protocol.SourceArguments;
import us.bringardner.parley.dap.protocol.SourceBreakpoint;
import us.bringardner.parley.dap.protocol.StackFrame;
import us.bringardner.parley.dap.protocol.StackTraceArguments;
import us.bringardner.parley.dap.protocol.StepInArguments;
import us.bringardner.parley.dap.protocol.StepOutArguments;
import us.bringardner.parley.dap.protocol.StoppedEventBody;
import us.bringardner.parley.dap.protocol.TerminatedEventBody;
import us.bringardner.parley.dap.protocol.Variable;
import us.bringardner.parley.dap.protocol.VariablesArguments;

/**
 * fshdb --listen: a debugger attaches over the Debug Adapter Protocol (a real socket, parley-dap
 * on both ends) to a script run by fsh.
 */
public class TestDap {

	static final String LOOP =
			"total=0\n"                       // 1
			+ "for i in 1 2 3 4; do\n"        // 2
			+ "  total=$((total + i))\n"      // 3
			+ "done\n"                        // 4
			+ "echo \"total=$total\"\n";      // 5

	static final String FUNCTIONS =
			"greet() {\n"                     // 1
			+ "  local who=$1\n"              // 2
			+ "  echo \"hello $who\"\n"       // 3
			+ "}\n"                           // 4
			+ "list=(a 'b c')\n"              // 5
			+ "greet world\n"                 // 6
			+ "echo done\n";                  // 7

	/** The debugger's side: records the events. */
	static final class Client implements IDebugClient {
		final BlockingQueue<StoppedEventBody> stops = new LinkedBlockingQueue<>();
		final BlockingQueue<Integer> exits = new LinkedBlockingQueue<>();
		final BlockingQueue<String> outputs = new LinkedBlockingQueue<>();
		final CompletableFuture<Void> initialized = new CompletableFuture<>();
		final CompletableFuture<Void> terminated = new CompletableFuture<>();
		// the order the initialize response and the initialized event came in
		volatile boolean initializeAnswered;
		volatile boolean initializedBeforeAnswer;

		@Override
		public void initialized() {
			initializedBeforeAnswer = !initializeAnswered;
			initialized.complete(null);
		}

		@Override
		public void stopped(StoppedEventBody body) {
			stops.add(body);
		}

		@Override
		public void exited(ExitedEventBody body) {
			exits.add(body.getExitCode());
		}

		@Override
		public void terminated(TerminatedEventBody body) {
			terminated.complete(null);
		}

		@Override
		public void output(OutputEventBody body) {
			outputs.add(body.getOutput());
		}

		StoppedEventBody nextStop() throws InterruptedException {
			StoppedEventBody s = stops.poll(20, TimeUnit.SECONDS);
			assertNotNull(s, "no stopped event");
			return s;
		}
	}

	private DapHost host;
	private Thread hostThread;
	private volatile int exitCode = -999;
	private final ByteArrayOutputStream scriptOut = new ByteArrayOutputStream();
	private final List<Socket> sockets = new ArrayList<>();

	private int listen(String code, boolean suspend) throws Exception {
		ScriptFile script = new ScriptFile("/tmp/dap-test.sh", code);
		PrintStream out = new PrintStream(scriptOut, true, StandardCharsets.UTF_8);
		DebugEngine engine = new DebugEngine(script, new ByteArrayInputStream(new byte[0]), out, out);
		host = new DapHost(engine, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), suspend, null);
		int port = host.bind();
		hostThread = new Thread(()->{
			try {
				exitCode = host.run();
			} catch (Exception e) {
				exitCode = -1;
			}
		}, "test host");
		hostThread.setDaemon(true);
		hostThread.start();
		return port;
	}

	/** Connects a debugger and initializes it (initialize, initialized event, attach). */
	private IDebugAdapter attach(int port, Client client) throws Exception {
		Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
		sockets.add(s);
		DapConnection c = DapConnection.client(client, s.getInputStream(), s.getOutputStream());
		IDebugAdapter server = c.getAdapter();
		assertTrue(server.initialize(new InitializeRequestArguments().setAdapterID("fshdb").setLinesStartAt1(true))
				.thenApply(caps -> {
					client.initializeAnswered = true;
					return caps;
				}).get(10, TimeUnit.SECONDS).getSupportsConfigurationDoneRequest());
		client.initialized.get(10, TimeUnit.SECONDS);
		server.attach(new AttachRequestArguments()).get(10, TimeUnit.SECONDS);
		return server;
	}

	private static List<Breakpoint> setBreakpoints(IDebugAdapter server, String path, int ... lines) throws Exception {
		List<SourceBreakpoint> bps = new ArrayList<>();
		for(int line : lines) {
			bps.add(new SourceBreakpoint().setLine(line));
		}
		return server.setBreakpoints(new SetBreakpointsArguments().setSource(new Source().setPath(path)).setBreakpoints(bps))
				.get(10, TimeUnit.SECONDS).getBreakpoints();
	}

	private static List<Breakpoint> setBreakpoints(IDebugAdapter server, SourceBreakpoint ... bps) throws Exception {
		return server.setBreakpoints(new SetBreakpointsArguments().setSource(new Source().setPath("/tmp/dap-test.sh"))
				.setBreakpoints(List.of(bps))).get(10, TimeUnit.SECONDS).getBreakpoints();
	}

	private static List<StackFrame> frames(IDebugAdapter server) throws Exception {
		return server.stackTrace(new StackTraceArguments().setThreadId(FshdbAdapter.THREAD_ID)).get(10, TimeUnit.SECONDS).getStackFrames();
	}

	private static int line(IDebugAdapter server) throws Exception {
		return frames(server).get(0).getLine();
	}

	/** The variables of the scope named name in the top frame. */
	private static Map<String,Variable> variables(IDebugAdapter server, String scopeName) throws Exception {
		List<Scope> scopes = server.scopes(new ScopesArguments().setFrameId(frames(server).get(0).getId())).get(10, TimeUnit.SECONDS).getScopes();
		for(Scope s : scopes) {
			if( s.getName().equals(scopeName)) {
				return children(server, s.getVariablesReference());
			}
		}
		throw new AssertionError("no scope "+scopeName);
	}

	private static Map<String,Variable> children(IDebugAdapter server, int ref) throws Exception {
		Map<String,Variable> ret = new HashMap<>();
		for(Variable v : server.variables(new VariablesArguments().setVariablesReference(ref)).get(10, TimeUnit.SECONDS).getVariables()) {
			ret.put(v.getName(), v);
		}
		return ret;
	}

	private static void configurationDone(IDebugAdapter server) throws Exception {
		server.configurationDone(new ConfigurationDoneArguments()).get(10, TimeUnit.SECONDS);
	}

	private static void resume(IDebugAdapter server) throws Exception {
		assertTrue(server.continue_(new ContinueArguments().setThreadId(FshdbAdapter.THREAD_ID)).get(10, TimeUnit.SECONDS).getAllThreadsContinued());
	}

	private int waitForExit() throws InterruptedException {
		hostThread.join(20_000);
		assertFalse(hostThread.isAlive(), "fshdb --listen didn't end");
		return exitCode;
	}

	private String output() {
		return new String(scriptOut.toByteArray(), StandardCharsets.UTF_8);
	}

	@AfterEach
	public void cleanup() throws Exception {
		for(Socket s : sockets) {
			s.close();
		}
		if( host != null ) {
			host.close();
		}
	}

	@Test
	public void suspendedScriptStopsAtABreakpointAndShowsVariables() throws Exception {
		int port = listen(LOOP, true);
		Client client = new Client();
		IDebugAdapter server = attach(port, client);
		assertFalse(client.initializedBeforeAnswer, "the initialized event came before the initialize response");
		List<Breakpoint> bps = setBreakpoints(server, "/tmp/dap-test.sh", 3);
		assertTrue(bps.get(0).getVerified());
		assertEquals(3, bps.get(0).getLine());
		// nothing runs before configurationDone
		Thread.sleep(300);
		assertTrue(output().isEmpty());
		configurationDone(server);

		StoppedEventBody stop = client.nextStop();
		assertEquals("breakpoint", stop.getReason());
		assertEquals(FshdbAdapter.THREAD_ID, stop.getThreadId());
		assertEquals(List.of(bps.get(0).getId()), stop.getHitBreakpointIds());
		List<StackFrame> f = frames(server);
		assertEquals(1, f.size());
		assertEquals("main", f.get(0).getName());
		assertEquals(3, f.get(0).getLine());
		assertEquals("/tmp/dap-test.sh", f.get(0).getSource().getPath());
		Map<String,Variable> vars = variables(server, "Variables");
		assertEquals("1", vars.get("i").getValue());
		assertEquals("0", vars.get("total").getValue());

		resume(server);
		client.nextStop();
		assertEquals("2", variables(server, "Variables").get("i").getValue());

		// no breakpoints: runs to the end
		setBreakpoints(server, "/tmp/dap-test.sh");
		resume(server);
		assertEquals(0, client.exits.poll(20, TimeUnit.SECONDS));
		client.terminated.get(10, TimeUnit.SECONDS);
		assertEquals(0, waitForExit());
		assertTrue(output().contains("total=10"), output());
	}

	@Test
	public void conditionAndHitCount() throws Exception {
		int port = listen(LOOP, true);
		Client client = new Client();
		IDebugAdapter server = attach(port, client);
		SourceBreakpoint cond = new SourceBreakpoint().setLine(3).setCondition("[ $i -eq 3 ]");
		setBreakpoints(server, cond);
		configurationDone(server);
		client.nextStop();
		assertEquals("3", variables(server, "Variables").get("i").getValue());

		SourceBreakpoint hits = new SourceBreakpoint().setLine(3).setHitCondition("1");
		// a new hit count starts from this hit
		setBreakpoints(server, hits);
		resume(server);
		client.nextStop();
		assertEquals("4", variables(server, "Variables").get("i").getValue());
		resume(server);
		assertEquals(0, waitForExit());
	}

	@Test
	public void steppingAndTheCallStack() throws Exception {
		int port = listen(FUNCTIONS, true);
		Client client = new Client();
		IDebugAdapter server = attach(port, client);
		setBreakpoints(server, "/tmp/dap-test.sh", 6);
		configurationDone(server);
		client.nextStop();
		assertEquals(6, line(server));

		StepInArguments in = new StepInArguments().setThreadId(FshdbAdapter.THREAD_ID);
		server.stepIn(in).get(10, TimeUnit.SECONDS);
		assertEquals("step", client.nextStop().getReason());
		List<StackFrame> f = frames(server);
		assertEquals(2, f.size());
		assertEquals("greet", f.get(0).getName());
		assertEquals(2, f.get(0).getLine());
		assertEquals("main", f.get(1).getName());
		assertEquals(6, f.get(1).getLine());

		NextArguments next = new NextArguments().setThreadId(FshdbAdapter.THREAD_ID);
		server.next(next).get(10, TimeUnit.SECONDS);
		client.nextStop();
		assertEquals(3, line(server));
		assertEquals("world", variables(server, "Variables").get("who").getValue());
		assertEquals("world", variables(server, "Arguments").get("$1").getValue());

		StepOutArguments out = new StepOutArguments().setThreadId(FshdbAdapter.THREAD_ID);
		server.stepOut(out).get(10, TimeUnit.SECONDS);
		client.nextStop();
		assertEquals(7, line(server));
		assertTrue(output().contains("hello world"));
		resume(server);
		assertEquals(0, waitForExit());
	}

	@Test
	public void arraysSetVariableAndEvaluate() throws Exception {
		String code = "list=(a 'b c')\nx=1\necho \"x=$x\"\n";
		int port = listen(code, true);
		Client client = new Client();
		IDebugAdapter server = attach(port, client);
		setBreakpoints(server, "/tmp/dap-test.sh", 3);
		configurationDone(server);
		client.nextStop();
		Map<String,Variable> vars = variables(server, "Variables");
		Variable list = vars.get("list");
		assertEquals("(a \"b c\")", list.getValue());
		assertTrue(list.getVariablesReference() > 0);
		Map<String,Variable> items = children(server, list.getVariablesReference());
		assertEquals("\"b c\"", items.get("[1]").getValue());

		SetVariableArguments sv = new SetVariableArguments().setVariablesReference(1).setName("x").setValue("42");
		assertEquals("42", server.setVariable(sv).get(10, TimeUnit.SECONDS).getValue());

		EvaluateArguments ev = new EvaluateArguments().setExpression("$x");
		assertEquals("42", server.evaluate(ev).get(10, TimeUnit.SECONDS).getResult());
		ev.setExpression("rm -rf /");
		ExecutionException ex = assertThrows(ExecutionException.class, ()->server.evaluate(ev).get(10, TimeUnit.SECONDS));
		assertTrue(((DapException) ex.getCause()).getText().contains("Only variables"), ex.getCause().getMessage());

		resume(server);
		assertEquals(0, waitForExit());
		assertTrue(output().contains("x=42"), output());
	}

	@Test
	public void attachToARunningScriptAndPause() throws Exception {
		String code = "i=0\nwhile true; do\n  i=$((i+1))\ndone\n";
		int port = listen(code, false);
		// it runs before any debugger attaches
		Thread.sleep(300);
		Client client = new Client();
		IDebugAdapter server = attach(port, client);
		configurationDone(server);
		PauseArguments p = new PauseArguments().setThreadId(FshdbAdapter.THREAD_ID);
		server.pause(p).get(10, TimeUnit.SECONDS);
		assertEquals("pause", client.nextStop().getReason());
		int i = Integer.parseInt(variables(server, "Variables").get("i").getValue());
		assertTrue(i > 0, "it had been running: i="+i);

		// a breakpoint while it runs
		resume(server);
		setBreakpoints(server, "/tmp/dap-test.sh", 3);
		assertEquals("breakpoint", client.nextStop().getReason());

		DisconnectArguments d = new DisconnectArguments().setTerminateDebuggee(true);
		server.disconnect(d).get(10, TimeUnit.SECONDS);
		assertEquals(128+9, waitForExit());
	}

	@Test
	public void detachLetsTheScriptGoOnAndAnotherDebuggerAttach() throws Exception {
		String code = "i=0\nwhile [ $i -lt 2000000 ]; do\n  i=$((i+1))\ndone\necho \"end $i\"\n";
		int port = listen(code, true);
		Client first = new Client();
		IDebugAdapter server = attach(port, first);
		setBreakpoints(server, "/tmp/dap-test.sh", 3);
		configurationDone(server);
		first.nextStop();
		// detach while stopped: it goes on, without the breakpoint
		server.disconnect(new DisconnectArguments()).get(10, TimeUnit.SECONDS);
		Thread.sleep(300);
		assertTrue(hostThread.isAlive());

		Client second = new Client();
		IDebugAdapter again = attach(port, second);
		setBreakpoints(again, "/tmp/dap-test.sh", 3);
		configurationDone(again);
		assertEquals("breakpoint", second.nextStop().getReason());
		setBreakpoints(again, "/tmp/dap-test.sh");
		resume(again);
		assertEquals(0, second.exits.poll(60, TimeUnit.SECONDS));
		assertEquals(0, waitForExit());
		assertNull(first.stops.poll(), "the first debugger heard nothing after it left");
	}

	@Test
	public void breakpointsOnOtherFilesAndLinesWithoutCode() throws Exception {
		int port = listen("# a comment\n\nx=1\n", true);
		Client client = new Client();
		IDebugAdapter server = attach(port, client);
		List<Breakpoint> other = setBreakpoints(server, "/somewhere/else.sh", 3);
		assertFalse(other.get(0).getVerified());
		// the same file name in another directory (the debugger's copy) is taken as the script
		List<Breakpoint> moved = setBreakpoints(server, "/home/me/copy/dap-test.sh", 1, 9);
		assertTrue(moved.get(0).getVerified());
		assertEquals(3, moved.get(0).getLine(), "moved to the first line with code");
		assertFalse(moved.get(1).getVerified());

		SourceArguments sa = new SourceArguments().setSourceReference(0);
		assertEquals("# a comment\n\nx=1\n", server.source(sa).get(10, TimeUnit.SECONDS).getContent());
		configurationDone(server);
		assertEquals("breakpoint", client.nextStop().getReason());
		resume(server);
		assertEquals(0, waitForExit());
	}

	@Test
	public void theExitCodeIsFshdbs() throws Exception {
		int port = listen("echo bye\nexit 7\n", true);
		Client client = new Client();
		IDebugAdapter server = attach(port, client);
		configurationDone(server);
		assertEquals(7, client.exits.poll(20, TimeUnit.SECONDS));
		client.terminated.get(10, TimeUnit.SECONDS);
		assertEquals(7, waitForExit());
	}

	@Test
	public void addresses() throws Exception {
		assertEquals(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5005), DapHost.parseAddress("5005"));
		assertEquals(5005, DapHost.parseAddress("0.0.0.0:5005").getPort());
		assertTrue(DapHost.parseAddress("0.0.0.0:5005").getAddress().isAnyLocalAddress());
		assertEquals(4711, DapHost.parseAddress("[::1]:4711").getPort());
		assertThrows(java.io.IOException.class, ()->DapHost.parseAddress("x:abc"));
	}
}
