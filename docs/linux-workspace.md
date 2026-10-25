# Linux workspace

Status: phases 1 and 2 (below) are built and have shipped since 0.10.0; phase 3 is a plan. This page began as the
design, after a spike on 2026-10-22 (emulator, Android 16 / API 36, arm64). It now describes what's built, and keeps
the spike's findings for the record.

`run_command` used to run Termux's Android builds of Node, Python and git, shipped in the APK. That covered scripts,
but not what a coding agent expects from a computer: no apt, no glibc, no compiler, package commands had to go
through their interpreter, and every command was killed when it ended, so nothing could be served. Now Ubuntu is
Whittle's only runtime, downloaded during onboarding. Commands and builds run inside it, servers keep running, and
what they serve shows in a live preview. The APK ships only proot and tar.
Everything still runs on the phone: no server, no account.

| Piece | Where it runs |
| --- | --- |
| `run_command`, package installs, builds, servers | Inside Ubuntu, behind the guest sandbox |
| Apps | Vite projects the agent runs with `start_process`, shown in the live preview. There's no separate build tool, no saved apps and no APK export |
| `read_file`, `write_file`, `edit_file`, `list_files` | Kotlin, on Linux paths mapped into the Ubuntu folder (see File tools) |
| `use_project` | Kotlin: picks the chat's project folder (see Shared workspace and projects) |
| `web_fetch`, MCP connections, memory, policy | Kotlin, unchanged: they need Android's keystore, cards and provenance |

## What the spike showed

Ubuntu 24.04 base under proot, inside Whittle's own process (SELinux domain `untrusted_app_27`):

| Step | Time on the emulator | Disk afterwards |
| --- | --- | --- |
| Download Ubuntu base (30 MB, SHA-256 checked) | 9 s | |
| Unpack and configure | 2 s | 119 MB |
| `apt-get update` | 25 s | |
| curl, ca-certificates, xz | 20 s | |
| Node 22 (official tarball) | 13 s | 680 MB |
| build-essential, Python 3.12, venv, git | 65 s | |
| numpy (wheel) and markupsafe built from source | 36 s | |
| A Vite + React app (`npm create`, `npm install`) | 53 s | 2.2 GB with all of the above |

- gcc compiles and runs C. pip installs manylinux wheels and builds C extensions.
- A Vite dev server started in the background kept serving after its command ended, and Android-side code
  fetched it on `127.0.0.1:5173`.
- Whittle's `whittle_sandbox.c`, compiled inside Ubuntu, works unchanged on guest commands:
  - offline mode blocks DNS, the internet and loopback;
  - `--network` allows them;
  - ptrace is refused;
  - Node, Python, git and `vite build` run normally under it.
- Claude Code (native linux-arm64 build) and Codex CLI also install and start. That isn't the goal; it only shows
  that standard Linux binaries run.
- The emulator runs on a fast Mac with fast internet. Expect real phones to be several times slower; the spike has
  not run on one yet.

## Approach and alternatives

**Chosen:** proot plus a distribution root filesystem. proot uses ptrace to give programs their own root folder
without root access. This is the standard technique behind Termux's proot-distro, UserLAnd and Andronix. Whittle
uses Termux's own `proot` package, pinned like our other runtimes, and Ubuntu's official base image. Nothing comes
from Mobile-Harness.

| Option | Why not |
| --- | --- |
| Termux runtimes only (before 0.10) | Android's bionic libc: no glibc binaries, no apt, many pip and npm packages fail |
| Android Virtualization Framework (a real VM, as in Google's Terminal app) | Needs `MANAGE_VIRTUAL_MACHINE`, which is signature/privileged: third-party apps can't use it |
| QEMU full-system emulation | 5–20× slower; large download |
| chroot | Needs root |
| A remote Linux box | Breaks "no backend". It could be an optional extra later (the user's own server over SSH) |
| Alpine root filesystem | Much smaller, but musl: manylinux wheels and most prebuilt glibc binaries don't run |
| Debian 13 slim | Equivalent to Ubuntu and slightly smaller. The image is a config value, so either works |

## Distribution: one build, on GitHub

Android lets an app run programs from its own storage only when it targets SDK 28 or lower. Termux makes the same
choice for the same reason. So Whittle targets SDK 28 (`androidLinuxTargetSdk` in the version catalog) and ships on
GitHub Releases only. There is no Play build:

- Play requires a target of API 36 or higher for new apps and updates (from August 31, 2026).
- Play's policy forbids downloading code to run, which apt does by design.

What targeting SDK 28 costs:

- **Install floor:** Android 14 blocks installing apps that target below 23, and Android 15 and 16 below 24. 28
  installs fine.
- **UI behaviours that only apply from newer targets:** edge-to-edge is enforced from 35. Keep calling
  `enableEdgeToEdge()`, then check insets everywhere.
- **Notification permission:** Android asks for it automatically at the first notification, not at the moment we
  choose.
- **Foreground-service types:** not enforced at 28.
- **Package visibility:** below 30 the app sees every installed package.
- **Data folder path:** the app is given `/data/user/0/<package>` instead of `/data/data/<package>`. The spike found
  that ToolRuntime compared these paths as text and refused to set up; it now compares device and inode.

## Sandbox

Every `run_command` becomes:

```
proot -r <rootfs> -b /dev -b /proc -b /sys -b <workspace>:/workspace -w /workspace \
  /usr/local/libexec/whittle/sandbox [--network] -- /bin/bash -lc "[cd -- '<project>' && ]<command>"
```

- The mounts, shell and environment come from `linux-workspace.json`. When the chat has a project, the command
  starts in that folder (see Shared workspace and projects).
- **proot runs unfiltered.** It needs ptrace and process_vm_readv itself. The sandbox filter is installed inside the
  guest, so it applies to the command and its children only. That's the order the spike verified.
- **The guest sandbox:** the same `whittle_sandbox.c`, built as a static arm64 binary with the NDK, so it needs no
  libc from the image, and bind-mounted read-only into the guest.
- **What the guest sees:**
  - the root filesystem;
  - the shared workspace as `/workspace`;
  - `/dev`, `/proc` and `/sys`;
  - nothing else of the app's storage: no chats, no keys, no databases.
- **Honesty about proot:** it is a filesystem view, not a security boundary. Protection still comes from three
  layers:
  - Android's per-app sandbox;
  - the seccomp filter: no ptrace, no process_vm, no io_uring, and no sockets when offline;
  - the policy engine's approvals.
- **Checked on the device:** a guest process can't ptrace Whittle or read its memory through `/proc/<pid>/mem`.
- The guest runs as fake root (`proot -0`), so apt works. That gives no extra power outside the guest.

## Shared workspace and projects

- **One workspace for every chat.** `/workspace` is one folder outside Ubuntu, so Reset and Remove never touch
  your files. A new chat can carry on with work an earlier chat started, and deleting a chat leaves its files.
- **What stays per chat:** its running processes, its record of the files it changed, and its project. A chat's
  id (`WorkspaceId`) ties them together.
- **Projects:** a chat can have one folder of the workspace as its project. You pick it from the chip above the
  composer, or the agent picks it with `use_project`. Relative paths in the file tools and the working folder of
  commands and processes then start there. A chat with no project works on the whole workspace.
- **Changes:** `write_file` and `edit_file` report what a file held before. `FileChangeLog` keeps the first version
  each chat saw, so the file browser can show what the chat changed, line by line, and ask the agent to undo it.
- **The file browser** (top bar, a Wrote or Edited step, or Projects in the sidebar) lists the projects, what this
  chat changed, and every file, with copy, share and delete.
- **Android's Files app** sees the same workspace through a `DocumentsProvider`.

## File tools

The file tools see the same Linux the commands do. Ubuntu is an ordinary folder on the phone, so Kotlin reads and
writes it directly: about 2 ms a read on the emulator, against about 50 ms through proot. Both see the same bytes,
and a write from either side shows up on the other at once.

- **One set of paths:** the agent only uses Linux paths.
  - `/workspace/...` maps to the shared workspace.
  - A relative path starts at the chat's project, or at `/workspace` when it has none.
  - Everything else maps into the Ubuntu folder.
  - Tool results show Linux paths too.
- **Links resolve the way Linux would, inside Ubuntu.**
  - An absolute link target starts at Ubuntu's root, never the phone's.
  - `..` stops at that root.
  - proot's stand-ins for hard links (`--link2symlink`) are followed like any link.
  - Nothing outside Ubuntu and the workspace is reachable.
- **Not covered:** `/proc`, `/dev` and `/sys` are live views passed in from Android, not files in the folder. Only
  `run_command` sees them.
- **Owners:** inside Linux, files look owned by root, because proot pretends; on disk, the app owns them. Only the
  label differs.

## Network control

The `network` flag on each command is enforced by the guest sandbox and decided by the policy engine. That means a
card for untrusted models and tainted runs.

- apt, pip and npm installs need network, so they're outbound.
- **DNS:** Android has no `/etc/resolv.conf`. Setup writes one from the active network's DNS servers
  (`ConnectivityManager` link properties), falling back to servers named in config, and refreshes it when the
  network changes.
- **Loopback only (later):** a dev server, and tests that call it, need sockets but not the internet. seccomp can't
  see addresses. proot can, since it already reads the program's memory at each system call. A small proot
  extension could allow `bind`/`connect` only to 127.0.0.1, ::1 and Unix sockets. Until then, servers run with
  network on, behind the usual card.

## Process manager

`run_command` stays one-shot, with its timeout. Servers and watchers get their own tools:

| Tool | Does |
| --- | --- |
| `start_process` | Starts a command in the background (name, command, port, network); returns an id |
| `read_process_output` | The latest output, or output since an offset |
| `list_processes` | What this chat is running, with uptime and listening ports |
| `stop_process` | Stops it and its children |

- **Lifetime:** a process belongs to the chat that started it. It stops when that chat is deleted, from the process
  card, from Settings › Linux workspace, or with Stop all on the notification.
- **Limits** come from `terminal.json` (`processes`): how many each chat may run, how long to wait for a port, how
  much output to keep, and the grace period before a stop turns into a kill.
- **Kept alive by a foreground notification** while anything runs and Keep working in the background is on, with a
  Stop all action.
- **Output** is kept in memory, capped at `keptOutputChars`, and read in sections.
- **Android's phantom process killer (12+):**
  - It allows at most 32 child processes across all apps.
  - It kills CPU-heavy children of background apps.
  - npm and Vite spawn several processes, and proot adds one. Keep counts low and stop idle servers.
  - When something is killed with signal 9, say so plainly and point to the developer-options switch that turns
    the killer off.
  - Whether a foreground service exempts us is unclear. Measure it on a real phone.
- **System prompt:** for servers, use `start_process`, then show the preview.

## Use from your computer

Settings › Linux workspace › Your computer lets the user's own computers sign in to Linux over SSH, for a terminal,
VS Code or Cursor over Remote-SSH, and the phone's dev servers through `ssh -L`. Settings are in
`computer-access.json`.

- **Off until turned on.** The first time, OpenSSH's server is installed without recommended extras.
- **Wi-Fi only.** It listens on the phone's Wi-Fi address (and on the phone itself), turns itself off when the phone
  leaves Wi-Fi, and moves when the address changes.
- **Keys only.** Computers are added by pasting their public key; passwords and keyboard-interactive sign-in are off.
  Keys live in `/etc/whittle/computers`, one per line, with no `authorized_keys` options.
- **The user's shell, not the agent's.** The agent never uses an SSH session.
- **Kept alive** by a foreground notification with Turn off while it's on. Reset and Remove turn it off first.
- **Under proot** sshd needs `/run/sshd` made before it starts and `StrictModes=no`, as proot reports every file as
  root's.

## Localhost preview

- The process card opens `http://127.0.0.1:<port>` in a sheet with a WebView.
- **The sheet has:** the process name, reload, stop, open in browser, and a console. The console collects the
  page's messages; Whittle can read them, and Ask to fix sends the errors to the agent.
- The network security config allows cleartext to 127.0.0.1 and localhost only.
- Vite's hot reload uses a WebSocket on the same port. The spike checked serving but not hot reload; check it.
- Apps aren't saved or exported: an app is its project folder plus the server that runs it. When the server stops,
  the sheet says so, and the card or the agent starts it again.

## Setup flow

- **Where it's offered:**
  - **Onboarding,** before the model: Welcome, then "Give Whittle a computer" with Set up. Setup starts in the
    background while you connect a model, and a strip under that screen shows its progress. It can be skipped
    (Not now).
  - **Settings › Linux workspace,** to set it up later or manage it.
- **Until it's set up,** Whittle still chats, reads web pages and uses connections. Commands and Linux paths fail
  with a plain "isn't set up on this phone yet".
- **Steps, shown as ordinary Whittle steps:**
  1. Download: checksum pinned in config, resumable.
  2. Unpack: Kotlin gunzips the download into Termux's `tar`, run under `proot --link2symlink -0` so hard links and
     root-owned files unpack as the app (the spike's method).
  3. Configure: `resolv.conf`, `APT::Sandbox::User`, locale, `/workspace`, the guest sandbox.
  4. Base packages: `ca-certificates curl git xz-utils poppler-utils` (poppler-utils gives `pdftotext` and `pdfinfo`,
     for reading PDF attachments), then Node.js LTS (pinned, checked by SHA-256) for web apps. Python and compilers
     come later with apt, when a task needs them.
- **Top-up at launch:** setup records which base packages it installed (`.base-packages` beside the markers). At each
  launch, packages added to `baseSetup.packages` since then are installed quietly, with the network, while Linux stays
  ready. A Linux set up before the record existed gets the whole list once; apt skips what's already there. Offline or
  failed, it tries again next launch. Only packages top up: a change to `baseSetup.commands` (Node.js) reaches an
  existing Linux through Repair.
- **Runs under a foreground notification** with progress, so it survives switching apps.
- **Manage:** see the space used, Repair (re-runs setup, keeps everything), Reset (a fresh Ubuntu, workspace kept),
  Remove (frees the space; commands are unavailable until it's set up again).
- **Storage:** `filesDir/linux`, excluded from backups. The image is stored once; the workspace is one folder that
  every chat shares.

## Size

- **APK:** dropping the Termux runtimes and shipping only proot, tar and their libraries took the release APK from
  69 MB to 8.9 MB. Phase 2 removed esbuild's WebAssembly build and APK export with it.
- **Setup (measured on the emulator):** a fresh setup takes 83 s (download, unpack and configure 13 s; packages and
  Node.js 70 s) and leaves 589 MB: 374 MB of Ubuntu, packages and Node.js, and 214 MB of caches, nearly all apt's
  package lists. `setupSizeBytes` says 590 MB.
- **On the phone:** about 2 GB with compilers, Python and a typical web project. Settings shows the space used, by
  part, and can clear the caches.

## Config, not code

`linux-workspace.json` holds:

- `images` (URL, SHA-256, version and setup size per ABI);
- the mounts (`deviceMounts`, `workspaceMount`, `sandboxMount`) and proot options;
- shell and environment;
- DNS fallback;
- base setup: `packages`, the `installCommand` that takes them as `{{packages}}`, then `commands` (Node.js);
- caches Settings can clear.

Process limits are in `terminal.json`. proot and tar are pinned in `app/termux-runtimes.lock.json`.

## Risks to check on a real phone

- **Speed:** ptrace costs on every system call. npm and pip are the slowest.
- **The phantom process killer** under a foreground service.
- **OEM kernels and SELinux:** some vendors break proot. Termux's issue tracker lists known ones.
- **Storage pressure:** low-storage phones need a clear warning before installing.
- **Supply chain:** apt, pip and npm fetch code from the internet. That's why installs stay outbound and approved.

## Phases

1. **Foundation (built):**
   - the single SDK-28 build, with the Termux runtimes replaced by proot and tar;
   - `linux-workspace.json`;
   - setup with progress, in onboarding and in Settings;
   - `run_command` inside Ubuntu, with the static guest sandbox;
   - file tools on Linux paths;
   - DNS;
   - device tests in the app's own process.
2. **Processes and preview (built):** the four process tools, the process card, the notification with Stop all, the
   live preview with its console, Node LTS in base setup, and Settings' storage breakdown. `build_web_app`, saved
   apps and APK export were removed: apps are servers. Since then: one shared workspace, per-chat projects, and the
   file browser.
3. **Polish (planned):** loopback-only network in proot, an interactive terminal screen (a PTY with stdin), and
   optional presets (Node, Python, a C toolchain).
