# fshdb

A debugger for shell scripts that runs in a terminal: no Swing, no JavaFX, so it works over
SSH and on servers without a display. It uses [fsh](https://github.com/parley-project/parley-fsh),
the FileSource Shell, as the interpreter and the UI-free core of
[fsh-ide](https://github.com/parley-project/parley-fsh-ide) (`us.bringardner.fsh.ide.core`) for
running and debugging. fsh aims to be close to bash/POSIX, so fshdb works for those scripts too.

Commands are like gdb's or bashdb's. Scripts are edited with your own editor (`$VISUAL` or
`$EDITOR`).

```
$ fshdb deploy.sh staging
fshdb 1.0.0: deploy.sh, 42 lines. Type help for the commands.
(fshdb) break 17 if [ $retries -gt 2 ]
Breakpoint 1 at line 17, if [ $retries -gt 2 ].
(fshdb) run
Debugging deploy.sh staging
Breakpoint 1, line 17
 17 *>   upload "$file"
(fshdb) print file retries
file = build/app.tar.gz
retries = 3
(fshdb) step
Stopped at line 5
  5  >   local target=$1
(fshdb) finish
...
```

## Commands

| Command | |
|---|---|
| `run [ARGS]` (`r`) | Run the script, stopping at breakpoints. ARGS replace the script's arguments. |
| `start [ARGS]` | Run and stop before the first statement. |
| `exec [ARGS]` | Run without stopping (no breakpoints). |
| `continue` (`c`) | Go on until a breakpoint or the end. |
| `step` (`s`) | Run the statement and stop at the next one, going into functions. |
| `next` (`n`) | Run the statement and stop at the next one here, stepping over function calls. |
| `finish` (`fin`) | Run until the current function (or sourced file) returns. |
| `kill` (`k`) | Stop the script. |
| `break [LINE] [hits N] [if COMMAND]` (`b`) | Breakpoint on a line (default: the current one). `hits N` stops on the Nth time only; `if COMMAND` only when the command succeeds. A line with no code moves the breakpoint to the next line that has some. |
| `delete [N...]`, `clear LINE`, `disable [N...]`, `enable [N...]`, `condition N [COMMAND]` | Manage breakpoints. |
| `info breakpoints\|args\|redirects\|script` | Show them. |
| `list [LINE \| FIRST,LAST \| -]` (`l`) | Show lines; `*` marks a breakpoint (`o` a disabled one), `>` where the script is stopped. |
| `print NAME...` (`p`) | Variables of the stopped script: `p x $1 $@ $# arr`. |
| `vars [all]` | All the script's variables (without `all`, not the ones inherited unchanged from the environment or still at the shell's defaults). |
| `set NAME=VALUE` | Change a variable (or `$1` ...) of the stopped script. |
| `set args [ARGS]`, `set stdin\|stdout\|stderr [FILE]` | Arguments and redirects for the next run. |
| `where` (`bt`) | The running functions and sourced files, innermost first. |
| `trace on\|off` | Print each statement as it runs (`+ LINE: statement`). |
| `edit [LINE]` (`e`) | Edit the script with `$VISUAL`/`$EDITOR` (vi, vim, nano, emacs, micro ... open at LINE), then reload it. |
| `reload`, `file SCRIPT` | Read the script again; debug another one. |
| `help [COMMAND]`, `quit` (`q`) | |

A blank line repeats `step`, `next`, `continue` or `list`. Tab completes commands, and variable
names after `print` and `set`. History is kept in `~/.fshdb_history`.

**Ctrl-C** while the script runs stops it at its next statement; a second Ctrl-C within two
seconds (or one during `exec`) stops the script. A script waiting in a program (`sleep`, a
build ...) reaches its next statement only when that program ends, so use the second Ctrl-C.

The script's input and output are the terminal: when it reads (`read -p "name? " name`), type
its input as usual.

### Command line

```
fshdb [-e COMMAND]... [-x FILE] SCRIPT [ARGUMENTS...]
```

`-e` (repeatable) and `-x FILE` run debugger commands first, as if typed, for example
`fshdb -e 'break 30' -e run build.sh`. With commands on standard input instead of a terminal
(`fshdb script.sh < commands.txt`) it runs them in order.

SCRIPT can be a local file or a URL a parley-files file system understands
(`sftp://user@host/home/me/job.sh`, `ftp://...`). `edit` on a remote script edits a local copy
and writes it back.

A script is run with the arguments and redirects fsh-ide keeps for it (in
`~/.fsh-ide/run-settings`); arguments on fshdb's command line, and `set args`, replace them for
fshdb only. A script saved by an earlier fsh-ide may still carry them in comment lines
(`#BjlIdeScriptArgs=` ...): fshdb reads those and runs them as blank lines. The script runs as it
is in its file, so line numbers, `$LINENO` and fsh's messages are always the file's.

## Attaching a debugger (DAP)

Like a Java program started with `-agentlib:jdwp`, a script can run in one console while a
debugger attaches to it from somewhere else, over the
[Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/):

```
console 1:  fshdb --listen 5005 --suspend deploy.sh staging    # the script's input and output stay here
console 2:  fshdb --attach 5005                               # or any DAP client: VS Code, Eclipse (LSP4E) ...
```

- `--listen [HOST:]PORT` serves DAP on PORT. HOST defaults to 127.0.0.1 (this machine only);
  `0.0.0.0` accepts debuggers from other machines, or use an SSH tunnel.
- `--suspend` makes the script wait for a debugger (its `configurationDone`) before it starts;
  `"stopOnEntry": true` in the launch or attach configuration stops it before its first
  statement. Without `--suspend` the script starts at once, and a debugger can attach while it
  runs and `pause` it.
- One debugger at a time. When it disconnects, the script goes on without breakpoints and
  another can attach; with "terminate" it stops the script. When the script ends, fshdb tells
  the debugger and exits with the script's exit code.
- Supported: breakpoints with conditions and hit counts, continue, next, step in, step out,
  pause, the call stack, variables (arrays expand), setting variables, hovering over or
  evaluating a variable (only variables: nothing else is run), and the source (`loadedSources`
  names the script, `source` gives its text).
- One thread, "main". Every frame shows the variables of the statement the script is stopped
  at (fsh doesn't keep the callers' variables apart).

### fshdb --attach

```
fshdb --attach [HOST:]PORT [-e COMMAND]... [-x FILE]
```

is the command-line debugger for a script started with `--listen`, from another console or
another machine (`--attach build-server:5005`, or through an SSH tunnel). The commands are the
same as for a script run here; the script's text comes from the other fshdb, so `list` and
`break` show what runs there.

- If the script waits (`--suspend`), set breakpoints, then `run`, or `start` to stop before its
  first statement.
- If it's running, breakpoints take effect as you set them. A stop, or the script's end, is
  shown above the prompt; Ctrl-C pauses it.
- `quit` leaves the script running without breakpoints (another debugger can attach); `kill`
  stops it. When the script ends, fshdb --attach leaves with its exit code.
- The script's arguments, redirects and code belong to the fshdb it runs in, so `set args`,
  `set stdin`, `trace`, `edit`, `reload`, `file` and `exec` say so instead.

The DAP side is [parley-dap](https://github.com/parley-project/parley-dap).

## Requirements

- Java 11 or later
- fsh, fsh-ide and parley-dap (their SNAPSHOTs: `mvn install` them first), and JLine 3 (from
  Maven Central)
- Linux or macOS terminal (Ctrl-C handling and terminal input need a real terminal; Windows
  isn't tested)

## Build and run

```sh
mvn package
bin/fshdb script.sh            # or: java -jar target/fshdb-1.0.0-SNAPSHOT-all.jar script.sh
```

`target/fshdb-<version>-all.jar` holds everything (about 10 MB): copy it and `bin/fshdb` to a
server's PATH. `FSHDB_JAVA_OPTS` passes options to Java.

## How it works

fshdb is a front end like fsh-ide's Swing and JavaFX IDEs: `ScriptRun` runs the script on its
own thread with the terminal as its standard streams; a `DebugSession` stops it at breakpoints.
`DebugEngine` holds the run, the breakpoints and where the script is stopped; the command line
(`Debugger`) and the DAP adapter (`FshdbAdapter`, served by `DapHost`) are two front ends to it.
The command line works through the `DebugTarget` interface, so with `--attach` it drives a
`RemoteTarget` instead: a DAP client of another fshdb's `FshdbAdapter`.
`ConsoleDebugSession` adds what a command-line debugger needs on top of fsh-ide's session:
`next` and `finish` by function depth, `step` that doesn't stop on a function's `{` line, and
`start`. Only fsh-ide's core classes are used; its pom's Swing and JavaFX libraries are excluded.

## License

Apache License 2.0. See [LICENSE](LICENSE).
