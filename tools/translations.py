#!/usr/bin/env python3
"""Translation tooling for Arcane Storage's locale files.

Three jobs, because localizing this mod is three separate problems:

  template <lang>   start a translation file, prefilled with English to edit over
  glossary <lang>   the game's own official wording for the vocabulary our strings borrow
  check             validate every shipped locale file against en.lang

Named translations.py rather than locale.py on purpose: a module called locale.py shadows the standard
library's own `locale`, and argparse imports it indirectly through gettext, so the script cannot even
parse its arguments. Python's error message says so outright, which is a kinder failure than most.

Run `check` before every release. The other two are for whoever is translating.

Why a glossary matters more than it sounds: our strings say "Workstation" and "Iron Bar" because those
are Necesse's words, and the game already ships official translations of all of them. Taking the wording
from there means a player reads the same term in our tooltip as on the bench they are standing at, which
no independent translation will reliably reproduce.

What the game does for us, so the tooling does not have to:

  * A language finds our file by scanning the jar for an entry under resources/ whose path ENDS WITH its
    own file name. Nothing is registered and no code runs. That suffix match is also a trap, since
    "broken.lang" ends with "en.lang" and would load as English -- so `check` insists on exact names.
  * Missing keys fall back to English per key, so a partial translation is safe and a file need not be
    complete. That is why `check` treats a missing key as coverage information rather than an error.
  * Only ONE file per language is read (the loader breaks at the first match), so translations cannot be
    split across several files.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
from collections import OrderedDict

REPO = pathlib.Path(__file__).resolve().parent.parent
MOD_LOCALE = REPO / "src/main/resources/locale"
GAME_LOCALE_CANDIDATES = [
    pathlib.Path.home() / ".steam/debian-installation/steamapps/common/Necesse/locale",
    pathlib.Path.home() / ".local/share/Steam/steamapps/common/Necesse/locale",
]

PLACEHOLDER = re.compile(r"<[a-zA-Z][a-zA-Z0-9_]*>")
KEY_LINE = re.compile(r"^([a-zA-Z0-9_]+)=(.*)$")
CATEGORY_LINE = re.compile(r"^\[([a-zA-Z0-9_]+)\]$")


def game_locale_dir() -> pathlib.Path:
    for path in GAME_LOCALE_CANDIDATES:
        if path.is_dir():
            return path
    sys.exit(
        "Could not find the game's locale folder. It ships beside the executable; tried:\n  "
        + "\n  ".join(str(p) for p in GAME_LOCALE_CANDIDATES)
    )


def parse(path: pathlib.Path) -> "OrderedDict[tuple[str, str], str]":
    """Parse a .lang file into {(category, key): value}, preserving order.

    The game's own files are CRLF and ours are LF, so line endings are stripped rather than assumed.
    Duplicate keys are kept as the last value, which is what the game's parser does.
    """
    out: "OrderedDict[tuple[str, str], str]" = OrderedDict()
    category = "null"
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.rstrip("\r").strip()
        if not line or line.startswith("//"):
            continue
        cat = CATEGORY_LINE.match(line)
        if cat:
            category = cat.group(1)
            continue
        kv = KEY_LINE.match(line)
        if kv:
            out[(category, kv.group(1))] = kv.group(2)
    return out


def known_language_codes() -> set[str]:
    """The languages the game ships, which are the only file names it will ever look for.

    Read from the install rather than hardcoded, because the list grows between game versions and a
    stale copy here would reject a language that had just become valid. Note the codes are not all
    ISO: Swedish is `se`, not `sv`.
    """
    return {p.stem for p in game_locale_dir().glob("*.lang")}


def cmd_template(args) -> int:
    english = MOD_LOCALE / "en.lang"
    target = MOD_LOCALE / f"{args.lang}.lang"

    codes = known_language_codes()
    if args.lang not in codes:
        sys.exit(
            f"'{args.lang}' is not a language the game has. Its file would never be read.\n"
            f"Valid codes: {' '.join(sorted(codes))}"
        )
    if target.exists() and not args.force:
        sys.exit(f"{target} exists already. Pass --force to overwrite it.")

    lines = [
        f"// Arcane Storage -- {args.lang}.lang",
        "//",
        "// Translate the text after each '='. Two rules that matter more than the wording:",
        "//",
        "//   1. Keep every <placeholder> exactly as written. They are substituted at runtime, so a",
        "//      dropped one silently loses the number or name it was standing in for.",
        "//   2. Do not rename or add keys. A key not in en.lang is read by nothing.",
        "//",
        "// Lines left in English are harmless: the game falls back to English per key anyway. Run",
        "// `tools/locale.py check` to see what is still untranslated.",
        "",
    ]

    # Carries en.lang's own '//' comments through too, not just its [category] headers and keys.
    # This file has two of them documenting the frequency-band vocabulary and the settings tab,
    # and every one of nine translation runs so far dropped them because this loop used to keep
    # only what CATEGORY_LINE or KEY_LINE matched -- a blank-line-separated comment matches neither.
    category = None
    for raw in english.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        cat = CATEGORY_LINE.match(stripped)
        if cat:
            if category is not None:
                lines.append("")
            category = cat.group(1)
            lines.append(stripped)
            continue
        if KEY_LINE.match(stripped) or stripped.startswith("//"):
            lines.append(stripped)
        elif not stripped and lines and lines[-1].startswith("//"):
            # A blank line ending a comment block, so the block does not run into the next key.
            lines.append("")

    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    keys = len(parse(target))
    print(f"wrote {target.relative_to(REPO)} with {keys} keys to translate")
    return 0


def cmd_glossary(args) -> int:
    game = game_locale_dir()
    dst = game / f"{args.lang}.lang"
    if not dst.is_file():
        sys.exit(f"The game has no {dst.name}. Valid codes: {' '.join(sorted(known_language_codes()))}")

    game_en = parse(game / "en.lang")
    game_other = parse(dst)
    ours = parse(MOD_LOCALE / "en.lang")

    # Whole-word matching, not substring. Without it "Range" matches "Arrange" and "Disc" matches
    # "Discard", and the resulting glossary is mostly wrong in a way that takes a speaker to notice.
    def find_use(term: str) -> str | None:
        pattern = re.compile(rf"(?<![A-Za-z]){re.escape(term)}(?![A-Za-z])", re.I)
        for value in ours.values():
            if pattern.search(value):
                return value
        return None

    terms: dict[str, dict] = {}
    for (cat, key), english in game_en.items():
        if len(english) < 4 or not re.fullmatch(r"[A-Za-z][A-Za-z '-]*", english):
            continue
        translated = game_other.get((cat, key))
        if not translated or translated == english:
            continue
        use = find_use(english)
        if use is None:
            continue
        entry = terms.setdefault(english, {"use": use, "variants": {}})
        entry["variants"].setdefault(translated, set()).add(cat)

    if not terms:
        print(f"No shared vocabulary found for {args.lang}. Either that file is largely untranslated, or")
        print("our strings do not reuse the game's terms, which would be worth knowing either way.")
        return 0

    print(f"// Glossary for {args.lang}, taken from the game's own {dst.name}.")
    print("// Use these words so the mod reads as part of the game rather than beside it.")
    print("//")
    print("// Each entry shows one of our strings using the term, because a game term does not always")
    print("// carry our sense of it -- the game's \"Content\" is a settler's mood, not a container's")
    print("// contents. Judge by the usage line and discard what does not fit.")
    print(f"//\n// {len(terms)} candidate terms.\n")

    # Longest first: multi-word domain terms are the reliable ones and should be read before the
    # single generic words further down.
    for english in sorted(terms, key=lambda t: (-len(t), t.lower())):
        entry = terms[english]
        variants = entry["variants"]
        use = entry["use"]
        use = use if len(use) <= 72 else use[:69] + "..."
        if len(variants) == 1:
            translated, cats = next(iter(variants.items()))
            print(f"{english}  ->  {translated}")
            print(f"    // [{','.join(sorted(cats))}] used in: {use}")
        else:
            # German does exactly this: tungstenworkstation is a Werkbank in one category and an
            # Arbeitsstation in another, so picking blind would be wrong some of the time.
            print(f"{english}  ->  AMBIGUOUS, pick by context:")
            for translated, cats in sorted(variants.items()):
                print(f"    [{','.join(sorted(cats))}] {translated}")
            print(f"    // used in: {use}")
    return 0


def cmd_check(args) -> int:
    english_path = MOD_LOCALE / "en.lang"
    english = parse(english_path)
    codes = known_language_codes()
    errors: list[str] = []
    notes: list[str] = []

    # en.lang's own hygiene. A duplicate key is the failure that cannot be seen by reading, because
    # the parser keeps the last one and the first silently does nothing.
    seen: dict[tuple[str, str], int] = {}
    category = "null"
    for n, raw in enumerate(english_path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        cat = CATEGORY_LINE.match(line)
        if cat:
            category = cat.group(1)
            continue
        kv = KEY_LINE.match(line)
        if not kv:
            if line and not line.startswith("//"):
                errors.append(f"en.lang:{n}: neither a category, a comment, nor key=value: {line!r}")
            continue
        ident = (category, kv.group(1))
        if ident in seen:
            errors.append(
                f"en.lang:{n}: [{category}] {kv.group(1)} is already defined on line {seen[ident]}; "
                "the earlier one is dead"
            )
        seen[ident] = n

    for path in sorted(MOD_LOCALE.glob("*.lang")):
        if path.name == "en.lang":
            continue

        # The loader matches by path suffix, so a misnamed file is not ignored -- it is loaded as
        # whichever language its name happens to end with.
        if path.stem not in codes:
            wrong = [c for c in codes if path.name.endswith(f"{c}.lang")]
            hint = f" It would load as {wrong[0]}!" if wrong else " The game would ignore it."
            errors.append(f"{path.name}: not a language the game has.{hint}")
            continue

        other = parse(path)
        extra = [f"[{c}] {k}" for (c, k) in other if (c, k) not in english]
        if extra:
            errors.append(
                f"{path.name}: {len(extra)} key(s) absent from en.lang, so nothing reads them: "
                + ", ".join(extra[:5])
                + (" ..." if len(extra) > 5 else "")
            )

        for ident, value in other.items():
            if ident not in english:
                continue
            want = sorted(PLACEHOLDER.findall(english[ident]))
            got = sorted(PLACEHOLDER.findall(value))
            if want != got:
                errors.append(
                    f"{path.name}: [{ident[0]}] {ident[1]} has placeholders {got or 'none'} but "
                    f"English has {want or 'none'}; a substitution would be lost"
                )

        translated = sum(1 for i, v in other.items() if i in english and v != english[i])
        missing = len(english) - len([i for i in other if i in english])
        notes.append(
            f"{path.name}: {translated}/{len(english)} translated, "
            f"{len(other) - translated} still English, {missing} absent (English is used for those)"
        )

    print(f"en.lang: {len(english)} keys, "
          f"{sum(1 for v in english.values() if PLACEHOLDER.search(v))} with placeholders")
    for note in notes:
        print(note)
    if not notes:
        print("no other languages shipped yet")

    if errors:
        print(f"\n{len(errors)} problem(s):", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1
    print("\nok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    t = sub.add_parser("template", help="start a translation file for a language")
    t.add_argument("lang", help="game language code, e.g. de, ru, zh-CN (Swedish is se, not sv)")
    t.add_argument("--force", action="store_true", help="overwrite an existing file")
    t.set_defaults(func=cmd_template)

    g = sub.add_parser("glossary", help="the game's official wording for terms our strings borrow")
    g.add_argument("lang", help="game language code")
    g.set_defaults(func=cmd_glossary)

    c = sub.add_parser("check", help="validate every shipped locale file")
    c.set_defaults(func=cmd_check)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
