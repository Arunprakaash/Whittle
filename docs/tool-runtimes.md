# Command-line tools on the phone

`run_command` gives the agent Node.js 24 LTS with npm, Python 3.14 with pip, and git, on top of Android's
toybox shell. They are Termux's Android builds, shipped inside the APK.

## How it works

- **What ships** is listed in `app/termux-runtimes.json` and pinned, with SHA-256s, in
  `app/termux-runtimes.lock.json`. Builds only download what the lock names and fail on a mismatch.
- **Packaging** (build-logic `TermuxRuntimeTask`, per build variant):
  - Every program and shared library (ELF) becomes a native library (`lib…so`). Android 10+ only lets apps
    run native code from the APK's native-library folder, never from their own storage, so the APK uses
    `useLegacyPackaging` to extract them.
  - Everything else (JavaScript, Python's standard library, certificates) goes into
    `assets/runtimes/<abi>/files.zip`, with a manifest of links and executable bits.
  - Termux compiles its prefix, `/data/data/com.termux/files/usr`, into its programs. Every file is rewritten
    to `/data/data/<applicationId>/rt`, padded with `_` to the same length so binaries stay intact.
- **On the phone**, `ToolRuntime` lays the prefix out on the first command, and again after any update,
  since Android moves the native-library folder on every install. Programs and libraries in the prefix are
  links into the native-library folder.
- **Environment:** commands get `PATH`, caches and the rest from `toolRuntime` in `app-defaults.json`.
- **Script tools:** tools that are scripts (`npm`, `npx`, `pip`) are shell functions that call their
  interpreter (`toolRuntime.shellFunctions`). Nothing intercepts how programs start.

## Updating

Termux keeps only the latest version of each package, so a pinned file eventually disappears and the
build says so. Then:

```
./gradlew :app:updateTermuxLock
```

Review the lock diff, rebuild, and run the device tests.

## Testing

`app/src/androidTest/.../ToolRuntimeTest` runs inside the app's own process and SELinux domain, which is
the only place Android's rules can be checked. It covers Node and npm, an HTTP server on loopback,
`npm run`, Python with sqlite3, ssl and pip, and a git commit.

```
./gradlew :app:connectedDebugAndroidTest
```

## Limits

- **Package commands:** commands that packages install (`node_modules/.bin/vite`, pip console scripts) can't
  be started directly, because they're scripts in app storage. Run them through their interpreter
  (`node node_modules/vite/bin/vite.js`, `python3 -m flask`); the system prompt tells the agent so.
- **Native code:** packages that compile native code (node-gyp add-ons, most pip packages with C
  extensions) won't install.
- **Servers:** a command ends when it finishes or times out (`terminal.json`), so servers don't keep
  running after the command.
- **Users:** only the phone's main user. A work profile has a different data folder, and the tools are
  built for the main one's path, so they stay off there.
- **Git:** subcommands written as shell or Perl scripts are unavailable; the built-in ones work.
- **Size:** the release APK is about 69 MB (164 MB uncompressed) for arm64-v8a alone. Adding x86_64 would
  roughly double the native part.

## Licenses and store policy

- **Licenses:**
  - Node.js, ICU and zlib: permissive licenses.
  - OpenSSL: Apache-2.0.
  - npm: Artistic-2.0.
  - Python: PSF License.
  - git: GPLv2, which obliges us to offer its source. The exact Termux package versions are in the lock file.
- **Play Store:** before publishing, check its policy on apps that download and run code: npm and pip fetch
  packages that the shipped interpreters then run.
