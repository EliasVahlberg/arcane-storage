#!/usr/bin/env bash
#
# Publish docs/wiki/ to the repository's GitHub wiki.
#
# docs/wiki/ stays the source of truth. It is versioned with the code it describes, a pull request can review a
# documentation change alongside the change it documents, and WikiTest checks its links and style on every build.
# A GitHub wiki is a separate git repository with none of that, so it is treated as a published copy rather than
# as somewhere to edit.
#
# The transform this performs:
#
#   README.md            -> Home.md          the wiki's landing page is Home, not README
#   getting-started.md   -> Getting-started.md   GitHub turns hyphens into spaces in the page title
#   ](terminal.md)       -> ](Terminal)       wiki links carry no extension
#   images/, screenshots/                     copied across, since a wiki cannot reach into the code repository
#   _Sidebar.md                               generated, so every page gets the same navigation
#
# One manual step is unavoidable the first time. GitHub does not create the wiki's git repository until a page
# exists, and there is no API for creating one, so the wiki has to be opened in a browser and any page saved
# before this script has somewhere to push. After that this script owns the contents.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
SOURCE_COMMIT="$(git rev-parse --short HEAD)"

REPO="${WIKI_REPO:-EliasVahlberg/arcane-storage}"
SOURCE="docs/wiki"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

if [[ ! -d "$SOURCE" ]]; then
   echo "error: no $SOURCE directory. Run this from the repository." >&2
   exit 1
fi

echo "==> cloning the wiki of $REPO"
if ! git clone --quiet "https://github.com/$REPO.wiki.git" "$WORK/wiki" 2>/dev/null; then
   cat >&2 <<EOF
error: $REPO's wiki repository does not exist yet.

GitHub creates it only after the first page is saved, and offers no API for doing so. To fix this once:

  1. open https://github.com/$REPO/wiki
  2. press "Create the first page", save it with any content at all
  3. run this script again, which will overwrite whatever you saved

EOF
   exit 1
fi

echo "==> transforming $SOURCE into wiki pages"

# Everything the wiki previously held is removed rather than merged over, so a page deleted from docs/wiki
# disappears from the wiki too instead of lingering as an orphan nobody remembers publishing.
find "$WORK/wiki" -mindepth 1 -maxdepth 1 -not -name '.git' -exec rm -rf {} +

python3 - "$SOURCE" "$WORK/wiki" <<'PYTHON'
import pathlib
import re
import shutil
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])


def page_name(filename):
    """docs/wiki/getting-started.md becomes the wiki page Getting-started."""
    if filename == "README.md":
        return "Home"
    stem = filename[:-3] if filename.endswith(".md") else filename
    return stem[0].upper() + stem[1:]


# The sidebar follows the index's own order rather than the alphabet, because the index is written to be read
# start to finish and alphabetical order would put Items before Getting started.
index = (source / "README.md").read_text(encoding="utf-8")
ordered = [m for m in re.findall(r"\]\((?!https?:)([\w-]+\.md)\)", index)]
seen = set()
ordered = [p for p in ordered if not (p in seen or seen.add(p))]
for extra in sorted(p.name for p in source.glob("*.md")):
    if extra != "README.md" and extra not in ordered:
        ordered.append(extra)

for page in sorted(source.glob("*.md")):
    text = page.read_text(encoding="utf-8")

    # Rewrite links between pages. Everything else, including links out to necessewiki.com, is left alone.
    def relink(match):
        return "](" + page_name(match.group(1)) + ")"

    text = re.sub(r"\]\((?!https?:)([\w-]+\.md)\)", relink, text)

    out = target / f"{page_name(page.name)}.md"
    out.write_text(text, encoding="utf-8")
    print(f"    {page.name:24} -> {out.name}")

for folder in ("images", "screenshots"):
    if (source / folder).is_dir():
        shutil.copytree(source / folder, target / folder)
        count = len(list((source / folder).glob("*")))
        print(f"    {folder + '/':24} -> {folder}/  ({count} files)")

sidebar = ["### Arcane Storage", "", "[Home](Home)", ""]
for name in ordered:
    title = re.sub(r"^#\s*(.+)", r"\1", (source / name).read_text(encoding="utf-8").splitlines()[0]).strip()
    sidebar.append(f"[{title}]({page_name(name)})")
sidebar += ["", "---", "", "[Source repository](https://github.com/EliasVahlberg/arcane-storage)"]
(target / "_Sidebar.md").write_text("\n".join(sidebar) + "\n", encoding="utf-8")
print(f"    {'_Sidebar.md':24} -> generated, {len(ordered)} pages")
PYTHON

cd "$WORK/wiki"

if git diff --quiet && git diff --cached --quiet && [[ -z "$(git status --porcelain)" ]]; then
   echo "==> the wiki already matches $SOURCE, nothing to publish"
   exit 0
fi

git add -A
echo
echo "==> changes to publish"
git status --short

if [[ "$DRY_RUN" == 1 ]]; then
   echo
   echo "==> dry run, nothing pushed"
   exit 0
fi

# The wiki's history is a log of publications rather than of authorship, so the message records the source commit
# that produced it. That is the only thing about a published copy worth being able to look up later.
git -c commit.gpgsign=false commit --quiet -m "Publish docs/wiki from $SOURCE_COMMIT"
git push --quiet origin HEAD
echo
echo "==> published. https://github.com/$REPO/wiki"
