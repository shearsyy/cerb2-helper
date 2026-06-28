Cerberus Helper (RuneLite plugin)
A personal-use RuneLite plugin that:
Counts Cerberus's attacks using her fixed 6-tick attack speed.
Predicts upcoming Combo / Lava / Ghosts attacks the same way the
reference table does (Combo on #1, 11, 21...; Ghosts-slot every 7th;
Lava-slot every 5th), with a small caveat note for the HP gate.
Shows the real Summoned Soul prayer order (Melee/Range/Mage) the
instant the three ghosts spawn, since the order is randomised each time
and can't be predicted - only detected.
Has a resettable, hotkey-bound tick/attack counter (default `F9`).
Auto-resets when Cerberus dies or the fight otherwise ends.
Has an optional "No Ghosts strategy" reminder: hold her above 400 HP
until attack #14, with a warning if you slip below that early.
⚠️ Read this first: account risk
A plugin that predicts/reveals Cerberus's ghost order and attack pattern is
not a new idea - one existed in RuneLite back in 2018 and was removed in
2019 at Jagex's request, alongside an AoE plugin, an in-game Zulrah
Helper, and a Volcanic Mine Helper. Jagex's current third-party client
rules also target plugins that "trivialize" a fight, and only RuneLite and
HDOS are on the Approved Client List - any other client or unapproved
plugin is technically a breach that can carry ban consequences.
This plugin almost certainly would not pass Plugin Hub review for the same
reason the 2018 one was pulled. It's written here purely for personal,
local use at your own risk, the way you asked for. It is not published
anywhere, won't auto-update, and only runs if you build and load it
yourself.
What I could and couldn't verify
I don't have a Java/Gradle/Maven-connected environment to actually compile
or run this against a live RuneLite client, so treat this as a strong first
draft rather than a finished, tested product. Specifically:
NPC IDs (`CERBERUS_NPC_IDS`, `SOUL_MELEE_ID`, `SOUL_RANGED_ID`,
`SOUL_MAGIC_ID` in `CerberusHelperPlugin.java`) come from the OSRS Wiki's
current data. The melee/ranged/magic mapping for the three soul IDs in
particular was inferred from an image-gallery tab order, not an explicit
table - if the prayer-order banner shows the wrong style for a given
color the first time you test it, just swap the constants.
Tick-cycle sync: the counter starts on the first hitsplat Cerberus
lands on you (always part of the opening Triple Attack = attack #1), then
free-runs on her known 6-tick attack speed, resyncing to 0 whenever her
"Aaarrrooooooo" (souls) or "Grrrr..." (lava) overhead text appears. This
should stay accurate through a normal kill, but if you ever see it drift,
hit the reset hotkey at the start of your next pull.
Build/runtime: I followed RuneLite's official `example-plugin`
template structure as closely as I could from its public docs, but
couldn't compile it here. Expect to fix a small build error or two.
Setup (local-only, not Plugin Hub)
Install IntelliJ IDEA (Community is fine) and JDK 11 (Eclipse Temurin).
Clone RuneLite's official template: `github.com/runelite/example-plugin`.
Delete its example `com.example` package contents and copy in this
project's `src/main/java/com/cerberushelper/*.java` files instead (or
just open this folder directly in IntelliJ as the project — the
`build.gradle`/`settings.gradle` here already mirror the template).
Open `CerberusHelperPluginTest.java` (under `src/test/java`) and run its
`main()` method with `-ea` added to the run configuration's VM options
(Run/Debug Configurations → Modify options → Add VM options).
A real RuneLite client window will launch with Cerberus Helper already
enabled. Log in and go fight Cerberus to test it.
Open the plugin's config panel (wrench icon → search "Cerberus Helper")
to change the reset hotkey or toggle the No Ghosts reminder.
Calibrating the No Ghosts strategy
With "No Ghosts strategy reminders" on, the left-side panel will show
Hold >400hp till #14 in green until attack #14 begins, and flip to red
with a warning if Cerberus's health drops below 400 before that. Per the
strategy: bring her to just above 400 HP, then back off (Dinh's bulwark /
Guthan's are both commonly used here) until the panel's attack counter
hits #14, then resume DPS. If you mess up and need to restart the count,
walk back over the entrance flames and press your reset hotkey.
Files
`CerberusHelperPlugin.java` – all tracking logic and event handling.
`CerberusHelperConfig.java` – hotkey + display options.
`CerberusTrackerOverlay.java` – the attack-counter side panel.
`SoulOrderOverlay.java` – the top-center ghost prayer-order banner.
`AttackType.java` / `SoulType.java` – small display enums.
`CerberusHelperPluginTest.java` – local launcher, not part of the plugin
itself.
