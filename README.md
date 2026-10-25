# XC CODITER

A VS Code-inspired code editor for Android (Kotlin + Jetpack Compose). No ads, no analytics, no permissions requested.

## Status: Phase 2 (core editor) — early

Honest feature status. "Implemented" means the code exists; only items marked *unit-tested* were run by tests.

| Area | Status |
|---|---|
| Workspace in app-private storage, path-traversal guard, atomic save | Implemented, unit-tested |
| File explorer: create file/folder, rename, delete, expand/collapse | Implemented (UI not yet run on a device) |
| Multi-tab editing, unsaved dot, auto-save when app is backgrounded | Implemented (UI not yet run on a device) |
| Undo / redo (up to 200 steps), auto-indent | Implemented (UI not yet run on a device) |
| Syntax highlighting (regex tokenizer, ~20 languages) | Implemented, unit-tested. Highlighting only: no parsing |
| Find, replace all, go to line, command palette | Implemented (UI not yet run on a device) |
| Mobile accessory key bar | Implemented (UI not yet run on a device) |
| **Instant Play (▶)**: HTML and JavaScript run in an offline WebView sandbox (JS on, network blocked, no file access); same-folder CSS/JS are inlined; console output shown | Logic unit-tested; WebView not yet run on a device |
| **Suggestions while typing**: snippets, keywords and words from your file (rule-based, not a language server) | Logic unit-tested; UI not yet run on a device |
| **SQL / SQLite**: Play on a `.sql` file runs it on Android's real SQLite engine (fresh in-memory DB each run, 10s query limit, 500-row cap), results shown as tables, errors shown as-is | Splitter and HTML output unit-tested; engine call not yet run on a device |
| **Settings**: light/dark theme, font size, tab size, auto-save, reset (stored privately on device) | Implemented (UI not yet run on a device). Import/export of settings not yet built |
| **Search and replace across files**, quick open by file name, jump to match line | Search/replace logic unit-tested; UI not yet run on a device |
| **Project templates** (website, JS, TypeScript, Python, Java, Kotlin, C, C++, PHP, SQL, Markdown) | Unit-tested; each says honestly whether it can run in-app |
| **Markdown preview** (▶ on a `.md` file; raw HTML is escaped) | Unit-tested; WebView not yet run on a device |
| **Terminal** (tabs, history, stop, copy). Runs in Android's own shell as the app's user. Not full Linux; no keyboard input to programs | Not yet run on a device |
| **Runtime Manager**: each runtime shows verified only after a live self-test on your device | Not yet run on a device |
| **Extensions**: data-only (snippets), manifest + lifecycle in `docs/EXTENSIONS.md`, bundled examples. No online registry. VS Code extensions are not supported | Manifest/lifecycle unit-tested; UI not yet run on a device |
| **Settings import/export** via clipboard | Codec unit-tested |
| **Git** (clone over https, init + set remote, status, diff, history, branches, commit all, pull, push) using JGit; token stored encrypted (Android Keystore); network used only when you press clone/pull/push | Wrapper tested against real local repos in CI; not yet run against GitHub or on a device |
| **Phone folders**: import a folder into the app, export a folder back (copy-based, no storage permission; limits: 3000 files, 5 MB per file, 100 MB total) | Not yet run on a device. Not live editing |
| **Python** (embedded CPython 3.12 via Chaquopy, MIT): output streams into a terminal tab, Stop, confirmation before first run of a file | Builds in CI; not yet run on a device |
| **Lua** (LuaJ, Lua 5.2): sandboxed (no io/os/require), Stop works | Tests pass in CI (incl. stopping an infinite loop); not yet run on a device |
| **TypeScript** (▶ on `.ts`): the real TypeScript 6.0.3 compiler runs in a hidden, network-blocked WebView, then the JavaScript runs as a module. **Syntax errors are shown; types are NOT checked.** The compiler file is downloaded by the CI build, not stored in git | Result parsing tested; compiler tested in headless Chromium; WebView path not yet run on a device |
| **Python debugger** (Command palette → "Debug (Python)"): breakpoints by line number, run to breakpoint or stop on first line, step over/into/out, call stack, variables, evaluate expression, stop; stops on an uncaught error with its variables visible. Python only | Debugger engine has 8 tests run in CI; UI not yet run on a device |
| **Python code intelligence** (jedi + pyflakes, offline): completions while typing, Problems, go to definition, docs at cursor, find references, rename (single file) | Real jedi/pyflakes tests run in CI; UI not yet run on a device |
| **Language servers** (C, C++, Go, Rust, TypeScript/JavaScript, Java, Kotlin, PHP, Ruby, Lua): a built-in LSP client connects over localhost to a server you run (e.g. in Termux) and gives completions, Problems, definition, docs, references, rename | Client tested against a mock server over a real socket. **Never tried against clangd, gopls, rust-analyzer or others**; the Termux commands shown in the app are suggestions |
| **Termux bridge**: Java, Kotlin, C, C++, Go, Rust, PHP, Ruby and shell run inside Termux (a separate app you install; you must enable `allow-external-apps`), with confirmation before each run | Command building tested; not yet run against a real Termux |
| **Online extension registry** (https index + SHA-256-checked data-only extensions) and **release signing** from GitHub secrets (`docs/RELEASE.md`) | See `docs/`; registry/signing checks run in CI; install flow not yet run on a device |

## Get the APK (from a phone)

1. Create a GitHub repo and upload this folder's contents (keep the `.github` folder).
2. Open the repo's **Actions** tab, pick **Android CI**, and wait for the green check (a few minutes).
3. Open the finished run, scroll to **Artifacts**, download **xc-coditer-debug-apk**, unzip it.
4. Open `app-debug.apk`; allow "Install unknown apps" for your browser/Files app when asked.

If the run is red, open it, tap the failing step, and send the log; the first build is the real compile check.

## Releases

See `docs/RELEASE.md`. Never commit a keystore (`.gitignore` blocks `*.jks`/`*.keystore`). For a signed release, store the keystore and passwords as GitHub Actions secrets and add a signing config reading them from environment variables.

## Not built (and why)

- **Compiling Java, Kotlin, C, C++, Go, Rust inside this app:** Android ships no such toolchains and bundling them is impractical. They run through **Termux** (separate app) or on a computer.
- **Ruby in-app:** no embedded Ruby; it runs through Termux only. PHP, C#, Dart: Termux only (C#, Dart: not at all yet).
- **Debugging languages other than Python:** none. A debugger needs a per-language adapter (DAP); not built.
- **Type checking for TypeScript, and deep project-wide intelligence:** TypeScript is transpiled only. Language servers see the text of the file you have open, not your whole project on disk.
- **Python limits:** 64-bit phones only; bundled standard library plus jedi/pyflakes (no pip at runtime, no numpy/native packages); no keyboard input to programs; Stop acts at the next Python instruction.
- **VS Code extension compatibility:** extensions are data-only snippets; VS Code extensions are not supported.
- **Live editing of phone folders:** folders are copied in and out.
- **Everything marked "not yet run on a device":** the UI has only been compiled and unit-tested in CI. Please install the APK and report what breaks.

## Privacy

No ads, no analytics. Permissions: INTERNET (Git clone/pull/push, the extension registry when you open it, and localhost connections to language servers you configure) and Termux RUN_COMMAND (only to run a file in Termux after you confirm). The HTML/JS preview blocks all network loads. Your Git token is stored encrypted with the Android Keystore and is only sent to the Git server you choose.
