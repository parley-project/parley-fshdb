# Changelog

## 1.0.0 (unreleased)

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
- fsh-ide's settings lines (#BjlIdeScriptArgs= ...) give the arguments and redirects; line
  numbers stay the file's.
- One jar with everything (target/fshdb-<version>-all.jar) and a bin/fshdb launcher.
- `--listen [HOST:]PORT [--suspend]`: run the script in this console and let a debugger attach
  over the Debug Adapter Protocol (parley-dap): breakpoints with conditions and hit counts,
  stepping, pause, call stack, variables, set variable, evaluate (variables only), detach and
  re-attach.
- `DebugEngine`: the run, breakpoints and stopped state, shared by the command line and the
  DAP adapter.
- Variables include the running functions' `local` ones (needs fsh with the
  `ShellContext.getVariables()` locals fix).
