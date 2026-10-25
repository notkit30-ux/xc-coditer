"""Python code intelligence for XC CODITER: pyflakes diagnostics and jedi completion, definitions, hover,
references and rename. All functions take the editor's current text and return JSON strings.
Lines are 1-based. Columns are 0-based, as in jedi."""
import json
import os

try:
    import jedi
except Exception:  # noqa: BLE001 - missing or broken install is reported, not fatal
    jedi = None
try:
    import pyflakes.api
    import pyflakes.messages
except Exception:  # noqa: BLE001
    pyflakes = None


def available():
    return json.dumps({"jedi": jedi.__version__ if jedi else None,
                       "pyflakes": __import__("pyflakes").__version__ if pyflakes else None})


def diagnostics(code, path="<editor>"):
    out = []
    try:
        compile(code, path, "exec")
    except SyntaxError as e:
        out.append({"line": e.lineno or 1, "col": max((e.offset or 1) - 1, 0), "severity": "error", "message": e.msg})
        return json.dumps(out)
    if pyflakes is None:
        return json.dumps(out)

    class Reporter:
        def unexpectedError(self, filename, msg):
            out.append({"line": 1, "col": 0, "severity": "warning", "message": str(msg)})

        def syntaxError(self, filename, msg, lineno, offset, text):
            out.append({"line": lineno or 1, "col": max((offset or 1) - 1, 0), "severity": "error", "message": str(msg)})

        def flake(self, message):
            out.append({"line": message.lineno, "col": getattr(message, "col", 0), "severity": "warning",
                        "message": message.message % message.message_args})

    pyflakes.api.check(code, path, Reporter())
    out.sort(key=lambda d: (d["line"], d["col"]))
    return json.dumps(out)


def _script(code, path, root):
    if jedi is None:
        raise RuntimeError("jedi is not installed in this build")
    project = jedi.Project(path=root) if root else None
    return jedi.Script(code, path=path or None, project=project)


def complete(code, line, col, path="", root=""):
    res = []
    for c in _script(code, path, root).complete(line, col)[:60]:
        res.append({"name": c.name, "type": c.type, "detail": c.description, "insert": c.name})
    return json.dumps(res)


def goto(code, line, col, path="", root=""):
    res = []
    for n in _script(code, path, root).goto(line, col, follow_imports=True):
        mp = str(n.module_path) if n.module_path else ""
        res.append({"name": n.name, "path": mp, "line": n.line or 0, "col": n.column or 0,
                    "same_file": bool(mp) and bool(path) and os.path.realpath(mp) == os.path.realpath(path),
                    "builtin": bool(n.in_builtin_module())})
    return json.dumps(res)


def hover(code, line, col, path="", root=""):
    names = _script(code, path, root).help(line, col)
    if not names:
        return ""
    n = names[0]
    sig = ""
    try:
        sigs = n.get_signatures()
        if sigs:
            sig = sigs[0].to_string()
    except Exception:  # noqa: BLE001
        pass
    doc = (n.docstring(raw=True) or "").strip()
    head = "%s %s" % (n.type, sig or n.full_name or n.name)
    return (head + ("\n\n" + doc if doc else ""))[:1800]


def references(code, line, col, path="", root=""):
    res = []
    for n in _script(code, path, root).get_references(line, col, scope="file"):
        if n.line is not None:
            res.append({"line": n.line, "col": n.column, "name": n.name})
    return json.dumps(res)


def rename(code, line, col, new_name, path="", root=""):
    """Returns {"ok": true, "code": newText} or {"ok": false, "error": msg}. Single-file renames only."""
    try:
        r = _script(code, path or "editor.py", root).rename(line, col, new_name=new_name)
        files = r.get_changed_files()
        if len(files) != 1:
            return json.dumps({"ok": False, "error": "rename touches %d files; only single-file renames are supported" % len(files)})
        return json.dumps({"ok": True, "code": next(iter(files.values())).get_new_code()})
    except Exception as e:  # noqa: BLE001
        return json.dumps({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})
