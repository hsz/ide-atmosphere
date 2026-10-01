<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Atmosphere Changelog

## [Unreleased]

### Added

- `RainEffect`: fine rain streaking down in depth layers (nearer drops longer, brighter and faster), leaning with a gentle breeze and splashing where the near drops land; a success eases the rain, a failure brings a stormy gust with a brief brightness-only flash.
- `StarfieldEffect`: a slowly drifting, twinkling night sky with a rare shooting star (cold white on dark themes, soft slate-blue on light ones); a success lifts the stars and launches a shooting star, a failure makes the sky shiver and dim.
- **Reduce motion** setting: every atmosphere runs very slowly at a capped frame rate without success bursts, failure gusts, click puffs or typing boosts.
- **Atmosphere** status bar widget showing the current atmosphere, with a popup to switch to any other one, **Pause for 1 Hour** (a non-persisted snooze, resumable at any time) and a shortcut to the settings page.
- Optional per-theme presets: a different atmosphere for light and for dark themes (**Same as above** by default), applied on theme change.
- Pointer modes **Avoid / Attract / Off** replace the mouse-avoidance checkbox: *Attract* pulls particles towards the pointer and holds them just outside a soft stop radius; the persisted `avoidMouse` flag migrates automatically (`false` → *Off*).
- Click bursts: a mouse press puffs nearby particles outward (passively observed through the toolkit, never consumed); off under Reduce Motion or with the pointer mode *Off*.
- Typing rhythm: keystrokes feed a smoothed flow level (`AtmosphereEffect.setFlow`) that stirs every atmosphere up to 1.5× its speed while typing fast and settles within a few seconds after a pause; **React to typing** toggle.
- Test-run outcomes: `TestResultActivityProducer` publishes one SUCCESS (all passed) or FAILURE (any failed) per run from the test runner's result tree; stopped or empty runs are neutral.
- Commit and push outcomes: a successful commit (`CheckinHandler`) or Git push celebrates, a failed commit or a rejected push shivers. Registered through optional `com.intellij.modules.vcs` / `Git4Idea` descriptors so platform-only IDEs still load the plugin.
- `SnowEffect`: a third atmosphere — layered snowflakes with depth (near flakes larger, brighter and faster), gentle sway and wind, theme-aware white / blue-grey colours, the same idle, activity, success and failure reactions and cursor avoidance as the other atmospheres.
- Optional, smooth mouse avoidance for every atmosphere using passive cursor observation without consuming input; particles within 180 px accelerate away (up to 120 px/s) and glide back afterwards.
- Motion-only outcome reactions: a success lifts the whole field for a moment (fireflies also brighten), a failure scatters the fireflies or sends one gust through petals and snow while everything dims briefly. **Preview success** / **Preview failure** buttons play them on the open windows.
- External-system activity tracking for project sync and task execution, including Gradle, with neutral cancellation.
- Platform investigation write-up (`docs/overlay-investigation.md`) documenting the glass-pane painter approach, repaint cost measurements and rejected alternatives.
- Effect-agnostic core contracts: `AtmosphereEffect`, `AtmosphereEvent`, `AtmospherePalette`, `EffectContext`.
- Plugin metadata and `AtmosphereBundle` message bundle.
- `FirefliesEffect`: 20–50 slow, low-opacity, theme-aware fireflies (structure-of-arrays, allocation-free update/paint, cached alpha color tables, dirty-rect reporting) reacting to `AtmosphereEvent`s (idle calm-down, activity speed-up, success glow, failure scatter); `AtmosphereEffects` in-code registry.
- Rendering infrastructure: glass-pane `AtmospherePainter`, `DirtyRegionCollector` (cell-union merge with cost-model fallback), `FrameBudgetGovernor` (adaptive 10–30 FPS), shared `AtmosphereTicker`, per-frame `FrameAtmosphere` with content-pane/header-band repaint routing, and debug `AtmosphereStats` (`-Dide.atmosphere.debug=true`).
- Lifecycle wiring: app-level `AtmosphereManager` (frame registry, shared ticker ownership, persisted `ide.atmosphere.enabled` flag, pause reasons, event fan-out, palette refresh), `AtmosphereProjectService` + `AtmosphereProjectActivity` attaching the atmosphere to every project frame, and application listeners for theme changes (`LafManagerListener`), Power Save mode and application activation (low-rate mode in background). The `runIde` task enables `-Dide.atmosphere.debug=true`; first sandbox measurements are recorded in `docs/overlay-investigation.md` §10.
- `View | Appearance | Atmosphere` toggle action (`ToggleAtmosphereAction`, state persisted via `PropertiesComponent`).
- IDE-event seam producer: `IdleActivityProducer` turns `IdleTracker.events` into `USER_IDLE` after 30 s without input and `USER_ACTIVE` on the next user event; `AtmosphereManager` fans events out to every frame (or one project) and caps the tick rate while idle (`AtmosphereTicker.calm`).
- README (features, toggle, debug flag, architecture, limitations) and investigation doc §10.2 (cost-reduction changes and second sandbox run).
- Fade-out/fade-in on application (de)activation: `FrameAtmosphere.setVisible` eases a painter-level opacity over 0.5 s, dormant frames are neither simulated nor painted, and the shared ticker stops once every frame is dormant (`AtmosphereTicker.onAllDormant`), so a backgrounded IDE costs nothing.
- Nested single-flush batch repaint: `AtmospherePainter` exposes a one-shot `nestedPaint` hook (paint depth ≤ 2) through which `FrameAtmosphere` paints rectangles 2..n from inside the first `contentPane.paintImmediately`, sharing one back-buffer flush; direct paints remain as a fallback and the split is reported in the stats line (`nested` / `direct`).
- Investigation doc §3 (nested batching re-evaluated with the `RepaintManager.paintDepth` / `BufferStrategyPaintManager` reasoning), §6 (per-strategy measurement table) and §10.3 (smoothness fix, before/after stats).

### Changed

- `CursorAvoidance` gained a `PointerMode` and a `burst` puff; `AtmosphereEffect` gained `onPointerPressed`, `setPointerMode` and `setFlow` (all default no-ops). `FrameAtmosphere.cursorAvoidanceEnabled` became `pointerMode`.
- The plugin description, README and settings comments now cover the five atmospheres, pointer modes, typing, test / commit / push reactions, Reduce Motion, per-theme presets and the status bar widget.
- Adaptive commit for particle repainting: `SnowEffect`, `FirefliesEffect` and `SakuraEffect` now report a particle as dirty only when it moved ≥ 0.4 px, its alpha changed by ≥ 3 of 256 steps or it was stale for 4 ticks (shared `CommitPolicy`, replacing the per-effect `MOVE_EPSILON = 0.02 px`), so slow and twinkling particles skip ticks while fast ones keep the full rate. The `FrameBudgetGovernor` budget dropped from 100 000 to 70 000 µs/s and the debug stats line gained `rects/s`. See `docs/overlay-investigation.md` §13.
- Settings page rebuilt around what matters: atmosphere picker with a one-line description, **Intensity** (Subtle…Vivid) and **Speed** (Calm…Lively) sliders and three behaviour switches. The ADSR envelope editors, their numeric fields and the persisted per-theme/shared reaction profiles are gone; reaction timings are built into each atmosphere.
- Success no longer paints an expanded, green-tinted halo and failure no longer tints particles red — particles keep their colour and shape at all times; outcomes are expressed through motion and brightness only.
- Recalibrated the dirty-region cost model and particle densities (Step 15) from fresh built-in display measurements. The per-rectangle fixed cost was raised from 120 µs to 500 µs to reflect real-world Swing repaint overhead, resulting in ≈ 60 % fewer rectangles per tick and a stable active-user CPU cost of ≈ 10 %. Particle density was lowered to stay near the minimum threshold (≈ 20 fireflies, ≈ 16 petals).
- `DirtyRegionCollector` merges regions by fine-grid bucketing followed by greedy, cost-model-driven agglomeration (fewer, tighter rectangles; a single bounding box whenever it is the cheapest option) instead of a fixed 3×2 cell-union. Recalibrated for the nested batch: per-rectangle fixed cost 120 µs → 500 µs, 12×8 grid, no forced rectangle cap (the cost model alone decides).
- `FirefliesEffect` `AREA_PER_PARTICLE` 45 000 → 60 000 (≈ 24 fireflies on a 1512×949 frame); slower alpha pulse.
- Frame-rate policy: `FrameBudgetGovernor` budget 30 000 → 100 000 µs/s (≈ 10 % of one core) with a 20–30 FPS range starting at 30 (was 10–30 starting at 24); the idle (`calm`) cap is 15 FPS (was 6) because the effect halves its speed at the same time.
- Application deactivation fades the atmosphere out and stops the ticker instead of throttling to 4 FPS; `AtmosphereStats` reports the effective mode as `normal` / `calm` / `fading`.

### Fixed

- The success reaction was barely noticeable. The updraft is now about three times stronger (fireflies lift 70–120 px/s and brighten up to 2.5×, petals and snow rise at up to 100 / 90 px/s with a wider sway and flutter), still motion-only for petals and snow.
- Particles fleeing the cursor left a fading "after shadow": at up to 260 px/s they jumped 10+ px per frame at the 20–30 FPS animation rate. The escape speed cap is now 120 px/s (with a proportionally gentler acceleration), so each frame moves only a few pixels. Dirty-region coverage during avoidance is now covered by a regression test for all atmospheres.

- A Gradle run configuration was reported by both the Run/Debug listener and the external-system listener, producing two outcomes per run (and, for Sakura, two opposing gusts that could cancel each other). External-system process handlers are now left to the external-system producer, and a gust keeps one direction while it is active.
- Aborted builds now finish neutrally instead of producing a failure reaction.
- Intensity changes now immediately affect existing particle opacity and reconcile population on the next tick, clearing removed particles without a resize. Speed scales normal, idle and busy motion independently of reaction envelopes.
- Fireflies hopped from pixel to pixel (whole device pixels on Retina) instead of gliding: positions were rounded to integers, dirty regions were reported only when the rounded position changed, and the governor sat at a 10 FPS floor (6 FPS idle, 4 FPS in the background). `FirefliesEffect` now renders a reused `Ellipse2D.Float` at float centres and radii (relying on the painter's `STROKE_PURE`), reports a region for every visible sub-pixel move (`MOVE_EPSILON = 0.02 px`, 256 alpha steps) and runs at 20–30 FPS.
