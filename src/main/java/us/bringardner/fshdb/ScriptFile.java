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

import us.bringardner.fsh.ide.core.ScriptDocument;
import us.bringardner.fsh.ide.core.ScriptText;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * The script being debugged, as it is in its file.
 * <p>
 * Line numbers shown to the user are the file's (1-based), as an editor shows them. The code
 * that runs is the file without the fsh-ide settings lines (#BjlIdeScriptArgs= and so on, see
 * ScriptDocument); fsh-ide's core numbers that code's lines from 0 ("editor lines"). This class
 * maps between the two.
 */
public class ScriptFile {

	private final String path;
	private final FileSource file;
	private ScriptDocument document = new ScriptDocument("", "", "", "", "");
	// the file's lines, without their line ends
	private List<String> lines = Collections.emptyList();
	// editor line (index) -> file line (1-based)
	private int [] fileLineOf = new int[0];
	// file line (index, 1-based) -> editor line, or -1 for a settings line
	private int [] editorLineOf = new int[1];

	/**
	 * @param path a file name, or a path or URL any parley-files file system understands
	 *        (sftp://host/dir/script.sh ...)
	 */
	public ScriptFile(String path) throws IOException {
		this.path = path;
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
		document = ScriptDocument.parse(text);
		List<String> all = new ArrayList<>();
		for(String line : text.split("\n", -1)) {
			all.add(line);
		}
		if( !all.isEmpty() && all.get(all.size()-1).isEmpty()) {
			// the line end of the last line doesn't start another line
			all.remove(all.size()-1);
		}
		lines = all;
		editorLineOf = new int[all.size()+1];
		editorLineOf[0] = -1;
		List<Integer> body = new ArrayList<>();
		for(int i=0; i < all.size(); i++) {
			if( isSettingsLine(all.get(i))) {
				editorLineOf[i+1] = -1;
			} else {
				editorLineOf[i+1] = body.size();
				body.add(i+1);
			}
		}
		fileLineOf = new int[body.size()];
		for(int i=0; i < fileLineOf.length; i++) {
			fileLineOf[i] = body.get(i);
		}
	}

	private static boolean isSettingsLine(String line) {
		// as ScriptDocument.parse recognizes them
		return line.startsWith("#BjlIdeScriptArgs=") || line.startsWith("#BjlIdeScriptIn=")
				|| line.startsWith("#BjlIdeScriptOut=") || line.startsWith("#BjlIdeScriptErr=");
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

	/** The code to run: the file without its settings lines. */
	public String code() {
		return ScriptText.clean(document.body());
	}

	public ScriptDocument document() {
		return document;
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
		return editorLine >= 0 && editorLine < fileLineOf.length ? fileLineOf[editorLine] : -1;
	}

	/** The editor line (0-based) of a file line (1-based), or -1 for a settings line or no line. */
	public int editorLine(int fileLine) {
		return fileLine >= 1 && fileLine < editorLineOf.length ? editorLineOf[fileLine] : -1;
	}

	/** True if a statement could start on the line: not blank, not only a comment, not settings. */
	public boolean mayHaveCode(int fileLine) {
		String l = line(fileLine);
		if( l == null || editorLine(fileLine) < 0 ) {
			return false;
		}
		String t = l.trim();
		return !t.isEmpty() && !t.startsWith("#");
	}
}
