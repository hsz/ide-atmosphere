# Launch kit

Ready-to-use material for launching IDE Atmosphere 1.0.0: the listing pitch, video scripts, social posts, a blog
outline and the schedule. Replace `<MARKETPLACE_URL>`, `<TRAILER_URL>`, `<BLOG_URL>` and `<ISSUES_URL>` once they exist.

---

## 1. Core message

- **One-liner:** *Your IDE, alive.* Soft ambient atmospheres (fireflies, sakura, snow, rain and more) that drift over
  IntelliJ and quietly react to what you do.
- **Three proof points** (repeat them everywhere):
  1. **It reacts:** typing stirs the field, builds speed it up, green tests lift it, red ones make it shiver, and a
     breakpoint makes it hold its breath.
  2. **It never gets in the way:** a passive overlay that never takes a click or a keystroke. It pauses in Power Save,
     fades out when the IDE is in the background, and has a *Reduce motion* setting.
  3. **It's cheap:** dirty-region repainting at 20–30 FPS within a fixed CPU budget.
- **Eight atmospheres:** Fireflies, Sakura, Snow, Rain, Starfield, Autumn Leaves, Bubbles, Dust Motes. They adapt to
  light and dark themes, and you can set one per theme.
- **Tone:** cozy, calm, a bit playful. Never "productivity hack". Let the visuals speak.
- **Hashtags (sparingly):** `#IntelliJIDEA #JetBrains #Kotlin #CozyCoding #DevTools`.

## 2. Recording setup

- **Machine state:** run `./gradlew runIde` with a clean sandbox, the New UI, the default *Dark* theme (*Light* for
  theme-switch shots), and the editor font size at 16–18 (the Presentation Assistant plugin is optional). Hide the
  bookmarks bar and personal project names, turn off notifications (*Do Not Disturb* on macOS) and close unrelated
  tool windows.
- **Demo project:** a small Kotlin/Gradle project with one passing test class, one test you can make fail, and a `main`
  with a breakpoint. Put a short, readable function on screen (no lorem ipsum).
- **Capture:** OBS or Screen Studio at **60 fps**. For YouTube, capture at native Retina resolution and export **4K 16:9**.
  For Shorts, use a **1080×1920** crop of the editor area (or a window sized to 1080×1350 and letterboxed).
- **Atmosphere settings for the camera:** Intensity one notch above default and Speed at default. Particles that look
  calm live can disappear after compression, so check a test export on a phone.
- **Audio:** soft, royalty-free ambient or lo-fi music (YouTube Audio Library, Pixabay Music or Uppbeat), with light
  keyboard foley. No voice-over in the trailer; captions only.
- **Pacing:** hold every reaction for at least 2 s. Use cuts on actions (Enter, Run, the breakpoint gutter click).

## 3. Trailer (YouTube, 60–90 s, 16:9)

| # | Time | Shot | On-screen caption |
|---|---|---|---|
| 1 | 0:00–0:06 | Cold open: a dark editor, nothing moving, then Fireflies slowly fade in. | *Your IDE, alive.* |
| 2 | 0:06–0:14 | Typing a function. The fireflies stir and speed up with the rhythm, then settle when typing stops. | *It feels your rhythm.* |
| 3 | 0:14–0:22 | Click *Build* / run Gradle. The field drifts faster while the build runs. | *It notices when the IDE works.* |
| 4 | 0:22–0:30 | Run tests: green. The whole field lifts and brightens for a moment. | *Green tests lift it…* |
| 5 | 0:30–0:37 | Break one assertion and run again: red. The field shivers and dims briefly. | *…red ones make it shiver.* |
| 6 | 0:37–0:44 | Debug: the breakpoint hits and everything almost freezes; *Resume* lets it breathe again. | *A breakpoint holds its breath.* |
| 7 | 0:44–0:52 | The mouse moves through the field (*Avoid*), then switch the pointer mode to *Attract*. A click puffs the particles away. | *It never takes a click.* |
| 8 | 0:52–1:08 | Montage, about 2 s each: Sakura, Snow, Rain, Starfield, Autumn Leaves, Bubbles, Dust Motes. Switch through the status-bar widget for one of them. | *Eight atmospheres.* |
| 9 | 1:08–1:14 | Theme switch from light to dark: the colours adapt. | *Made for light and dark.* |
| 10 | 1:14–1:20 | Settings page: drag Intensity / Speed while the live preview changes. | *Calm or lively, your call.* |
| 11 | 1:20–1:28 | End card: plugin icon, "IDE Atmosphere", *Free on JetBrains Marketplace*, the URL. | *Free on JetBrains Marketplace* |

**YouTube details**

- Title: `IDE Atmosphere: your IntelliJ, alive (fireflies, sakura, snow & more)`
- Description: the one-liner, the three proof points, `Install: <MARKETPLACE_URL>`, `Feedback: <ISSUES_URL>`, and chapters
  (0:00 Intro, 0:06 Typing, 0:14 Builds, 0:22 Tests, 0:37 Debugger, 0:52 Atmospheres, 1:14 Settings).
- Thumbnail: a dark editor full of Fireflies, with the large text "Your IDE, alive" and the plugin icon. Make a second
  variant with Sakura for an A/B test (YouTube *Test & Compare*).
- Tags: IntelliJ IDEA, JetBrains, plugin, cozy coding, Kotlin, developer tools, IDE theme.
- Pin a comment that asks: "Which atmosphere should come next?"

## 4. Shorts / Reels / TikTok (vertical, 10–20 s)

Each one starts with the hook in the first second, shows one idea, and ends on the plugin name. The caption text goes on
screen and in the post.

| # | Name | Script | Hook caption |
|---|---|---|---|
| S1 | Tests | A quiet editor with Fireflies. Run tests, green, everything lifts. Break one test, run again, red, it shivers. | *My IDE reacts to my tests* |
| S2 | Breakpoint | Debug run, the breakpoint hits, the particles nearly freeze. Resume, and they flow again. | *The debugger makes it hold its breath* |
| S3 | Sakura | Slow typing with Sakura petals drifting. Move the pointer through them and they part. | *Coding under cherry blossoms* |
| S4 | Snow | Snow in the dark theme, then a Gradle build: the snowfall picks up. | *It snows harder when Gradle builds* |
| S5 | Rain | Rain at night (dark theme), with typing in a steady rhythm. | *Rainy-day coding, in your IDE* |
| S6 | Starfield | A shooting star after a green test run. | *Green tests launch a shooting star* |
| S7 | Autumn | Leaves swinging down while you scroll; the mouse wheel sends a gust. | *Scrolling makes it windy* |
| S8 | Bubbles | Bubbles rising, with *Attract* mode pulling them to the cursor. | *My cursor is a bubble magnet* |
| S9 | Dust | Dust motes hanging in the light theme, very slow. Turn on Reduce motion. | *The calmest IDE plugin* |
| S10 | All eight | Status-bar widget: switch through all atmospheres, 1.5 s each. | *Pick your IDE's weather* |

## 5. Social posts (drafts)

### X / Bluesky / Mastodon thread

Attach a GIF from `docs/media` to every post. Keep each under ~5 MB (the generated ones are 0.45–0.7 MB).

1. 🌙 I made my IDE a little more alive.
   **IDE Atmosphere** paints soft fireflies, sakura, snow, rain and more over IntelliJ, and they react to what you do.
   Free on JetBrains Marketplace 👇 `<MARKETPLACE_URL>` *(fireflies.gif)*
2. Green tests lift the whole field. Red ones make it shiver. A breakpoint makes it hold its breath. *(trailer clip or
   tests GIF)*
3. It never takes a click or a keystroke. It's a passive overlay that pauses in Power Save and fades out when the IDE
   is in the background, and Reduce motion is one checkbox away. *(sakura.gif)*
4. Eight atmospheres, and they follow your light or dark theme. You can set a different one per theme. *(snow.gif +
   starfield.gif)*
5. How it works (glass-pane painter, dirty regions, an FPS budget) is in the write-up: `<BLOG_URL>`.
   Feedback and ideas: `<ISSUES_URL>`. Which atmosphere should be next? 🍂🫧✨

Mastodon: add an alt text to every GIF (for example "IntelliJ editor with small glowing fireflies drifting over the
code") and put the hashtags at the end of post 1.

### LinkedIn

> I shipped a small side project: **IDE Atmosphere**, a free plugin for IntelliJ-based IDEs.
>
> It paints calm, ambient atmospheres over the IDE (fireflies, sakura, snow, rain, a starfield and more), and they react
> to your work: typing stirs them, builds speed them up, passing tests lift them, failing tests make them shiver, and a
> breakpoint makes them hold still.
>
> The interesting part was making it **invisible to performance and input**. It's a passive painter on the IDE's glass
> pane that repaints only the pixels that changed, runs within a fixed CPU budget at 20–30 FPS, and never takes a click.
> I wrote up the investigation here: `<BLOG_URL>`
>
> 🎬 Trailer: `<TRAILER_URL>`
> 🧩 Marketplace: `<MARKETPLACE_URL>`
>
> I'd love to hear which atmosphere you'd want next.

(Attach the trailer as a native video, not a link: native video gets more reach. Put the links in the first comment if
reach matters more than clicks.)

### Reddit (r/IntelliJIDEA, r/Jetbrains, r/kotlin; one sub per day)

Check each sub's self-promotion rules first. Post as a GIF or video with a short, honest text and no marketing tone.

- **Title:** `I made a small plugin that puts calm fireflies/snow/sakura over IntelliJ and reacts to tests & builds`
- **Body:**
  > Side project I've been working on: IDE Atmosphere. It's a passive overlay (never takes input), repaints only
  > dirty regions and pauses in Power Save / when the IDE is in the background. Green tests lift the field, red ones
  > make it shiver, breakpoints freeze it. It's free on the Marketplace: `<MARKETPLACE_URL>`.
  > Happy to answer questions about how it paints over the IDE without hurting performance.
- r/kotlin: lead with the technical angle (Kotlin, allocation-free structure-of-arrays particles, ADSR envelopes) and
  link the blog post rather than the Marketplace.

### JetBrains Platform Slack / forum `#showcase`, Kotlin Slack

> 👋 Just released **IDE Atmosphere**: ambient particle atmospheres painted on `IdeGlassPane` via `addPainter`, with
> dirty-region repainting and an adaptive FPS governor. Reactions come from execution / external-system / test / VCS /
> debugger listeners. Notes on what worked and what didn't (nested `paintImmediately` batching, cost model):
> `<BLOG_URL>`. Marketplace: `<MARKETPLACE_URL>`. Feedback very welcome!

### Show HN (only with the blog post)

- **Title:** `Show HN: Painting on the IDE's glass pane without stealing a single click`
- **URL:** `<BLOG_URL>`
- **First comment:** two or three sentences on why you built it, the main technical lesson (repaint cost is dominated by
  the per-rectangle overhead, not by pixels), and a link to the plugin.

## 6. Technical blog post outline

**Title:** *Painting on the IDE's glass pane without stealing a single click*. Source: `docs/overlay-investigation.md`.

1. **Hook:** a GIF of fireflies over the editor. "This costs less than you think. Here's how."
2. **The constraint:** a frame-wide layer that must never take input, never slow typing and never fight the IDE's own
   overlays.
3. **How an IntelliJ frame is built:** root pane, `IdeGlassPaneImpl`, and why you can't replace the glass pane (§1–2).
4. **The supported path:** `IdeGlassPane.addPainter` and the important `needsRepaint()` semantics (§2).
5. **What didn't work:** a table of the rejected alternatives (§3).
6. **Repaint cost is per rectangle, not per pixel:** the dirty-region collector, grid bucketing, and the cost model
   (≈ 500 µs fixed cost per rectangle) (§3, §6, §13).
7. **Nested batch paints:** sharing one back-buffer flush via `paintImmediately`, and `RepaintManager.paintDepth` (§3).
8. **An FPS budget instead of a fixed frame rate:** `FrameBudgetGovernor`, 20–30 FPS, calm mode when idle, and a
   stopped ticker in the background (§10).
9. **Being a good citizen:** Power Save, application deactivation, Reduce motion, and dynamic unload.
10. **Making it feel alive:** ADSR envelopes for idle / busy / paused, motion-only outcomes, and typing flow.
11. **Numbers:** before/after stats lines from the sandbox (§10.2, §10.3), and the CPU cost with an active user.
12. **Try it:** Marketplace link, issues link, and the next atmospheres.

Cross-post to dev.to / Hashnode with a `canonical_url` pointing to your blog.

## 7. Schedule

T0 = the day the Marketplace approves 1.0.0. Aim for **Tue–Thu around 16:00 CET** (EU afternoon, US morning).

| When | What |
|---|---|
| T−7 | Record and edit the trailer. Record S1–S3. Write the blog post. Prepare all posts with placeholders. Create the feedback repo. |
| T−5 | Do the smoke test (`RELEASE.md` §2.2). Make the thumbnails. Upload the trailer to YouTube as **unlisted**. |
| T−3 | Upload 1.0.0 to the Marketplace (`RELEASE.md` §2.3), leaving a buffer for review. |
| T0 | After approval: make the trailer public, add the trailer to the listing, then post the X / Bluesky / Mastodon thread, then LinkedIn, then the Slack communities. |
| T+1 | Reddit r/IntelliJIDEA. Reply to every comment. |
| T+2 | Reddit r/Jetbrains. Post Short S1. |
| T+3 | Publish the blog post and cross-post to dev.to / Hashnode. Post on Reddit r/kotlin (technical angle). |
| T+4–5 | Show HN, only if the post is strong. Post Short S2. |
| T+7–21 | One Short every 2–3 days (S3 → S10). Answer reviews and issues. Ship **1.0.1** with the first fixes (an active changelog helps the listing). |
| T+21 | Review the metrics (below) and pick the next feature. |

### Seasonal hooks for later posts

- **October:** Autumn Leaves ("cozy autumn coding").
- **December:** Snow (holiday post, and the year in review).
- **March–April:** Sakura (cherry-blossom season).
- **Summer nights:** Fireflies and Starfield.
- **Rainy days / exam season:** Rain ("study with me" style long video, 1 h of rain coding).
- Every new atmosphere or reaction: a Short, plus a post in the original thread.

## 8. What to measure

| Metric | Where | What it tells you |
|---|---|---|
| Downloads per day, total, rating | Marketplace *Statistics* | Overall traction and the effect of each post |
| Downloads by IDE / version | Marketplace *Statistics* | Which IDEs and versions to prioritise and verify |
| Trailer views, **average view duration**, retention graph | YouTube Studio | Where people drop off: tighten that part of the trailer |
| Shorts views per atmosphere | YouTube / TikTok / Instagram | Which atmosphere resonates, and which to make the next hero |
| Clicks to Marketplace | YouTube cards, link shorteners (optional) | Which channel converts |
| Issues and feature requests | `<ISSUES_URL>` | Bugs to fix in 1.0.x and the next atmospheres or reactions |

Write the T+21 numbers into a short note in `docs/release/` so the next launch (2.0 or the open-source release) has a
baseline.
