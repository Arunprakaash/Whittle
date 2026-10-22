# Linux workspace

Status: phases 1 and 2 (below) are built. A spike on 2026-10-22 (emulator, Android 16 / API 36, arm64) proved every
step works; phase 3 is a plan.

`run_command` used to run Termux's Android builds of Node, Python and git, shipped in the APK. That covered scripts,
but not what a coding agent expects from a computer: no apt, no glibc, no compiler, package commands had to go
through their interpreter, and every command was killed when it ended, so nothing could be served. Now Ubuntu is
Whittle's only runtime, downloaded during onboarding. Commands and builds run inside it; later phases let servers
keep running and show what they serve in a live preview. The APK ships only proot and tar.
Everything still runs on the phone: no server, no account.

| Piece | Where it runs |
| --- | --- |
| `run_command`, package installs, builds, servers | Inside Ubuntu, behind the guest sandbox |
| Apps | Vite projects the agent runs with `start_process`, shown in the live preview. There's no separate build tool, no saved apps and no APK export |
| `read_file`, `write_file`, `edit_file`, `list_files` | Kotlin, on Linux paths mapped into the Ubuntu folder (see File tools) |
| `web_fetch`, MCP connections, memory, policy, APK export | Kotlin, unchanged: they need Android's keystore, cards and provenance |

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
without root access. This is the standard technique behind Termux's proot-distro, UserLAnd and Andronix. The
spike uses Termux's own `proot` package, pinned like our other runtimes, and Ubuntu's official base image.
Nothing comes from Mobile-Harness.

| Option | Why not |
| --- | --- |
| Termux runtimes only (today) | Android's bionic libc: no glibc binaries, no apt, many pip and npm packages fail |
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

The cost of targeting SDK 28, all to check:

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
proot -r <rootfs> -b /dev -b /proc -b /sys -b <chat workspace>:/workspace -w /workspace \
  /usr/local/libexec/whittle/sandbox [--network] -- /bin/bash -lc "<command>"
```

- **proot runs unfiltered.** It needs ptrace and process_vm_readv itself. The sandbox filter is installed inside the
  guest, so it applies to the command and its children only. That's the order the spike verified.
- **The guest sandbox:** the same `whittle_sandbox.c`. Built as a static arm64 binary with the NDK, so it needs no
  libc from the image, and bind-mounted read-only into the guest. (The spike built it with the image's gcc; the
  static NDK build still needs checking.)
- **What the guest sees:**
  - the root filesystem;
  - this chat's workspace as `/workspace`;
  - `/dev`, `/proc` and `/sys`;
  - nothing else of the app's storage: no other chats, no keys, no databases.
- **Honesty about proot:** it is a filesystem view, not a security boundary. Protection still comes from three
  layers, as today:
  - Android's per-app sandbox;
  - the seccomp filter: no ptrace, no process_vm, no io_uring, and no sockets when offline;
  - the policy engine's approvals.
- **To verify:** a guest process can't read Whittle's own memory through `/proc/<pid>/mem`. Release builds aren't
  dumpable, which should refuse it.
- The guest runs as fake root (`proot -0`), so apt works. That gives no extra power outside the guest.

## File tools

The file tools see the same Linux the commands do. Ubuntu is an ordinary folder on the phone, so Kotlin reads and
writes it directly: about 2 ms a read on the emulator, against about 50 ms through proot. Both see the same bytes,
and a write from either side shows up on the other at once.

- **One set of paths:** the agent only uses Linux paths.
  - `/workspace/...` maps to the chat's folder, which lives outside Ubuntu, so Reset and Remove never touch your
    files.
  - Everything else maps into the Ubuntu folder.
  - Tool results show Linux paths too.
- **Links resolve the way Linux would, inside Ubuntu.**
  - An absolute link target starts at Ubuntu's root, never the phone's.
  - `..` stops at that root.
  - proot's stand-ins for hard links (`--link2symlink`) are followed like any link.
  - Nothing outside Ubuntu and the chat's folder is reachable.
- **Not covered:** `/proc`, `/dev` and `/sys` are live views passed in from Android, not files in the folder. Only
  `run_command` sees them.
- **Owners:** inside Linux, files look owned by root, because proot pretends; on disk, the app owns them. Only the
  label differs.

## Network control

The model stays the same: the `network` flag on each command, enforced by the guest sandbox, and decided by the
policy engine. That means a card for untrusted models and tainted runs.

- apt, pip and npm installs need network, so they're outbound, as `npm install` is today.
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
| `start_process` | Starts a command in the background (name, command, network); returns an id |
| `read_process_output` | The latest output, or output since an offset |
| `list_processes` | What's running in this chat, with uptime and listening ports |
| `stop_process` | Stops it and its children |

- **Lifetime:** a process belongs to a chat. It stops when the chat is deleted or archived, on app update, or at the
  configured limits. The limits come from config: processes per chat, total processes, run time, output buffer.
- **Kept alive by the run notification.** While anything runs, the existing foreground notification says so and
  has a Stop all action.
- **Output:** each process logs to its own capped file in `.whittle/processes/<id>.log`, which the tools read in
  sections like `.whittle/outputs` does today.
- **Android's phantom process killer (12+):**
  - It allows at most 32 child processes across all apps.
  - It kills CPU-heavy children of background apps.
  - npm and Vite spawn several processes, and proot adds one. Keep counts low and stop idle servers.
  - When something is killed with signal 9, say so plainly and point to the developer-options switch that turns
    the killer off.
  - Whether a foreground service exempts us is unclear. Measure it on a real phone.
- **System prompt:** "for servers, use start_process, then show the preview".

## Localhost preview

- A tool (or an option on `start_process`) opens `http://127.0.0.1:<port>` in the app viewer, the same viewer that
  shows built apps today.
- **The viewer adds:**
  - a "Live" label with the process name;
  - reload;
  - stop;
  - open in browser;
  - console messages, so the agent can read errors.
- The network security config allows cleartext to 127.0.0.1 and localhost only.
- Vite's hot reload uses a WebSocket on the same port. The spike checked serving but not hot reload; check it.
- Apps aren't saved or exported: an app is its project folder plus the server that runs it. When the server stops
  (stopped, or closed with the app), the card or the agent starts it again.

## Setup flow (prototype in HTML first)

- **Where it's offered:**
  - **Onboarding**, right after the model key: what you get, the download size (about 30 MB to start, growing with
    what you install), a Wi-Fi tip, then Set up. It can be skipped.
  - **When a task needs it** and setup was skipped or removed, a card: "Set up a Linux workspace? Downloads about
    30 MB." Approve or not now.
  - **Settings › Linux workspace**, to manage it.
- **Until it's set up,** Whittle still chats, searches the web and uses connections. It just can't run commands.
- **Steps, shown as ordinary Whittle steps:**
  1. Download: checksum pinned in config, resumable.
  2. Unpack: Kotlin gunzips the download into Termux's `tar`, run under `proot --link2symlink -0` so hard links and
     root-owned files unpack as the app (the spike's method).
  3. Configure: `resolv.conf`, `APT::Sandbox::User`, locale, `/workspace`, the guest sandbox.
  4. Base packages: `ca-certificates curl git xz-utils`, then Node.js LTS (pinned, checked by SHA-256) for web apps.
     Python and compilers come later with apt, when a task needs them.
- **Runs under the foreground notification** with progress and cancel, so it survives switching apps.
- **Manage:** see the space used, Repair (re-run configure), Reset (fresh image, workspaces kept), Remove (frees the
  space; commands are unavailable until it's set up again).
- **Storage:** `filesDir/linux`, excluded from backups. The image is stored once; workspaces stay per chat.

## Size

- **APK:** dropping the Termux runtimes and shipping only proot, tar and their libraries took the release APK from
  69 MB to 8.9 MB. Phase 2 removed esbuild's WebAssembly build and APK export with it.
- **Setup (measured on the emulator):** 56 s from scratch, leaving 416 MB (Ubuntu, apt's package lists, git and
  curl; apt's download cache is cleaned). With Node.js in base setup (phase 2), a fresh setup measured on the
  emulator takes 83 s (download, unpack and configure 13 s; packages and Node.js 70 s) and leaves 589 MB: 374 MB
  of Ubuntu, packages and Node.js, and 214 MB of caches, nearly all apt's package lists. `setupSizeBytes` says
  590 MB.
- **On the phone:**
  - 120 MB after setup;
  - about 700 MB with Node;
  - about 2 GB with compilers, Python and a typical web project.
  - Settings shows the space used and offers `apt-get clean`.

## Config, not code

A new `linux-workspace.json` holds:

- `image` (URL, SHA-256 and version per ABI);
- DNS fallback;
- binds;
- environment;
- base packages;
- process limits;
- preview options.

proot is pinned in `termux-runtimes.*.lock.json` like the other runtimes.

## Risks to check on a real phone

- **Speed:** ptrace costs on every system call. npm and pip are the slowest; measure them against Termux's native
  builds.
- **The phantom process killer** under a foreground service.
- **OEM kernels and SELinux:** some vendors break proot. Termux's issue tracker lists known ones.
- **Storage pressure:** low-storage phones need a clear warning before installing.
- **Supply chain:** apt, pip and npm fetch code from the internet, as npm and pip already do today. That's why
  installs stay outbound and approved.

## Phases

1. **Foundation:**
   - the single SDK-28 build, with the Termux runtimes replaced by proot and tar;
   - `linux-workspace.json`;
   - setup with progress, in onboarding and in Settings;
   - `run_command` inside Ubuntu, with the static guest sandbox;
   - file tools on Linux paths;
   - DNS;
   - device tests in the app's own process.
2. **Processes and preview:** the four process tools, the process card, the notification with Stop all, the live
   preview with its console, Node LTS in base setup, and Settings' storage breakdown. `build_web_app`, saved apps
   and APK export were removed: apps are servers.
3. **Polish:** loopback-only network in proot, an interactive terminal screen (a PTY with stdin), and optional
   presets (Node, Python, a C toolchain).

## Spike code (uncommitted)

- `app/build.gradle.kts`: a `linux` build type, which installs as `app.whittle.linux`, targets SDK 28 and uses its
  own runtime spec.
- `app/termux-runtimes.linux.json` and its lock file: the usual runtimes plus `proot` and `tar`.
- `app/src/linux/`: `SpikeShellService`, which runs a script inside the app's process for testing.
- `ToolRuntime.kt`: the data-folder check by device and inode. This is a real fix, worth keeping either way.
