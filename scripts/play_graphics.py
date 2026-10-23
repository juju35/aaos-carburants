"""Google Play graphics: 512 px icon and 1024 x 500 feature graphic, from the app's own icon and fuel colours.

Usage: python scripts/play_graphics.py  (needs `pip install playwright pillow` and `playwright install chromium`)
Writes play/graphics/icon-512.png and play/graphics/feature-graphic.png.
"""

from __future__ import annotations

import io
import re
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "play" / "graphics"

# Launcher icon: colour and pump pictogram (adaptive icon foreground, 108 dp canvas).
BACKGROUND = re.search(r'name="launcher_background">(#[0-9A-Fa-f]{6})<', (RES / "values" / "colors.xml").read_text())[1]
PUMP = re.search(r'pathData="([^"]+)"', (RES / "drawable" / "ic_launcher_foreground.xml").read_text())[1]

# Name at the pump, EU code, colours (data/Models.kt).
FUELS = [
    ("Gazole", "B7", "#E8A000", "#1A1A1A"),
    ("SP95", "E5", "#00A651", "#FFFFFF"),
    ("SP98", "E5", "#00703C", "#FFFFFF"),
    ("SP95", "E10", "#009A3E", "#FFFFFF"),
    ("E85", "", "#0057A8", "#FFFFFF"),
    ("GPLc", "", "#C62828", "#FFFFFF"),
]


def icon_svg(size: int) -> str:
    """Full-bleed square: Google Play applies its own mask and shadow."""
    return (
        f"<svg xmlns='http://www.w3.org/2000/svg' width='{size}' height='{size}' viewBox='0 0 108 108'>"
        f"<rect width='108' height='108' fill='{BACKGROUND}'/>"
        f"<g transform='translate(27 27) scale(2.25)'><path fill='#fff' d='{PUMP}'/></g></svg>"
    )


def badge(label: str, code: str, bg: str, fg: str) -> str:
    code_html = f"<span class='code' style='color:{bg}'>{code}</span>" if code else ""
    return f"<div class='badge' style='background:{bg};color:{fg}'><span>{label}</span>{code_html}</div>"


FEATURE = """<!doctype html><html><head><meta charset='utf-8'><style>
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
body {{ width: 1024px; height: 500px; overflow: hidden; font-family: Inter, Roboto, sans-serif;
       background: radial-gradient(circle at 78% 40%, #15a874 0%, {bg} 45%, #0a5c40 100%); color: #fff;
       display: flex; align-items: center; padding: 0 64px; gap: 48px; }}
.icon {{ width: 200px; height: 200px; border-radius: 46px; overflow: hidden; flex: none;
        box-shadow: 0 18px 40px rgba(0,0,0,.35); }}
h1 {{ font-size: 76px; font-weight: 800; letter-spacing: -1px; }}
p {{ font-size: 27px; font-weight: 500; margin-top: 10px; opacity: .95; line-height: 1.3; white-space: nowrap; }}
.badges {{ display: flex; gap: 12px; margin-top: 30px; }}
.badge {{ width: 78px; height: 78px; border-radius: 18px; display: flex; flex-direction: column; align-items: center;
         justify-content: center; gap: 5px; font-weight: 800; font-size: 19px; box-shadow: 0 6px 16px rgba(0,0,0,.25); }}
.code {{ background: #fff; border-radius: 12px; font-size: 16px; padding: 1px 10px; }}
</style></head><body>
<div class='icon'>{icon}</div>
<div><h1>Carburants</h1><p>Les stations les moins chères autour de vous,<br>sur l'écran de votre voiture.</p>
<div class='badges'>{badges}</div></div>
</body></html>"""


def screenshot(page, html: str, width: int, height: int) -> Image.Image:
    page.set_viewport_size({"width": width, "height": height})
    page.set_content(html)
    page.wait_for_load_state("load")
    return Image.open(io.BytesIO(page.screenshot()))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        icon = screenshot(page, f"<body style='margin:0'>{icon_svg(512)}</body>", 512, 512)
        icon.convert("RGB").save(OUT / "icon-512.png", optimize=True)
        html = FEATURE.format(bg=BACKGROUND, icon=icon_svg(200), badges="".join(badge(*f) for f in FUELS))
        # The feature graphic must have no alpha channel.
        screenshot(page, html, 1024, 500).convert("RGB").save(OUT / "feature-graphic.png", optimize=True)
        browser.close()
    print("play/graphics/icon-512.png\nplay/graphics/feature-graphic.png")


if __name__ == "__main__":
    main()
