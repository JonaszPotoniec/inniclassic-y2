---
name: release-notes
description: >-
  Draft, audit, and update GitHub Release descriptions and changelogs for
  JonaszPotoniec/inniclassic-y2 using the gh CLI and Humanizer writing rules.
  Use this skill whenever preparing a new release, updating existing GitHub
  Releases, or writing user-facing changelog entries for InniClassic.
---

# InniClassic Release Notes & Changelog Guide

Use this skill to write or update GitHub Release descriptions on `JonaszPotoniec/inniclassic-y2` so every release starts with a human-readable changelog focused on user impact and features.

## Release Description Structure

Every GitHub release body must follow this exact structure:

1. **Human-readable changelog first**:
   - Start immediately with the changelog bullets (or a short one-sentence overview for major milestone releases, followed by bullets).
   - Do **not** include a top-level `### InniClassic Release` heading or "Automated build with full support..." boilerplate.
   - Focus on what changed for the user (features, UI changes, audio/hardware behavior, bug fixes) with light technical context where helpful (for example, `mtkbt` daemon, `libaudiocustparam.so`, `/system/app`, `.y1_thumbs_cache`).
2. **Release assets table**:
   - Use sentence case with no emoji (`#### Release assets`).
   - Keep file names in inline code (`InniClassic-<TAG>.apk`, `update.json`, `rom_y2.zip`, `rom_y1_type_a.zip`, `rom_y1_type_b.zip`, `SHA256SUMS.txt`) without bold decoration on every cell.
3. **Full changelog link**:
   - End with `Full changelog: https://github.com/JonaszPotoniec/inniclassic-y2/compare/<PREV_TAG>...<TAG>`.
   - **Never** include the `Flashing Instructions (SP Flash Tool)` section or decorative `---` horizontal rules.

### Template

```markdown
- <Bullet 1: user-facing feature or fix>
- <Bullet 2: user-facing feature or fix>

#### Release assets

| File | Target device | Notes |
| :--- | :--- | :--- |
| `InniClassic-<TAG>.apk` | All devices (Y1 / Y2) | Standalone launcher APK (sideload or install) |
| `update.json` | All devices | OTA update metadata for in-app updater |
| `rom_y2.zip` | Innioasis Y2 (MT6582) | Complete flashable ROM with dual-storage, watchdog & clickwheel keylayout |
| `rom_y1_type_a.zip` (`rom.zip`) | Innioasis Y1 (Type A) | Flashable ROM for Y1 players with original firmware 2.0.0 or higher |
| `rom_y1_type_b.zip` (`rom_type_b.zip`) | Innioasis Y1 (Type B) | Flashable ROM for Y1 players with original firmware below 2.0.0 |
| `SHA256SUMS.txt` | All files | SHA-256 integrity checksums |

Full changelog: https://github.com/JonaszPotoniec/inniclassic-y2/compare/<PREV_TAG>...<TAG>
```

## Humanizer Writing Rules

Apply the [blader/humanizer](https://github.com/blader/humanizer/raw/refs/heads/main/SKILL.md) guidelines to every release description:

1. **No bold labels on list items (§19)**: Do not start bullets with `- **Feature Name:** ...` or bold random words for decoration. Write normal sentences in each bullet.
2. **Vary sentence openings (§7)**: Do not start every bullet with a past-tense verb (`Added...`, `Fixed...`, `Improved...`). Mix subject-first openings (`The About screen now...`, `FM Radio remembers...`, `Volume uses...`) with action openings.
3. **No em dashes or en dashes (§8)**: Do not use `—`, `–`, or spaced double hyphens (` -- `) in prose. Use commas, periods, colons, or parentheses instead. (Hyphens inside inline code, paths, or UI quotes like `"Connected - Eject before disconnecting"` are fine.)
4. **No "not X, but Y" contrasts (§1) or one-line closers (§2)**: State what changed directly without rhetorical contrast or summary taglines.
5. **No AI watch words (§12-§18)**: Avoid `additionally`, `align with`, `bolstered`, `crucial`, `deep dive`, `delve`, `enduring`, `enhance`, `featuring`, `boasts`, `serves as`, `offers`, `garner`, `highlight`, `interplay`, `intricate`, `key` (adjective), `landscape`, `meticulous`, `pivotal`, `quietly`, `robust` (figurative), `seamless`, `showcase`, `tapestry`, `testament`, `underscore`, `valuable`, `vibrant`, and shallow `-ing` riders (`, ensuring...`, `, highlighting...`, `, reflecting...`).
6. **Decorative formatting (§20-§21)**: Use sentence-case headings (`#### Release assets`), no emojis in headings or bullets, no horizontal rules (`---`), and straight quotes (`"`).
7. **Strict factual accuracy**: Every detail must come from the git commits in the tag range. Never invent causes, metrics, or behavior.

## Workflow for Updating a New Release

Note: Pushing to `main` triggers [release.yml](../../../.github/workflows/release.yml), which publishes an automated GitHub Release using a static template that still contains the legacy `Flashing Instructions` block unless overwritten. After a release is published (or when preparing release notes):

1. **List releases and find the tag range**:
   ```bash
   gh release list --repo JonaszPotoniec/inniclassic-y2 --limit 10
   git fetch --tags origin
   git log --format="- %h %s%n%w(0,4,4)%b" <PREV_TAG>..<NEW_TAG>
   ```
2. **Inspect commits with short messages**:
   For any commit that only has a one-line subject, inspect its diff so you capture the real user impact:
   ```bash
   git show --stat <COMMIT_SHA>
   git show <COMMIT_SHA> -- app/src/main/assets/languages/english.json
   ```
3. **Draft and audit the release notes**:
   - Write the release body using the template above.
   - Check against the Humanizer rules (no `—`/`–`, no `- **Label:**`, varied bullet openings, no AI watch words, no `Flashing Instructions`).
   - Compare against past examples in [release-history.md](./references/release-history.md).
4. **Update the GitHub Release**:
   ```bash
   gh release edit <NEW_TAG> --repo JonaszPotoniec/inniclassic-y2 --notes-file /tmp/release_notes.md
   rm -f /tmp/release_notes.md
   ```
5. **Verify**:
   ```bash
   gh release view <NEW_TAG> --repo JonaszPotoniec/inniclassic-y2
   ```
