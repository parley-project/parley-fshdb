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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import us.bringardner.parley.dap.DapConnection;
import us.bringardner.parley.dap.DapException;
import us.bringardner.parley.dap.protocol.AttachRequestArguments;
import us.bringardner.parley.dap.protocol.Capabilities;
import us.bringardner.parley.dap.protocol.ConfigurationDoneArguments;
import us.bringardner.parley.dap.protocol.ContinueArguments;
import us.bringardner.parley.dap.protocol.DisconnectArguments;
import us.bringardner.parley.dap.protocol.EvaluateArguments;
import us.bringardner.parley.dap.protocol.ExitedEventBody;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.IDebugClient;
import us.bringardner.parley.dap.protocol.InitializeRequestArguments;
import us.bringardner.parley.dap.protocol.LoadedSourcesArguments;
import us.bringardner.parley.dap.protocol.NextArguments;
import us.bringardner.parley.dap.protocol.OutputEventBody;
import us.bringardner.parley.dap.protocol.PauseArguments;
import us.bringardner.parley.dap.protocol.Scope;
import us.bringardner.parley.dap.protocol.ScopesArguments;
import us.bringardner.parley.dap.protocol.SetBreakpointsArguments;
import us.bringardner.parley.dap.protocol.SetBreakpointsResponseBody;
import us.bringardner.parley.dap.protocol.SetVariableArguments;
import us.bringardner.parley.dap.protocol.Source;
import us.bringardner.parley.dap.protocol.SourceArguments;
import us.bringardner.parley.dap.protocol.SourceBreakpoint;
import us.bringardner.parley.dap.protocol.StackFrame;
import us.bringardner.parley.dap.protocol.StackTraceArguments;
import us.bringardner.parley.dap.protocol.StepInArguments;
import us.bringardner.parley.dap.protocol.StepOutArguments;
import us.bringardner.parley.dap.protocol.StoppedEventBody;
import us.bringardner.parley.dap.protocol.TerminateArguments;
import us.bringardner.parley.dap.protocol.TerminatedEventBody;
import us.bringardner.parley.dap.protocol.Variable;

/**
 * fshdb --attach: a script running in another fshdb (started with --listen), debugged over the
 * Debug Adapter Protocol. The command line (Debugger) uses it as it uses a DebugEngine.
 * <ul>
 * <li>The script's text comes from that fshdb (loadedSources, then source), so list and break
 *     work on what runs there, even on another machine.</li>
 * <li>A script that's running is attached to at once; breakpoints go to it as they're set.
 *     One waiting for a debugger (--suspend) starts with run (or start, which stops before its
 *     first statement) once breakpoints are set.</li>
 * <li>The script's arguments, redirects, trace and code (edit, reload, file) belong to the
 *     fshdb it runs in; those commands say so.</li>
 * </ul>
 * Threads: requests are made on the caller's thread and waited for. What the other fshdb
 * reports (stopped, output, exited) is handled in order on a thread of this target's own, which
 * calls the Listener.
 */
public class RemoteTarget implements DebugTarget {

	/** How long to wait for an answer. */
	static final long TIMEOUT_MS = 15000;
	private static final List<DebugEngine.Frame> NO_FRAMES = Collections.emptyList();
	private static final String WHERE = "the fshdb you attached to";

	private final String address;
	private final Socket socket;
	private final DapConnection connection;
	private final IDebugAdapter adapter;
	private final ExecutorService events;

	private volatile ScriptFile script;
	private volatile Breakpoints breakpoints;
	private volatile Source source;
	private volatile DebugEngine.Listener listener;
	// breakpoint ids the other fshdb gave -> ours
	private final Map<Integer,Breakpoints.Entry> byRemoteId = new ConcurrentHashMap<>();

	// started: running, or waiting for run (--suspend)
	private volatile boolean configured;
	private volatile boolean running;
	private volatile boolean ended;
	private volatile boolean detached;
	private volatile boolean killed;
	private volatile int lastExitCode;
	private volatile boolean paused;
	private volatile int pausedLine = -1;
	private volatile List<DebugEngine.Frame> frames = NO_FRAMES;

	private RemoteTarget(String address, Socket socket) throws IOException {
		this.address = address;
		this.socket = socket;
		this.events = Executors.newSingleThreadExecutor(r -> {
			java.lang.Thread t = new java.lang.Thread(r, "fshdb attach events");
			t.setDaemon(true);
			return t;
		});
		this.connection = DapConnection.client(new Client(), socket.getInputStream(), socket.getOutputStream());
		this.adapter = connection.getAdapter();
		connection.closed().thenRun(()->events.execute(this::connectionClosed));
	}

	/**
	 * Connects to fshdb --listen and learns its script.
	 * @param spec [HOST:]PORT (the host defaults to the loopback address)
	 */
	public static RemoteTarget connect(String spec) throws IOException {
		InetSocketAddress a = DapHost.parseAddress(spec);
		Socket s = new Socket();
		try {
			s.connect(a, (int) TIMEOUT_MS);
			s.setTcpNoDelay(true);
		} catch (IOException e) {
			s.close();
			throw new IOException("Can't connect to "+spec+": "+Debugger.message(e), e);
		}
		RemoteTarget t = new RemoteTarget(a.getHostString()+":"+a.getPort(), s);
		try {
			t.handshake();
		} catch (IOException | RuntimeException e) {
			t.close();
			throw e instanceof IOException ? (IOException) e : new IOException(Debugger.message(e), e);
		}
		return t;
	}

	private void handshake() throws IOException {
		Capabilities caps = call(adapter.initialize(new InitializeRequestArguments().setClientID("fshdb")
				.setClientName("fshdb").setAdapterID("fsh").setLinesStartAt1(true).setColumnsStartAt1(true)
				.setPathFormat("path")));
		if( caps == null || !Boolean.TRUE.equals(caps.getSupportsLoadedSourcesRequest())) {
			throw new IOException(address+" isn't fshdb --listen (it doesn't say which script it runs).");
		}
		List<Source> sources = call(adapter.loadedSources(new LoadedSourcesArguments())).getSources();
		if( sources == null || sources.isEmpty()) {
			throw new IOException(address+" has no script.");
		}
		Source src = sources.get(0);
		String text = call(adapter.source(new SourceArguments().setSource(src)
				.setSourceReference(src.getSourceReference() == null ? 0 : src.getSourceReference()))).getContent();
		source = src;
		script = new ScriptFile(src.getPath() != null ? src.getPath() : src.getName(), text == null ? "" : text);
		breakpoints = new Breakpoints(script);
		breakpoints.setChangeListener(this::breakpointsChanged);
		boolean started = !call(adapter.threads()).getThreads().isEmpty();
		if( started ) {
			// it's running: debug it from now on
			call(adapter.attach(new AttachRequestArguments()));
			call(adapter.configurationDone(new ConfigurationDoneArguments()));
			configured = true;
			running = true;
		}
	}

	/** Where it's connected (HOST:PORT). */
	public String getAddress() {
		return address;
	}

	/** True if the script is waiting for run (fshdb --listen --suspend). */
	public boolean isWaitingToStart() {
		return !configured && !ended;
	}

	// ---- requests

	/** Waits for an answer; an error answer (or none) is an IllegalStateException with its text. */
	private static <T> T call(CompletableFuture<T> f) {
		try {
			return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			Throwable c = e.getCause() == null ? e : e.getCause();
			if( c instanceof DapException ) {
				throw new IllegalStateException(((DapException) c).getText(), c);
			}
			throw new IllegalStateException(Debugger.message(c), c);
		} catch (TimeoutException e) {
			throw new IllegalStateException("No answer from "+WHERE+".", e);
		} catch (InterruptedException e) {
			java.lang.Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted.", e);
		}
	}

	private static IllegalStateException notHere(String what) {
		return new IllegalStateException(what+" "+WHERE+" (fshdb --listen).");
	}

	// ---- what the other fshdb reports, handled in order on the events thread

	private final class Client implements IDebugClient {
		@Override
		public void stopped(StoppedEventBody body) {
			events.execute(()->stoppedAt(body));
		}

		@Override
		public void exited(ExitedEventBody body) {
			if( body.getExitCode() != null ) {
				lastExitCode = body.getExitCode();
			}
		}

		@Override
		public void terminated(TerminatedEventBody body) {
			events.execute(()->ended(null));
		}

		@Override
		public void output(OutputEventBody body) {
			String text = body.getOutput();
			if( text != null && !OutputEventBody.Category.TELEMETRY.equals(body.getCategory())) {
				events.execute(()->{
					DebugEngine.Listener l = listener;
					if( l != null ) {
						l.message(text);
					}
				});
			}
		}
	}

	private void stoppedAt(StoppedEventBody body) {
		List<DebugEngine.Frame> list;
		try {
			list = fetchFrames();
		} catch (RuntimeException e) {
			list = NO_FRAMES;
		}
		DebugEngine.Frame top = list.isEmpty() ? null : list.get(0);
		int line = top != null && top.inScript ? top.line : -1;
		Breakpoints.Entry bp = null;
		List<Integer> ids = body.getHitBreakpointIds();
		if( ids != null && !ids.isEmpty()) {
			bp = byRemoteId.get(ids.get(0));
		}
		DebugEngine.StopReason reason;
		String r = body.getReason() == null ? "" : body.getReason();
		switch (r) {
		case StoppedEventBody.Reason.BREAKPOINT: reason = DebugEngine.StopReason.BREAKPOINT; break;
		case StoppedEventBody.Reason.ENTRY: reason = DebugEngine.StopReason.ENTRY; break;
		case StoppedEventBody.Reason.PAUSE: reason = DebugEngine.StopReason.PAUSE; break;
		default: reason = DebugEngine.StopReason.STEP;
		}
		frames = list;
		pausedLine = line;
		paused = true;
		DebugEngine.Listener l = listener;
		if( l != null ) {
			l.stopped(reason, line, reason == DebugEngine.StopReason.BREAKPOINT ? bp : null);
		}
	}

	private List<DebugEngine.Frame> fetchFrames() {
		List<StackFrame> sf = call(adapter.stackTrace(new StackTraceArguments().setThreadId(FshdbAdapter.THREAD_ID))).getStackFrames();
		List<DebugEngine.Frame> ret = new ArrayList<>();
		if( sf == null ) {
			return ret;
		}
		String me = script.getPath();
		for(int i=0; i < sf.size(); i++) {
			StackFrame f = sf.get(i);
			Source s = f.getSource();
			String file = s == null ? "" : s.getPath() != null ? s.getPath() : s.getName() == null ? "" : s.getName();
			boolean inScript = me.equals(file);
			String calls = null;
			if( i > 0 ) {
				DebugEngine.Frame inner = ret.get(i-1);
				calls = "source".equals(inner.name) ? "source "+inner.file : inner.name;
			}
			int line = f.getLine() == null || f.getLine() <= 0 ? -1 : f.getLine();
			ret.add(new DebugEngine.Frame(f.getName(), file, line, inScript, calls));
		}
		return ret;
	}

	private void ended(Exception error) {
		synchronized (this) {
			if( ended ) {
				return;
			}
			ended = true;
			running = false;
			clearPaused();
			notifyAll();
		}
		DebugEngine.Listener l = listener;
		if( l != null ) {
			l.ended(lastExitCode, killed, error);
		}
	}

	private void connectionClosed() {
		if( !ended && !detached ) {
			ended(new IOException("The connection to "+address+" closed."));
		}
	}

	// ---- breakpoints

	private void breakpointsChanged() {
		if( configured && !ended && !detached ) {
			sendBreakpoints();
		}
	}

	/** Sends the enabled breakpoints (all of them: setBreakpoints replaces the script's). */
	private synchronized void sendBreakpoints() {
		List<Breakpoints.Entry> list = new ArrayList<>();
		List<SourceBreakpoint> sbs = new ArrayList<>();
		for(Breakpoints.Entry e : breakpoints.list()) {
			if( !e.breakpoint.isEnabled(false)) {
				continue;
			}
			SourceBreakpoint sb = new SourceBreakpoint().setLine(e.fileLine);
			if( e.breakpoint.isConditional()) {
				sb.setCondition(e.breakpoint.getCondition());
			}
			if( e.breakpoint.isHitCount() && e.breakpoint.getHitCount() > 0 ) {
				sb.setHitCondition(String.valueOf(e.breakpoint.getHitCount()));
			}
			list.add(e);
			sbs.add(sb);
		}
		SetBreakpointsResponseBody r = call(adapter.setBreakpoints(new SetBreakpointsArguments()
				.setSource(source).setBreakpoints(sbs)));
		byRemoteId.clear();
		List<us.bringardner.parley.dap.protocol.Breakpoint> answers = r.getBreakpoints() == null ? List.of() : r.getBreakpoints();
		for(int i=0; i < answers.size() && i < list.size(); i++) {
			us.bringardner.parley.dap.protocol.Breakpoint b = answers.get(i);
			if( b.getId() != null ) {
				byRemoteId.put(b.getId(), list.get(i));
			}
			if( !Boolean.TRUE.equals(b.getVerified())) {
				DebugEngine.Listener l = listener;
				if( l != null ) {
					l.message("breakpoint "+list.get(i).number+" wasn't set"+(b.getMessage() == null ? "" : ": "+b.getMessage()));
				}
			}
		}
	}

	// ---- DebugTarget: settings

	@Override
	public void setListener(DebugEngine.Listener listener) {
		this.listener = listener;
	}

	@Override
	public ScriptFile getScript() {
		return script;
	}

	@Override
	public Breakpoints getBreakpoints() {
		return breakpoints;
	}

	@Override
	public boolean isAttached() {
		return true;
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public void setArguments(String arguments) {
		throw notHere("The script's arguments are given to");
	}

	@Override
	public String getStdIn() {
		return "";
	}

	@Override
	public String getStdOut() {
		return "";
	}

	@Override
	public String getStdErr() {
		return "";
	}

	@Override
	public void setRedirects(String in, String out, String err) {
		throw notHere("The script's input and output are set in");
	}

	@Override
	public boolean isTrace() {
		return false;
	}

	@Override
	public void setTrace(boolean trace) {
		throw notHere("trace isn't available when attached; the statements run in");
	}

	@Override
	public void setScript(ScriptFile s) {
		throw notHere("The script is chosen by");
	}

	@Override
	public void reload() {
		throw notHere("The script runs (and is edited) in");
	}

	// ---- state

	@Override
	public boolean isRunning() {
		return running && !ended && !detached;
	}

	@Override
	public boolean isDebugging() {
		return true;
	}

	@Override
	public boolean isPaused() {
		return paused;
	}

	@Override
	public int getPausedLine() {
		return paused ? pausedLine : -1;
	}

	@Override
	public int getLastExitCode() {
		return lastExitCode;
	}

	// ---- running

	/** run or start: a script waiting for a debugger (--suspend) starts. */
	@Override
	public void start(boolean debug, boolean stopAtFirst) {
		if( ended || detached ) {
			throw new IllegalStateException("The script has ended.");
		}
		if( configured ) {
			throw new IllegalStateException("The script is already running.");
		}
		if( !debug ) {
			throw new IllegalStateException("exec isn't available when attached; use run.");
		}
		Map<String,Object> config = new LinkedHashMap<>();
		if( stopAtFirst ) {
			config.put("stopOnEntry", Boolean.TRUE);
		}
		call(adapter.attach(new AttachRequestArguments().setExtra(config)));
		configured = true;
		sendBreakpoints();
		running = true;
		call(adapter.configurationDone(new ConfigurationDoneArguments()));
	}

	private void requirePaused() {
		if( !isRunning()) {
			throw new IllegalStateException("The script isn't running.");
		}
		if( !paused ) {
			throw new IllegalStateException("The script isn't stopped.");
		}
	}

	private void clearPaused() {
		paused = false;
		pausedLine = -1;
		frames = NO_FRAMES;
	}

	@Override
	public void resume() {
		requirePaused();
		clearPaused();
		call(adapter.continue_(new ContinueArguments().setThreadId(FshdbAdapter.THREAD_ID)));
	}

	@Override
	public void step() {
		requirePaused();
		clearPaused();
		call(adapter.stepIn(new StepInArguments().setThreadId(FshdbAdapter.THREAD_ID)));
	}

	@Override
	public void next() {
		requirePaused();
		clearPaused();
		call(adapter.next(new NextArguments().setThreadId(FshdbAdapter.THREAD_ID)));
	}

	@Override
	public boolean finish() {
		requirePaused();
		if( frames.size() <= 1 ) {
			return false;
		}
		clearPaused();
		call(adapter.stepOut(new StepOutArguments().setThreadId(FshdbAdapter.THREAD_ID)));
		return true;
	}

	@Override
	public boolean pause() {
		if( !isRunning() || paused ) {
			return false;
		}
		call(adapter.pause(new PauseArguments().setThreadId(FshdbAdapter.THREAD_ID)));
		return true;
	}

	@Override
	public void kill() {
		if( ended || detached ) {
			return;
		}
		if( !configured ) {
			// it hasn't started: there's nothing to stop yet
			throw new IllegalStateException("The script hasn't started.");
		}
		killed = true;
		clearPaused();
		try {
			call(adapter.terminate(new TerminateArguments()));
		} catch (IllegalStateException e) {
			// the connection is going: the script ends with it, or has
		}
	}

	@Override
	public boolean killAndWait(long millis) {
		kill();
		long until = System.currentTimeMillis()+millis;
		synchronized (this) {
			while( !ended ) {
				long left = until-System.currentTimeMillis();
				if( left <= 0 ) {
					return false;
				}
				try {
					wait(left);
				} catch (InterruptedException e) {
					java.lang.Thread.currentThread().interrupt();
					return ended;
				}
			}
		}
		return true;
	}

	/** Leaves: the script goes on without breakpoints, and the connection closes. */
	@Override
	public void detach() {
		if( detached ) {
			return;
		}
		detached = true;
		clearPaused();
		if( !ended && !connection.isClosed()) {
			try {
				call(adapter.disconnect(new DisconnectArguments().setTerminateDebuggee(false)));
			} catch (IllegalStateException e) {
				// closing anyway
			}
		}
		close();
	}

	/** Closes the connection (detach() first to leave the script running cleanly). */
	public void close() {
		detached = true;
		connection.close();
		try {
			socket.close();
		} catch (IOException e) {
			// closed
		}
		events.shutdown();
	}

	// ---- the stopped script

	private Map<String,Scope> scopes() {
		Map<String,Scope> ret = new LinkedHashMap<>();
		List<Scope> list = call(adapter.scopes(new ScopesArguments().setFrameId(1))).getScopes();
		if( list != null ) {
			for(Scope s : list) {
				String key = Boolean.TRUE.equals(s.getExpensive()) ? "all"
						: s.getPresentationHint() == null ? s.getName() : s.getPresentationHint();
				ret.putIfAbsent(key, s);
			}
		}
		return ret;
	}

	private List<Variable> variablesOf(Scope s) {
		if( s == null || s.getVariablesReference() == null || s.getVariablesReference() == 0 ) {
			return List.of();
		}
		List<Variable> list = call(adapter.variables(new us.bringardner.parley.dap.protocol.VariablesArguments()
				.setVariablesReference(s.getVariablesReference()))).getVariables();
		return list == null ? List.of() : list;
	}

	@Override
	public Map<String,String> variableList(boolean all) {
		if( !paused ) {
			return null;
		}
		Map<String,Scope> scopes = scopes();
		Map<String,String> ret = new LinkedHashMap<>();
		// $0, $1 ... first, as the command line shows them for a script run here
		for(Variable v : variablesOf(scopes.get(Scope.PresentationHint.ARGUMENTS))) {
			if( !"$#".equals(v.getName())) {
				ret.put(v.getName(), v.getValue());
			}
		}
		for(Variable v : variablesOf(scopes.get(all ? "all" : Scope.PresentationHint.LOCALS))) {
			ret.put(v.getName(), v.getValue());
		}
		return ret;
	}

	@Override
	public boolean hasVariables() {
		return paused;
	}

	@Override
	public String formattedVariable(String name) {
		if( !paused ) {
			return null;
		}
		try {
			return call(adapter.evaluate(new EvaluateArguments().setExpression(name).setFrameId(1)
					.setContext(EvaluateArguments.Context.REPL))).getResult();
		} catch (IllegalStateException e) {
			if( e.getMessage() != null && e.getMessage().endsWith(" is not set")) {
				return null;
			}
			throw e;
		}
	}

	@Override
	public String setVariable(String name, String value) {
		requirePaused();
		String n = DebugTarget.variableName(name);
		Scope locals = scopes().get(Scope.PresentationHint.LOCALS);
		int ref = locals == null || locals.getVariablesReference() == null ? 0 : locals.getVariablesReference();
		// quoted, so the value is taken as it is (the other side removes one pair of quotes)
		call(adapter.setVariable(new SetVariableArguments().setVariablesReference(ref).setName(n).setValue("\""+value+"\"")));
		return n;
	}

	@Override
	public List<DebugEngine.Frame> frames() {
		return frames;
	}
}
