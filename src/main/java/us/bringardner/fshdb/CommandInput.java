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

/** Where the debugger's commands come from: the terminal (JLine), a file, or a test. */
public interface CommandInput {

	/**
	 * Reads a command.
	 * @return the line typed, "" if it was abandoned (Ctrl-C), or null at the end of input (Ctrl-D)
	 */
	String readLine(String prompt) throws IOException;

	/** Gives the terminal to another program (an editor) until resume(). */
	default void pause() {
	}

	default void resume() {
	}

	/**
	 * Shows text above the line being typed (a remote script stopped while the prompt was up).
	 * @return false if it can't (the debugger then reports it after the next command)
	 */
	default boolean printAbove(String text) {
		return false;
	}

	/** Ends a readLine that's waiting (it returns ""), so the debugger can leave. */
	default void wake() {
	}
}
