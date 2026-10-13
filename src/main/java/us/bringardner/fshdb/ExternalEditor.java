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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Edits a file with the user's editor: $VISUAL, else $EDITOR, else the first of nano, vim, vi
 * found on the PATH. The editor gets the terminal until it exits.
 */
public class ExternalEditor {

	/** Editors that open a file at a line given as +LINE before the file name. */
	private static final Set<String> PLUS_LINE = Set.of("vi", "vim", "nvim", "view", "nano", "pico", "emacs",
			"emacsclient", "micro", "ne", "joe", "jed", "mg", "kak", "ed");

	private final Map<String,String> env;

	public ExternalEditor() {
		this(System.getenv());
	}

	ExternalEditor(Map<String,String> env) {
		this.env = env;
	}

	/** The editor command, split into words, or null if none is set or found. */
	public List<String> command() {
		for(String var : new String[] {"VISUAL", "EDITOR"}) {
			String v = env.get(var);
			if( v != null && !v.isBlank()) {
				return new ArrayList<>(Arrays.asList(v.trim().split("\\s+")));
			}
		}
		for(String e : new String[] {"nano", "vim", "vi"}) {
			if( onPath(e)) {
				return new ArrayList<>(List.of(e));
			}
		}
		return null;
	}

	private boolean onPath(String program) {
		String path = env.get("PATH");
		if( path != null ) {
			for(String dir : path.split(File.pathSeparator)) {
				if( !dir.isEmpty() && new File(dir, program).canExecute()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The command line for editing file at line (1-based; 0 for no line).
	 * @throws IOException if there's no editor
	 */
	public List<String> commandLine(File file, int line) throws IOException {
		List<String> cmd = command();
		if( cmd == null || cmd.isEmpty()) {
			throw new IOException("No editor: set EDITOR (for example export EDITOR=nano)");
		}
		String name = new File(cmd.get(0)).getName();
		if( line > 0 && PLUS_LINE.contains(name)) {
			cmd.add("+"+line);
		}
		cmd.add(file.getAbsolutePath());
		return cmd;
	}

	/**
	 * Runs the editor on file and waits for it to exit.
	 * @return the editor's exit code
	 */
	public int edit(File file, int line) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(commandLine(file, line));
		pb.inheritIO();
		Process p = pb.start();
		while( true ) {
			try {
				return p.waitFor();
			} catch (InterruptedException e) {
				// keep waiting: the editor has the terminal (and gets the Ctrl-C itself)
			}
		}
	}
}
