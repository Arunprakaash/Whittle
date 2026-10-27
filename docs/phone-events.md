# Phone events, storage and memory review

Status: triggers are built on feat/phone-events (Tasks 1-9); the phone pass and the place-alert check are still to do.
Storage tools and the memory review are on their own branches, not built yet.

Covers roadmap items A2 (event triggers) and the follow-ups the user asked for on
2026-10-24: storage cleanup and a memory of the user. A mail checker was discussed and set aside (see Later).

## Why

Automations (A1) start only at a time of day. A phone knows much more than the time: a text arrived, an app posted
something, the user got home, plugged in for the night, joined their Wi-Fi. Whittle should be able to act on those.

The user also wants two things that ride on the same work:

- **Storage cleanup.** Their phone fills up, and they've already asked Whittle to suggest what to clear. Whittle
  can't see anything outside its own workspace today.
- **A memory of them.** Whittle should learn who they are from using it and bring that into every task. Memory
  exists, but it holds only what the model decides to save mid-chat, which in practice is almost nothing.

## The approach: building blocks, not fixed flows

The user chose to give the agent native building blocks and let it compose the flows, rather than hardcoding a
"storage cleaner" or a "bank SMS logger". A flow is an automation the agent (or the user) sets up: a trigger, a
prompt and its tools. Ready-made flows are recipes in config that the user starts from and changes.

Code is written only where the model can't or mustn't be trusted to do it:

1. **Triggers.** Android wakes an app for these events only through registered components, so each trigger kind
   is code. Which automations use them, and what they do then, is not.
2. **Tools the agent can't make itself.** Reading phone storage and deleting files.
3. **Safety.** Rules the policy engine enforces whatever the prompt says.
4. **The memory review.** The one fixed flow, by the user's choice: after a chat, Whittle proposes what it learned
   about the user and waits for their OK.

## Goals

- An automation's "When" is a time (as today), a repeating interval, or a phone event. Events:
  - **SMS received**, from anyone or from chosen senders.
  - **Notification posted** by a chosen app, optionally only when its text contains given words.
  - **Arrive at or leave a place**, picked on a map or from the current location, with a radius.
  - **Start or stop charging.**
  - **Join or leave a Wi-Fi network**, by name.
  - **Every N minutes** (interval), so a check can run more than once a day.
- The event's details (sender and text, app and notification text, place name, network name) reach the run as
  untrusted content, as pasted output does today, so the run is tainted.
- The agent can set up an event automation from chat ("when my bank texts me, add the amount to expenses.csv"),
  with the same always-ask approval card `schedule_task` has.
- The user can set one up in Settings › Automations, and start from recipes.
- **Storage:** the agent can see what's using space on the phone (totals, largest folders and files, old
  downloads, duplicates, large videos) and delete files the user approves.
- **Memory review:** after a chat, Whittle proposes lasting facts about the user; they're kept only when the user
  says so.

## Non-goals (for this version)

- Reading mail. Gmail's own notifications don't reach this user, so a mail checker needs inbox access (IMAP app
  password, Zapier or Google sign-in). Set aside by the user; see Later.
- Sending SMS, or replying to notifications, from a trigger.
- Clearing other apps' caches. Android doesn't let an app do that; Whittle can open that app's storage page.
- Triggers on calls, Bluetooth, headphones, screen on/off, app opened, battery level, or calendar events. They can
  follow the same pattern later.
- Play Services. Whittle stays installable without it.

## Anti-goals (a failure even if it works)

- **One-time codes reaching the model.** The user allowed any message to fire a trigger, but a message that looks like
  it holds a code is dropped first.
- **A chatty sender or app burning the user's API budget.** Every trigger has a cooldown and a daily cap.
- **A file deleted without the user seeing it listed.** No pre-approval, automation or prompt can get around this.
- **Memories the user didn't agree to.** Nothing the review proposes is used until the user keeps it.
- **Memories planted by a web page, file or message.** The review reads only what the user wrote.
- **A battery drain.** No always-on foreground service or constant location tracking just to watch for events
  (place triggers may fall back to a check every 15 minutes; see Triggers).

## Constraints

- Whittle is sideloaded from GitHub and the app targets SDK 28 (`androidLinuxTargetSdk`, for the Linux sandbox);
  minSdk is 26. Both shape what Android allows below. Check every trigger on Android 16 on the test phone (vivo
  V2558).
- Since Android 8, apps that target 26+ can't receive most implicit broadcasts through manifest receivers.
  `SMS_RECEIVED` is exempt; charging and connectivity broadcasts are not.
- On Android 13+, a sideloaded app's notification access (and on newer versions SMS) is a "restricted setting":
  the user must first tap "Allow restricted settings" in Whittle's app info. Whittle detects this and walks them
  through it.
- Wi-Fi network names are readable only with location permission and location on.
- Config over code, as everywhere in Whittle: limits, recipes, wording the model reads (agent-messages.json), the
  code-masking patterns, and trigger defaults live in config.
- Existing pieces to build on, not duplicate: `Automation`/`Schedule` and `Automations` (store, alarms,
  `rescheduleAll`), `AutomationReceiver`, `AutomationRunner` (unattended runs, retries, foreground service),
  `schedule_task`, Settings › Automations and its editor, `MemoryStore` and Settings › Memory, the approval
  notifications (A3), `pastedOutput` for untrusted content, and the policy engine's rules and risk tiers.

## Triggers

**Model.** An automation's "When" becomes one of: a time of day with weekdays (today's `Schedule`), an interval, or
an event with its filters. Existing automations keep working unchanged after an update.

**What fires.**

| Trigger | Filters | Android needs |
|---|---|---|
| SMS received | senders (any or a list), words in the text | `RECEIVE_SMS` (runtime; restricted setting on newer Android) |
| Notification posted | app (required), words in title or text | notification access (`NotificationListenerService`; restricted setting) |
| Arrive / leave place | place (lat, lng, radius, name) | precise location, all the time; platform proximity alerts, no Play Services |
| Start / stop charging | none | see below |
| Join / leave Wi-Fi | network name (any or one) | location permission for the name |
| Every N minutes | N, with a floor in config (15) | alarms, as today |

How each event reaches Whittle while it isn't running is the builder's call, within the anti-goals: the SMS
receiver can be in the manifest; connectivity can use a `PendingIntent` network callback; charging can't use a
manifest receiver at targetSdk 28, so a job constraint, or a receiver registered from a service Android already
keeps alive (the notification listener, when granted), are the options to weigh. Permissions are asked only when
the user saves an automation that needs them, never up front.

*Built:* texts, notifications and location are capabilities (`sms`, `notification_access`, `location`,
`location_background` in `AndroidCapabilities`) with switches in Settings › On this phone (phone-actions.json), and
the editor asks for them through the shared `rememberCapabilityRequest`; automations.json `events.capabilities` says
which each trigger needs. `EventSources` turns each source on only while an enabled trigger that's allowed (granted,
switch on) needs it: the SMS receiver is a disabled component until then, the notification listener unbinds itself
while it watches no app, and the periodic job runs only for an edge nothing else wakes Whittle for (unplugging,
leaving Wi-Fi, a plug-in while already plugged in, polled places).

**Places, if proximity alerts aren't reliable** *(the user's choice)*: build place triggers on the platform's
proximity alerts first and check them on the test phone early, before the rest. If vivo's battery management keeps
them from firing, fall back to a coarse location check every 15 minutes (interval in config) against the saved
places, only while at least one place automation is on. Arrivals can then fire up to 15 minutes late; the editor
says so and mentions the battery cost. This is the one exception to "no constant location tracking", and it's
limited to that.

**When an event fires.**

- Matching automations start like a scheduled one, through `AutomationRunner`: unattended, with their pre-approved
  tools, retried if a task is running or the user is in the app, given up after the configured time.
- The event's details go into the run as untrusted content, named for what it is ("a text from HDFC-BK"), and as
  personal data, so a run that has them asks before reading the web once anything has steered it.
- **One-time codes:** patterns in config (a 4–8 digit number, or a code of letters and digits, near words like OTP,
  code, verification, PIN, and so on). A text or notification that looks like it holds one is dropped before
  anything is stored or sent to a model; from a messaging app, any of those words is enough. When unsure, drop.
- **Limits per automation:** a cooldown between runs and a daily cap on runs, both in config with defaults (for
  example 2 minutes and 30 a day).
- **Events during the cooldown wait, they aren't lost** (a bank text 30 seconds after another still gets logged).
  They're kept, encrypted, in a small queue that survives the app being killed, and go together into the next run when
  the cooldown ends, so three texts in a minute make one run. The queue has a cap in config (for example 20 events
  per automation); past it the oldest are dropped. *Decided by the designer.*
- **Past the daily cap, events are dropped**, including any still queued, and the automation's row says how many
  and why. *Decided by the designer.*

**The agent's side.** `schedule_task` (or a sibling tool, the builder's call) takes a "when" that can be an event
with its filters. It always asks, like today; the approval card says the event in words ("When you get a text from
HDFC-BK") and what it will do. The model never picks pre-approved tools, as today.

**What an event automation may do on its own.** The same as a scheduled one, except that one started by a text or a
notification only reads, since a stranger writes what it reads *(changed in review)*:

- An automation the agent creates gets the default tools from `automations.json` (`default: true`), as
  `schedule_task` does now; one on a text or a notification gets `events.messageDefaultTools` instead, without the web.
- A recipe brings its own tool list, since the user chose the recipe. No recipe on a message may write: the bank-text
  recipe asks before appending to `expenses.csv`. Choices marked `writes` are refused for message triggers when saved,
  and never pre-approved for their runs.
- A recipe that acts on what a text says (the bank one) needs a sender (`senderRequired`), and the editor warns
  whenever From is empty. A sender matches whole: a number by its digits (one of 8 or more may leave out the country
  code), a sender ID as itself or as one dash-separated part ("HDFCBK" is "VM-HDFCBK"), never as a substring.
- Anything else asks through the approval notification (A3), with Approve and Decline where its tier allows.
- The user can tick more tools in the editor at any time; the approval card for an agent-created event automation
  says which tools it may use on its own and that more can be allowed in Settings.

**The user's side.** The automation editor's When section offers Time, Every, and the events. Each event has its
filter fields, and asks for what it needs when saved. The list row shows the trigger in words and when it last
fired. A trigger whose permission was later revoked, or whose switch is off, says Needs access; in the editor a row
of the same name asks again.

**Recipes** (config, in `automations.json` `ideas`, with their trigger):

- Bank texts → log the amount, merchant and date to `expenses.csv` (SMS, senders the user picks).
- Delivery texts or notifications → one line on what's arriving and when.
- Plugging in at night → a short summary of tomorrow (calendar, reminders).
- Arriving home → reminders tied to home.
- Weekly storage check → see Storage.

## Storage

**Tools** (new, under `tools:android` or wherever phone tools live):

- `storage_overview` (READ): total, used and free; the largest top-level folders; how much is photos, videos,
  downloads, other apps' data where Android tells.
- `find_files` (READ): files matching size, age, type and folder filters, largest first; duplicates by size then
  content hash; capped results.
- `delete_files` (DELETE_OR_PAY): moves the given files to the trash where Android has one, or deletes them. It
  **always asks**, through a policy rule that no pre-approval overrides. The card lists every file with its size
  and the total. A3's rule already keeps DELETE_OR_PAY off notification buttons, so from an automation this opens
  the app.
- `open_app_storage` (WRITE_LOCAL): opens an app's system storage page, for caches Whittle can't clear.

**Access.** Broad file access, which the user agreed to ("All files access"). Which Android grant that is at
targetSdk 28 on Android 11–16 (`MANAGE_EXTERNAL_STORAGE`, or the legacy storage permissions that still apply at 28)
is the builder's call; it must let the tools above see shared storage on Android 16. Asked for only when a storage
tool is first used, with a sentence on why.

**Not in the Linux workspace.** These are native tools; shared storage isn't bound into proot.

**Recipe:** a weekly storage check reports what grew and proposes what to delete, with nothing deleted until the
user approves the card.

## Memory review (the fixed flow)

- **When.** After a chat goes quiet: its task has ended and the user has left it or not written for a while (time in
  config). Only chats where the user wrote something; never automation runs on their own.
- **What it reads.** Only the user's own messages and what they chose (answers, approvals), never tool output,
  web pages, files, notifications or texts. This is what keeps planted memories out.
- **What it does.** One model call, with the current memories, asks for lasting facts about the user that aren't
  already there (and changes to ones that are). The prompt and its limits live in config.
- **Where they go.** Settings › Memory gets a Suggested section: each suggestion with Keep and Dismiss, and an edit
  before keeping. A gentle dot on the Memory row says new ones are waiting; no notification.
- **What's used.** Only kept memories reach the system prompt, as today. Dismissed suggestions aren't proposed
  again.
- **Off switch.** Settings › Memory › Suggest memories after chats, on by default. Each review costs one small model
  call on the user's key.
- The model's own `remember` during a chat stays as it is.

## Safety, in the policy engine

New rules in `policy-rules.json`:

- `delete_files` asks every time, even when pre-approved or unattended.
- Creating or changing an event automation asks every time (extends `automations-always-confirm`).
- Event content is untrusted: a run started by an SMS or notification is tainted from its first step, so sending
  data out afterwards needs approval, as with web content.

## Checking

- Unit tests for: the trigger model (including reading an old automation file), code masking against a set of real
  SMS formats (Indian banks, delivery, OTP), cooldown/cap/batching, filter matching, the storage tools' filtering
  and duplicate detection, the memory review's input (no untrusted content gets in), and the new policy rules.
- On the phone (vivo V2558, Android 16), each trigger fires with Whittle swiped away: a real SMS from another phone,
  a notification from a chosen app, a place (a short walk, or the emulator's mock location if one is used), plug and
  unplug, Wi-Fi off and on. The restricted-settings walkthrough on a fresh grant. A storage overview that matches
  Android's own Storage page roughly, and a delete of test files only.
- Never uninstall Whittle or clear its data on the user's phone.

**What the phone pass found about background limits** (vivo V2558, Android 16, 2026-10-25): with its default
battery setting, vivo freezes Whittle soon after it leaves the screen, and AlarmManager then removes Whittle's alarms
with `Reason=frozen`. That stops scheduled automations as well as event ones. Adding Whittle to the device-idle
whitelist didn't help. Automations run once the phone's own setting ("Allow high background power consumption" on
vivo) is on for Whittle. Whittle doesn't detect this. The Automations screen carries a short note pointing to the
phone's battery settings. Separately, the charging job's "charging" constraint stayed false on slow USB charging.

## Packaging

Three PRs, in order: **triggers** (with interval, code masking, limits, recipes), **storage tools**, **memory
review**. Each is self-contained; the storage recipe lands with the storage PR.

## Left to the builder

- How each event wakes Whittle within the constraints and anti-goals.
- The exact trigger model types and how the old file is read.
- Batching window, default cooldown and cap, and the masking patterns' exact form.
- Whether `schedule_task` grows or a sibling tool is added.
- Duplicate detection details, trash vs delete per Android version, result caps.
- The memory review's "quiet" timing and prompt wording.
- Editor layout, following the existing Settings components and the design system.

## Later

- **Mail checker.** Needs inbox access first: a Gmail app password over IMAP was the suggested route (also works
  for Outlook and others), with Zapier or Google sign-in as alternatives. Once there's access, the checker is a
  recipe on the interval trigger, using memory to judge what matters.
- More triggers (calls, Bluetooth, headphones, battery level, calendar events).
- Home-screen widgets (P2) showing an automation's latest result.
