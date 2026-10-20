"""Builds the design-system page from the token file, so the page can never drift from the tokens.

Usage: python3 docs/design/site/build.py
"""

import json
from pathlib import Path

SITE_DIR = Path(__file__).resolve().parent
TOKENS_FILE = SITE_DIR.parent / "tokens" / "whittle.tokens.json"
SPRITE_FILE = SITE_DIR.parent / "sprites" / "beaver.sprite.json"
TEMPLATE_FILE = SITE_DIR / "template.html"
OUTPUT_FILE = SITE_DIR / "whittle-design-system.html"
PLACEHOLDERS = {"__WHITTLE_TOKENS__": TOKENS_FILE, "__WHITTLE_SPRITE__": SPRITE_FILE}


def main() -> None:
    page = TEMPLATE_FILE.read_text(encoding="utf-8")
    for placeholder, source in PLACEHOLDERS.items():
        if page.count(placeholder) != 1:
            raise SystemExit(f"{TEMPLATE_FILE.name} must contain {placeholder} exactly once")
        data = json.loads(source.read_text(encoding="utf-8"))
        # Escape "</" so the data can never close the surrounding <script> element.
        page = page.replace(placeholder, json.dumps(data, ensure_ascii=False).replace("</", "<\\/"))
    OUTPUT_FILE.write_text(page, encoding="utf-8")
    print(f"Wrote {OUTPUT_FILE.relative_to(SITE_DIR.parents[2])}")


if __name__ == "__main__":
    main()
