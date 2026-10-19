# IDE Atmosphere

[![Sponsor](https://img.shields.io/github/sponsors/hsz?style=flat&logo=githubsponsors&label=Sponsor)](https://github.com/sponsors/hsz)

## Overview

IDE Atmosphere adds subtle animated effects to IntelliJ-based IDEs. Choose **Fireflies**, **Sakura**, **Snow**,
**Rain**, **Starfield**, **Autumn Leaves**, **Bubbles** or **Dust Motes**. Effects appear across your project window
without blocking input or changing your code.

> **Free and sponsor-supported.** If you enjoy IDE Atmosphere, consider supporting its development via
> **[GitHub Sponsors](https://github.com/sponsors/hsz)**, **[Ko-fi](https://ko-fi.com/hszanowski)**, or **[PayPal](https://paypal.me/hsz)**.
> You can also find my other projects on [GitHub](https://github.com/hsz).

| Fireflies | Sakura | Snow | Rain |
|---|---|---|---|
| ![Fireflies](docs/media/fireflies.gif) | ![Sakura](docs/media/sakura.gif) | ![Snow](docs/media/snow.gif) | ![Rain](docs/media/rain.gif) |
| **Starfield** | **Autumn Leaves** | **Bubbles** | **Dust Motes** |
| ![Starfield](docs/media/starfield.gif) | ![Autumn Leaves](docs/media/autumn.gif) | ![Bubbles](docs/media/bubbles.gif) | ![Dust Motes](docs/media/dust.gif) |

Dark- and light-theme stills (`<id>-dark.png`, `<id>-light.png`) live next to the GIFs in [docs/media](./docs/media);
the Development table below shows how to regenerate them.

## What it does

- **Covers the project window.** Effects appear over the editor, tool windows, header and status bar, and adapt to
  the window size.
- **Stays out of the way.** Typing, clicks and mouse movement work as usual. Popups and dialogs stay above the effects.
- **Matches your theme.** Every atmosphere has light and dark variants. You can also choose a different atmosphere
  for each theme.
- **Reacts to IDE activity.** Optional reactions reflect builds, tests, background tasks and debugger pauses.
- **Responds to you.** Particles can follow your typing rhythm, avoid or gather around the pointer, and scatter on clicks.
- **Slows down when idle.** Animation pauses when the window is hidden, Power Save mode is on or you snooze it.
- **Offers reduced motion.** Gentler animation without bursts, gusts, click effects or typing boosts.

### Toggle and status bar

Use `View | Appearance | Atmosphere` to show or hide effects on all project windows. Effects are on by default,
and your choice is saved across restarts.

The **Atmosphere** status bar menu lets you switch effects, **Pause for 1 Hour**, resume, open settings or support
the project. The pause ends when you restart the IDE.

### Settings

Open `Settings | Appearance & Behavior | Atmosphere` to customize:

- **Atmosphere**, with a live preview before applying changes.
- **Intensity** (Subtle … Vivid) and **Speed** (Calm … Lively).
- **React to IDE activity** and **React to typing**. Use **Preview success** and **Preview failure** to try reactions
  on your open windows.
- **Mouse pointer** — avoid it, gather around it or ignore it.
- **Pause when the IDE window is in the background** and **Reduce motion**.
- **Light and dark themes** — optionally choose a separate atmosphere for each.

Select **Apply** to save changes, **Reset** to reload saved settings or **Cancel** to discard edits.

### Debug statistics

Start the IDE with `-Dide.atmosphere.debug=true` (the `runIde` Gradle task sets it) and every 10 s `idea.log` gets a
line such as

```
atmosphere: ticks=218 fps=21.7 (governed 24, normal) tick avg=4295µs max=22708µs rects/tick=9.8 (nested 8.8, direct 1.0) px/tick=86541 cpu≈9.34%
```

— ticks in the period, effective and governed FPS, rate mode (`normal` / `calm` / `fading`), tick cost, repaint
rectangles per tick split into those painted inside the nested single-flush batch (`nested`) and directly
(`direct`; a healthy batch shows `nested ≈ rects − 1`), painted user-space pixels per tick, and the tick cost as a
share of one core. No lines are logged while the atmosphere is dormant (application deactivated).

## Architecture

```
AtmosphereProjectActivity ──attach──▶ AtmosphereManager (app service)
AtmosphereProjectService ──dispose─▶   │  frame registry · shared AtmosphereTicker · isEnabled · pause reasons (Power Save, snooze)
LafManager / PowerSave / Activation ──▶ │  publish(event) fan-out · IdleActivityProducer (IdleTracker.events)
ToggleAtmosphereAction ──isEnabled──▶  │  setFlow(level) fan-out · TypingRhythmProducer (passive key observation)
Status bar widget ──switch/snooze──▶   │
                                        ▼
                         FrameAtmosphere (one per IdeFrame)
                           effect.update(dt) ──▶ DirtyRegionCollector ──▶ contentPane.paintImmediately(rect 1)
                                                                      │     └─▶ painter hook ──▶ paintImmediately(rects 2..n) nested
                                                                      └─▶ rootPane.repaint(header rect)
                           IdeGlassPaneImpl ──▶ AtmospherePainter (opacity, depth ≤ 2) ──▶ effect.paint(g, clip)
```

| Package | Contents |
|---|---|
| `core` | `AtmosphereEffect`, `AtmosphereEvent`, `AtmospherePalette`, `PointerMode`, `EffectContext`, `AdsrEnvelope` (smooth reaction envelopes), `CursorAvoidance` (shared avoid / attract steering and click bursts) — effect-agnostic, Swing-only contracts |
| `render` | `AtmospherePainter` (glass-pane painter with fade opacity and the one-shot nested-paint hook), `DirtyRegionCollector` (cost-model region merging), `FrameBudgetGovernor` (adaptive 20–30 FPS), `AtmosphereTicker` (shared Swing timer, calm mode, dormant notification), `AtmosphereStats` |
| `frame` | `FrameAtmosphere` — one effect on one root pane, nested batch repaint routing, passive pointer / click observation, fade-out/fade-in and dormancy |
| `effects` | `AtmosphereEffects` registry, `fireflies/FirefliesEffect`, `sakura/SakuraEffect`, `snow/SnowEffect`, `rain/RainEffect`, `starfield/StarfieldEffect`, and `autumn/AutumnLeavesEffect`, `bubbles/BubblesEffect`, `dust/DustEffect` on the shared `common/DriftingParticleEffect` base (structure-of-arrays, allocation-free hot path) |
| `settings` | Persisted state, the settings page and its `AtmospherePreviewPanel` |
| `project`, `listeners`, `events`, `actions`, `widget` | Lifecycle wiring, platform listeners, idle, typing, build, execution, debugger, background-task, indexing, external-system, test-result producers, `ToggleAtmosphereAction`, the status bar widget |

Adding another atmosphere means implementing `AtmosphereEffect` (or, for independent drifting particles, extending
`DriftingParticleEffect` with spawn, drift and paint hooks) and registering it in `AtmosphereEffects`; adding an
IDE integration means publishing `AtmosphereEvent`s through `AtmosphereManager.publish` (or `publishOutcome` for a
plain success / failure). Neither touches the painter, the ticker or the lifecycle code. The debugger
producer lives behind the optional `com.intellij.modules.xdebugger`
descriptor (`META-INF/ideatmosphere-xdebugger.xml`), so IDEs without it still load the plugin. Background tasks are sampled
from the project's status bar once a second (`BackgroundTaskActivityProducer`), because the platform's task-start
listener is internal API; sampling stops with *React to IDE activity* off and skips ticks while the IDE is in the
background.

## Limitations (proof of concept)

- No atmosphere on floating tool windows, detached editor windows, the Welcome screen, dialogs or popups.
- Activity integration does not observe terminal commands, and background tasks that are not shown in the status bar
  (or finish within about a second) are not noticed. External-system notifications cover Gradle sync/tasks;
  Maven-specific sync is not verified.
- A test run whose process exit code agrees with its result tree reacts twice in quick succession (once from the
  runner, once from the process), which reads as a slightly longer reaction.
- While the overlay is visible, ordinary IDE repaints pay a ≈ 5–10 % overhead (the same as IDE background images).
- CPU cost (1512×949 @2x, 24 fireflies): ≈ 8–11 % of one core while the user is active at 20–24 FPS (smoothness was
  deliberately prioritised over the original 3 % target), ≈ 6 % while the user is idle (15 FPS, half speed), 0 %
  while the IDE application is deactivated. Measurements are recorded in
  [docs/overlay-investigation.md](./docs/overlay-investigation.md) §10.
- The single-flush saving of the nested batch repaint needs Swing's `BufferStrategyPaintManager`
  (`swing.bufferPerWindow=true`; verified on macOS/Metal). On other pipelines every rectangle pays its own blit and
  the governor settles at 20 FPS.

## Support development

Donation links are also available in **Settings → Appearance & Behavior → Atmosphere**. The Atmosphere status-bar
menu includes **♥ Sponsor IDE Atmosphere**, which opens GitHub Sponsors; the same action is available in Find Action.
Donations are optional; all plugin features remain free.

A sponsorship reminder can appear only after at least **10 project openings with Atmosphere enabled** and
**14 days since first use**. Disabled Atmosphere, default projects, headless runs and unit tests do not count.
**Remind me later**, or closing the reminder, snoozes it for **90 days**; **Don't show again**, or selecting a donation
provider in the reminder, stops future reminders permanently. Timing and reminder choices are stored locally in
`ideatmosphere-sponsor.xml` and are not synchronized or sent anywhere.

Marketplace release notes also include a sponsorship notice.

## Development

| Command | Purpose |
|---|---|
| `./gradlew test` | Unit tests (collector, governor, effects, envelopes, producers) and platform tests (`BasePlatformTestCase`) |
| `./gradlew runIde` | Sandbox IDE with the plugin and debug statistics enabled |
| `./gradlew verifyPlugin` | Plugin Verifier against the configured IDE versions |
| `./gradlew build` | Compile, test and package |
| `./gradlew test --tests '*MarketplaceMediaGenerator' -Pide.atmosphere.media.dir=docs/media` | Re-render the screenshots and GIFs in `docs/media` (skipped in ordinary test runs) |
| `./gradlew signPlugin` / `publishPlugin` | Sign and upload a release; credentials come only from environment variables |

Releasing: [`docs/release/RELEASE.md`](docs/release/RELEASE.md) (signing key, Marketplace upload, every later release)
and [`docs/release/LAUNCH-KIT.md`](docs/release/LAUNCH-KIT.md) (trailer and Shorts scripts, social posts, schedule).

## Plugin structure

```
.
├── docs/overlay-investigation.md   Platform investigation and measurements
├── docs/media/                     Screenshots and GIFs for the README and Marketplace listing
├── docs/release/                   Release checklist and launch kit
├── src/main/kotlin/info/chrzanowski/ideatmosphere/
│   ├── core/ · render/ · frame/ · effects/ · project/ · listeners/ · events/ · actions/ · widget/
│   ├── AtmosphereBundle.kt · AtmosphereManager.kt
├── src/main/resources/META-INF/plugin.xml   Plugin manifest (activity, listeners, action, status bar widget)
├── src/main/resources/META-INF/ideatmosphere-xdebugger.xml   Optional debugger descriptor
├── src/main/resources/messages/             Message bundle
├── src/test/kotlin/...                      Tests mirroring the main packages
├── build.gradle.kts · settings.gradle.kts
└── CHANGELOG.md · README.md · LICENSE
```

The plugin logo is `src/main/resources/META-INF/pluginIcon.svg`; Marketplace screenshots come from `docs/media`.

## Links

- [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [JetBrains Marketplace Quality Guidelines](https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html)
