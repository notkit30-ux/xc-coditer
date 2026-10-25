import json, os, sys, tempfile, threading, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "main", "python"))
import xcdebug

PROG = """\
def add(a, b):
    c = a + b
    return c

x = 1
y = add(x, 2)
print("sum", y)
z = [1, 2, 3]
print("done")
"""


class Sink:
    def __init__(self, script):
        self.out, self.stops, self.code, self.script = [], [], None, list(script)

    def write(self, kind, text):
        self.out.append((kind, text))

    def paused(self, js):
        d = json.loads(js)
        self.stops.append(d)
        # act like the UI thread: send the next command from another thread
        cmd = self.script.pop(0) if self.script else "continue"
        threading.Thread(target=lambda: xcdebug.command(cmd)).start()

    def finished(self, code):
        self.code = code

    def text(self):
        return "".join(t for _, t in self.out)


def go(src, script, bps=(), entry=False):
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "prog.py")
        with open(p, "w") as fh: fh.write(src)
        s = Sink(script)
        code = xcdebug.run(p, d, list(bps), entry, s)
        return s, code


class T(unittest.TestCase):
    def test_runs_to_end_without_breakpoints(self):
        s, code = go(PROG, [])
        self.assertEqual(code, 0)
        self.assertEqual(s.stops, [])
        self.assertIn("sum 3", s.text())
        self.assertEqual(s.code, 0)

    def test_breakpoint_hits_with_variables_and_stack(self):
        s, code = go(PROG, ["continue"], bps=[2])
        self.assertEqual(code, 0)
        self.assertEqual(len(s.stops), 1)
        st = s.stops[0]
        self.assertEqual(st["line"], 2)
        self.assertEqual([f["function"] for f in st["stack"]], ["add", "<module>"])
        v = {x["name"]: x["value"] for x in st["variables"]}
        self.assertEqual(v["a"], "1")
        self.assertEqual(v["b"], "2")

    def test_entry_then_step_and_next(self):
        s, code = go(PROG, ["next", "next", "next", "step", "step", "continue"], entry=True)
        self.assertEqual(code, 0)
        lines = [x["line"] for x in s.stops]
        self.assertEqual(lines[0], 1)          # def add
        self.assertEqual(lines[1], 5)          # x = 1
        self.assertEqual(lines[2], 6)          # y = add(...)
        self.assertEqual(lines[3], 7)          # next stepped over add()
        self.assertEqual(lines[4], 8)          # step on print() stays out of library code
        self.assertIn("sum 3", s.text())

    def test_step_into_function_and_out(self):
        s, _ = go(PROG, ["continue"], bps=[6])
        s2, _ = go(PROG, ["step", "out", "continue"], bps=[6])
        self.assertEqual([x["line"] for x in s2.stops][:2], [6, 2])
        self.assertEqual(s2.stops[2]["line"], 7)  # returned to the caller

    def test_uncaught_exception_pauses(self):
        s, code = go("a = 1\nb = a / 0\n", ["continue"])
        self.assertEqual(code, 1)
        self.assertTrue(s.stops[0]["reason"].startswith("exception: ZeroDivisionError"))
        self.assertEqual(s.stops[0]["line"], 2)
        self.assertIn("ZeroDivisionError", s.text())

    def test_stop_command(self):
        s, code = go(PROG, ["stop"], entry=True)
        self.assertEqual(code, 130)
        self.assertNotIn("done", s.text())

    def test_evaluate_while_paused(self):
        res = {}

        class S(Sink):
            def paused(self, js):
                res["a"] = xcdebug.evaluate("a + b", 0)
                res["bad"] = xcdebug.evaluate("nope", 0)
                super().paused(js)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "prog.py")
            with open(p, "w") as fh: fh.write(PROG)
            xcdebug.run(p, d, [2], False, S(["continue"]))
        self.assertEqual(res["a"], "3")
        self.assertTrue(res["bad"].startswith("error: NameError"))

    def test_exit_code_from_sys_exit(self):
        s, code = go("import sys\nsys.exit(3)\n", [])
        self.assertEqual(code, 3)


if __name__ == "__main__":
    unittest.main()
