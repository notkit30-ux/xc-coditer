"""Runs a Python file for XC CODITER and streams stdout/stderr to the app."""
import ctypes
import io
import os
import runpy
import sys
import threading
import traceback

_tid = None


class _Stream(io.TextIOBase):
    def __init__(self, sink, kind):
        self._sink = sink
        self._kind = kind

    def writable(self):
        return True

    def write(self, s):
        if s:
            self._sink.write(self._kind, s)
        return len(s)

    def flush(self):
        pass


def selftest():
    """Returns the Python version if the interpreter can really execute code, else None."""
    return sys.version.split()[0] if eval("6*7") == 42 else None


def run(path, cwd, args, sink):
    global _tid
    _tid = threading.get_ident()
    saved = (sys.stdout, sys.stderr, sys.stdin, sys.argv, os.getcwd())
    sys.stdout = _Stream(sink, "out")
    sys.stderr = _Stream(sink, "err")
    sys.stdin = io.StringIO("")  # no keyboard input yet: input() raises EOFError
    sys.argv = [path] + list(args)
    code = 0
    try:
        os.chdir(cwd)
        runpy.run_path(path, run_name="__main__")
    except SystemExit as e:
        code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
    except KeyboardInterrupt:
        sys.stderr.write("\n[stopped]\n")
        code = 130
    except BaseException:
        traceback.print_exc()
        code = 1
    finally:
        _tid = None
        sys.stdout, sys.stderr, sys.stdin, sys.argv = saved[:4]
        try:
            os.chdir(saved[4])
        except OSError:
            pass
    return code


def stop():
    """Raises KeyboardInterrupt in the running program. It takes effect when Python next runs code:
    a single long blocking call (e.g. time.sleep(100)) finishes first."""
    if _tid is not None:
        ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(_tid), ctypes.py_object(KeyboardInterrupt))
