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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import us.bringardner.fsh.ide.core.RunSettings;
import us.bringardner.fsh.ide.core.RunSettingsStore;
import us.bringardner.fsh.ide.core.ScriptDocument;
import us.bringardner.fsh.ide.core.ScriptText;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * The script being debugged, as it is in its file, and the arguments and redirects it's run
 * with.
 * <p>
 * Line numbers are the file's (1-based), as an editor shows them, and the code that runs is
 * the file as it is, so $LINENO and fsh's messages agree with them too. A script saved by an
 * earlier fsh-ide still has its settings in comment lines (#BjlIdeScriptArgs= and so on, see
 * ScriptDocument): they're read for the settings and run as blank lines. fsh-ide's core numbers
 * lines from 0 ("editor lines"); here that's the file line less one.
 * <p>
 * The settings come from those lines if the script has them, else from the user's run
 * settings, which fsh-ide keeps (RunSettingsStore). fshdb only reads them.
 */
public class ScriptFile {

	private final String path;
	private final FileSource file;
	private RunSettings settings = RunSettings.NONE;
	// where the settings are read from; null: the user's
	private final RunSettingsStore store;
	// the file's lines, without their line ends
	private List<String> lines = Collections.emptyList();
	// what runs: the text with any settings lines blanked
	private String code = "";

	/**
	 * @param path a file name, or a path or URL any parley-files file system understands
	 *        (sftp://host/dir/script.sh ...)
	 */
	public ScriptFile(String path) throws IOException {
		this(path, (RunSettingsStore) null);
	}

	/** @param store the run settings to read (null: the user's) */
	ScriptFile(String path, RunSettingsStore store) throws IOException {
		this.path = path;
		this.store = store;
		this.file = path.matches("[a-zA-Z][a-zA-Z0-9+.-]+://.*") ? FileSourceFactory.getFileSource(path)
				: FileSourceFactory.getDefaultFactory().createFileSource(path);
		if( file == null ) {
			throw new IOException("Can't open "+path);
		}
	}

	/** For tests: a script that isn't in a file. */
	ScriptFile(String name, String text) {
		this.path = name;
		this.file = null;
		this.store = null;
		setText(text);
	}

	/** Reads the file (again). */
	public void load() throws IOException {
		if( file == null ) {
			return;
		}
		if( !file.exists()) {
			throw new IOException(path+" does not exist");
		}
		setText(ScriptText.read(file));
	}

	final void setText(String text) {
		text = text.replace("\r\n", "\n");
		List<String> all = new ArrayList<>();
		for(String line : text.split("\n", -1)) {
			all.add(line);
		}
		if( !all.isEmpty() && all.get(all.size()-1).isEmpty()) {
			// the line end of the last line doesn't start another line
			all.remove(all.size()-1);
		}
		lines = all;
		// settings lines of an earlier fsh-ide run as blank lines, so every line keeps its number
		StringBuilder run = new StringBuilder(text.length());
		for(String line : all) {
			if( !ScriptDocument.isLegacySettingsLine(line)) {
				run.append(line);
			}
			run.append('\n');
		}
		code = ScriptText.clean(run.toString());
		settings = settingsFor(ScriptDocument.parse(text));
	}

	/** The script's settings lines if it has any, else what the user's store keeps for its file. */
	private RunSettings settingsFor(ScriptDocument doc) {
		if( doc.hasLegacySettings() || file == null ) {
			return doc.settings();
		}
		try {
			return (store != null ? store : RunSettingsStore.forUser()).get(file);
		} catch (IOException | RuntimeException e) {
			// run without them rather than not at all
			return RunSettings.NONE;
		}
	}

	/** The name given on the command line. */
	public String getPath() {
		return path;
	}

	/** The file, or null for a script that isn't in one. */
	public FileSource getFile() {
		return file;
	}

	/** The script as a local file (it can be given to $EDITOR as it is), or null if it isn't one. */
	public java.io.File localFile() {
		if( file != null && FileProxyFactory.FACTORY_ID.equals(file.getFileSourceFactory().getTypeId())) {
			return new java.io.File(file.getAbsolutePath());
		}
		return null;
	}

	/** $0 for the script: the file's absolute path. */
	public String scriptName() {
		return file == null ? path : file.getAbsolutePath();
	}

	/** The code to run: the file, line for line (any settings lines of an earlier fsh-ide are blank). */
	public String code() {
		return code;
	}

	/** The arguments and redirects to run it with (from the user's run settings, or its settings lines). */
	public RunSettings settings() {
		return settings;
	}

	public int lineCount() {
		return lines.size();
	}

	/** A line of the file (1-based), or null if there's no such line. */
	public String line(int fileLine) {
		return fileLine >= 1 && fileLine <= lines.size() ? lines.get(fileLine-1) : null;
	}

	/** The file line (1-based) of an editor line (0-based), or -1 if it isn't in this file. */
	public int fileLine(int editorLine) {
		return editorLine >= 0 && editorLine < lines.size() ? editorLine+1 : -1;
	}

	/** The editor line (0-based) of a file line (1-based), or -1 if there's no such line. */
	public int editorLine(int fileLine) {
		return fileLine >= 1 && fileLine <= lines.size() ? fileLine-1 : -1;
	}

	/** True if a statement could start on the line: not blank, not only a comment (settings lines are comments). */
	public boolean mayHaveCode(int fileLine) {
		String l = line(fileLine);
		if( l == null ) {
			return false;
		}
		String t = l.trim();
		return !t.isEmpty() && !t.startsWith("#");
	}
}
