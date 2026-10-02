---
sessionId: session-261001-221202-sc40
---

# Requirements

### Overview & Goals
Ship **IDE Atmosphere 1.0.0** to the JetBrains Marketplace **Stable** channel as a closed-source, free plugin, with no secrets in the repository or the plugin ZIP, followed by a coordinated launch on YouTube and social media.

### Current state (investigated)
- **No secrets anywhere:** I searched all 12 commits for tokens, passwords and keys and found nothing. There is no git remote yet. Commit metadata contains your two emails (author `jakub@chrzanowski.info`, GPG UID `@jetbrains.com`). This only becomes visible if you publish the repository.
- `.gitignore` only covers `.gradle`, `.idea`, `.intellijPlatform` and `build`. It does **not** cover `.kotlin/`, logs, `.env`, `local.properties` or signing material (`*.pem`, `*.key`, `*.crt`, `chain.crt`). A stray `.output.txt` (tool output) is sitting in the project root.
- There is no `LICENSE` file. `build.gradle.kts` has no `pluginConfiguration` / `signing` / `publishing` / `pluginVerification` blocks, `gradle.properties` says `version=1.0.0-SNAPSHOT`, and `CHANGELOG.md` has a large `[Unreleased]` section.
- Assets are ready: `pluginIcon.svg`, `docs/media/*-dark|light.png` and `*.gif` (from `MarketplaceMediaGenerator`).

### Scope
**In scope**
- Repository hygiene so credentials can never be committed, plus a final secret scan.
- An Apache 2.0 `LICENSE`, prepared now (sources stay private; the Marketplace licence field points to the Apache text).
- Release build: version 1.0.0, change notes from the changelog, signing and publishing from environment variables only, Plugin Verifier on recommended IDEs.
- `docs/release/RELEASE.md`: a step-by-step checklist for the manual Marketplace work.
- `docs/release/LAUNCH-KIT.md`: listing copy, video scripts, social posts and a schedule.

**Out of scope**
- Publishing the sources and CI pipelines (they'd need a hosted repo, so they come later). Beta channel. FUS. Producing the actual videos: you record them, and the kit gives scripts and shot lists.

### Functional Requirements
- `git ls-files` contains no credential-like file, and the ignore rules block future ones.
- `./gradlew buildPlugin verifyPlugin` passes. The ZIP contains only the plugin JAR and its resources (no `docs/`, `.junie/` or media).
- `./gradlew publishPlugin` reads `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` from the environment and works without any file in the repo.
- `CHANGELOG.md` has a `[1.0.0]` section, which also becomes the Marketplace *What's New*.

# Technical Design

### Key Decisions
- **Secrets stay outside the repo.** Values go in environment variables or `~/.gradle/gradle.properties`, never in project files. Rationale: there's no CI yet, and this works the same once GitHub Actions secrets exist.
- **First upload is manual** via plugins.jetbrains.com. `publishPlugin` can only update a plugin that already exists. Every later release runs `./gradlew publishPlugin`.
- **Compatibility:** `sinceBuild` comes from the target platform (`253`), and `untilBuild` stays open (the 2.x Gradle plugin default). The verifier runs against `recommended()` IDEs to catch problems on new builds.
- **Licence:** Apache 2.0 `LICENSE` in the repo now. The Marketplace licence link goes to `https://www.apache.org/licenses/LICENSE-2.0`. No source URL until the code is opened.

### Proposed Changes
**`.gitignore`**: append:
```
.kotlin/
*.log
.output.txt
local.properties
.env*
*.pem
*.key
*.crt
*.p12
*.jks
signing/
```
Then delete the stray `.output.txt`.

**`build.gradle.kts`**: add inside `intellijPlatform { }`:
```kotlin
intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")
        changeNotes = provider {
            changelog.renderItem(changelog.getOrNull(project.version.toString()) ?: changelog.getUnreleased(), Changelog.OutputType.HTML)
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification { ides { recommended() } }
}
```
Also set `changelog { groups.empty(); repositoryUrl unset }` only if it's needed for rendering, and run `./gradlew patchChangelog` to turn `[Unreleased]` into `[1.0.0]`.

**`gradle.properties`**: `version=1.0.0`.

**`LICENSE`**: the standard Apache 2.0 text with `Copyright 2026 Jakub Chrzanowski`.

**`docs/release/RELEASE.md`**: the manual checklist (see the Release Playbook tab).
**`docs/release/LAUNCH-KIT.md`**: copy and schedule (see the Launch Playbook tab).

### Risks
- **Email exposure** if the repo ever goes public. Decide on a `.mailmap` or a history rewrite before the first push.
- **Marketplace review** (about 1–3 business days) may question the glass-pane overlay or the status-bar polling. The README/description already explain both; answer reviewers with `docs/overlay-investigation.md`.
- **Side-project policy:** you're at JetBrains, so check the internal rules for personal Marketplace plugins (vendor name, using the JetBrains brand) before you upload.

# Release Playbook

### One-time setup (you, about 1 hour)
1. **Marketplace account and vendor profile** at plugins.jetbrains.com: personal vendor "Jakub Chrzanowski", URL `https://chrzanowski.info` (it matches `<vendor>`).
2. **Signing key:**
   `openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096`, then `openssl rsa -in private_encrypted.pem -out private.pem`, then `openssl req -key private.pem -new -x509 -days 3650 -out chain.crt`.
   Keep these **outside the project** (for example `~/.secrets/ide-atmosphere/`) and back them up in your password manager. The same key has to sign every release.
3. **Personal access token:** Marketplace → My Tokens, scope *Plugin Upload*. Store it in your password manager.
4. **Shell env** (for example via `direnv` outside the repo, or a password-manager CLI): `export PUBLISH_TOKEN=… CERTIFICATE_CHAIN="$(cat …/chain.crt)" PRIVATE_KEY="$(cat …/private.pem)" PRIVATE_KEY_PASSWORD=…`.

### Release 1.0.0
1. `./gradlew test verifyPlugin signPlugin`. Smoke-test the signed ZIP from `build/distributions/` with *Install Plugin from Disk* in a clean 2025.3 IDE, plus the latest EAP.
2. **First upload (manual):** plugins.jetbrains.com → *Upload plugin*. Choose the signed ZIP, channel Stable, licence Apache 2.0 (link), tags *Theme*, *UI*, *Fun* or *Customization*. Fill in the *Issue tracker* field (see below).
3. **Listing media:** upload 6–8 PNGs from `docs/media` (the dark variants first, 16:10 preferably). Use Fireflies as the hero, then Sakura, Snow, Autumn, Starfield and Rain. Add the YouTube trailer link once it's live.
4. **Wait for approval**, which takes about 1–3 business days. Reply quickly to reviewer emails.
5. **Feedback channel:** the sources are private, so create a public, empty `ide-atmosphere-issues` GitHub repo (issues only) or use an email address. Link it in the listing and the README.
6. After approval: tag `v1.0.0` locally, bump to `1.0.1-SNAPSHOT`, and add a fresh `[Unreleased]` section. All later releases run `./gradlew publishPlugin`.

### Before you ever push the sources
- Re-run the secret scan (`gitleaks detect` over the full history).
- Decide what to do about the commit emails.
- Add the source URL in the Marketplace listing.

# Launch Playbook

### Assets to make (before launch day)
- **Trailer (YouTube, 60–90 s, 16:9 4K):** a cold open on a quiet dark editor with Fireflies, then typing makes the particles stir, a build makes them speed up, a green test run makes them lift, a red one makes them shiver, a breakpoint makes them hold their breath. Then a montage of all 8 atmospheres, a theme switch from light to dark, the Settings live preview, and an end card saying "Free on JetBrains Marketplace". Record with OBS or Screen Studio at 60 fps in `runIde` with a clean demo project, a large font and the New UI. Add soft ambient music (royalty-free).
- **Shorts/Reels/TikTok (vertical, 10–20 s each):** one per atmosphere, plus "the IDE reacts to your tests". Post them over the 2–3 weeks after launch.
- **Technical blog post:** "Painting on the IDE's glass pane without stealing a single click", built from `docs/overlay-investigation.md` (dirty-region commits, the FPS budget, Power Save). This is what developer communities actually share.
- **GIFs** from `docs/media` for posts. A hero GIF should stay under about 5 MB for X and Bluesky.

### Channels and messaging
| Channel | Format | Angle |
|---|---|---|
| YouTube | Trailer, then Shorts | "Your IDE, alive": the trailer description links to the Marketplace |
| X / Bluesky / Mastodon | Thread: hero GIF + 3 reaction GIFs | Cozy coding; it reacts to builds, tests and the debugger |
| LinkedIn | Personal post + trailer | Why you built it, with the performance focus |
| Reddit r/IntelliJIDEA, r/Jetbrains, r/kotlin | GIF + short text, no hard sell | Read each sub's self-promo rules |
| JetBrains Platform Slack/Forum (#showcase), Kotlin Slack | Short note | Plugin-dev audience, glass-pane technique |
| dev.to / Hashnode / personal blog | Technical post | Long-tail search traffic |
| Hacker News (Show HN) | The blog post, not the plugin | Only if the post is strong |

### Schedule
- **T−7 days:** finish the trailer, write the posts, prepare 3 Shorts.
- **T−3:** submit 1.0.0 to the Marketplace (review buffer).
- **T0 (approval day, Tue–Thu, about 16:00 CET for EU+US):** publish the trailer, then the X/Bluesky/Mastodon thread, then LinkedIn, then Slack communities.
- **T+1–2:** Reddit posts (one per day, different subs).
- **T+3–5:** the blog post, then Show HN.
- **T+7–21:** one Short every 2–3 days. Reply to every comment and review. Ship 1.0.1 with quick fixes, which helps the listing look active.
- **Seasonal hooks later:** Snow in December, Sakura in spring, Autumn in October. Each is a ready-made reason to post again.

### Measure
Marketplace stats (downloads, rating), YouTube retention on the trailer, and which atmosphere people ask about. Feed this into the next features.

# Testing

### Validation Approach
- `git ls-files` checked against the credential patterns returns nothing. `git check-ignore` confirms that `.kotlin/`, `foo.pem`, `chain.crt`, `.env` and `.output.txt` are ignored.
- History scan: a grep over `git log -p --all` for key/token patterns (and `gitleaks`, if installed) finds nothing.
- `./gradlew test buildPlugin verifyPlugin` passes, and the verifier reports no compatibility problems on the recommended IDEs.
- `unzip -l build/distributions/*.zip` lists only `lib/ide-atmosphere-1.0.0.jar`. The JAR has no `docs/`, `.junie/` or media.
- `./gradlew patchPluginXml` output has version `1.0.0` and change notes rendered from `[1.0.0]`.
- `signPlugin` and `publishPlugin` fail clearly (missing env) when no variables are set. This proves no secret is baked in.

# Delivery Steps

### ✓ Step 1: Harden repository against committing secrets
The repo ignores all credential and local-tool files, has a licence, and is verified to contain no secrets in its history.

- Extend `.gitignore` with `.kotlin/`, `*.log`, `.output.txt`, `local.properties`, `.env*`, `*.pem`, `*.key`, `*.crt`, `*.p12`, `*.jks`, `signing/`.
- Delete the stray root `.output.txt`.
- Add an Apache 2.0 `LICENSE` (`Copyright 2026 Jakub Chrzanowski`).
- Scan tracked files and the full history for key/token/password patterns, and run `gitleaks` if it's available.
- Report any finding, including the commit-email exposure note.

### ✓ Step 2: Configure release build, signing and publishing
`./gradlew buildPlugin verifyPlugin` produces a 1.0.0 artifact, with signing and publishing driven only by environment variables.

- Add `pluginConfiguration` (version, change notes from `CHANGELOG.md`) to `build.gradle.kts`.
- Add `signing` (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) and `publishing` (`PUBLISH_TOKEN`) to `build.gradle.kts`.
- Add `pluginVerification { ides { recommended() } }`.
- Set `version=1.0.0` in `gradle.properties`.
- Run `patchChangelog` to turn `[Unreleased]` into `[1.0.0]`.
- Run tests and `verifyPlugin`.
- Inspect the distribution ZIP contents.
- Confirm that sign/publish fail cleanly without env vars.

### ✓ Step 3: Write release checklist
`docs/release/RELEASE.md` takes you from zero to an approved Stable listing.

- One-time setup: vendor profile, the openssl signing-key commands, key storage outside the repo, the token, and env setup.
- 1.0.0 steps: smoke test, manual first upload with the field values, the media order from `docs/media`, the issues-only feedback repo, and review handling.
- Post-release: tag, bump to the next SNAPSHOT, and the `publishPlugin` flow for future releases.
- A "before open-sourcing" checklist.
- A JetBrains side-project policy reminder.

### ✓ Step 4: Write launch kit for YouTube and social media
`docs/release/LAUNCH-KIT.md` contains ready-to-use launch material and a schedule.

- A shot-by-shot script for the trailer and for each Short.
- Recording setup tips.
- Draft posts for X/Bluesky/Mastodon (thread), LinkedIn, Reddit, the Slack communities and Show HN.
- An outline for the technical blog post, based on `docs/overlay-investigation.md`.
- The T−7 to T+21 schedule, the seasonal re-posting hooks, and the metrics to track.
- A link to both release docs from the README's development section.