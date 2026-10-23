# Native apps

Status: built on `feat/native-apps`, with the check on a real phone still to do (see Open). A spike on 2026-10-23 on
a real phone (vivo V2558, Android 16, arm64) built, signed, installed and ran a Kotlin app made entirely inside
Whittle's Ubuntu. The numbers below come from it.

## Why

When the user asks for an app today, the system prompt (`prompts/agent-system.md`) has the agent make a Vite + React
project, run it as a server and show it in the live preview. That is a website. It only lives inside Whittle, stops
when its server stops, and isn't an app on the phone.

The user wants two separate things:

- **Websites** stay React (Vite), built and previewed as now.
- **Mobile apps** are native Android apps written in Kotlin. They are built on the phone, installed as real apps
  with their own icon and sandbox, and changed by asking Whittle again.

Everything still runs on the phone: no server, no account, no Gradle. The original diagrams planned React Native
mini-apps and a cloud Gradle export (`docs/architecture` d11, d12, phases 5–6 in d18). This replaces that plan.

## Goals

- "Build me an app" produces a native Kotlin app the user can install, open from the home screen and use without
  Whittle.
- The loop is quick enough to iterate: change, build, update in well under a minute after the first time.
- The agent fixes its own build errors and, with the user's tap, the app's crashes.
- An app Whittle makes can't touch Whittle's keys, chats or workspace: it is a separate Android app, in its own
  sandbox.
- iPhone app building can be added later as a second builder without reworking this one (see Later).

## Non-goals (for this version)

- Jetpack Compose, AndroidX or any third-party library in generated apps. They use the Android framework only:
  `Activity`, built-in views and the framework APIs.
- Gradle, Android Studio projects, or publishing to Play.
- Home-screen widgets, and running apps inside Whittle without installing them.
- iPhone apps.

## Anti-goals (a failure even if it works)

- Installing anything without the user's tap. The model never installs; the user does, and Android asks too.
- A website when the user asked for an app, or an app made of a WebView around a website.
- Generated apps that can reach Whittle's data through anything but what the user approved at install, or a
  signing key the model or a command can read.
- A powerful permission (above) installed without the user seeing it named.

## What the spike showed

Ubuntu 24.04 under proot inside Whittle, driven the way `run_command` runs commands:

| Step | Time |
|---|---|
| Install the toolchain once: `openjdk-21-jdk-headless aapt zipalign zip unzip` via apt | 72 s |
| Convert the Kotlin standard library to dex once (`d8`), then cached | 15 s |
| Each build: `aapt2 link` | 0.3–1.7 s |
| Each build: `kotlinc` (cold JVM) | 10–15 s |
| Each build: `d8`, the app's own classes only | 2–3 s |
| Each build: zip, `zipalign`, sign | 2 s |
| **Each build, total** | **17–18 s** |

- Ubuntu's `aapt` package ships an arm64 `aapt2` (Android 14). It can't read the resource table of the API 35
  or 37 `android.jar` ("entry offsets overlap"), but links fine against **API 34**. Apps target API 34 too:
  targeting a higher level than the build compiles against would leave its newer behaviours unchecked.
- Without the cached standard library, `d8` took 18–35 s per build, so that cache matters.
- The installed app (750 KB, mostly the Kotlin standard library, minSdk 26, two dex files) opened with the
  phone's DayNight theme, and its button and text updated as expected.
- Disk: JDK 287 MB, Kotlin compiler 87 MB, `android.jar` 43 MB, `d8.jar` 21 MB.

## How it works

### The flow

1. The user asks for an app. The agent calls `create_app` with a name. Whittle makes a project folder from a
   template in the workspace (a project, as in `use_project`) and focuses the chat on it.
2. The agent writes Kotlin and resources in the project with the existing file tools.
3. The agent calls `build_app`. The first build sets up the toolchain (with a status line, as Linux setup has).
   On errors, the tool result lists them with file and line, and the agent fixes them and builds again.
4. On success the chat shows an **app card**: icon, name, version, the permissions it asks for, and **Install**
   (or **Update**, then **Open**). Tapping Install hands the APK to Android's installer, which asks the user.
5. Changes: the user asks, the agent edits and builds again, and the card offers **Update**.
6. If the app crashes, its report comes back to Whittle, and the card shows it with **Ask to fix**, which sends
   the stack trace to the agent, as the live preview's console does.

### The project

A project folder in the shared workspace, from a template Whittle ships (config, not code):

```
counter/
  whittle-app.json        name, package, version code, created by Whittle
  AndroidManifest.xml
  src/…/MainActivity.kt
  res/                    values/strings.xml, drawable/ic_launcher.xml (vector), …
```

- **Package:** `app.whittle.made.<slug>`, made unique on the phone by `create_app`. The prefix tells Whittle's
  own apps apart (see Crashes).
- **Versions:** `build_app` raises the version code on every build, so Update always works.
- **Icon:** a vector drawable from the template, a rounded square in a colour picked from the name (no letter), which
  the agent may replace.
- **API level:** compiled against and targeting API 34 (above), with `minSdkVersion` 26; both come from config and
  a test keeps the target equal to the pinned platform. The build sets them and the version, so the manifest
  doesn't.
- **Template:** includes the crash reporter (below), so the agent doesn't have to remember it.

### The toolchain

Installed in Ubuntu once per phone, from a new `native-apps.json`, pinned like `linux-workspace.json`'s Node. The
model can't start the download: the first `build_app` without a toolchain answers "not set up", and the chat shows
a **Set up app building** card (size, what it downloads). The user's tap starts it in a foreground service, as Linux
setup does, and the agent's run waits on it like a capability (`requiredCapabilities` / `missingCapability`), with
no change to the policy engine. *(My call: fixed, pinned URLs make a one-time consent card enough.)* Nothing starts
until Linux itself is set up; the card says so. A setup that fails is told to the agent as a failure, with its
reason, not as the user saying no.

What it installs:

- apt: `openjdk-21-jdk-headless aapt zipalign zip unzip`. Both `apt-get` calls wait up to five minutes for dpkg's
  lock (`-o DPkg::Lock::Timeout=300`), since Linux's own top-ups and Claude Code's update use apt too.
- Downloads checked by SHA-256 into `/opt/whittle-apps`: the Kotlin compiler zip (JetBrains releases), `d8.jar`
  (Google's Maven, `com.android.tools:r8`) and API 34's `android.jar`. Shipping them inside the APK isn't an
  option, at about 150 MB.
- The dexed Kotlin standard library is cached next to them, keyed by the compiler's version.
- Settings › Linux shows whether it's set up, with its size, and can remove it like the caches, except while an
  app is building.

### Building

`build_app` runs one script from config inside Linux, through `CommandRunner` and the sandbox, **with the network
off**: a build needs nothing from outside once the toolchain is there. The script runs `aapt2 link`, then `kotlinc`,
`d8` and zip + `zipalign`. It writes an **unsigned** APK to `<project>/build/`. Whittle signs it (below). Two builds
of one project wait for each other, and only the newest three signed APKs stay in `build/`.

Then Android's own parser reads the signed APK, the way the installer will: the package, no `sharedUserId`, the
version Whittle set, the SDK levels from config, and the permissions and powerful declarations (disabled components
included, since an app can enable its own). A build that doesn't match is refused, with what to fix.

The tool result is short JSON: success with the APK's size, version, permissions and powerful declarations, or the
compiler's errors (`file:line: message`, capped). Long output goes to `.whittle/outputs` as other tools' output
does; the card is made from the whole result.

### Signing

The signing key never enters Linux or the workspace. Whittle generates **one RSA key per app** in the **Android
Keystore**, aliased by package, and signs the APK in Kotlin with Google's `apksig` library, which takes any JCA
`PrivateKey`. This was a planned spike in d18 and is the first thing to prove (see Open). *(My call: one key per
app, so two generated apps can't share data through `sharedUserId` or signature permissions.)*

- An app is always signed with its own key, so updates install over earlier versions.
- If the key is lost (app data cleared), the app can't be updated, only removed and installed again. The card
  says so if Android refuses an update for that reason.

### Installing

The card's buttons, never the model:

- **Install / Update:** a `PackageInstaller` session with the signed APK. Android shows its own prompt each
  time. Whittle needs `REQUEST_INSTALL_PACKAGES`, and the user allows "Install unknown apps" for Whittle once;
  the card explains and opens that setting the first time.
- The APK in the workspace can be replaced by anything the model runs, so Install copies it somewhere private and
  checks the copy: first that it's signed by the key Whittle already holds for the app (never a new one), then that
  its facts are still the ones the card shows. That same copy is what's installed.
- **Open:** the app's launch intent.
- **Remove:** Android's uninstall prompt.

Before the first Install, the card lists the app's permissions from its manifest in plain words ("Uses the
internet, the camera"), so the user sees what they are installing. Android still asks at runtime for the
dangerous ones.

### Crashes

The template sets an uncaught-exception handler that sends the stack trace to Whittle through an exported
`ContentProvider` (`app.whittle.apps.crashes`), then lets the app die as usual. Whittle accepts a report only
when the caller is verified by its uid, its package is one Whittle built, and its signing certificate is the key
Whittle already holds for it; checking never makes a key. Any other report is dropped. The card shows the last
crash, and **Ask to fix** sends it to the agent with the app's project folder. Installing a newer build clears it.

### What a generated app may declare

Any permission or service Android allows a normal app. The user decides, with the powerful ones made obvious.
*(The user's call, 2026-10-23: allow everything, warn before install.)*

- **Powerful:** declarations that let an app watch or control the rest of the phone. These are services for
  accessibility, notification listening, device admin, VPN, autofill, input methods and wallpapers, and the
  credential providers, and the permissions `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`,
  `MANAGE_EXTERNAL_STORAGE`, SMS, MMS and WAP push, call log, usage stats and background location.
  `native-apps.json` lists them, each with a plain sentence ("Can read every notification you get"). The card
  shows them under a warning. Install then asks once more in Whittle, listing them, before Android's own prompt.
  Android still makes the user switch most of them on in system settings.
- **Ordinary:** everything else, such as internet, camera, microphone, location, contacts, calendar,
  notifications, vibration and alarms. They're listed on the card, and Android asks at runtime as usual.
- **Refused by `build_app`:** only `sharedUserId`, among declarations. It's deprecated, and with one key per app it
  couldn't share anything anyway.
- **A build from a tainted run** (one that read a web page or another outside source) gets a line on the card
  ("Made after reading content from the web; check what it does before installing"), next to any powerful
  declarations. Taint is per run: the line reflects what the run that built the app read, not the chat's earlier runs.

### Network in generated apps

Apps may use the internet with the framework only (`HttpURLConnection`, `org.json`): HTTPS APIs, including the
user's Supabase through its REST API with the publishable key, which is meant to be public. The template sets
`usesCleartextTraffic=false`. The system prompt routes "an app with sign-in or shared data" to a native app when
the user wants it on the phone, and to a website when they want it in the browser. *(My call.)*

### Policy

| Tool | Risk tier | Why |
|---|---|---|
| `create_app` | `WORKSPACE` | Writes a project folder, like `use_project` |
| `build_app` | `WORKSPACE` | Runs the build in Linux under the command sandbox with **no network**; changes only the project's `build/` |

Installing and the toolchain download aren't tools, so the model can do neither; both are the user's taps.

A chat confined to a project (an automation's) can't call `create_app`, which would make a new project and move
the chat to it, and can call `build_app` only for its own project; both refuse in `use_project`'s words.

### Websites and the system prompt

`agent-system.md` changes from "any app is a Vite project" to two cases:

- **A website** (or web page, web game, dashboard in the browser): Vite + React + TypeScript, `start_process`,
  live preview. Unchanged.
- **A mobile app**, or anything the user wants on the phone's home screen: `create_app`, Kotlin, `build_app`.
  When unclear, the agent asks.

The prompt also carries the rules a model needs for framework-only Kotlin on Android: no AndroidX, layouts in
code or XML, `Activity` lifecycle, storing data in `SharedPreferences` or files.

## Later

- **Faster builds:** keep a Kotlin compiler daemon warm between builds (most of the 17 s), and incremental `d8`.
- **Jetpack Compose:** a template with the Compose runtime and compiler plugin, with its libraries dexed once.
  Slower first build, same flow.
- **Newer `aapt2`:** Termux's arm64 build (16.0.0.4) reads newer platforms, if API 34 becomes a limit.
- **iPhone apps:** after Whittle runs on iOS (KMP). Building them needs a Mac, so either a cloud Mac build with
  the user's Apple account and TestFlight, or apps that run inside Whittle. `build_app` sits behind a
  per-platform builder for that.

## Open

- **To check on a phone:** the whole loop as built, from setup to install, update and a crash report, with signing
  by `apksig` and an Android Keystore key (Whittle targets SDK 28) and the build under the command sandbox with
  sockets blocked. The spike ran the build without the sandbox and signed with `apksigner` and a file key.

- **Updates without a prompt:** `PackageInstaller.SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)`
  (Android 12+) may let Update skip Android's prompt for apps Whittle installed. Whether it works when the
  installer targets SDK 28 is to check on a device.
- **vivo and similar phones** may add their own install checks (virus scan, "unknown source" warnings) on top of
  Android's prompt. To check.
