# XC CODITER extension registry

`index.json` lists extensions. Each entry points to a manifest file and its SHA-256 hash; the app refuses to install a file whose hash does not match.

Publish an extension: add `extensions/<id>.json`, add an entry to `index.json` with `sha256sum extensions/<id>.json`, and open a pull request. Anyone can also host their own registry at any https URL (same `index.json` format) and paste that URL into the app's Extensions screen.

Extensions are data-only (snippets): even a malicious one cannot run code or read files. Format: `docs/EXTENSIONS.md`.
