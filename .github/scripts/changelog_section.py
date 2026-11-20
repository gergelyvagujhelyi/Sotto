"""Prints one version's section of CHANGELOG.md, for the release notes.

Usage: changelog_section.py <version> <changelog>

The section runs from the "## <version> " heading to the next "## " heading. Prints nothing
when the changelog has no section for that version.
"""

import sys


def section(version: str, text: str) -> str:
    lines, inside = [], False
    for line in text.splitlines():
        if line.startswith("## "):
            if inside:
                break
            inside = line.startswith(f"## {version} ")
            continue
        if inside:
            lines.append(line)
    return "\n".join(lines).strip()


if __name__ == "__main__":
    with open(sys.argv[2], encoding="utf-8") as changelog:
        found = section(sys.argv[1], changelog.read())
    if found:
        print(found)
