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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import us.bringardner.fsh.ide.core.DebugVariables;
import us.bringardner.parley.dap.DapEndpoint;
import us.bringardner.parley.dap.DapException;
import us.bringardner.parley.dap.protocol.AttachRequestArguments;
import us.bringardner.parley.dap.protocol.Breakpoint;
import us.bringardner.parley.dap.protocol.Capabilities;
import us.bringardner.parley.dap.protocol.ConfigurationDoneArguments;
import us.bringardner.parley.dap.protocol.ContinueArguments;
import us.bringardner.parley.dap.protocol.ContinueResponseBody;
import us.bringardner.parley.dap.protocol.DisconnectArguments;
import us.bringardner.parley.dap.protocol.EvaluateArguments;
import us.bringardner.parley.dap.protocol.EvaluateResponseBody;
import us.bringardner.parley.dap.protocol.ExitedEventBody;
import us.bringardner.parley.dap.protocol.IDebugAdapter;
import us.bringardner.parley.dap.protocol.IDebugClient;
import us.bringardner.parley.dap.protocol.InitializeRequestArguments;
import us.bringardner.parley.dap.protocol.LaunchRequestArguments;
import us.bringardner.parley.dap.protocol.NextArguments;
import us.bringardner.parley.dap.protocol.OutputEventBody;
import us.bringardner.parley.dap.protocol.PauseArguments;
import us.bringardner.parley.dap.protocol.Scope;
import us.bringardner.parley.dap.protocol.ScopesArguments;
import us.bringardner.parley.dap.protocol.ScopesResponseBody;
import us.bringardner.parley.dap.protocol.SetBreakpointsArguments;
import us.bringardner.parley.dap.protocol.SetBreakpointsResponseBody;
import us.bringardner.parley.dap.protocol.SetExceptionBreakpointsArguments;
import us.bringardner.parley.dap.protocol.SetExceptionBreakpointsResponseBody;
import us.bringardner.parley.dap.protocol.SetVariableArguments;
import us.bringardner.parley.dap.protocol.SetVariableResponseBody;
import us.bringardner.parley.dap.protocol.Source;
import us.bringardner.parley.dap.protocol.SourceArguments;
import us.bringardner.parley.dap.protocol.SourceBreakpoint;
import us.bringardner.parley.dap.protocol.SourceResponseBody;
import us.bringardner.parley.dap.protocol.StackFrame;
import us.bringardner.parley.dap.protocol.StackTraceArguments;
import us.bringardner.parley.dap.protocol.StackTraceResponseBody;
import us.bringardner.parley.dap.protocol.StepInArguments;
import us.bringardner.parley.dap.protocol.StepOutArguments;
import us.bringardner.parley.dap.protocol.StoppedEventBody;
import us.bringardner.parley.dap.protocol.TerminateArguments;
import us.bringardner.parley.dap.protocol.TerminatedEventBody;
import us.bringardner.parley.dap.protocol.Thread;
import us.bringardner.parley.dap.protocol.ThreadsResponseBody;
import us.bringardner.parley.dap.protocol.Variable;
import us.bringardner.parley.dap.protocol.VariablesArguments;
import us.bringardner.parley.dap.protocol.VariablesResponseBody;

/**
 * fshdb as a DAP debug adapter (parley-dap): DAP requests become DebugEngine calls, and what
 * the engine reports becomes DAP events. One instance serves one debugger's connection.
 * <p>
 * The script is the one given on fshdb's command line (launch and attach don't choose one). It
 * has one thread, "main" (id 1). Its variables are those of the statement it's stopped at:
 * fsh doesn't keep the variables of the functions further up the stack apart, so every frame
 * shows the same scopes.
 */
public class FshdbAdapter implements IDebugAdapter {

	/** The script's only thread. */
	public static final int THREAD_ID = 1;

	/** What the adapter needs from its host (DapHost). */
	public interface Host {
		/** configurationDone: a script waiting for a debugger (--suspend) may start. */
		void configured();

		/** The debugger disconnected: close the connection. */
		void disconnected(FshdbAdapter adapter);
	}

	private final DebugEngine engine;
	private final Host host;
	private final IDebugClient client;
	private volatile boolean connected = true;
	private volatile boolean linesStartAt1 = true;

	// variable references, valid while the script is stopped (cleared when it goes on)
	private final Map<Integer,Supplier<List<Variable>>> references = new ConcurrentHashMap<>();
	private final AtomicInteger nextReference = new AtomicInteger(1);

	public FshdbAdapter(DebugEngine engine, Host host, IDebugClient client) {
		this.engine = engine;
		this.host = host;
		this.client = client;
	}

	// ---- what the engine reports (DapHost forwards it)

	void stopped(DebugEngine.StopReason reason, int fileLine, Breakpoints.Entry breakpoint) {
		references.clear();
		if( !connected ) {
			return;
		}
		StoppedEventBody e = new StoppedEventBody().setThreadId(THREAD_ID).setAllThreadsStopped(true);
		switch (reason) {
		case BREAKPOINT: e.setReason(StoppedEventBody.Reason.BREAKPOINT); break;
		case STEP: e.setReason(StoppedEventBody.Reason.STEP); break;
		case ENTRY: e.setReason(StoppedEventBody.Reason.ENTRY); break;
		default: e.setReason(StoppedEventBody.Reason.PAUSE); break;
		}
		if( breakpoint != null ) {
			e.setHitBreakpointIds(List.of(breakpoint.number));
		}
		if( fileLine < 0 ) {
			e.setDescription("Stopped in a sourced file");
		}
		client.stopped(e);
	}

	void ended(int exitCode) {
		references.clear();
		if( !connected ) {
			return;
		}
		client.exited(new ExitedEventBody().setExitCode(exitCode));
		client.terminated(new TerminatedEventBody());
	}

	void output(String text) {
		if( connected ) {
			client.output(new OutputEventBody().setCategory(OutputEventBody.Category.CONSOLE)
					.setOutput(text.endsWith("\n") ? text : text+"\n"));
		}
	}

	// ---- lines: DAP clients may count from 0

	private int toClient(int fileLine) {
		return linesStartAt1 ? fileLine : fileLine-1;
	}

	private int fromClient(int line) {
		return linesStartAt1 ? line : line+1;
	}

	// ---- errors

	private static <T> CompletableFuture<T> error(String message) {
		CompletableFuture<T> f = new CompletableFuture<>();
		f.completeExceptionally(DapException.forUser(message));
		return f;
	}

	/** Runs an engine call, turning its IllegalStateException ("isn't stopped" ...) into an error response. */
	private CompletableFuture<Void> engineCall(Runnable r) {
		try {
			r.run();
			references.clear();
			return CompletableFuture.completedFuture(null);
		} catch (IllegalStateException | IllegalArgumentException e) {
			return error(Debugger.message(e));
		}
	}

	// ---- the session

	@Override
	public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
		if( args != null && args.getLinesStartAt1() != null ) {
			linesStartAt1 = args.getLinesStartAt1();
		}
		// the initialized event follows the response
		DapEndpoint.afterResponse(client::initialized);
		return CompletableFuture.completedFuture(new Capabilities()
				.setSupportsConfigurationDoneRequest(true)
				.setSupportsConditionalBreakpoints(true)
				.setSupportsHitConditionalBreakpoints(true)
				.setSupportsSetVariable(true)
				.setSupportsEvaluateForHovers(true)
				.setSupportsTerminateRequest(true)
				.setSupportTerminateDebuggee(true));
	}

	@Override
	public CompletableFuture<Void> launch(LaunchRequestArguments args) {
		return start(args == null ? null : args.getExtra());
	}

	@Override
	public CompletableFuture<Void> attach(AttachRequestArguments args) {
		return start(args == null ? null : args.getExtra());
	}

	/** launch and attach: the script is fshdb's; it's running, or waits for configurationDone. */
	private CompletableFuture<Void> start(Map<String,Object> config) {
		Object program = config == null ? null : config.get("program");
		if( program instanceof String && !sameFile((String) program)) {
			output("fshdb is debugging "+engine.getScript().scriptName()+", not "+program);
		}
		engine.attach();
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
		host.configured();
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> disconnect(DisconnectArguments args) {
		boolean terminate = args != null && Boolean.TRUE.equals(args.getTerminateDebuggee());
		if( terminate ) {
			engine.kill();
		} else {
			// the script goes on without a debugger
			engine.getBreakpoints().clear();
			engine.detach();
		}
		references.clear();
		connected = false;
		// close once the response has gone
		DapEndpoint.afterResponse(()->host.disconnected(this));
		return CompletableFuture.completedFuture(null);
	}

	/** The connection closed without a disconnect request. */
	void connectionLost() {
		connected = false;
	}

	@Override
	public CompletableFuture<Void> terminate(TerminateArguments args) {
		engine.kill();
		return CompletableFuture.completedFuture(null);
	}

	// ---- breakpoints

	private boolean sameFile(String path) {
		if( path == null ) {
			return false;
		}
		ScriptFile s = engine.getScript();
		if( path.equals(s.scriptName()) || path.equals(s.getPath())) {
			return true;
		}
		File local = s.localFile();
		if( local != null ) {
			try {
				if( new File(path).getCanonicalPath().equals(local.getCanonicalPath())) {
					return true;
				}
			} catch (java.io.IOException e) {
				// compare the names
			}
		}
		// the debugger's copy may be elsewhere (another machine): the same file name will do
		return new File(path).getName().equals(new File(s.scriptName()).getName());
	}

	private Source source() {
		ScriptFile s = engine.getScript();
		return new Source().setName(new File(s.scriptName()).getName()).setPath(s.scriptName());
	}

	@Override
	public CompletableFuture<SetBreakpointsResponseBody> setBreakpoints(SetBreakpointsArguments args) {
		List<SourceBreakpoint> wanted = args.getBreakpoints() == null ? List.of() : args.getBreakpoints();
		List<Breakpoint> result = new ArrayList<>();
		String path = args.getSource() == null ? null : args.getSource().getPath();
		if( !sameFile(path)) {
			for(SourceBreakpoint sb : wanted) {
				result.add(new Breakpoint().setVerified(false).setLine(sb.getLine())
						.setMessage("fshdb is debugging "+engine.getScript().scriptName()));
			}
			return CompletableFuture.completedFuture(new SetBreakpointsResponseBody().setBreakpoints(result));
		}
		Breakpoints bps = engine.getBreakpoints();
		ScriptFile s = engine.getScript();
		bps.clear();
		for(SourceBreakpoint sb : wanted) {
			Breakpoint b = new Breakpoint().setSource(source());
			int line = fromClient(sb.getLine());
			int at = line;
			while( at >= 1 && at <= s.lineCount() && !s.mayHaveCode(at)) {
				at++;
			}
			Integer hits = null;
			String message = null;
			if( sb.getHitCondition() != null && !sb.getHitCondition().isBlank()) {
				try {
					hits = Integer.parseInt(sb.getHitCondition().trim().replaceFirst("^(==|=)", "").trim());
				} catch (NumberFormatException e) {
					message = "The hit count must be a number";
				}
			}
			if( at < 1 || at > s.lineCount()) {
				b.setVerified(false).setLine(sb.getLine()).setMessage("No code on or after line "+line);
			} else if( message != null ) {
				b.setVerified(false).setLine(sb.getLine()).setMessage(message);
			} else {
				Breakpoints.Entry e = bps.add(at, sb.getCondition(), hits == null ? 0 : hits);
				b.setId(e.number).setVerified(true).setLine(toClient(at));
			}
			result.add(b);
		}
		return CompletableFuture.completedFuture(new SetBreakpointsResponseBody().setBreakpoints(result));
	}

	@Override
	public CompletableFuture<SetExceptionBreakpointsResponseBody> setExceptionBreakpoints(SetExceptionBreakpointsArguments args) {
		// none (clients send this during configuration anyway)
		return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponseBody().setBreakpoints(List.of()));
	}

	// ---- running

	@Override
	public CompletableFuture<ContinueResponseBody> continue_(ContinueArguments args) {
		return engineCall(engine::resume).thenApply(v -> new ContinueResponseBody().setAllThreadsContinued(true));
	}

	@Override
	public CompletableFuture<Void> next(NextArguments args) {
		return engineCall(engine::next);
	}

	@Override
	public CompletableFuture<Void> stepIn(StepInArguments args) {
		return engineCall(engine::step);
	}

	@Override
	public CompletableFuture<Void> stepOut(StepOutArguments args) {
		return engineCall(()->{
			if( !engine.finish()) {
				// in the main script: there's nothing to step out of, so it runs on
				engine.resume();
			}
		});
	}

	@Override
	public CompletableFuture<Void> pause(PauseArguments args) {
		if( !engine.isRunning()) {
			return error("The script isn't running.");
		}
		engine.attach();
		engine.pause();
		return CompletableFuture.completedFuture(null);
	}

	// ---- the stopped script

	@Override
	public CompletableFuture<ThreadsResponseBody> threads() {
		Thread t = new Thread().setId(THREAD_ID).setName("main ("+new File(engine.getScript().scriptName()).getName()+")");
		return CompletableFuture.completedFuture(new ThreadsResponseBody().setThreads(engine.isRunning() ? List.of(t) : List.of()));
	}

	@Override
	public CompletableFuture<StackTraceResponseBody> stackTrace(StackTraceArguments args) {
		List<DebugEngine.Frame> frames = engine.frames();
		int start = args.getStartFrame() == null ? 0 : Math.max(0, args.getStartFrame());
		int levels = args.getLevels() == null || args.getLevels() <= 0 ? frames.size() : args.getLevels();
		List<StackFrame> list = new ArrayList<>();
		for(int i=start; i < frames.size() && list.size() < levels; i++) {
			DebugEngine.Frame f = frames.get(i);
			StackFrame sf = new StackFrame().setId(i+1).setName(f.name)
					.setLine(f.line > 0 ? toClient(f.line) : 0).setColumn(linesStartAt1 ? 1 : 0);
			if( f.inScript ) {
				sf.setSource(source());
			} else if( f.file != null && !f.file.isEmpty()) {
				sf.setSource(new Source().setName(new File(f.file).getName()).setPath(f.file));
			}
			list.add(sf);
		}
		return CompletableFuture.completedFuture(new StackTraceResponseBody().setStackFrames(list).setTotalFrames(frames.size()));
	}

	private int reference(Supplier<List<Variable>> children) {
		int ref = nextReference.getAndIncrement();
		references.put(ref, children);
		return ref;
	}

	@Override
	public CompletableFuture<ScopesResponseBody> scopes(ScopesArguments args) {
		if( engine.variables() == null ) {
			return error("The script isn't stopped.");
		}
		Scope script = new Scope().setName("Variables").setPresentationHint(Scope.PresentationHint.LOCALS)
				.setVariablesReference(reference(()->variablesOf(engine.variables(), false)));
		Scope params = new Scope().setName("Arguments").setPresentationHint(Scope.PresentationHint.ARGUMENTS)
				.setVariablesReference(reference(()->positionalOf(engine.variables())));
		Scope all = new Scope().setName("All (environment and shell)").setExpensive(true)
				.setVariablesReference(reference(()->variablesOf(engine.variables(), true)));
		return CompletableFuture.completedFuture(new ScopesResponseBody().setScopes(List.of(script, params, all)));
	}

	private List<Variable> variablesOf(Map<String,Object> vars, boolean all) {
		List<Variable> ret = new ArrayList<>();
		if( vars != null ) {
			for(us.bringardner.fsh.ide.core.Variable v : DebugVariables.list(vars, all)) {
				if( !v.getName().startsWith("$")) {
					ret.add(variable(v.getName(), v.getValue()));
				}
			}
		}
		return ret;
	}

	private List<Variable> positionalOf(Map<String,Object> vars) {
		List<Variable> ret = new ArrayList<>();
		if( vars != null ) {
			if( vars.containsKey("$0")) {
				ret.add(variable("$0", vars.get("$0")));
			}
			List<Object> pos = DebugEngine.positional(vars);
			for(int i=0; i < pos.size(); i++) {
				ret.add(variable("$"+(i+1), pos.get(i)));
			}
			ret.add(variable("$#", pos.size()));
		}
		return ret;
	}

	private Variable variable(String name, Object value) {
		Variable v = new Variable().setName(name)
				.setValue(value == null ? "" : Debugger.format(value))
				.setType(DebugVariables.typeName(value))
				.setEvaluateName(name.startsWith("$") ? name : "$"+name)
				.setVariablesReference(0);
		if( value instanceof List && !((List<?>) value).isEmpty()) {
			List<?> list = (List<?>) value;
			v.setIndexedVariables(list.size());
			v.setVariablesReference(reference(()->{
				List<Variable> ret = new ArrayList<>();
				for(int i=0; i < list.size(); i++) {
					ret.add(new Variable().setName("["+i+"]").setValue(Debugger.format(String.valueOf(list.get(i)))).setVariablesReference(0));
				}
				return ret;
			}));
		} else if( value instanceof Map && !((Map<?,?>) value).isEmpty()) {
			Map<?,?> map = (Map<?,?>) value;
			v.setNamedVariables(map.size());
			v.setVariablesReference(reference(()->{
				List<Variable> ret = new ArrayList<>();
				for(Map.Entry<?,?> en : map.entrySet()) {
					ret.add(new Variable().setName("["+en.getKey()+"]").setValue(Debugger.format(String.valueOf(en.getValue()))).setVariablesReference(0));
				}
				return ret;
			}));
		}
		return v;
	}

	@Override
	public CompletableFuture<VariablesResponseBody> variables(VariablesArguments args) {
		Supplier<List<Variable>> s = references.get(args.getVariablesReference());
		return CompletableFuture.completedFuture(new VariablesResponseBody().setVariables(s == null ? List.of() : s.get()));
	}

	@Override
	public CompletableFuture<SetVariableResponseBody> setVariable(SetVariableArguments args) {
		try {
			String value = args.getValue();
			if( value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
				value = value.substring(1, value.length()-1);
			}
			engine.setVariable(args.getName(), value);
			return CompletableFuture.completedFuture(new SetVariableResponseBody().setValue(Debugger.format(value)).setType("String"));
		} catch (IllegalStateException | IllegalArgumentException e) {
			return error(Debugger.message(e));
		}
	}

	@Override
	public CompletableFuture<EvaluateResponseBody> evaluate(EvaluateArguments args) {
		// variables only: NAME, $NAME, ${NAME}, $1, $@, $# ...
		String expr = args.getExpression() == null ? "" : args.getExpression().trim();
		if( expr.isEmpty() || !engine.hasVariables()) {
			return error(engine.isRunning() ? "The script isn't stopped." : "The script hasn't run.");
		}
		if( !expr.matches("\\$?\\{?[A-Za-z_][A-Za-z0-9_]*\\}?|\\$?[0-9]+|\\$?[@*#]")) {
			return error("Only variables can be shown (NAME, $1, $@, $# ...)");
		}
		Object value = engine.variable(expr);
		if( value == null ) {
			return error(expr+" is not set");
		}
		Variable v = variable(expr, value);
		return CompletableFuture.completedFuture(new EvaluateResponseBody().setResult(v.getValue()).setType(v.getType())
				.setVariablesReference(v.getVariablesReference())
				.setIndexedVariables(v.getIndexedVariables()).setNamedVariables(v.getNamedVariables()));
	}

	@Override
	public CompletableFuture<SourceResponseBody> source(SourceArguments args) {
		ScriptFile s = engine.getScript();
		StringBuilder b = new StringBuilder();
		for(int i=1; i <= s.lineCount(); i++) {
			b.append(s.line(i)).append('\n');
		}
		return CompletableFuture.completedFuture(new SourceResponseBody().setContent(b.toString()).setMimeType("text/x-shellscript"));
	}
}
