# Overlay investigation: painting a frame-wide, non-interactive layer in IntelliJ

This document records the platform investigation that precedes the first IDE Atmosphere proof of concept.
Its purpose is to answer one question with evidence: **how can a plugin paint a subtle, animated particle
field across the whole project frame (editor, tool windows, header, status bar) using only supported
platform APIs, without ever touching input, editor content, PSI or languages — and at an idle CPU cost that
is hard to notice?**

## Method

Findings come from two sources:

1. Reading the IntelliJ Platform 2025.3.6.1 sources: `IdeGlassPaneImpl.kt`, `IdeRootPane.kt`,
   `PainterHelper.java`, `IdeGlassPane.java`, `Painter` / `AbstractPainter`, `ProjectFrameHelper.kt`, and the
   JBR 25 `JComponent` / `JRootPane` implementations.
2. Probes executed **in a running IDE** (three project frames open): `WindowManager.getFrame(project)`
   component-tree dumps and timed `paintImmediately` calls with a temporary glass-pane painter installed.

Measurement environment: macOS, 1512×949 window @2x (HiDPI), Metal rendering pipeline, JBR 25,
`swing.bufferPerWindow=true` (true double buffering).

## 1. How the main IDE frame is structured

```
IdeFrameImpl (JFrame)
└── IdeRootPane                      non-opaque; isOptimizedDrawingEnabled = !glassPane.isVisible()
    ├── IdeGlassPaneImpl             glass pane (see §2)
    └── JBLayeredPane
        ├── MacToolbarFrameHeader    layer -3, 1512×40, non-opaque (macOS)
        └── content JPanel           opaque, y = 40, 1512×909
            ├── tool window panes
            ├── editor area
            └── status bar
```

- `IdeRootPane` has exactly two children: `IdeGlassPaneImpl` and a `JBLayeredPane`.
- On macOS the layered pane holds the toolbar/header (`MacToolbarFrameHeader`) and the **opaque** content
  pane positioned below it. Status bar, tool windows and editors all live inside the content pane.
- `IdeFrame.getComponent()` returns the root pane. `WindowManager.getIdeFrame(project)` and
  `WindowManager.getAllProjectFrames()` enumerate frames.
- Per `JRootPane`, `isOptimizedDrawingEnabled()` is `false` whenever the glass pane is visible. This matters for
  repaint cost (see §5).

## 2. Is `JRootPane.glassPane` usable, and does IntelliJ own it?

IntelliJ installs its own glass pane, `IdeGlassPaneImpl`, in `ProjectFrameHelper` and **forbids replacing it**:

```kotlin
// IdeRootPane
override fun setGlassPane(glass: Component) {
  check(!fixedGlassPane) { "Setting of glass pane for IdeFrame is prohibited" }
  ...
}
```

So `rootPane.setGlassPane(myComponent)` is not an option. The good news is that `IdeGlassPaneImpl` is designed
to host exactly our kind of layer:

| Property | Value | Consequence |
|---|---|---|
| `isOpaque` | `false` | Content below is composited through. |
| `isEnabled` | `false` | No cursor interference; Swing does not treat it as an input target. |
| `layout` | `null` | Children (if any) are absolutely positioned. |
| AWT mouse listeners | none | Mouse preprocessing goes through an `IdeEventQueue` dispatcher and only for explicitly registered listeners; hit-testing of the real components is unaffected. |
| `isVisible` | managed by `applyActivationState()` | Visible only while it has painters or children; the platform toggles it for us. |

### Public API

- `IdeGlassPane.addPainter(@Nullable Component component, Painter painter, Disposable parent)` — registers a
  painter; `component = null` means the painter is positioned relative to the glass pane itself (offsets are 0),
  i.e. coordinates are in root-pane space.
- `IdeGlassPaneUtil.find(component)` — locates the glass pane for any component in the frame.
- `com.intellij.openapi.ui.Painter` / `AbstractPainter` — the painter contract.
- Removal is deferred: when the parent `Disposable` is disposed, `IdeGlassPaneImpl` removes the painter via
  `SwingUtilities.invokeLater`.

IntelliJ itself uses this mechanism for frame and editor background images (`IdeBackgroundUtil.initFramePainters`),
so it is a first-class, supported path.

### `needsRepaint()` semantics — important

`PainterHelper.runAllPainters` paints **only painters whose `needsRepaint()` returns `true`**.
`AbstractPainter.paint()` resets its internal flag *before* calling `executePaint`, and
`AbstractPainter.setNeedsRepaint(true)` triggers a **full glass-pane `repaint()`**.

Consequences for an animated overlay:

- The overlay painter must **override `needsRepaint()`** to return its own "active" state so it is painted on every
  clip that reaches the glass pane.
- The painter must **never call `setNeedsRepaint(true)` for animation** — doing so would repaint the entire window on
  every tick.
- `executePaint` is invoked for *every* IDE repaint (typing, scrolling, tool window updates), not just for our own
  ticks. It must be cheap: O(particles) with a clip-intersection test and no allocations.

## 3. Alternatives considered and rejected

| Alternative | Why rejected |
|---|---|
| Replace the glass pane with our own component | Prohibited by `IdeRootPane.setGlassPane` (throws). |
| Add our own component to `JBLayeredPane` on a high layer | Works visually, but the component becomes a hit-test target for `IdeGlassPaneImpl.getTargetComponentFor` and DnD logic, and relies on internal layer conventions. |
| Wrap the content pane in a `JLayer` | Wraps platform-owned components, fragile across platform updates, and still does not cover the header band. |
| Separate translucent always-on-top window over the frame | Separate native window: focus/activation issues, z-order fights with popups and dialogs, expensive per-pixel compositing by the OS, not clipped to the frame on resize/move. |
| Nested batch paints (one outer `paintImmediately` containing N inner ones) | *Initially* rejected: 20 nested 32×32 paints measured 2.9 ms vs 3.9–6.9 ms separate — cheaper, but re-entrant painting inside a paint cycle looked unsafe with Swing's `RepaintManager`. **Re-evaluated and adopted in the smoothness fix (§10.3)** — see below. |

**Decision (validated with the user): the overlay is a glass-pane `Painter`** registered via
`IdeGlassPaneUtil.find(rootPane).addPainter(null, painter, parentDisposable)` on the frame's existing
`IdeGlassPaneImpl`. One painter per `IdeFrame`.

### Nested batch paints, re-evaluated

The merged-rectangle strategy turned out to be the wrong lever on the target machine (§6, rows F/G): six
rectangles covering a sixth of the window each cost *more* than one full-window paint, so the governor was pinned at
its floor and the fireflies hopped. Per-particle rectangles are what the cost structure wants, but 24 separate
`paintImmediately` calls pay 24 buffer flushes. Painting rectangles 2..n *from inside* the first paint shares one
flush — and reading the JDK 21 `javax.swing` sources shows why that is legitimate rather than a hack:

- `JComponent._paintImmediately` brackets its work with `RepaintManager.beginPaint()` / `endPaint()`, which are
  **reference-counted** (`paintDepth`, same thread). `BufferStrategyPaintManager` only calls
  `flushAccumulatedRegion()` — one `SubRegionShowable.show()` of the accumulated bounding box — when the depth returns
  to 0; every nested `paint()` merely `accumulate()`s its region into the same back buffer.
- The re-entered path is `IdeRootPane.paintChildren → JBLayeredPane/content → IdeGlassPaneImpl.paintComponent →
  PainterHelper.runAllPainters`. `runAllPainters` is a stateless iteration and painters are never added or removed
  during a paint, so re-entering it at depth 2 is safe; our painter is the last thing painted and only paints.
- The outer graphics is a child `create()`d from the root pane's graphics, so the `bsg.dispose()` performed by the
  nested `prepare()` does not invalidate it.
- Where IntelliJ does not use `BufferStrategyPaintManager`, nested paints degrade to one blit per rectangle — still
  correct, just without the saving.

The implementation bounds the re-entrancy by construction: `FrameAtmosphere` arms a **one-shot** hook on the
painter before painting rectangle 1, the painter consumes it exactly once at depth 1 (after the effect has been
painted and the hints restored), the hook paints rectangles 2..n at depth 2, and the painter ignores any re-entry
beyond `MAX_DEPTH = 2`. If the hook never fires (frame not showing, region obscured, painter detached mid-tick), the
rectangles still pending after the outer call are painted directly — identical result, only slower. Verified in the
live IDE (24 exact clips, depth 2) and by the `nested ≈ rects − 1` column of the stats line.

## 4. Frame lifecycle and safe attach/detach

- A `ProjectActivity` (`postStartupActivity`) runs after the frame exists. From there:
  `WindowManager.getInstance().getIdeFrame(project)` → `IdeGlassPaneUtil.find(frame.component)` →
  `addPainter(null, painter, disposable)`.
- `getIdeFrame(project)` may return `null` (headless, LightEdit, Welcome screen). Attach is skipped in that case.
- Detach = dispose the parent `Disposable` (a project-level service). Because removal is asynchronous
  (`invokeLater`), the painter must flip its `active` flag to `false` **synchronously** first so `needsRepaint()`
  is already `false` before the painter is physically removed.
- Frame reuse (opening a project in the same window) is covered by per-project attach/detach.

## 5. Popups, dialogs, floating tool windows

`JBPopup` (completion, Search Everywhere, …), `DialogWrapper` dialogs, floating tool windows and detached editor
windows are **separate heavyweight windows** with their own root panes. Therefore:

- The overlay never paints over them — particles stay *behind* popups and dialogs, which is the desired behaviour.
- The overlay never affects their input.
- Modal dialogs do not stop Swing timers, so animation continues beneath them.
- Floating/detached windows and the Welcome screen do **not** get an atmosphere in the PoC. Documented limitation.

## 6. Repaint cost (measured)

All numbers: 1512×949 @2x, Metal, JBR 25, `swing.bufferPerWindow=true`.

| Operation | Cost |
|---|---|
| `contentPane.paintImmediately` 1×1 | 0.33–0.49 ms |
| `contentPane.paintImmediately` 32×32 | 0.27–0.36 ms |
| `contentPane.paintImmediately` 200×200 | ≈ 0.48 ms |
| Full content pane (1512×909) | ≈ 7.0 ms |
| 20 separate 32×32 paints | 3.9–6.9 ms |
| 20 paints nested inside one outer paint | 2.9 ms (adopted later, see §3 and the second table) |
| `glassPane.paintImmediately` / `rootPane.paintImmediately` | 6 µs — degrades to an asynchronous `Frame.repaint` because both components are non-opaque; AWT `RepaintArea` then coalesces all UPDATE rectangles into **one** bounding rectangle |

Second measurement session (smoothness fix; same machine, main display, window activated, temporary glass-pane
painter, 24 particle rectangles of 22×22 px):

| Strategy | Cost per tick | Notes |
|---|---|---|
| A. 24 direct `contentPane.paintImmediately` | 6.8 ms | ≈ 0.28 ms per rectangle, one buffer flush each |
| B. 24 `repaint(deepest component)` + `RepaintManager.paintDirtyRegions()` | 4.0 ms | 13 distinct components → fewer flushes |
| **C. 24 paints nested inside the first paint** | **2.9 ms** | ≈ 0.12 ms per rectangle, **one** flush; 24 painter invocations, each with the exact clip of its rectangle, depth 2 (verified) |
| D. single 22×22 paint | 0.32 ms | fixed cost of one flush |
| E. full content pane | 8.6 ms | |
| F. 6 rectangles of 1/6 window, direct (stage-5 strategy) | 11.8 ms | more than a full-window paint |
| G. same 6 rectangles via `repaint` + `paintDirtyRegions` (coalesced to 1) | 8.1 ms | |

### Interpretation

- Cost is dominated by a **fixed per-paint overhead (~0.3 ms, mostly buffer flush)** rather than by area; the
  per-pixel cost is roughly `5 ns / user-space pixel`. The first conclusion drawn from this — merge dirty regions
  into a few large rectangles — was wrong on this machine (row F: 6 large rectangles cost more than a full-window
  paint). The right lever is to make the fixed cost cheap by sharing one flush across all rectangles (row C) and to
  keep rectangles per-particle sized. Cost model used by the dirty-region collector after the fix:
  `cost ≈ 120 µs + area × 5 ns` per rectangle (the nested per-rectangle cost), with a union performed only when it
  is cheaper than two paints; the bucketing grid is 12×8 so a cell can never force a union that costs more than the
  flush it saves.
- `contentPane` is the only opaque ancestor that yields **synchronous** region paints. Rectangles inside the
  content pane are painted with `contentPane.paintImmediately(x - cx, y - cy, w, h)`.
- Regions outside the content pane (the header band above it) are clipped away by `_paintImmediately`; they must use
  `rootPane.repaint(rect)` (asynchronous, coalesced). Keeping those rectangles confined to the header strip prevents
  AWT coalescing from growing to the whole window.
- With a visible glass pane, `JRootPane.isOptimizedDrawingEnabled()` is `false`, so every IDE repaint restarts from
  the root pane (`_paintImmediately` → `getObscuredState` = PARTIALLY_OBSCURED because of the non-opaque glass
  sibling) and composites our painter over it. Measured overhead on ordinary repaints: 20×32² paints 3.9 → 4.2 ms;
  full frame 7.1 → 7.9 ms (≈ 5–10 %). This is the same overhead IntelliJ users with background images already pay.
  Accepted.

### Budget

Original target: ≤ ~3 % of one core while idle (`30 000 µs` of tick cost per second) with a 10–30 FPS governor.
That budget kept the governor at its 10 FPS floor and produced visibly hopping fireflies (§10.3). **Revised with the
user for the smoothness fix:** `100 000 µs/s` (≈ 10 % of one core) governed between **20 and 30 FPS** while the
user is active — with the nested batch a tick costs ≈ 3 ms on the main display, so 30 FPS fits and the floor of
20 FPS keeps the motion fluid before any other lever is touched; **15 FPS** while the user is idle (`USER_IDLE`
halves the speed, so the pixels moved per frame stay the same); **0 %** while the application is deactivated (fade
out, then the ticker stops), in Power Save mode or when the frame is hidden/iconified.

## 7. Other relevant platform facts

| Concern | API | Notes |
|---|---|---|
| Theme detection | `JBColor.isBright()`, `UIUtil.getPanelBackground()` | Public. `StartupUiUtil.isDarkTheme` is `@Internal` and not used. |
| Theme change | `LafManagerListener.TOPIC` | Public application-level topic. |
| User idle/active | `IdleTracker.getInstance().events: SharedFlow<Unit>` | Public app service; emits on every user-initiated AWT event. |
| Reduce motion | — | No platform API in 2025.3. `UISettings.animateWindows` is a tool-window registry flag. |
| Power save | `PowerSaveMode.isEnabled()`, `PowerSaveMode.Listener` | Public; honoured (animation paused). |
| Application activation | `ApplicationActivationListener` | Public; delivered with the `IdeFrame`. Used to fade the atmosphere out (and stop the ticker) when the IDE is in the background and fade it back in on activation. |
| UI coroutines | `Dispatchers.UI` | Public dispatcher for pure-UI coroutines. |
| Named Swing timer | `TimerUtil.createNamedTimer` | Named, coalescing Swing timer for the shared ticker. |
| HiDPI | `JBUIScale`, `Graphics2D` transform | On macOS the `IdeRootPane` UI is `AquaRootPaneUI`; scale 2.0 is carried by the `Graphics2D` transform handed to painters, so painters work in user-space (root-pane) coordinates. |

## 8. Chosen architecture (summary)

1. **Overlay** — `AtmospherePainter : AbstractPainter` registered on `IdeGlassPaneImpl` via
   `IdeGlassPane.addPainter(null, …)`; `needsRepaint()` returns the active flag; never calls `setNeedsRepaint`.
2. **Repaint** — effects report one small dirty rectangle per moved particle (sub-pixel float geometry, so a
   rectangle is reported for every visible move) to a `DirtyRegionCollector` that unites rectangles only where the
   cost model (`120 µs + area × 5 ns` per rectangle) says a union is cheaper than two paints. Content-pane
   rectangles are painted as **one nested single-flush batch**: rectangle 1 via `contentPane.paintImmediately`, the
   rest re-entrantly from the painter's one-shot hook at paint depth 2 (§3), with a direct-paint fallback; header-band
   rectangles use `rootPane.repaint`. Whole-content paints happen only during the 0.5 s fade transitions.
3. **Frame rate** — a `FrameBudgetGovernor` measures tick cost (EMA, fade ticks excluded) and scales FPS between 20
   and 30 to stay under the ≈ 10 % budget; capped at 15 FPS after 30 s without user input (`USER_IDLE`, where the
   effect also halves its speed); frozen when the frame is not showing / iconified or Power Save is on; when the
   application is deactivated every frame **fades to transparent over 0.5 s and the ticker stops** until
   re-activation fades it back in (hidden frames snap instead of fading).
4. **Ticker** — one shared named Swing timer driving all frames with `System.nanoTime()` deltas clamped to 0.1 s;
   notifies its owner once when every frame is dormant so the timer can be stopped.
5. **Lifecycle** — per-project `Disposable` service created from a `ProjectActivity`, mapped 1:1 to the project's
   frame; an app-level `AtmosphereManager` owns the ticker, frame registry and listeners.
6. **Events** — generic `AtmosphereEvent` contract, with only the idle/active producer (`IdleTracker.events`,
   30 s timeout) wired in the PoC; `View | Appearance | Atmosphere` toggles the whole thing (persisted in
   `PropertiesComponent`).

## 9. Known limitations of the PoC

- No atmosphere on floating tool windows, detached editor windows, the Welcome screen, dialogs or popups.
- Ordinary IDE repaints carry a ≈ 5–10 % overhead while the glass pane is visible (same as background images).
- No "reduce motion" setting (no platform API); Power Save mode is the only throttle input besides window state.
- Header-band repaints are asynchronous and coalesced by AWT.
- CPU while the user is active is ≈ 8–11 % of one core on a 1512×949 @2x frame (agreed ceiling ≈ 10 %); the
  governor trades frame rate (down to 20 FPS) for cost before anything else. Idle users cost ≈ 6 %, a deactivated
  IDE 0 %.
- The single-flush saving of the nested batch depends on Swing's `BufferStrategyPaintManager`
  (`swing.bufferPerWindow=true`, verified on macOS/Metal). Elsewhere the batch is still correct but each rectangle
  pays its own blit; the governor absorbs that by dropping to 20 FPS.
- The per-paint cost differs between displays on the same machine (≈ 0.3 ms on the built-in display vs ≈ 1 ms on an
  external one in §10.2), so the 30 FPS target is reached on the former and the governor settles at 20–24 FPS on the
  latter.

## 10. Verification results

### 10.1 First sandbox run (stage 4)

Setup: `./gradlew runIde` (the task passes `-Dide.atmosphere.debug=true`, so `AtmosphereStats` logs every 10 s to
`.intellijPlatform/sandbox/ide-atmosphere/IU-2025.3.6.1/log_runIde/idea.log`), IntelliJ IDEA Ultimate 2025.3.6.1,
JBR 21, Metal pipeline, macOS, 2× HiDPI, Dark theme, `FirefliesEffect` (33 particles on a ≈1512×949 frame).
One project window for the first ~95 s, a second project window for the last ~30 s.

| Period (10 s) | Ticks | Effective FPS | Governed FPS | Tick avg / max | Rects per tick | Painted px per tick | Tick cost ≈ CPU |
|---|---|---|---|---|---|---|---|
| startup + indexing | 111 | 11.1 | 10 | 8.0 ms / 25.1 ms | 6.6 | 481 k | 8.9 % |
| indexing | 97 | 9.7 | 10 | 4.8 ms / 13.5 ms | 5.9 | 479 k | 4.6 % |
| indexing | 97 | 9.7 | 10 | 6.3 ms / 10.1 ms | 6.5 | 606 k | 6.1 % |
| steady, one window | 97 | 9.7 | 10 | 4.6 ms / 8.2 ms | 6.5 | 430 k | 4.5 % |
| steady, one window (EDT busy) | 78 | 7.7 | 10 | 4.0 ms / 8.8 ms | 6.4 | 355 k | 3.1 % |
| steady, one window (EDT busy) | 60 | 6.0 | 10 | 3.9 ms / 11.5 ms | 5.9 | 372 k | 2.3 % |
| second project opening | 98 | 9.7 | 10 | 9.2 ms / 45.5 ms | 6.0 | 582 k | 9.0 % |
| two windows | 97 | 9.7 | 10 | 7.6 ms / 12.7 ms | 6.2 | 517 k | 7.4 % |

"CPU" is the sum of measured tick costs per wall-clock second (the `cpu≈` column of the stats line), i.e. EDT time
spent inside `FrameAtmosphere.tick` including the synchronous `paintImmediately` calls. `ps` on the IDE process
showed 20–30 % during indexing and is not attributable to the plugin.

Observations:

- **Lifecycle works as designed**: the painter attached on project open (`ProjectActivity`), a second project window
  got its own `FrameAtmosphere`, and the IDE shut down cleanly with no exception or leak report from the plugin.
- **The governor is pinned at the 10 FPS floor.** With ~33 particles spread over the frame, the cell-union merge
  yields 6 rectangles covering ≈ 30–40 % of the window every tick (≈ 430–600 k user-space pixels), so a tick costs
  ≈ 4–5 ms in steady state (6 × 0.3 ms fixed + ≈ 2.5 ms of area), which is already above the 30 ms/s budget at
  10 FPS. Steady-state cost is **≈ 3–4.5 % of a core for one window** and ≈ 7 % with two windows — above the ≤ 3 %
  target. The cost model of §6 predicted this order of magnitude (6 × 0.3 ms + 480 k × 5 ns ≈ 4.2 ms).
- Rectangles per tick slightly exceed 6 because rectangles that cross the header/content boundary are split into a
  synchronous content paint and an asynchronous header repaint.
- The effective FPS drops below the governed value when the EDT is busy (indexing, project opening): the coalescing
  timer simply skips ticks, which is the intended behaviour.

Follow-ups suggested by the numbers (not applied in stage 4 to keep the change set reviewable):

1. The dominant term is the painted area, and the area is inherent to covering uniformly spread particles with
   ≤ 6 rectangles. Levers: fewer particles per window (e.g. `area / 70 000`), a lower FPS floor when the user is
   idle (`USER_IDLE` from stage 5 already halves the speed; halving the tick rate as well would halve the cost),
   or temporally staggered updates (commit half of the particles per tick).
2. Reduce the per-tick fixed overhead by allowing the collector to use fewer rectangles when the area saving does
   not pay for the extra flushes (the cost model already supports this; `maxRects` could be 3–4 instead of 6).
3. Skip particles whose alpha pulse changed by less than 1/64 (currently 1/128) — at 10 FPS every particle
   moves ≥ 0.6 px per tick, so the movement threshold alone never filters anything.

Manual checks that still need a human at the keyboard (not verifiable from the log): subtlety of the fireflies in
Light vs Dark theme, resizing, popups (Search Everywhere, completion) and a modal dialog staying on top of the
particles, HiDPI crispness, Power Save pause.

### 10.2 Cost reduction and second sandbox run (stage 5)

Changes applied after the stage-4 numbers (all in the effect-agnostic layer except the last two):

1. **Cost-driven region merging.** `DirtyRegionCollector` now buckets regions into a fine 6×4 grid and then merges
   clusters greedily by the cost model (`300 µs + area × 5 ns`): while more than `maxRects` clusters remain, or while
   joining two clusters is cheaper than two separate paints. Nearby fireflies collapse into one rectangle, distant
   ones stay apart, and a single bounding box falls out automatically when it is the cheapest option. The former
   fixed 3×2 cell-union produced six rectangles of roughly one sixth of the window each regardless of where the
   particles were.
2. **Quantised dirty reporting.** A firefly is drawn at its rounded integer position with one of 64 alpha steps, and
   a region is reported only when that quantised appearance changes. The previous thresholds (0.25 px, 1/128 alpha)
   repainted particles whose pixels had not changed at all. The alpha pulse was also slowed (0.5–1.2 rad/s) so the
   quantised alpha changes less often.
3. **Rate modes.** `USER_IDLE` (30 s without input, from `IdleTracker.events`) caps the tick rate at 6 FPS (`calm`);
   application deactivation throttles to 4 FPS (was 6). Both are applied by the shared ticker, so every frame
   benefits.
4. **Fewer particles.** `AREA_PER_PARTICLE` 45 000 → 60 000 (≈ 24 fireflies on 1512×949 instead of 33; still within
   the 20–50 requirement).

Second run: same machine, `./gradlew runIde`, project reopened automatically, **frame 1400×909 on an external
display**, window mostly *deactivated* (the sandbox was driven from a script, not by hand; the only user event was a
scripted click at ≈ 30 s). Two runs of ≈ 2 and ≈ 1 minutes; the IDE shut down cleanly both times with no plugin
exceptions.

| Period (10 s) | Mode (last tick) | Ticks | Tick avg / max | Rects per tick | Painted px per tick | Tick cost ≈ CPU |
|---|---|---|---|---|---|---|
| startup + indexing | normal (10 FPS) | 111 | 8.6 ms / 21 ms | 6.5 | 240 k | 9.5 % |
| indexing | normal (10 FPS) | 97 | 7.9 ms / 21 ms | 7.1 | 272 k | 7.6 % |
| steady, deactivated (6 FPS, first run) | throttled | 59–60 | 7.5–9.3 ms / 11–15 ms | 5.4–6.2 | 224–290 k | 4.4–5.5 % |
| steady, deactivated (4 FPS, second run) | throttled | 40 | 7.9 ms / 15.5 ms | 4.4 | 217 k | 3.1 % |
| steady, activated by a click | normal (10 FPS) | 89 | 4.8 ms / 12.7 ms | 5.6 | 223 k | 4.2 % |

What changed versus stage 4 and what did not:

- **Painted area per tick dropped by ≈ 40–50 %** (430–600 k → 220–290 k px) and rectangles per tick fell below six
  (4.4–5.6 in steady state) — the merge and quantisation changes behave as designed.
- **Tick cost did not fall proportionally.** In this environment a tick with 5–6 rectangles and ≈ 250 k px costs
  5–9 ms, i.e. ≈ 1 ms per `paintImmediately` rather than the 0.3 ms measured in §6. Two confounders differ from the
  stage-4 run and could not be separated without a person at the keyboard: the frame lived on the external display
  (different scale/compositing path) and was deactivated/occluded for most of the run. The one "normal" steady bucket
  right after activation (4.8 ms) is closer to the §6 model. The fixed per-paint cost is the dominant term either
  way, which is why the rate modes are the lever that actually moves the number.
- **Unattended cost is now ≈ 3 %**: with the IDE deactivated the 4 FPS throttle lands at 3.1 % even with 8 ms ticks.
  The `calm` (6 FPS) cap for an idle-but-active user was **not observed in isolation** in these runs (the throttle,
  which wins over `calm` in the stats label, was active whenever the user was idle), so its effect is inferred:
  ≈ 3–5 % at 5–8 ms ticks, ≈ 2.8 % at the 4.6 ms ticks of stage 4.
- **Active-user cost is still above target**: at the 10 FPS floor a 5–8 ms tick is 5–8 % of a core while the user is
  interacting. Remaining levers, in the order they should be tried: (a) re-measure the per-paint fixed cost on the
  main display with the window activated and, if it really is ≈ 1 ms, raise `DEFAULT_FIXED_COST_NANOS` so the
  collector merges more aggressively (3–4 rectangles); (b) lower the governor floor to 8 FPS; (c) stagger commits
  spatially (only one grid cell of particles commits per tick). None of these requires touching the painter or the
  lifecycle. *(Superseded: the low frame rate these levers would have deepened turned out to be the cause of the
  hopping motion reported after this stage — see §10.3, where the strategy was inverted.)*

Idle producer wiring verified from the code path only (unit-tested with virtual time; `USER_IDLE` after 30 s,
`USER_ACTIVE` on the next event, one event per transition); the stats line reports `calm` when it is the effective
mode.

### 10.3 Smoothness fix (stages 6–9)

**Symptom.** In the stage-5 sandbox the fireflies did not glide: each one sat still and then hopped to the next
position, and the hops were whole Retina-doubled pixels.

**Root cause** (all three confirmed in the sources of the stage-5 build):

1. *Integer-pixel rendering and dirty reporting.* `FirefliesEffect.paint` drew with `roundToInt()` + `fillOval(Int…)`
   and `update` reported a particle only when its rounded position or 64-step alpha index changed. A firefly was
   frozen until its continuous position crossed a pixel boundary and then jumped by exactly 1 user px = 2 device px
   on a 2× display.
2. *Frame rate.* The governor was pinned at its 10 FPS floor (budget 30 000 µs/s, ticks 4–5 ms); `calm` capped at
   6 FPS 30 s after the last input; `throttled` dropped to 4 FPS whenever the application was deactivated — exactly
   the state while watching the sandbox from another window. At 4–10 FPS a 6–18 px/s firefly moves 0.6–4.5 px per
   frame in discrete steps.
3. *Why the governor could not go faster.* The ≤ 6 merged rectangles covered 30–40 % of the window every tick; on
   this machine six rectangles of a sixth of the window cost 11.8 ms — more than one full content-pane paint
   (8.6 ms, §6 rows E/F). Merging into large rectangles was the wrong lever.

**Fix** (decisions validated with the user: ≈ 10 % CPU ceiling at 30 FPS, nested single-flush batch, fade out then
pause on deactivation):

- `FirefliesEffect` renders a reused `Ellipse2D.Float` at float centres and radii (the painter's `STROKE_PURE` makes
  the geometry sub-pixel), reports a region for every visible move (`MOVE_EPSILON = 0.02 px`, 256 alpha steps) with
  per-particle padding; the `AtmosphereEffect` contract is unchanged.
- `DirtyRegionCollector` recalibrated for per-particle rectangles: fixed cost 300 → 120 µs, grid 12×8, no forced
  rectangle cap; unions happen only where cheaper than two paints.
- `FrameAtmosphere` queues content rectangles and paints them as one nested batch (§3): rectangle 1 directly, 2..n
  from the painter's one-shot hook at depth 2, direct fallback for anything still pending.
- `FrameBudgetGovernor` budget 100 000 µs/s, 20–30 FPS starting at 30; `AtmosphereTicker.calm` 15 FPS.
- Application deactivation: `FrameAtmosphere.setVisible(false)` eases the painter opacity 1 → 0 over 0.5 s (one
  whole-content paint per fade tick, fade ticks excluded from the governor), the frame becomes dormant, and the
  ticker stops through `onAllDormant`; activation fades back in. Hidden/iconified frames snap instead of fading.
  Power Save `paused` still wins.

**Third sandbox run** (stage 9): `./gradlew runIde`, IntelliJ IDEA 2025.3.6.1, macOS, Metal, JBR 21, **built-in
display** 1512×949 @2x, 24 fireflies, Dark theme then Light theme, one project window; the user worked in the
sandbox the whole time (browsing, opening Settings, switching the theme), then in a second launch switched to another
application and back several times.

| Period (10 s) | Mode (last tick) | Ticks | Governed FPS | Tick avg / max | Rects per tick (nested / direct) | Painted px per tick | Tick cost ≈ CPU |
|---|---|---|---|---|---|---|---|
| startup + indexing | normal | 176 | 20 | 15.9 ms / 84 ms | 11.5 (9.9 / 1.0) | 108 k | 27.9 % |
| indexing | normal | 189 | 20 | 8.8 ms / 31 ms | 10.1 (9.1 / 1.0) | 121 k | 16.7 % |
| steady | normal | 194 | 22 | 4.8 ms / 13 ms | 9.2 (8.2 / 1.0) | 117 k | 9.4 % |
| steady, user idle | **calm** | 149 | 20 (cap 15) | 4.4 ms / 11 ms | 9.5 (8.5 / 1.0) | 103 k | 6.6 % |
| steady, user active (×6) | normal | 183–195 | 20–21 | 5.5–6.0 ms / 16–40 ms | 9.7–11.1 (8.7–9.8 / 1.0) | 92–126 k | 10.7–11.2 % |
| steady, Light theme | normal | 217–218 | 20–24 | 4.1–4.3 ms / 17–23 ms | 9.5–9.8 (8.5–8.8 / 1.0) | 87–92 k | 8.8–9.3 % |
| second launch, working | normal | 193–202 | 20–24 | 5.4–6.2 ms / 12–93 ms | 10.3–10.7 (9.2–9.3 / 1.0) | 109–114 k | 10.4–12.4 % |
| app switched away and back | **fading** | 220 | 30 | 4.1 ms / 25 ms | 8.8 (7.3 / 1.0) | 199 k | 7.8 % |
| app switched away and back | **fading** | 169 | 30 | 4.6 ms / 13 ms | 5.6 (4.0 / 1.0) | 768 k | 6.9 % |

Before/after, steady state on the same frame:

| | stage 5 (before) | stage 9 (after) |
|---|---|---|
| effective FPS, user active | 9.7 (floor 10) | 18–22 (governed 20–24) |
| effective FPS, user idle | 6 (calm) / 4 (background) | 15 (calm) / **0** (dormant) |
| rendering | integer pixels, 64 alpha steps | sub-pixel float geometry, 256 alpha steps |
| rectangles per tick | 4.4–6.5 large unions, 220–600 k px | 9–11 per-particle rects, 90–125 k px |
| tick cost | 4.6–9 ms | 4.1–6 ms (≈ 0.45 ms per rectangle incl. simulation) |
| tick cost ≈ CPU | 3–4.5 % (idle target ≤ 3 %) | 9–11 % active, ≈ 6.6 % idle, 0 % background (ceiling ≈ 10 %) |

Observations:

- **The batch works as designed**: `nested = rects − direct − header` in every line (`direct` is always exactly
  1.0 — the outer rectangle; the remaining fraction of a rectangle per tick is the asynchronous header-band strip).
  There is no line in which the fallback painted more than the first rectangle.
- **Motion accepted by the user** ("smooth now"): with sub-pixel geometry at 20 FPS the fastest firefly moves
  ≤ 0.9 px per frame, and every frame is a real render rather than a pixel-boundary crossing.
- **The 30 FPS ceiling is not sustained on this frame.** A tick costs 4–6 ms (≈ 0.45 ms per rectangle including the
  simulation and the effect's repeated `paint` per clip) rather than the 2.9 ms the isolated probe suggested, so the
  governor settles at 20–24 FPS to hold the 100 ms/s budget (≈ 9–11 % measured, slightly above the 10 % ceiling
  during heavy interaction; ≈ 9 % in the Light theme). The probe painted a bare painter over a static IDE; in the
  real run every nested paint also repaints the underlying IDE components for its clip. This is consistent with the
  agreed policy (smoothness first, governor lowers the rate before anything else) but should be kept in mind when
  counting particles.
- **`calm` observed in isolation for the first time** (the deactivation throttle no longer exists to mask it):
  149 ticks / 10 s at the 15 FPS cap, ≈ 6.6 % of a core.
- **Fade → dormant → stop verified**: both `fading` periods span ≈ 11.2–11.6 s instead of 10 s — the stats report
  is tick-driven, so the extra 1.2–1.6 s is time in which **no tick ran** (all frames dormant, timer stopped). The
  fade ticks paint the whole content pane (768 k px/tick average in the period with the most switches) and are
  excluded from the governor, which therefore reports 30 after the restart. No exception was logged in either
  launch.
- Startup/indexing periods are noisy (max 84–93 ms ticks) because the EDT is busy with the IDE's own work; the
  coalescing timer simply skips ticks (effective FPS below governed).

Remaining levers if the active-user cost has to come down again without giving up smoothness: reduce the count
(`AREA_PER_PARTICLE` 60 000 → 80 000 ≈ 18 fireflies), skip the effect's `paint` for clips that do not intersect any
particle (already O(particles), but each nested clip walks all of them), or paint the halo and core in one shape.

## 11. Activity Event Producers (Step 14)

The PoC wiring was completed in Step 14 by adding producers for indexing (dumb mode) and builds.

- **Background tasks proxy:** As confirmed in the investigation, the platform lacks a stable "all background tasks" listener. Dumb mode (`DumbService.DumbModeListener`) is used as a proxy for IDE busy-ness. **Known limitation:** non-indexing background work (e.g. VCS operations) is not observed.
- **Unified Build System:** `ProjectTaskListener` is used to track compilation and Gradle/Maven tasks. Updates are wrapped in `invokeLater` to comply with the `AtmosphereManager` EDT contract.

## 12. Active-CPU tuning (Step 15)

The cost model and particle densities were recalibrated in Step 15 based on fresh sandbox measurements (macOS, built-in display, Metal).

### 12.1 Measurements and constants

The recalibration accounted for the real-world cost of repainting underlying IDE components, which was found to be ≈ 4x higher than the isolated probe suggested.

| Constant | Before | After | Rationale |
| :--- | :--- | :--- | :--- |
| `DEFAULT_FIXED_COST_NANOS` | 120 000 | 500 000 | Reflects the real ≈ 580 µs cost per content-pane `paintImmediately`. |
| Fireflies `AREA_PER_PARTICLE` | 60 000 | 72 000 | Reduces particles to ≈ 20 (the floor) to save CPU. |
| Sakura `AREA_PER_PARTICLE` | 70 000 | 90 000 | Reduces particles to ≈ 16 (near the 15 floor). |

### 12.2 Results

Final numbers with the new constants (steady state, project loaded):

| State | FPS | Tick Avg | Rects/Tick | Pixels/Tick | CPU (1 core) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Active user** | 21.1 | 4.8 ms | 4.9 | 328 k | ≈ 10.1 % |
| **Idle (calm)** | 14.4 (est) | 4.8 ms | 4.9 | 328 k | ≈ 6.9 % |
| **Background** | 0 | - | - | - | 0 % |

**Conclusion on goals:** The original ≤ 3 % idle aspiration is **not met** (estimated ≈ 7 %). The dominant cost remains the fixed Swing overhead of the nested `paintImmediately` calls and the area repaints. However, the recalibration successfully reduced the rectangle count per tick by ≈ 60 % (from 12.4 to 4.9) and stabilized the active-user cost at the 10 % budget ceiling while maintaining smooth motion.
