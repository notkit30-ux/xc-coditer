package app.xccoditer

data class Template(val id: String, val name: String, val files: Map<String, String>, val howToRun: String)

object Templates {
    val all: List<Template> = listOf(
        Template("website", "HTML / CSS / JS website", mapOf(
            "index.html" to "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>My site</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>Hello, web</h1>\n  <button id=\"btn\">Click me</button>\n  <p id=\"out\"></p>\n  <script src=\"script.js\"></script>\n</body>\n</html>\n",
            "style.css" to "body {\n  font-family: sans-serif;\n  padding: 16px;\n}\nbutton {\n  padding: 10px 16px;\n}\n",
            "script.js" to "let n = 0;\ndocument.getElementById('btn').addEventListener('click', () => {\n  n++;\n  document.getElementById('out').textContent = 'Clicked ' + n + ' times';\n  console.log('clicked', n);\n});\n",
        ), "Open index.html and press Play. Runs offline in the preview sandbox."),
        Template("javascript", "JavaScript script", mapOf(
            "main.js" to "// Plain JavaScript (browser engine, not Node.js)\nconst nums = [1, 2, 3, 4, 5];\nconsole.log('Sum:', nums.reduce((a, b) => a + b, 0));\nconsole.log(nums.map(n => n * n));\n",
        ), "Open main.js and press Play. This is browser JavaScript: no require() or Node modules."),
        Template("typescript", "TypeScript project", mapOf(
            "src/index.ts" to "function greet(name: string): string {\n  return `Hello, \${name}`;\n}\n\nconsole.log(greet('world'));\n",
            "tsconfig.json" to "{\n  \"compilerOptions\": {\n    \"target\": \"ES2020\",\n    \"module\": \"ES2020\",\n    \"strict\": true,\n    \"outDir\": \"dist\"\n  },\n  \"include\": [\"src\"]\n}\n",
        ), "Editing and highlighting work. Running TypeScript is not built in yet: compile it elsewhere with tsc."),
        Template("python", "Python script", mapOf(
            "main.py" to "def main():\n    name = \"world\"\n    print(f\"Hello, {name}!\")\n\n\nif __name__ == \"__main__\":\n    main()\n",
            "README.md" to "# Python script\n\nRun with `python main.py` in a Python environment (for example Termux).\n",
        ), "Editing works. Running Python inside XC CODITER is not built yet."),
        Template("java", "Java program", mapOf(
            "Main.java" to "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, Java\");\n    }\n}\n",
        ), "Editing works. Compile with `javac Main.java && java Main` on a computer or Termux. No on-device Java yet."),
        Template("kotlin", "Kotlin program", mapOf(
            "Main.kt" to "fun main() {\n    println(\"Hello, Kotlin\")\n}\n",
        ), "Editing works. Compile with kotlinc on a computer. No on-device Kotlin compiler yet."),
        Template("c", "C program", mapOf(
            "main.c" to "#include <stdio.h>\n\nint main(void) {\n    printf(\"Hello, C\\n\");\n    return 0;\n}\n",
        ), "Editing works. Build with `gcc main.c -o main` on a computer or Termux. No on-device C compiler yet."),
        Template("cpp", "C++ program", mapOf(
            "main.cpp" to "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, C++\" << std::endl;\n    return 0;\n}\n",
        ), "Editing works. Build with `g++ main.cpp -o main` on a computer or Termux. No on-device C++ compiler yet."),
        Template("php", "PHP project", mapOf(
            "index.php" to "<?php\n\necho \"Hello, PHP\\n\";\n",
        ), "Editing works. Running PHP is not built in yet."),
        Template("sql", "SQL / SQLite demo", mapOf(
            "demo.sql" to "CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT, age INTEGER);\nINSERT INTO people (name, age) VALUES ('Ada', 36), ('Linus', 54), ('Grace', 85);\nSELECT * FROM people ORDER BY age DESC;\nSELECT COUNT(*) AS total, AVG(age) AS avg_age FROM people;\n",
        ), "Open demo.sql and press Play. Runs on a real SQLite engine, in a fresh in-memory database each time."),
        Template("markdown", "Markdown documentation", mapOf(
            "README.md" to "# Project title\n\nShort description.\n\n## Usage\n\n```\nsteps here\n```\n",
        ), "Editing works. Markdown preview is not built yet."),
    )
}
