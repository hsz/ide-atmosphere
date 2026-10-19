<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Atmosphere Changelog

## Unreleased

### Added

- Optional support via GitHub Sponsors, Ko-fi and PayPal in the settings page, README and Marketplace description, plus a **♥ Sponsor IDE Atmosphere** action in the status-bar menu and Find Action.
- A local-only sponsorship reminder after 10 enabled project openings and 14 days of use, with a 90-day snooze and permanent opt-out.
- GitHub funding metadata and a sponsorship notice in Marketplace release notes.

## 1.0.0 - 2026-10-02

First public release.

### Added

- Eight ambient atmospheres painted softly over the IDE window: **Fireflies**, **Sakura**, **Snow**, **Rain**, **Starfield**, **Autumn Leaves**, **Bubbles** and **Dust Motes**, each with light- and dark-theme colours.
- The atmosphere reacts to what you do: it calms down when you are idle, stirs while you type, speeds up during builds, syncs, runs and long background tasks, and holds still while the debugger is stopped on a breakpoint.
- Outcome reactions expressed through motion and brightness only: a successful build or test run lifts the field; a failure sends a shiver or gust through it.
- Pointer modes **Avoid / Attract / Off**, click puffs, scroll gusts and an opt-in **Gather particles near the caret**; input is only observed, never consumed.
- Settings page (**Settings | Appearance & Behavior | Atmosphere**) with an atmosphere picker, live preview, **Intensity** and **Speed** sliders, separate light/dark theme presets, **React to IDE activity**, **React to typing** and **Reduce motion**.
- **Atmosphere** status bar widget to switch atmospheres, pause for an hour or open the settings, plus the **View | Appearance | Atmosphere** toggle.
- Built to stay out of the way: adaptive 20–30 FPS within a small CPU budget, partial repaints only, automatic pause in Power Save mode and a fade-out while the IDE is in the background.
