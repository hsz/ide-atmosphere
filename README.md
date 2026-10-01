# IDE Atmosphere

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Overview

IDE Atmosphere is an IntelliJ Platform plugin that paints a quiet, living layer across the whole project frame (editor,
tool windows, header and status bar): **Fireflies** on a summer night, **Sakura** petals in a spring breeze or softly
falling **Snow**. It never intercepts input and never changes editor content, PSI or languages. The overlay is a
`Painter` on the frame's existing `IdeGlassPaneImpl`; see [docs/overlay-investigation.md](./docs/overlay-investigation.md)
for the platform investigation behind that choice.

## What it does

- **Three atmospheres on every project window.** Wandering, breathing fireflies; drifting, tumbling Sakura petals;
  or layered snowflakes with depth (near ones larger, brighter and faster). Each covers the whole `IdeFrame` — editor,
  tool windows, empty editor area, toolbar/header band and status bar. The count scales with the window area and the
  field is rebuilt on resize.
- **Never in the way.** The overlay is a `Painter` on the frame's existing `IdeGlassPaneImpl`: passive cursor
  observation never consumes mouse events, enables an input target or changes the cursor. Popups, completion, Search Everywhere,
  dialogs and floating tool windows are separate windows, so particles always stay behind them.
- **Theme aware.** Palette derived from the current LaF (`JBColor.isBright()` + panel background luminance) and
  refreshed on theme change: every atmosphere has a dark and a light variant (warm amber / deep amber fireflies,
  soft pink petals, white / blue-grey snow).
- **Smooth.** Fireflies are rendered at sub-pixel positions (float geometry, antialiasing, `STROKE_PURE`) at a
  governed 20–30 FPS with real `deltaTime`, so they glide instead of hopping between pixels.
- **Runs away from the cursor.** Particles within 180 px of the pointer accelerate smoothly away from it (up to
  260 px/s right under the cursor) and glide back into their own motion once it has moved on. This has its own
  toggle, independent of activity reactions.
- **Reacts to the IDE — with motion, never with colour.** Indexing, project builds, Run/Debug processes and external
  project sync/tasks (including Gradle) speed the particles up while they run. A success makes the whole field rise
  for a moment (fireflies lift and brighten, petals and snow ride an updraft); a failure scatters the fireflies or
  sends one gust through the petals and flakes while everything dims briefly. Cancelled external tasks and aborted
  builds finish neutrally, and a Gradle run is reported exactly once. After 30 s without input (`IdleTracker`)
  particles slow down and animation drops to 15 FPS; input restores normal motion.
- **Cheap when unattended.** One small repaint rectangle per moved firefly, all painted in a single back-buffer
  flush (never a whole-window repaint during animation); an adaptive governor keeps the tick cost under ≈ 10 % of
  one core by lowering the rate to 20 FPS first. When the IDE application is deactivated the atmosphere fades out
  over 0.5 s and the animation timer stops (0 % CPU) until activation fades it back in; fully frozen while the frame
  is hidden or iconified or Power Save mode is on.

### Toggle

`View | Appearance | Atmosphere` shows or hides the effect on all windows. The choice is persisted across restarts
(`PropertiesComponent`, key `ide.atmosphere.enabled`, default on).

### Settings

`Settings | Appearance & Behavior | Atmosphere` is deliberately small:

- **Atmosphere** — Fireflies, Sakura or Snow, with a one-line description of the selected one.
- **Intensity** (Subtle … Vivid) and **Speed** (Calm … Lively) sliders. Intensity changes opacity and population on
  the next animation tick without a resize; speed scales normal, idle and busy motion alike.
- **React to IDE activity**, with **Preview success** / **Preview failure** buttons that play the reactions on the
  open windows right away (they work even while automatic reactions are off and never save anything).
- **Keep particles away from the mouse pointer** and **Pause when the IDE window is in the background**.

Reaction timings are built into each atmosphere and tuned to feel natural; there is nothing to configure there.
Changes stay drafts until Apply, Reset reloads the saved values and Cancel discards them. Everything is persisted in
`ide-atmosphere.xml`.

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
AtmosphereProjectService ──dispose─▶   │  frame registry · shared AtmosphereTicker · isEnabled · pause reasons
LafManager / PowerSave / Activation ──▶ │  publish(event) fan-out · IdleActivityProducer (IdleTracker.events)
ToggleAtmosphereAction ──isEnabled──▶  │
                                        ▼
                         FrameAtmosphere (one per IdeFrame)
                           effect.update(dt) ──▶ DirtyRegionCollector ──▶ contentPane.paintImmediately(rect 1)
                                                                      │     └─▶ painter hook ──▶ paintImmediately(rects 2..n) nested
                                                                      └─▶ rootPane.repaint(header rect)
                           IdeGlassPaneImpl ──▶ AtmospherePainter (opacity, depth ≤ 2) ──▶ effect.paint(g, clip)
```

| Package | Contents |
|---|---|
| `core` | `AtmosphereEffect`, `AtmosphereEvent`, `AtmospherePalette`, `EffectContext`, `AdsrEnvelope` (smooth reaction envelopes), `CursorAvoidance` (shared pointer steering) — effect-agnostic, Swing-only contracts |
| `render` | `AtmospherePainter` (glass-pane painter with fade opacity and the one-shot nested-paint hook), `DirtyRegionCollector` (cost-model region merging), `FrameBudgetGovernor` (adaptive 20–30 FPS), `AtmosphereTicker` (shared Swing timer, calm mode, dormant notification), `AtmosphereStats` |
| `frame` | `FrameAtmosphere` — one effect on one root pane, nested batch repaint routing, fade-out/fade-in and dormancy |
| `effects` | `AtmosphereEffects` registry, `fireflies/FirefliesEffect`, `sakura/SakuraEffect`, `snow/SnowEffect` (structure-of-arrays, allocation-free hot path) |
| `project`, `listeners`, `events`, `actions` | Lifecycle wiring, platform listeners, idle, build, execution, indexing and external-system activity producers, `ToggleAtmosphereAction` |

Adding another atmosphere means implementing `AtmosphereEffect` and registering it in `AtmosphereEffects`; adding an
IDE integration means publishing `AtmosphereEvent`s through `AtmosphereManager.publish`. Neither touches the painter,
the ticker or the lifecycle code.

## Limitations (proof of concept)

- No atmosphere on floating tool windows, detached editor windows, the Welcome screen, dialogs or popups.
- No dedicated Reduce Motion setting; activity integration does not observe arbitrary background actions or terminal
  commands. External-system notifications cover Gradle sync/tasks; Maven-specific sync is not verified.
- While the overlay is visible, ordinary IDE repaints pay a ≈ 5–10 % overhead (the same as IDE background images).
- CPU cost (1512×949 @2x, 24 fireflies): ≈ 8–11 % of one core while the user is active at 20–24 FPS (smoothness was
  deliberately prioritised over the original 3 % target), ≈ 6 % while the user is idle (15 FPS, half speed), 0 %
  while the IDE application is deactivated. Measurements are recorded in
  [docs/overlay-investigation.md](./docs/overlay-investigation.md) §10.
- The single-flush saving of the nested batch repaint needs Swing's `BufferStrategyPaintManager`
  (`swing.bufferPerWindow=true`; verified on macOS/Metal). On other pipelines every rectangle pays its own blit and
  the governor settles at 20 FPS.

## Development

| Command | Purpose |
|---|---|
| `./gradlew test` | Unit tests (collector, governor, effects, envelopes, producers) and platform tests (`BasePlatformTestCase`) |
| `./gradlew runIde` | Sandbox IDE with the plugin and debug statistics enabled |
| `./gradlew verifyPlugin` | Plugin Verifier against the configured IDE versions |
| `./gradlew build` | Compile, test and package |

## Plugin structure

```
.
├── docs/overlay-investigation.md   Platform investigation and measurements
├── src/main/kotlin/info/chrzanowski/ideatmosphere/
│   ├── core/ · render/ · frame/ · effects/ · project/ · listeners/ · events/ · actions/
│   ├── AtmosphereBundle.kt · AtmosphereManager.kt
├── src/main/resources/META-INF/plugin.xml   Plugin manifest (activity, listeners, action)
├── src/main/resources/messages/             Message bundle
├── src/test/kotlin/...                      Tests mirroring the main packages
├── build.gradle.kts · settings.gradle.kts · gradle/libs.versions.toml
└── CHANGELOG.md · README.md
```

The plugin logo is placed in `src/main/resources/META-INF/pluginIcon.svg`.
See [Plugin Logo][docs:logo] for more information and logo requirements.

## Build script

The [build.gradle.kts][file:build.gradle.kts] is the core of the project definition.
It applies three Gradle plugins:

| Plugin                            | Description                                                                      |
|-----------------------------------|----------------------------------------------------------------------------------|
| `org.jetbrains.kotlin.jvm`        | Adds Kotlin support                                                              |
| `org.jetbrains.changelog`         | Simplifies patching the [CHANGELOG.md][file:CHANGELOG.md] file                   |
| `org.jetbrains.intellij.platform` | The [IntelliJ Platform Gradle Plugin][docs:intellij-platform-gradle-plugin-docs] |

The `intellijPlatform` dependencies block selects the IDE to compile against:

```kotlin
intellijIdea("2025.3.6.1")
```

See [Target Versions][docs:target-version] for more information.

The `intellijPlatform` dependencies block also contains a dependency on the platform testing framework:

```kotlin
testFramework(TestFrameworkType.Platform)
```

See [Testing][docs:testing] for more information

## Plugin configuration file

The plugin configuration file is a [plugin.xml][file:plugin.xml] file located in the `src/main/resources/META-INF`
directory.
It provides general information about the plugin, its dependencies, extensions, and listeners.

You can read more about this file in the [Plugin Configuration File][docs:plugin.xml] section of our documentation.

### Plugin ID and name

Generated plugin ID and name may require adjustment.

These values are generated based on _Group ID_ and _Artifact ID_ provided in the IDE Plugin wizard.
It is recommended to review `<id>` and `<name>` elements in the plugin.xml file, and adjust them if needed.

Please note that Gradle properties `rootProject.name` and `project.group` don't need to match the `<id>` and `<name>`
elements.
There is no IntelliJ Platform-related reason they should as they serve different functions.

## Predefined Run/Debug configurations

Within the default project structure, there is a `.run` directory provided containing predefined *Run/Debug
configurations* that expose corresponding Gradle tasks:

| Configuration name  | Description                                                                                                                                                                           |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run IDE with Plugin | Runs [`:runIde`][docs:intellij-platform-gradle-plugin-runIde] IntelliJ Platform Gradle Plugin task. Use the *Debug* icon for plugin debugging.                                        |
| Run Tests           | Runs [`:check`][gradle:lifecycle-tasks] Gradle task.                                                                                                                                  |
| Run Verifications   | Runs [`:verifyPlugin`][docs:intellij-platform-gradle-plugin-verifyPlugin] IntelliJ Platform Gradle Plugin task to check the plugin compatibility against the specified IntelliJ IDEs. |

> [!NOTE]
> You can find the logs from the running task in the `idea.log` tab.

## Publishing the plugin

> [!TIP]
> Make sure to follow all guidelines listed in [Publishing a Plugin][docs:publishing] to follow all recommended and
required steps.

Releasing a plugin to [JetBrains Marketplace](https://plugins.jetbrains.com) is a straightforward operation that uses
the `publishPlugin` Gradle task provided by
the [intellij-platform-gradle-plugin][docs:intellij-platform-gradle-plugin-docs].

You can also upload the plugin to the [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload)
manually via UI.

## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [IntelliJ Platform Gradle Plugin Documentation][docs:intellij-platform-gradle-plugin-docs]
- [IntelliJ Platform Explorer][jb:ipe]
- [JetBrains Marketplace Quality Guidelines][jb:quality-guidelines]
- [IntelliJ Platform UI Guidelines][jb:ui-guidelines]
- [JetBrains Marketplace Paid Plugins][jb:paid-plugins]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij
[docs:plugin.xml]: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html?from=IJPluginReadmeFile
[docs:publishing]: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#runIde
[docs:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#verifyPlugin
[docs:logo]: https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html?from=IJPluginReadmeFile
[docs:target-version]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#target-versions
[docs:testing]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#testing

[file:build.gradle.kts]: ./build.gradle.kts
[file:CHANGELOG.md]: ./CHANGELOG.md
[file:gradle.properties]: ./gradle.properties
[file:plugin.xml]: ./src/main/resources/META-INF/plugin.xml

[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples

[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks

[jb:github]: https://github.com/JetBrains/.github/blob/main/profile/README.md
[jb:forum]: https://platform.jetbrains.com/
[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html
[jb:paid-plugins]: https://plugins.jetbrains.com/docs/marketplace/paid-plugins-marketplace.html
[jb:ipe]: https://jb.gg/ipe
[jb:ui-guidelines]: https://jetbrains.github.io/ui
