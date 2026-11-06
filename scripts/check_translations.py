#!/usr/bin/env python3
"""
Translation Consistency Checker for Classy.

Checks:
  Check A: All static string keys in code (localize("..."), t("...")) exist in english.json.
  Check B: Language JSON dictionaries (assets/languages/*.json) are consistent with english.json
           (no extra keys, and missing keys do not exceed the baseline).
  Check C: Informational warning about orphan keys in english.json not detected in static code.

Exit code 0 on success, non-zero on failure.
"""

import argparse
import glob
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path


def unescape_kotlin_str(s: str) -> str:
    """Decode the escape sequences of a Kotlin/Java string literal; other characters (also non-ASCII, e.g. "…") stay."""
    simple = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", '"': '"', "'": "'", "\\": "\\", "$": "$"}

    def decode(m: re.Match) -> str:
        e = m.group(1)
        return chr(int(e[1:], 16)) if e[0] == "u" else simple[e]

    return re.sub(r'\\(u[0-9a-fA-F]{4}|[ntrb"\'\\$])', decode, s)


def find_code_keys(java_dir: Path) -> dict[str, list[tuple[str, int]]]:
    """Scan Kotlin/Java source files for localize("...") and t("...") calls."""
    pattern = re.compile(
        r'(?:\bmain\.t|\bcontext\.t|\bctx\.t|\bMainActivity\.instance\?\.t|\blocalize|\bt)\s*\(\s*"((?:[^"\\]|\\.)*)"'
    )
    code_keys: dict[str, list[tuple[str, int]]] = defaultdict(list)

    for ext in ("*.kt", "*.java"):
        for path in java_dir.rglob(ext):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    for line_no, line in enumerate(f, 1):
                        stripped = line.strip()
                        if stripped.startswith("//"):
                            continue
                        for match in pattern.finditer(line):
                            raw_str = match.group(1)
                            unescaped = unescape_kotlin_str(raw_str)
                            rel_path = str(path)
                            code_keys[unescaped].append((rel_path, line_no))
            except Exception as e:
                print(f"Warning: Failed to read {path}: {e}", file=sys.stderr)

    return code_keys


def load_language_files(languages_dir: Path) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    """Load english.json and all other language JSON files."""
    en_file = languages_dir / "english.json"
    if not en_file.is_file():
        raise FileNotFoundError(f"Missing reference language file: {en_file}")

    with open(en_file, "r", encoding="utf-8") as f:
        english_dict = json.load(f)

    other_languages: dict[str, dict[str, str]] = {}
    for json_file in sorted(languages_dir.glob("*.json")):
        if json_file.name == "english.json":
            continue
        with open(json_file, "r", encoding="utf-8") as f:
            other_languages[json_file.name] = json.load(f)

    return english_dict, other_languages


def find_repo_root(start: Path) -> Path | None:
    """Return the repository root above `start`, or None.

    Recognises the root by `app/src/main/java`, so it does not depend on what
    the clone directory is called.
    """
    for candidate in (start, *start.parents):
        if (candidate / "app" / "src" / "main" / "java").is_dir():
            return candidate
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Check translation consistency for Classy")
    parser.add_argument(
        "--root-dir",
        type=Path,
        default=None,
        help="Path to the Classy repository root (auto-detected if omitted)",
    )
    parser.add_argument(
        "--baseline",
        type=int,
        default=0,
        help="Maximum missing keys allowed per language file in Check B (default: 0 - strict)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Enforce 0 missing keys across all languages (equivalent to --baseline 0)",
    )
    parser.add_argument(
        "--no-check-code",
        action="store_true",
        help="Skip Check A (code keys vs english.json)",
    )
    parser.add_argument(
        "--no-check-languages",
        action="store_true",
        help="Skip Check B (language files vs english.json)",
    )
    parser.add_argument(
        "--fail-on-orphans",
        action="store_true",
        help="Fail if english.json contains keys not found in static code",
    )
    parser.add_argument(
        "--warn-only",
        action="store_true",
        help="Always return exit code 0 even if checks fail",
    )

    args = parser.parse_args()

    if args.strict:
        args.baseline = 0

    # Auto-detect the repository root. Name-agnostic: the clone directory can be
    # called anything (Classy, inniclassic-y2, or a scratch checkout).
    if args.root_dir is None:
        root_dir = find_repo_root(Path(__file__).resolve().parent) or find_repo_root(Path.cwd())
        if root_dir is None:
            print("Error: Could not locate the Classy repository directory. Use --root-dir.", file=sys.stderr)
            return 2
    else:
        root_dir = args.root_dir

    java_dir = root_dir / "app" / "src" / "main" / "java"
    languages_dir = root_dir / "app" / "src" / "main" / "assets" / "languages"

    if not java_dir.is_dir():
        print(f"Error: Java source directory not found: {java_dir}", file=sys.stderr)
        return 2
    if not languages_dir.is_dir():
        print(f"Error: Languages directory not found: {languages_dir}", file=sys.stderr)
        return 2

    english_dict, other_languages = load_language_files(languages_dir)
    english_keys = set(english_dict.keys())

    is_github_actions = os.environ.get("GITHUB_ACTIONS") == "true"
    github_summary_path = os.environ.get("GITHUB_STEP_SUMMARY")

    has_errors = False
    markdown_lines = []
    markdown_lines.append("# Translation Consistency Check Report\n")

    print(f"Loaded {len(english_keys)} reference keys from english.json")
    print(f"Found {len(other_languages)} additional language file(s): {', '.join(other_languages.keys())}")
    print("=" * 60)

    # -------------------------------------------------------------
    # Check A: Code keys -> english.json
    # -------------------------------------------------------------
    if not args.no_check_code:
        print("\n[Check A] Verifying that code string keys exist in english.json...")
        code_keys_map = find_code_keys(java_dir)
        total_unique_code_keys = len(code_keys_map)
        missing_from_english = set(code_keys_map.keys()) - english_keys

        if missing_from_english:
            has_errors = True
            msg = f"Check A FAILED: {len(missing_from_english)} key(s) used in code are missing from english.json!"
            print(f"❌ {msg}\n")
            markdown_lines.append(f"### ❌ Check A: Code Keys vs english.json\n\n**{msg}**\n")
            markdown_lines.append("| Missing Key | Occurrence Count | First Found Location |\n| :--- | :--- | :--- |\n")

            for key in sorted(missing_from_english):
                occurrences = code_keys_map[key]
                first_file, first_line = occurrences[0]
                try:
                    rel_file = Path(first_file).relative_to(root_dir)
                except ValueError:
                    rel_file = first_file

                print(f"  • {repr(key)}")
                print(f"    first defined at {rel_file}:{first_line} ({len(occurrences)} total usage(s))")

                if is_github_actions:
                    print(
                        f"::error file={rel_file},line={first_line}::Missing translation in english.json: {repr(key)}"
                    )

                display_key = key.replace("\n", "\\n").replace("|", "\\|")
                markdown_lines.append(f"| `{display_key}` | {len(occurrences)} | `{rel_file}:{first_line}` |\n")

            print("\n  👉 To fix Check A: Add the missing keys to `english.json` (and all other translations).")
        else:
            success_msg = f"All {total_unique_code_keys} static keys found in code are present in english.json."
            print(f"✅ Check A PASSED: {success_msg}")
            markdown_lines.append(f"### ✅ Check A: Code Keys vs english.json\n\n{success_msg}\n")
    else:
        print("\n[Check A] Skipped (--no-check-code)")

    # -------------------------------------------------------------
    # Check B: Language JSON dictionaries consistency
    # -------------------------------------------------------------
    if not args.no_check_languages:
        print("\n[Check B] Verifying language packs against english.json...")
        print(f"  Baseline threshold: {args.baseline} missing keys max per file")

        markdown_lines.append("### Language Pack Coverage Summary\n")
        markdown_lines.append("| Language File | Total Keys | Missing vs English | Extra Keys | Status |\n")
        markdown_lines.append("| :--- | :--- | :--- | :--- | :--- |\n")

        lang_errors = []

        for lang_name, lang_dict in sorted(other_languages.items()):
            lang_keys = set(lang_dict.keys())
            missing_keys = english_keys - lang_keys
            extra_keys = lang_keys - english_keys

            missing_count = len(missing_keys)
            extra_count = len(extra_keys)
            total_count = len(lang_keys)

            is_ok = (missing_count <= args.baseline) and (extra_count == 0)

            if not is_ok:
                has_errors = True
                status_icon = "❌ FAIL"
                fail_reasons = []
                if missing_count > args.baseline:
                    fail_reasons.append(f"{missing_count} missing keys exceeds baseline ({args.baseline})")
                if extra_count > 0:
                    fail_reasons.append(f"{extra_count} extra unknown keys")
                lang_errors.append((lang_name, fail_reasons, missing_keys, extra_keys))
            else:
                status_icon = "✅ PASS"

            print(
                f"  {status_icon} {lang_name:15}: {total_count} keys, "
                f"{missing_count:3} missing, {extra_count:3} extra"
            )

            status_cell = "✅ PASS" if is_ok else f"❌ FAIL ({', '.join(fail_reasons)})"
            markdown_lines.append(
                f"| `{lang_name}` | {total_count} | {missing_count} | {extra_count} | {status_cell} |\n"
            )

        if lang_errors:
            print("\n❌ Check B FAILED for the following language file(s):")
            for lang_name, reasons, missing_keys, extra_keys in lang_errors:
                print(f"\n  • {lang_name}: {'; '.join(reasons)}")
                if extra_keys:
                    print(f"    Extra keys not in english.json: {list(sorted(extra_keys))[:10]}")
                if missing_keys and len(missing_keys) > args.baseline:
                    sample = list(sorted(missing_keys))[:5]
                    print(f"    Sample missing keys ({len(sample)} of {len(missing_keys)}):")
                    for k in sample:
                        print(f"      - {repr(k)}")

                if is_github_actions:
                    rel_lang = f"app/src/main/assets/languages/{lang_name}"
                    print(
                        f"::error file={rel_lang}::Language file {lang_name} failed check: {'; '.join(reasons)}"
                    )
        else:
            print(f"\n✅ Check B PASSED: All language files are within the baseline threshold ({args.baseline}).")
    else:
        print("\n[Check B] Skipped (--no-check-languages)")

    # -------------------------------------------------------------
    # Check C: Orphan keys (Informational warning)
    # -------------------------------------------------------------
    if not args.no_check_code:
        orphan_keys = english_keys - set(code_keys_map.keys())
        if orphan_keys:
            print(f"\nℹ️  [Check C - Info] {len(orphan_keys)} key(s) in english.json were not detected in static code scan.")
            print("   (Note: Some keys may be constructed dynamically at runtime, e.g. presets, labels, or repeat modes.)")
            if args.fail_on_orphans:
                has_errors = True
                print("❌ Check C FAILED: --fail-on-orphans was set.")

    # Write GITHUB_STEP_SUMMARY if available
    if github_summary_path:
        try:
            with open(github_summary_path, "a", encoding="utf-8") as f:
                f.write("\n".join(markdown_lines) + "\n")
        except Exception as e:
            print(f"Warning: Could not write GitHub step summary: {e}", file=sys.stderr)

    print("\n" + "=" * 60)
    if has_errors:
        print("RESULT: Translation check FAILED.")
        return 0 if args.warn_only else 1
    else:
        print("RESULT: All translation checks PASSED.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
