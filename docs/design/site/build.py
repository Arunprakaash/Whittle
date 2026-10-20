"""Builds the design-system page from the token file, so the page can never drift from the tokens.

Usage: python3 docs/design/site/build.py
"""

import json
from pathlib import Path

SITE_DIR = Path(__file__).resolve().parent
TOKENS_FILE = SITE_DIR.parent / "tokens" / "whittle.tokens.json"
TEMPLATE_FILE = SITE_DIR / "template.html"
OUTPUT_FILE = SITE_DIR / "whittle-design-system.html"
PLACEHOLDER = "__WHITTLE_TOKENS__"


def main() -> None:
    tokens = json.loads(TOKENS_FILE.read_text(encoding="utf-8"))
    # Escape "</" so token text can never close the surrounding <script> element.
    payload = json.dumps(tokens, ensure_ascii=False).replace("</", "<\\/")
    template = TEMPLATE_FILE.read_text(encoding="utf-8")
    if template.count(PLACEHOLDER) != 1:
        raise SystemExit(f"{TEMPLATE_FILE.name} must contain {PLACEHOLDER} exactly once")
    OUTPUT_FILE.write_text(template.replace(PLACEHOLDER, payload), encoding="utf-8")
    print(f"Wrote {OUTPUT_FILE.relative_to(SITE_DIR.parents[2])}")


if __name__ == "__main__":
    main()
