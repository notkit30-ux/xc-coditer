import json, os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "main", "python"))
import xcintel

HAVE = xcintel.jedi is not None and xcintel.pyflakes is not None


class Syntax(unittest.TestCase):
    def test_syntax_error_without_pyflakes(self):
        d = json.loads(xcintel.diagnostics("def f(:\n  pass\n"))
        self.assertEqual(d[0]["severity"], "error")
        self.assertEqual(d[0]["line"], 1)

    def test_clean_code_has_no_syntax_errors(self):
        d = json.loads(xcintel.diagnostics("x = 1\n"))
        self.assertEqual([x for x in d if x["severity"] == "error"], [])


@unittest.skipUnless(HAVE, "jedi and pyflakes are installed in CI and in the app, not in every dev sandbox")
class Intel(unittest.TestCase):
    CODE = "import os\nimport sys\n\ndef greet(name):\n    return 'hi ' + name\n\nmsg = greet('a')\nprint(msg, undefined_thing)\n"

    def test_pyflakes(self):
        msgs = [d["message"] for d in json.loads(xcintel.diagnostics(self.CODE))]
        self.assertTrue(any("imported but unused" in m and "os" in m for m in msgs), msgs)
        self.assertTrue(any("undefined name" in m and "undefined_thing" in m for m in msgs), msgs)

    def test_complete(self):
        r = json.loads(xcintel.complete("import os\nos.pa", 2, 5))
        self.assertIn("path", [c["name"] for c in r])

    def test_goto_same_file(self):
        r = json.loads(xcintel.goto(self.CODE, 7, 7, path="/tmp/x.py"))
        self.assertEqual(r[0]["line"], 4)
        self.assertEqual(r[0]["name"], "greet")

    def test_hover(self):
        h = xcintel.hover("def f(a, b):\n    '''Adds.'''\n    return a\n\nf(1, 2)\n", 5, 0)
        self.assertIn("f(a, b)", h)
        self.assertIn("Adds.", h)

    def test_references(self):
        r = json.loads(xcintel.references(self.CODE, 4, 5))
        self.assertEqual(sorted(x["line"] for x in r), [4, 7])

    def test_rename(self):
        r = json.loads(xcintel.rename(self.CODE, 4, 5, "welcome"))
        self.assertTrue(r["ok"], r)
        self.assertIn("def welcome(name)", r["code"])
        self.assertIn("msg = welcome('a')", r["code"])
        self.assertNotIn("greet", r["code"])


if __name__ == "__main__":
    unittest.main()
