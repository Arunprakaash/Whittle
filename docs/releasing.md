# Releasing Whittle

A release is one GitHub release, `vX.Y.Z`, with the phone's APK and the Mac app's `.dmg` attached. Both are built
from the same commit and carry the same version. The repository's releases are immutable: once published, a release
takes no new files and none can be replaced, so it's made as a draft, filled, checked, and only then published.

The phone finds updates through GitHub's latest release and looks for `Whittle-<version>.apk` there
(`updates` in `app-defaults.json`). The landing page links the same file (`apkPattern` in `site/site.json`).
Don't rename it.

## Once, on the machine that makes releases

**The phone**

- [ ] The signing key and `~/.whittle/release-signing.properties` (`storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`). Keep a copy of the keystore somewhere safe: an APK signed with another key won't install over
  the old one.

**The Mac app**

- [ ] A JDK with `jpackage`. Android Studio's has none. Install Temurin 21 (`brew install --cask temurin@21`) and
  name it in `~/.gradle/gradle.properties`:
  ```properties
  whittle.packageJdk=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
  ```
  Homebrew's `openjdk` works too, with `compose.desktop.packaging.checkJdkVendor=false` beside it.
- [ ] Membership in the Apple Developer Program (paid, yearly), for signing and notarizing. Without it the app is
  signed ad hoc: it runs, but every Mac that downloads it says "Apple could not verify Whittle is free of malware"
  until the user allows it once (see [Without a Developer ID](#without-a-developer-id)). The steps below up to
  `mac-signing.properties` need the membership.
- [ ] A **Developer ID Application** certificate in the login keychain. Create it in Xcode › Settings › Accounts ›
  Manage Certificates; only the team's Account Holder can. Check it's there:
  ```bash
  security find-identity -v -p codesigning
  ```
- [ ] An app-specific password for notarizing: account.apple.com › Sign-In and Security › App-Specific Passwords.
- [ ] The team ID, from developer.apple.com › Account › Membership details.
- [ ] `~/.whittle/mac-signing.properties`, never in the repo (`-Pwhittle.macSigningFile` names another path):
  ```properties
  # The certificate's name after "Developer ID Application: "
  identity=Jane Doe (AB12CD34EF)
  appleId=jane@example.com
  password=abcd-efgh-ijkl-mnop
  teamId=AB12CD34EF
  # Optional: a keychain other than the login one
  # keychain=/Users/jane/Library/Keychains/release.keychain-db
  ```
- [ ] Intel Macs: a `.dmg` carries one Java runtime, so it runs only on the kind of chip it was built on. Build an
  `x64` one on an Intel Mac, or say in the notes that the Mac app is for Apple silicon.

## The version

- [ ] On a branch, bump `whittle` in `gradle/libs.versions.toml` (the APK's `versionName` and the Mac app's version)
  and `versionCode` in `app/build.gradle.kts` (one more than the last release's).
- [ ] Open the pull request. CI must pass: tests, release lint, the debug APK, and the `mac` job's `.dmg`.
- [ ] Merge it, then check out `main` at the merge commit with a clean tree (`git status` shows nothing).

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

**The APK**

- [ ] Build it and copy it under its release name:
  ```bash
  ./gradlew :app:assembleRelease
  ```
  ```bash
  cp app/build/outputs/apk/release/app-release.apk dist/Whittle-X.Y.Z.apk
  ```
- [ ] Check it's signed with the release key (the SHA-256 matches the last release's):
  ```bash
  "$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)/apksigner" verify --print-certs dist/Whittle-X.Y.Z.apk
  ```

**The Mac app**

- [ ] Build, sign, notarize and staple it. With `mac-signing.properties` in place this signs with the Developer ID,
  sends the `.dmg` to Apple and waits (usually a few minutes), staples the ticket, then copies it to
  `dist/Whittle-X.Y.Z-arm64.dmg`:
  ```bash
  ./gradlew :companion:releaseDmg
  ```
- [ ] Check the signature, the notarization and Gatekeeper's verdict:
  ```bash
  codesign --verify --deep --strict --verbose=2 companion/build/compose/binaries/main-release/app/Whittle.app
  ```
  ```bash
  xcrun stapler validate dist/Whittle-X.Y.Z-arm64.dmg
  ```
  ```bash
  spctl --assess --type open --context context:primary-signature -vv dist/Whittle-X.Y.Z-arm64.dmg
  ```
  `spctl` must say `accepted` and `source=Notarized Developer ID`.
- [ ] Without a Developer ID, check the ad hoc signature instead (the `codesign` line above must still pass) and
  skip `stapler` and `spctl`, which reject it.
- [ ] If notarizing failed, read Apple's log for the submission it names:
  ```bash
  xcrun notarytool log <submission-id> --apple-id <appleId> --team-id <teamId>
  ```

## Try them

On a phone or emulator that has the last release installed, and a Mac that has never run Whittle:

- [ ] The APK installs over the last release (`adb install -r dist/Whittle-X.Y.Z.apk`) and keeps its chats and
  settings.
- [ ] Open the `.dmg` from a browser download (so it's quarantined, as a user's would be), drag Whittle to
  Applications and open it. Notarized, it opens with no warning; signed ad hoc, it opens after the steps in the
  notes. The Dock and the menu bar say Whittle, with the beaver.
- [ ] On the phone, turn on Settings › Linux workspace › Use from your computer and add the Mac's key. In the Mac app,
  connect by the phone's address: macOS asks once to use the local network, then the chats show. Send a message,
  open a file, change a setting, and check the phone shows the same.
- [ ] Close the Mac app and check nothing is left listening (`lsof -iTCP -sTCP:LISTEN | grep ssh` shows nothing).
- [ ] With a tunnel open, `http://127.0.0.1:8790/` in a browser shows the same screens, served by the phone.
- [ ] The Mac app and the phone must be the same version: the link has no version check yet, so a Mac app from
  one release can misread a phone on another.

## Publish

- [ ] Write the notes in the house style: `## What's new` with a bold lead line per change and the steps to find it,
  then `## Install`:
  ```markdown
  ## Install

  **Phone.** On Whittle 0.24.0 or later, tap **Update** in Settings › About. Otherwise download
  `Whittle-X.Y.Z.apk` on a **64-bit ARM Android 8+ phone**; it installs over earlier versions.

  **Mac.** Download `Whittle-X.Y.Z-arm64.dmg` (Apple silicon, macOS 12 or later), open it and drag Whittle to
  Applications. It shows your phone's Whittle in a window; your phone needs Whittle X.Y.Z too.
  ```
- [ ] Create the release as a draft, with every file at once:
  ```bash
  gh release create vX.Y.Z --draft --title "Whittle X.Y.Z" --notes-file notes.md --target main dist/Whittle-X.Y.Z.apk dist/Whittle-X.Y.Z-arm64.dmg
  ```
- [ ] Open the draft on GitHub. Check the notes read right and both files are there with these exact names and sizes.
- [ ] Publish it:
  ```bash
  gh release edit vX.Y.Z --draft=false
  ```
- [ ] Check it's the latest, with the APK the phone looks for:
  ```bash
  gh api repos/Arunprakaash/Whittle/releases/latest --jq '.tag_name, (.assets[].name)'
  ```
- [ ] On a phone with the last release, Settings › About › Check for updates finds X.Y.Z and installs it.
- [ ] The landing page rebuilds from the release (the `site` workflow); its download button gets the new APK.

## Without a Developer ID

macOS blocks an app that isn't notarized the first time it's opened from a download, and since macOS 15 there's no
right-click way past it. The user allows it once, then it opens like any other app. Put this in the notes' Mac
install steps:

```markdown
Whittle for Mac isn't notarized by Apple yet, so the first time macOS stops it:

1. Open Whittle, and choose **Done** when macOS says it could not verify it.
2. Open System Settings › Privacy & Security, scroll to Security, and choose **Open Anyway** beside Whittle.
3. Open Whittle again and choose **Open Anyway**.
```

## If something's wrong after publishing

The release can't take new files, so don't try to swap one. Fix it on `main`, bump the patch version, and publish
the fix as a new release. It becomes the latest, so the phone's update check and the landing page move to it at once.
Add a line to the broken release's notes saying which version replaces it.
