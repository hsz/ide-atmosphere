# Release checklist

From zero to an approved **Stable** listing on JetBrains Marketplace, then every release after it.

The sources stay private for now. Only the signed plugin ZIP leaves this machine. The Marketplace licence field points to
the Apache 2.0 text that is already in `LICENSE`.

> **Golden rule:** secrets never live inside this project directory. The build reads them only from environment
> variables. `.gitignore` blocks `*.pem`, `*.key`, `*.crt`, `*.p12`, `*.jks`, `.env*`, `local.properties` and
> `signing/` as a safety net, not as a place to keep them.

---

## 0. Before anything else: side-project policy

You work at JetBrains, so check the internal rules for personal Marketplace plugins **before the first upload**:

- [ ] Personal plugins with a JetBrains employee as vendor are allowed (check the current internal policy page / ask legal).
- [ ] Vendor name is **personal** ("Jakub Chrzanowski"), not JetBrains. The plugin must not look like an official
      JetBrains product: no JetBrains logos, and the name and description must not suggest endorsement.
- [ ] Do the Marketplace work from your **personal** account, not the `@jetbrains.com` one, if the policy says so.

## 1. One-time setup (about 1 hour)

### 1.1 Marketplace account and vendor profile

1. Sign in at <https://plugins.jetbrains.com> with your personal JetBrains Account.
2. *Profile → Vendors → Create vendor*: type **Personal**, name **Jakub Chrzanowski**, URL **https://chrzanowski.info**,
   which must match `<vendor url="…">` in `plugin.xml`. Add a contact email you're happy to show publicly.

### 1.2 Signing key (keep it forever: every release must be signed with the same key)

Create it **outside the project**:

```bash
mkdir -p ~/.secrets/ide-atmosphere && cd ~/.secrets/ide-atmosphere
chmod 700 .

# 1. Encrypted private key (asks for a pass phrase → this becomes PRIVATE_KEY_PASSWORD)
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 2. Unencrypted PEM form of the same key (needed by the signer)
openssl rsa -in private_encrypted.pem -out private.pem

# 3. Self-signed certificate chain, valid for 10 years
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt -subj "/CN=Jakub Chrzanowski"

chmod 600 *
```

- [ ] Back up `private_encrypted.pem`, `chain.crt` and the pass phrase in your password manager (for example as one
      secure note with attachments).
- [ ] You can delete `private.pem` after each release and recreate it from the encrypted key with step 2.

### 1.3 Marketplace upload token

1. *Profile → My Tokens → Generate Token*, name `ide-atmosphere-upload`, scope **Plugin Upload**.
2. Store it in your password manager. It's shown only once.

### 1.4 Shell environment

The build reads exactly these four variables (see `build.gradle.kts`):

| Variable | Content |
|---|---|
| `CERTIFICATE_CHAIN` | contents of `chain.crt` |
| `PRIVATE_KEY` | contents of `private.pem` |
| `PRIVATE_KEY_PASSWORD` | the pass phrase from 1.2 |
| `PUBLISH_TOKEN` | the token from 1.3 |

Load them only for the shell you release from, for example with a small script **outside the repo**:

```bash
# ~/.secrets/ide-atmosphere/env.sh: run with `source ~/.secrets/ide-atmosphere/env.sh`
export CERTIFICATE_CHAIN="$(cat ~/.secrets/ide-atmosphere/chain.crt)"
export PRIVATE_KEY="$(cat ~/.secrets/ide-atmosphere/private.pem)"
export PRIVATE_KEY_PASSWORD="$(security find-generic-password -s ide-atmosphere-key -w)"   # macOS Keychain
export PUBLISH_TOKEN="$(security find-generic-password -s ide-atmosphere-token -w)"
```

(Store the two secrets once with `security add-generic-password -a "$USER" -s ide-atmosphere-key -w '<pass phrase>'`
and the same for `ide-atmosphere-token`. You can also use a password-manager CLI such as `op read …`.)

Don't put them in the project's `gradle.properties`, `.envrc` or a run configuration under `.run/`.

## 2. Release 1.0.0

### 2.1 Build, verify and sign

```bash
source ~/.secrets/ide-atmosphere/env.sh
./gradlew clean test verifyPlugin signPlugin
unzip -l build/distributions/ide-atmosphere-1.0.0-signed.zip
```

- [ ] Tests are green.
- [ ] `verifyPlugin` reports *Compatible* for every released IntelliJ IDEA in the range (2025.3+). Reports are in
      `build/reports/pluginVerifier/`.
- [ ] The ZIP contains only `ide-atmosphere/lib/ide-atmosphere-1.0.0.jar` and
      `ide-atmosphere/lib/ide-atmosphere-1.0.0-searchableOptions.jar`.

**EAP check (by hand).** The verifier skips EAP builds on purpose. Before each release, install the signed ZIP in the
current EAP too.

### 2.2 Smoke test

In a **clean** IntelliJ IDEA 2025.3 (a fresh profile, for example via Toolbox → *Install another version* or
`-Didea.config.path`):

- [ ] *Settings → Plugins → ⚙ → Install Plugin from Disk…* → the signed ZIP. It installs without a restart.
- [ ] Fireflies appear. Switch through all 8 atmospheres from the status-bar widget.
- [ ] Light ↔ dark theme switch, Power Save mode (pauses), *View | Appearance | Atmosphere* toggle.
- [ ] The settings page live preview animates, and Apply changes the open windows.
- [ ] Run a test (green and red), start a Gradle build, hit a breakpoint: you see the reactions.
- [ ] Disable / uninstall the plugin without a restart, with no exceptions in `idea.log`.
- [ ] Repeat a short run in the latest EAP.

### 2.3 First upload (manual, only once)

`publishPlugin` can only update a plugin that already exists, so the first upload goes through the website.

At <https://plugins.jetbrains.com/plugin/add>:

| Field | Value |
|---|---|
| Vendor | Jakub Chrzanowski (personal) |
| Plugin file | `build/distributions/ide-atmosphere-1.0.0-signed.zip` |
| License | Apache License 2.0, link `https://www.apache.org/licenses/LICENSE-2.0` |
| Channel | Stable (default) |
| Tags | *Theme*, *UI*, *Fun*, *Customization* (pick from the offered list; at most 3–4) |
| Source code URL | leave empty (sources are private for now) |
| Issue tracker | the feedback repo from 2.5 |
| Documentation / Website | `https://chrzanowski.info` or the feedback repo's README |

Name, description, vendor, version, `since-build` and change notes come from the patched `plugin.xml`, so the
*What's New* tab shows the `1.0.0` section of `CHANGELOG.md`.

### 2.4 Listing media

After the upload, on the plugin page choose *Edit → Media*:

- Screenshots in this order, **dark variants first**: `fireflies-dark.png` (hero), `sakura-dark.png`, `snow-dark.png`,
  `autumn-dark.png`, `starfield-dark.png`, `rain-dark.png`, then `bubbles-dark.png`, `dust-dark.png` and one light one
  (`sakura-light.png`) to show theme awareness.
- The generated images are **720×400**. The Marketplace recommends about **1200×760** or larger. For the hero, add 1–2
  real full-window screenshots taken in `runIde` (New UI, a clean demo project, Fireflies). You can also
  re-render the generator output at a larger size.
- Add the YouTube trailer URL in *Video* once it's public (see `LAUNCH-KIT.md`).

### 2.5 Feedback channel

The sources are private, so create a **public, issues-only** repository:

1. GitHub → *New repository* `ide-atmosphere-issues` (public), with only a short `README.md`: what the plugin is, a
   Marketplace link, and "please include IDE version, OS and atmosphere".
2. *Settings → General*: turn off Wiki and Projects. Turn on Issues (and Discussions if you like).
3. Add issue templates for *Bug* (IDE version, OS, atmosphere, `idea.log` excerpt) and *Idea*.
4. Put its URL in the Marketplace *Issue tracker* field.

### 2.6 Review

- Approval takes about **1–3 business days**. Keep an eye on the email of the Marketplace account and answer quickly.
- Likely questions and answers:
  - *"Why do you paint on the glass pane?"* It's a passive `IdeGlassPane` painter that never consumes input, with
    dirty-region repainting and an FPS budget. See `docs/overlay-investigation.md`.
  - *"Why do you poll the status bar?"* The platform's task-start listener is internal API. The plugin samples the public
    status-bar progress once a second, only while *React to IDE activity* is on and the IDE is in the foreground.
  - Deprecated `StatusBarWidget` presentation API is reported by the verifier as a warning, not a problem.

## 3. After approval

```bash
git tag -s v1.0.0 -m "IDE Atmosphere 1.0.0"
# gradle.properties: version=1.0.1-SNAPSHOT
# CHANGELOG.md: keep an empty "## Unreleased" section at the top
git commit -am "Start 1.0.1 development"
```

### Every later release

1. Move `## Unreleased` entries into the release with `./gradlew patchChangelog` after setting the release version in
   `gradle.properties` (for example `version=1.0.1`).
2. `source ~/.secrets/ide-atmosphere/env.sh`
3. `./gradlew clean test verifyPlugin publishPlugin`. `publishPlugin` signs the ZIP and uploads it to Stable. It refuses
   to run if any of the four variables is missing, so it can never upload an unsigned build.
4. Tag, bump to the next `-SNAPSHOT`, commit.

## 4. Before you ever publish the sources

- [ ] Run a secret scan over the **full history**: `brew install gitleaks && gitleaks git -v .`. No findings are expected
      (the 1.0.0 scan was clean).
- [ ] Commit metadata contains `jakub@chrzanowski.info` (author) and the `@jetbrains.com` UID of the GPG signing key.
      Decide whether that's fine. If it isn't, rewrite the history (`git filter-repo --mailmap …`) **before the first push**.
- [ ] Decide whether `.junie/plans/` stays public (current decision: keep it).
- [ ] Add CI (GitHub Actions) with the four variables as repository secrets, and never echo them in logs.
- [ ] Add the source URL to the Marketplace listing and the README, and point the issue tracker to the main repo.
