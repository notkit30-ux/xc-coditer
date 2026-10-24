# XC extensions (manifest v1, data-only)

XC CODITER extensions are **data-only** today. They add snippets. They cannot run code, read your files, or use the network, so there is nothing to sandbox yet. Many VS Code extensions need Node.js, desktop APIs or native binaries, so **VS Code extensions are not compatible**.

## Manifest

```json
{
  "id": "publisher.name",
  "name": "Display name",
  "version": "1.0.0",
  "publisher": "Your name",
  "description": "Optional, up to 300 characters.",
  "permissions": [],
  "contributes": {
    "snippets": {
      "python": [
        { "label": "readfile", "insert": "with open('§') as f:\n    data = f.read()" }
      ]
    }
  }
}
```

- `id`: `publisher.name`, lowercase letters, digits and dashes.
- `version`: `major.minor.patch`.
- `permissions`: must be empty. Any permission is rejected (no code execution exists to grant it to).
- `contributes.snippets`: keys are language names (python, javascript, typescript, html, css, kotlin, java, c, cpp, go, rust, php, ruby, lua, shell, sql, json, yaml, markdown). Each snippet has a `label` (no spaces) and an `insert` text. `§` marks where the cursor lands. Limits: 200 snippets, 2000 characters each, manifest under 100 KB.

## Lifecycle

1. **Install**: the manifest is validated, then stored privately in the app. Installing an id that exists is an update; its enabled state is kept.
2. **Enable / disable**: disabled extensions stay installed but contribute nothing.
3. **Uninstall**: removes the manifest.

Invalid manifests are rejected with the reason shown. You can install by pasting JSON, from the bundled list, or from an online registry.

## Online registry

The default registry is the `registry/` folder of this repository (`registry/index.json`, see `registry/README.md`). In the app: Extensions > Browse. You can point the app at any other https registry with the same format. Every manifest is downloaded over https, limited to 200 KB, and checked against the SHA-256 in the index before install; the manifest's id and version must match its listing. The network is used only when you press Browse or Install.

## Future (not built)

Themes, formatters and sandboxed code extensions would each need their own permission model before shipping.
