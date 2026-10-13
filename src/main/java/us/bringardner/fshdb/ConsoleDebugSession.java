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

import java.awt.Point;
import java.util.function.IntFunction;

import us.bringardner.fsh.ShellContext;
import us.bringardner.fsh.ide.core.Breakpoint;
import us.bringardner.fsh.ide.core.DebugSession;
import us.bringardner.fsh.syntax.Ast;

/**
 * fsh-ide's DebugSession with the stepping a command-line debugger needs:
 * <ul>
 * <li>next: stop at the next statement that isn't inside a function called (or a file
 *     sourced) from here; fsh's own step over stops at the very next statement, like step.</li>
 * <li>finish: stop at the next statement after the current function (or sourced file)
 *     returns.</li>
 * <li>step: stop at the next statement, going into functions, but not at a function's { }
 *     body (fsh's step stops there too, before the body's first command).</li>
 * <li>stop at the script's first statement (the start command).</li>
 * </ul>
 * The depth used is the number of running functions and sourced files
 * (ShellContext.callFrames), which subshells (pipes, $( )) start with a copy of.
 */
public class ConsoleDebugSession extends DebugSession {

	private enum Mode {NONE, STEP, NEXT, FINISH}

	private volatile Mode mode = Mode.NONE;
	private volatile int depth;
	private volatile boolean stopAtFirst;
	// a breakpoint condition being evaluated calls isBreakpoint for its own commands
	private final ThreadLocal<Boolean> checking = ThreadLocal.withInitial(()->Boolean.FALSE);
	// the command about to run is a { } group (a function's body): stepping goes to its first command
	private final ThreadLocal<Boolean> group = ThreadLocal.withInitial(()->Boolean.FALSE);

	public ConsoleDebugSession(IntFunction<Breakpoint> breakpoints, Listener listener) {
		super(breakpoints, listener);
	}

	/** The next run stops before its first statement. */
	public void setStopAtFirst(boolean stop) {
		stopAtFirst = stop;
	}

	/** Resumes a paused script until a statement at depth (ShellContext.callFrames.size()) or less. */
	public void next(int depth) {
		this.depth = depth;
		mode = Mode.NEXT;
		resume();
	}

	/** Resumes a paused script until a statement at a depth less than depth. */
	public void finish(int depth) {
		this.depth = depth;
		mode = Mode.FINISH;
		resume();
	}

	@Override
	public void suspend() {
		mode = Mode.NONE;
		super.suspend();
	}

	/**
	 * Resumes a paused script until its next statement, going into functions. (fsh's own step,
	 * which DebugSession.stepInto uses, also stops at a function's { } body before its first
	 * command.)
	 */
	@Override
	public void stepInto() {
		mode = Mode.STEP;
		resume();
	}

	@Override
	public void stepOver() {
		mode = Mode.NONE;
		super.stepOver();
	}

	/** Stops any next or finish in progress (a new run, or the user pressed Ctrl-C). */
	public void clearStepping() {
		mode = Mode.NONE;
	}

	@Override
	public synchronized void before(Ast.Node node, String source, ShellContext ctx) {
		group.set(node instanceof Ast.BraceGroup);
		super.before(node, source, ctx);
	}

	@Override
	public synchronized boolean isBreakpoint(Point linePt, ShellContext ctx) {
		if( checking.get()) {
			// a breakpoint's condition is running: its commands aren't the script's
			return false;
		}
		boolean ret;
		checking.set(Boolean.TRUE);
		try {
			ret = super.isBreakpoint(linePt, ctx);
		} finally {
			checking.set(Boolean.FALSE);
		}
		if( !ret && isActive() && editorLine(linePt.x) >= 0 && !group.get()) {
			if( stopAtFirst ) {
				ret = true;
			} else {
				Mode m = mode;
				int d = ctx.callFrames.size();
				ret = m == Mode.STEP || (m == Mode.NEXT && d <= depth) || (m == Mode.FINISH && d < depth);
			}
		}
		if( ret ) {
			stopAtFirst = false;
			mode = Mode.NONE;
		}
		return ret;
	}
}
