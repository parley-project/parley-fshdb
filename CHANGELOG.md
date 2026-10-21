# Changelog

## 1.0.0 (2026-10-21)

First version: a terminal debugger for shell scripts, built on fsh and fsh-ide's UI-free core.

- Breakpoints on file lines, with conditions (a command that must succeed) and hit counts;
  enable, disable, delete, clear.
- run, start (stop at the first statement), exec (no stopping), continue, step, next (over
  function calls), finish (out of the current function), kill.
- print, vars and set for the stopped script's variables (including `$1`, `$@`, `$#`, arrays);
  where for the running functions and sourced files; trace of each statement.
- list with breakpoint and current-line marks; edit with `$VISUAL`/`$EDITOR` (at the line),
  then reload; scripts on any parley-files file system (a remote script is edited as a local
  copy and written back).
- JLine 3 command line: history in ~/.fshdb_history, Tab completion, Ctrl-C pauses the script
  (twice stops it). -e and -x run commands first; commands can also come from standard input.
- The arguments and redirects fsh-ide keeps for a script (`~/.fsh-ide/run-settings`) are used,
  or the settings lines of a script saved by an earlier fsh-ide (#BjlIdeScriptArgs= ...), which
  run as blank lines. The script runs as it is in its file, so line numbers and `$LINENO` are
  the file's.
- One jar with everything (target/fshdb-<version>-all.jar) and a bin/fshdb launcher.
- `--listen [HOST:]PORT [--suspend]`: run the script in this console and let a debugger attach
  over the Debug Adapter Protocol (parley-dap): breakpoints with conditions and hit counts,
  stepping, pause, call stack, variables, set variable, evaluate (variables only), detach and
  re-attach.
- `--attach [HOST:]PORT`: the command-line debugger for a script started with `--listen`, in
  another console or on another machine, with the same commands. It starts a waiting script
  (run, or start to stop at its first statement) or attaches to a running one; stops and the
  script's end are shown above the prompt; quit leaves the script running, kill stops it.
- The DAP adapter lists its script (`loadedSources`) and honors `"stopOnEntry"` for a script
  waiting with `--suspend`.
- `DebugEngine`: the run, breakpoints and stopped state, shared by the command line and the
  DAP adapter. The command line works through `DebugTarget`, which `RemoteTarget` (the
  `--attach` DAP client) implements too.
- Variables include the running functions' `local` ones (needs fsh with the
  `ShellContext.getVariables()` locals fix).
