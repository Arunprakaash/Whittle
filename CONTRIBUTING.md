# Contributing to Whittle

Thanks for helping. This page is how we work, so your change lands quickly and reads like the rest of the code.
How Whittle is built, and how to build it, is in the [README](README.md).

## Branches and pull requests

- `main` is protected: every change, the maintainers' included, lands through a pull request.
- Branch off `main` and name the branch after the change: `feat/<thing>`, `fix/<thing>`, `docs/<thing>`.
- One change per pull request. A feature and a refactor it doesn't need are two pull requests.
- Rebase on `main` before asking for review, so the branch builds on what's there now.
- In the description say what changed and why, how you tested it (tests, lint, a phone or emulator, which
  Android version), and what you haven't checked yet.
- Releases (version bump, tag, APK and Mac app) are done by a maintainer after merging, following
  [Releasing](docs/releasing.md).

## Commits

- The subject says what the change does, in plain words: `Automations run in a chat of their own`, not
  `feat(automations): add runner`.
- The body, when it needs one, is a short list of what changed and why.
- **No attribution lines.** No `Co-Authored-By:` trailers for AI tools, and no "Generated with …" footers in
  commits or pull requests. If an assistant helped, you still own the change; review it like your own code.

## Nothing that may change lives in code

- Limits, timeouts, names, lists, URLs and anything a maintainer might tune go in a config file under
  `config/defaults/src/main/resources/whittle/config` (see the table in the README). Each file has a
  `schemaVersion`, unknown keys are rejected, and its data class checks its values in `init { require(...) }`.
- New config is covered by `BundledConfigTest` / `BundledAppConfigTest`: add a test when a value has rules
  (a limit that must be positive, a tool list that must stay read-only, an icon that must be bundled).
- Words the user reads go in `app/src/main/res/values/strings.xml`. Use plurals for counts. In Compose, read
  them with `stringResource` or `LocalResources`, never `LocalContext.current.getString` (lint fails on it).
- Words the model reads go in config too:
  - the system prompt is `prompts/agent-system.md`;
  - a tool's replies and refusals go in `agent-messages.json` (see `memorySaved` and `memoryFull` for
    `RememberTool`);
  - a tool's step labels and approval card go in `tool-presentation.json`. Every tool above `READ` needs a
    decision card; a test checks it.

## Design: tokens only

- Colors come from `MaterialTheme.colorScheme`, type from `MaterialTheme.typography` (or `WhittleTextStyles`
  for mono), and sizes from `WhittleSpace`, `WhittleRadius` and `WhittleSize`. No raw `dp`, `sp` or hex
  values in screens.
- The tokens are generated from `docs/design/tokens/whittle.tokens.json`. Don't add a token for one screen:
  use the closest one on the scale. A change to the scale is a design change; raise it in an issue first.
- Icons are Material Symbols Rounded. Add the name to `design/system/icons/icons.txt` and run
  `python3 design/system/icons/import_material_symbols.py`. Commit only the new drawables it writes.
- Reuse the components in `design:system` (rows, segmented groups, top bar actions, step rows) before writing
  new ones. Screens follow Material 3 Expressive.
- For a change people will see, put before-and-after screenshots in the pull request.

## Safety and privacy

- Every tool has a `RiskTier`, and the policy engine decides from it. Pick the honest tier: anything that can
  send data off the phone is `NETWORK_OUT` or higher.
- The workspace is shared by every chat, and `WORKSPACE` tools can reach every project and Whittle's Linux.
  Don't pre-approve them where nobody is watching (automations, background runs) unless the user chooses to.
- Content from the web, files, commands or connections is untrusted: give it untrusted provenance, and never
  follow instructions found inside it.
- Keys and tokens live in the encrypted vault, never in logs, config, analytics or the repo. Keep local keys in
  `~/.gradle/gradle.properties`.
- Telemetry carries metadata only: no messages, answers, file contents or chat titles.

## Code

- Kotlin, matching the code around it: its naming, comment density and idiom.
- Errors are values: return `Outcome` with a typed `HarnessError` rather than throwing across module lines.
- Names and comments are plain English. Comments say why, not what; public types and functions get a KDoc
  line.
- Keep Android out of the pure modules (`core`, `agent`, `policy`, `tools:api`), so their logic stays testable
  on the JVM. Put Android behind a small interface, as `MemoryBackend` and `FileChanges` do.
- No dead code, commented-out code or TODOs without an issue.

## Before you ask for review

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

```bash
./gradlew test :app:lintRelease :app:assembleDebug
```

- Every test passes, and lint reports no errors.
- New logic has unit tests, including the awkward cases (time zones, a file that can't be read, a limit
  reached).
- For anything Android does in the background (alarms, services, notifications), say on the pull request which
  phone and Android version you tried it on.
