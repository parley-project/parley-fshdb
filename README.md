# fshdb

A debugger for shell scripts that runs in a terminal: no Swing, no JavaFX, so it works over
SSH and on servers without a display. It uses [fsh](https://github.com/tony-bringardner/fsh),
the FileSource Shell, as the interpreter and the UI-free core of
[fsh-ide](https://github.com/tony-bringardner/fsh-ide) (`us.bringardner.fsh.ide.core`) for
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

Scripts saved by fsh-ide keep their arguments and redirects in comment lines
(`#BjlIdeScriptArgs=` ...); fshdb uses them and doesn't count them as code, but line numbers are
always the file's, as your editor shows them.

## Requirements

- Java 11 or later
- fsh and fsh-ide (their SNAPSHOTs: `mvn install` them first), and JLine 3 (from Maven Central)
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
`ConsoleDebugSession` adds what a command-line debugger needs on top of fsh-ide's session:
`next` and `finish` by function depth, `step` that doesn't stop on a function's `{` line, and
`start`. Only fsh-ide's core classes are used; its pom's Swing and JavaFX libraries are excluded.

## License

Apache License 2.0. See [LICENSE](LICENSE).
