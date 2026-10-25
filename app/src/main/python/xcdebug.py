"""Python debugger for XC CODITER, built on the standard library's bdb.

The program runs on the calling thread. Whenever it stops (breakpoint, step, entry, or an uncaught
exception) the debugger calls sink.paused(json) and blocks until command() is called from another thread.
"""
import bdb
import ctypes
import io
import json
import os
import queue
import sys
import threading
import traceback

_dbg = None
_tid = None
_MAXVARS = 100


def _short(v, n=200):
    try:
        s = repr(v)
    except Exception as e:  # noqa: BLE001 - repr of user objects can raise anything
        s = "<repr failed: %s>" % type(e).__name__
    return s if len(s) <= n else s[:n] + "..."


class _Stream(io.TextIOBase):
    def __init__(self, sink, kind):
        self._sink, self._kind = sink, kind

    def writable(self):
        return True

    def write(self, s):
        if s:
            self._sink.write(self._kind, s)
        return len(s)

    def flush(self):
        pass


class Debugger(bdb.Bdb):
    def __init__(self, sink, path, entry):
        super().__init__()
        self.sink = sink
        self.path = os.path.realpath(path)
        self.entry = entry
        self.cmds = queue.Queue()
        self.frames = []
        self.paused = False
        self._first = True
        self._mode = "continue"
        self.stopped = False

    # ---- helpers ----
    def _mine(self, frame):
        return os.path.realpath(frame.f_code.co_filename) == self.path

    def _snapshot(self, frame, reason):
        self.frames = []
        f = frame
        while f is not None:
            if self._mine(f):
                self.frames.append(f)
            f = f.f_back
        return {"reason": reason, "line": frame.f_lineno,
                "stack": [{"function": f.f_code.co_name, "line": f.f_lineno} for f in self.frames],
                "variables": self.variables(0)}

    def variables(self, index):
        if index < 0 or index >= len(self.frames):
            return []
        f = self.frames[index]
        scope = f.f_locals
        out = []
        for k in list(scope)[:_MAXVARS]:
            if k.startswith("__") and k.endswith("__"):
                continue
            v = scope[k]
            out.append({"name": k, "type": type(v).__name__, "value": _short(v)})
        return out

    def evaluate(self, expr, index):
        if index < 0 or index >= len(self.frames):
            return "error: not paused"
        f = self.frames[index]
        try:
            return _short(eval(expr, f.f_globals, f.f_locals), 2000)
        except BaseException as e:  # noqa: BLE001
            return "error: %s: %s" % (type(e).__name__, e)

    # ---- bdb hooks ----
    def user_line(self, frame):
        if self._first:
            self._first = False
            if not self.entry and not self.break_here(frame):
                self._set_stopinfo(self.botframe, None, -1)  # like set_continue, but keeps tracing on
                return
        if not self._mine(frame):
            self.set_return(frame)  # don't step through library code
            return
        self._pause(frame, "breakpoint" if self.break_here(frame) else "step")

    def user_return(self, frame, value):
        # Finished a function while stepping: stop on the next line of the caller, like pdb.
        if self._mode in ("step", "next", "out"):
            self.set_step()

    def post_mortem(self, exc):
        """The program died with an uncaught exception: let the user inspect the dead frames."""
        frames = []
        tb = exc.__traceback__
        while tb is not None:
            if self._mine(tb.tb_frame):
                frames.append(tb.tb_frame)
            tb = tb.tb_next
        if not frames:
            return
        frames.reverse()  # innermost first
        self.frames = frames
        self.paused = True
        snap = {"reason": "exception: %s: %s" % (type(exc).__name__, exc), "line": frames[0].f_lineno,
                "stack": [{"function": f.f_code.co_name, "line": f.f_lineno} for f in frames],
                "variables": self.variables(0), "final": True}
        self.sink.paused(json.dumps(snap))
        self.cmds.get()
        self.paused = False
        self.frames = []

    def _pause(self, frame, reason):
        self.paused = True
        self.sink.paused(json.dumps(self._snapshot(frame, reason)))
        cmd = self.cmds.get()
        self.paused = False
        self.frames = []
        self._mode = cmd
        if cmd == "step":
            self.set_step()
        elif cmd == "next":
            self.set_next(frame)
        elif cmd == "out":
            self.set_return(frame)
        elif cmd == "stop":
            self.stopped = True
            self.set_quit()
        else:
            self.set_continue()


def set_breakpoints(lines):
    if _dbg is None:
        return
    _dbg.clear_all_breaks()
    for n in lines:
        _dbg.set_break(_dbg.path, int(n))


def command(cmd):
    """cmd: continue, step, next, out or stop. Ignored if the program is not paused (stop also interrupts a running one)."""
    d = _dbg
    if d is None:
        return
    if d.paused:
        d.cmds.put(cmd)
    elif cmd == "stop" and _tid is not None:
        ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(_tid), ctypes.py_object(KeyboardInterrupt))


def variables(index):
    return json.dumps(_dbg.variables(int(index))) if _dbg and _dbg.paused else "[]"


def evaluate(expr, index):
    return _dbg.evaluate(expr, int(index)) if _dbg and _dbg.paused else "error: not paused"


def run(path, cwd, breakpoints, entry, sink):
    """Runs the file under the debugger. Returns the exit code (0 ok, 1 error, 130 stopped)."""
    global _dbg, _tid
    _tid = threading.get_ident()
    saved = (sys.stdout, sys.stderr, sys.stdin, sys.argv, os.getcwd())
    sys.stdout, sys.stderr, sys.stdin = _Stream(sink, "out"), _Stream(sink, "err"), io.StringIO("")
    sys.argv = [path]
    _dbg = Debugger(sink, path, bool(entry))
    for n in breakpoints:
        _dbg.set_break(_dbg.path, int(n))
    code = 0
    try:
        os.chdir(cwd)
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        compiled = compile(src, os.path.realpath(path), "exec")
        _dbg.run(compiled, {"__name__": "__main__", "__file__": path, "__builtins__": __builtins__})
    except SystemExit as e:
        code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
    except KeyboardInterrupt:
        sys.stderr.write("\n[stopped]\n")
        code = 130
    except BaseException as e:  # noqa: BLE001
        if _dbg is not None:
            _dbg.post_mortem(e)
        traceback.print_exc()
        code = 1
    finally:
        if _dbg.stopped and code == 0:
            sys.stderr.write("\n[stopped]\n")
            code = 130
        _dbg = None
        _tid = None
        sys.settrace(None)
        sys.stdout, sys.stderr, sys.stdin, sys.argv = saved[:4]
        try:
            os.chdir(saved[4])
        except OSError:
            pass
    sink.finished(code)
    return code
