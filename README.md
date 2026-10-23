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
| **Running / compiling any language** | **Not implemented yet** (Phase 3) |
| Terminal, extensions, Git, LSP, debugging, SQLite, web preview | Not implemented yet |

Language support today: syntax highlighting only. Autocomplete, diagnostics, formatting, navigation, execution, compilation, debugging: none yet.

## Get the APK (from a phone)

1. Create a GitHub repo and upload this folder's contents (keep the `.github` folder).
2. Open the repo's **Actions** tab, pick **Android CI**, and wait for the green check (a few minutes).
3. Open the finished run, scroll to **Artifacts**, download **xc-coditer-debug-apk**, unzip it.
4. Open `app-debug.apk`; allow "Install unknown apps" for your browser/Files app when asked.

If the run is red, open it, tap the failing step, and send the log; the first build is the real compile check.

## Releases

Never commit a keystore (`.gitignore` blocks `*.jks`/`*.keystore`). For a signed release, store the keystore and passwords as GitHub Actions secrets and add a signing config reading them from environment variables.

## Phase 3 plan (runtimes) — to be verified, not yet built

- **JavaScript/TypeScript:** a bundled engine (e.g. QuickJS via JNI) for JS; TypeScript transpile via a bundled compiler running in that engine. Not Node.js: no `fs`/`require` of Node modules.
- **Python:** Chaquopy is the usual way to embed CPython on Android; its licensing and APK size (tens of MB per ABI) must be checked first. Native wheels (numpy etc.) are limited.
- **SQLite:** Android's built-in `android.database.sqlite` gives a real engine at zero size cost.
- **Java/Kotlin/C/C++/Go/Rust:** no realistic fully on-device compiler for most; plan is Termux integration or opt-in remote build, never silent uploads.
