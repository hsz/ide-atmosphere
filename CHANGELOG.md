<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Atmosphere Changelog

## [Unreleased]

### Added

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

- Recalibrated the dirty-region cost model and particle densities (Step 15) from fresh built-in display measurements. The per-rectangle fixed cost was raised from 120 µs to 500 µs to reflect real-world Swing repaint overhead, resulting in ≈ 60 % fewer rectangles per tick and a stable active-user CPU cost of ≈ 10 %. Particle density was lowered to stay near the minimum threshold (≈ 20 fireflies, ≈ 16 petals).
- `DirtyRegionCollector` merges regions by fine-grid bucketing followed by greedy, cost-model-driven agglomeration (fewer, tighter rectangles; a single bounding box whenever it is the cheapest option) instead of a fixed 3×2 cell-union. Recalibrated for the nested batch: per-rectangle fixed cost 120 µs → 500 µs, 12×8 grid, no forced rectangle cap (the cost model alone decides).
- `FirefliesEffect` `AREA_PER_PARTICLE` 45 000 → 60 000 (≈ 24 fireflies on a 1512×949 frame); slower alpha pulse.
- Frame-rate policy: `FrameBudgetGovernor` budget 30 000 → 100 000 µs/s (≈ 10 % of one core) with a 20–30 FPS range starting at 30 (was 10–30 starting at 24); the idle (`calm`) cap is 15 FPS (was 6) because the effect halves its speed at the same time.
- Application deactivation fades the atmosphere out and stops the ticker instead of throttling to 4 FPS; `AtmosphereStats` reports the effective mode as `normal` / `calm` / `fading`.

### Fixed

- Fireflies hopped from pixel to pixel (whole device pixels on Retina) instead of gliding: positions were rounded to integers, dirty regions were reported only when the rounded position changed, and the governor sat at a 10 FPS floor (6 FPS idle, 4 FPS in the background). `FirefliesEffect` now renders a reused `Ellipse2D.Float` at float centres and radii (relying on the painter's `STROKE_PURE`), reports a region for every visible sub-pixel move (`MOVE_EPSILON = 0.02 px`, 256 alpha steps) and runs at 20–30 FPS.
