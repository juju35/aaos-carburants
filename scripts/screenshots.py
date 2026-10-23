"""README screenshots: mock-ups of the app's three templates as the Android Automotive host draws them.

Usage: python scripts/screenshots.py  (needs `pip install playwright pillow` and `playwright install chromium`)

The pages copy the templates host layout (dark theme, 1408 x 792 like the AAOS emulator) and the
app's own drawing rules: logo tiles and initials badges (ui/Badges.kt), price and distance lines
(ui/StationListScreen.kt), detail rows (ui/StationDetailScreen.kt). Data is made up, around Rennes.
The map is built from the OpenStreetMap tiles in scripts/tiles, no network needed.
"""

from __future__ import annotations

import base64
import html
import io
import math
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
TILES = ROOT / "scripts" / "tiles"
LOGOS = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
OUT = ROOT / "screenshots"
WIDTH, HEIGHT = 1408, 792
ZOOM = 14

HERE = (48.1105, -1.6795)
# Where the car sits on screen: right of the list panel.
HERE_PX = (930, 430)
PANEL_RIGHT = 24 + 520

# ── Mock stations ────────────────────────────────────────────────────────────


STATIONS = [
    {
        "name": "Leclerc Rennes Colombier", "brand": "E.Leclerc", "lat": 48.1120, "lon": -1.6575,
        "address": "Boulevard de la Liberté", "status": ("Ouvert · ferme à 21:00", "open"),
        "prices": {"gazole": 1.689, "sp95": 1.789, "sp98": 1.849, "e10": 1.729, "e85": 0.829, "gplc": None},
        "extras": "Automate 24h/24 · Boutique alimentaire · Lavage",
    },
    {
        "name": "Super U Rennes Sud", "brand": "Super U", "lat": 48.0985, "lon": -1.6733,
        "status": ("Ouvert · ferme à 20:00", "open"), "prices": {"gazole": 1.709, "e10": 1.749, "sp98": 1.859},
    },
    {
        "name": "Total Access Rennes Saint-Malo", "brand": "Total Access", "lat": 48.1235, "lon": -1.6905,
        "logo": "logo_total_access.png", "status": ("Ouvert 24h/24", "open"), "prices": {"gazole": 1.719, "e10": 1.759, "sp98": None},
    },
    {
        "name": "Carrefour Market Thabor", "brand": "Carrefour Market", "lat": 48.1188, "lon": -1.6921,
        "status": ("Ouvert · ferme à 20:30", "open"), "prices": {"gazole": 1.749, "e10": 1.769},
    },
    {
        "name": "TotalEnergies Rennes Alma", "brand": "TotalEnergies", "lat": 48.1012, "lon": -1.6838,
        "logo": "logo_totalenergies.png", "address": "Rue de l'Alma", "status": ("Ouvert 24h/24", "open"),
        "prices": {"gazole": 1.769, "sp95": 1.839, "sp98": 1.879, "e10": 1.789, "e85": None, "gplc": 1.049},
        "extras": "Boutique alimentaire · Lavage · Gonflage",
    },
    {
        "name": "Esso Rennes Nord", "brand": "Esso", "lat": 48.1255, "lon": -1.6760,
        "status": ("Automate 24h/24", "automate"), "prices": {"gazole": 1.789, "e10": 1.809, "sp98": 1.899},
    },
]

FUELS = [
    # key, label, EU code, colour, text colour, description (as Fuel in data/Models.kt)
    ("gazole", "Gazole", "B7", "#E8A000", "#1A1A1A", "Diesel"),
    ("sp95", "SP95", "E5", "#00A651", "#FFFFFF", "Sans plomb 95"),
    ("sp98", "SP98", "E5", "#00703C", "#FFFFFF", "Sans plomb 98"),
    ("e10", "SP95-E10", "E10", "#009A3E", "#FFFFFF", "Sans plomb 95-E10"),
    ("e85", "E85", "", "#0057A8", "#FFFFFF", "Superéthanol"),
    ("gplc", "GPLc", "", "#C62828", "#FFFFFF", "Gaz de pétrole liquéfié"),
]

# Chosen in the picker (a petrol car): the list shows both, sorted on the cheaper one.
CHOSEN = ["e10", "sp98"]
SHORT = {"e10": "E10", "sp98": "SP98", "sp95": "SP95", "gazole": "Gazole", "e85": "E85", "gplc": "GPLc"}

PALETTE = ["#1565c0", "#2e7d32", "#c62828", "#6a1b9a", "#ef6c00", "#00838f", "#4e342e", "#283593"]

# ── Helpers (same rules as the app) ──────────────────────────────────────────


def distance_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(a))


def fmt_distance(km):
    if km < 1:
        return f"{round(km * 100) * 10} m"
    return f"{km:.1f} km".replace(".", ",")


def fmt_price(p):
    return f"{p:.3f} €".replace(".", ",")


def brand_color(name):
    h = 0
    for ch in name:
        h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return PALETTE[h % len(PALETTE)]


def initials(name):
    import re

    words = [w for w in re.sub(r"[^A-Za-zÀ-ÿ0-9 ]", " ", name).split() if w]
    if not words:
        return "?"
    return (words[0][:2] if len(words) == 1 else words[0][0] + words[1][0]).upper()


def data_uri(path: Path) -> str:
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode()


def logo(st, size):
    """Logo on a white tile, else the initials badge (Badges.logoTile / initialsBadge)."""
    radius = round(size * 0.22)
    if st.get("logo"):
        return (
            f'<div class="tile" style="width:{size}px;height:{size}px;border-radius:{radius}px;background:#fff">'
            f'<img src="{data_uri(LOGOS / st["logo"])}" style="width:80%;height:80%"></div>'
        )
    return (
        f'<div class="tile" style="width:{size}px;height:{size}px;border-radius:{radius}px;'
        f'background:{brand_color(st["brand"])};font-size:{round(size * 0.42)}px">{initials(st["brand"])}</div>'
    )


def fuel_badge(fuel, size):
    key, label, code, bg, fg, _ = fuel
    label = "SP95" if key == "e10" else label
    radius = round(size * 0.22)
    inner = (
        f'<span style="font-size:{round(size * 0.26)}px">{label}</span>'
        f'<span class="code" style="color:{bg};font-size:{round(size * 0.22)}px;height:{round(size * 0.30)}px;'
        f'width:{round(size * 0.62)}px;border-radius:{round(size * 0.15)}px">{code}</span>'
        if code
        else f'<span style="font-size:{round(size * 0.30)}px">{label}</span>'
    )
    return (
        f'<div class="fbadge" style="width:{size}px;height:{size}px;border-radius:{radius}px;'
        f'background:{bg};color:{fg}">{inner}</div>'
    )


# ── Map ──────────────────────────────────────────────────────────────────────


def world_px(lat, lon):
    n = 256 * 2**ZOOM
    x = (lon + 180) / 360 * n
    y = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n
    return x, y


def screen_px(lat, lon):
    hx, hy = world_px(*HERE)
    x, y = world_px(lat, lon)
    return round(HERE_PX[0] + x - hx), round(HERE_PX[1] + y - hy)


def map_image() -> str:
    """The screen-sized map, cut from the local tiles."""
    hx, hy = world_px(*HERE)
    left, top = hx - HERE_PX[0], hy - HERE_PX[1]
    canvas = Image.new("RGB", (WIDTH, HEIGHT), "#f2efe9")
    for tx in range(int(left // 256), int((left + WIDTH) // 256) + 1):
        for ty in range(int(top // 256), int((top + HEIGHT) // 256) + 1):
            path = TILES / str(ZOOM) / str(tx) / f"{ty}.png"
            if not path.exists():
                if tx * 256 - left + 256 <= PANEL_RIGHT:
                    continue  # Under the list panel, never seen.
                raise SystemExit(f"missing tile {path}")
            canvas.paste(Image.open(path).convert("RGB"), (round(tx * 256 - left), round(ty * 256 - top)))
    buf = io.BytesIO()
    canvas.save(buf, "PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


# ── Pages ────────────────────────────────────────────────────────────────────

CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body { width: 1408px; height: 792px; overflow: hidden; background: #0b0c0e; color: #e8eaed;
       font-family: Inter, Roboto, sans-serif; -webkit-font-smoothing: antialiased; position: relative; }
.tile { display: flex; align-items: center; justify-content: center; color: #fff; font-weight: 700; flex: none; }
.fbadge { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px;
          font-weight: 700; flex: none; }
.code { display: flex; align-items: center; justify-content: center; background: #fff; }
.secondary { color: #a8adb4; }
.green { color: #6ee7a0; } .red { color: #ff8a80; } .yellow { color: #fdd663; }
.header { display: flex; align-items: center; gap: 20px; height: 88px; padding: 0 28px; font-size: 30px; font-weight: 600; }
.round { width: 56px; height: 56px; border-radius: 28px; display: flex; align-items: center; justify-content: center;
         background: #2d3035; flex: none; }
.appicon { width: 52px; height: 52px; border-radius: 14px; background: #0E8A5F; display: flex;
           align-items: center; justify-content: center; flex: none; }
svg.i { width: 30px; height: 30px; fill: #e8eaed; flex: none; }
"""

GAS = (
    "M19.77,7.23l0.01,-0.01 -3.72,-3.72L15,4.56l2.11,2.11c-0.94,0.36 -1.61,1.26 -1.61,2.33 0,1.38 1.12,2.5 2.5,2.5 "
    "0.36,0 0.69,-0.08 1,-0.21v7.21c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1L17,14c0,-1.1 -0.9,-2 -2,-2h-1L14,5c0,-1.1 "
    "-0.9,-2 -2,-2L6,3c-1.1,0 -2,0.9 -2,2v16h10v-7.5h1.5v5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5L20.5,9c0,"
    "-0.69 -0.28,-1.32 -0.73,-1.77zM12,10L6,10L6,5h6v5zM18,10c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 "
    "-0.45,1 -1,1z"
)
SORT = "M16,17.01V10h-2v7.01h-3L15,21l4,-3.99h-3zM9,3L5,6.99h3V14h2V6.99h3L9,3z"
NAV = "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z"
PLACE = (
    "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 "
    "-2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"
)
CLOCK = (
    "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 "
    "-8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z"
)
BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"


def svg(path, size=30, color="#e8eaed"):
    return f'<svg class="i" viewBox="0 0 24 24" style="width:{size}px;height:{size}px;fill:{color}"><path d="{path}"/></svg>'


def page(body, extra_css=""):
    return f"<!doctype html><html><head><meta charset='utf-8'><style>{CSS}{extra_css}</style></head><body>{body}</body></html>"


def sorted_stations():
    for st in STATIONS:
        st["distance"] = distance_km(*HERE, st["lat"], st["lon"])
    return sorted(STATIONS, key=lambda s: (best(s)[1], s["distance"]))


def best(st):
    """Cheapest in-stock price among the chosen fuels (Station.best)."""
    found = [(k, st["prices"][k]) for k in CHOSEN if st["prices"].get(k)]
    return min(found, key=lambda kv: kv[1]) if found else (None, 99.0)


def list_page():
    stations = sorted_stations()
    cheapest = best(stations[0])[1]
    rows = []
    for st in stations:
        best_key, best_price = best(st)
        parts = []
        for key in CHOSEN:
            if key not in st["prices"]:
                continue  # Not sold here.
            price = st["prices"][key]
            if price is None:
                value = '<span class="red">rupture</span>'
            elif key == best_key and best_price == cheapest:
                value = f'<span class="green">{fmt_price(price)}</span>'
            else:
                value = fmt_price(price)
            parts.append(f"{SHORT[key]} {value}")
        price_html = " · ".join(parts)
        status, state = st["status"]
        cls = {"open": "green", "automate": "yellow", "closed": "red"}[state]
        rows.append(
            f"""<div class="row">{logo(st, 72)}<div class="txt">
            <div class="t">{html.escape(st['name'])}</div>
            <div class="l">{fmt_distance(st['distance'])} · {price_html}</div>
            <div class="l secondary">Rennes · <span class="{cls}">{status}</span></div></div></div>"""
        )
    markers = []
    for st in stations:
        x, y = screen_px(st["lat"], st["lon"])
        ring = "#34c47c" if best(st)[1] == cheapest else "#ffffff"
        markers.append(
            f'<div class="marker" style="left:{x - 26}px;top:{y - 26}px;border-color:{ring}">{logo(st, 44)}</div>'
        )
    hx, hy = HERE_PX
    body = f"""
    <img class="map" src="{map_image()}">
    {''.join(markers)}
    <div class="me" style="left:{hx - 16}px;top:{hy - 16}px"></div>
    <div class="panel">
      <div class="header"><div class="appicon">{svg(GAS, 32, '#fff')}</div>Stations à proximité</div>
      {''.join(rows)}
    </div>
    <div class="strip">
      <div class="chip">{svg(GAS, 26)}E10, SP98</div>
      <div class="chip">{svg(SORT, 26)}Prix</div>
    </div>
    <div class="attrib">© OpenStreetMap</div>
    """
    css = """
    .map { position: absolute; inset: 0; filter: invert(0.92) hue-rotate(180deg) brightness(0.95) contrast(0.9) saturate(0.6); }
    .panel { position: absolute; left: 24px; top: 24px; width: 520px; height: 744px; border-radius: 28px;
             background: rgba(32, 33, 36, 0.97); box-shadow: 0 8px 32px rgba(0,0,0,.5); overflow: hidden; }
    .row { display: flex; align-items: center; gap: 20px; height: 109px; padding: 0 24px;
           border-top: 1px solid #3c4043; }
    .txt { min-width: 0; }
    .t { font-size: 25px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 380px; }
    .l { font-size: 21px; margin-top: 3px; white-space: nowrap; }
    .marker { position: absolute; width: 52px; height: 52px; border-radius: 14px; border: 4px solid #fff;
              overflow: hidden; box-shadow: 0 3px 10px rgba(0,0,0,.6); display: flex; }
    .marker .tile { border-radius: 0 !important; width: 44px !important; height: 44px !important; font-size: 19px !important; }
    .me { position: absolute; width: 32px; height: 32px; border-radius: 16px; background: #4285f4; border: 5px solid #fff;
          box-shadow: 0 0 0 14px rgba(66,133,244,.25); }
    .strip { position: absolute; right: 24px; top: 24px; display: flex; gap: 12px; }
    .chip { display: flex; align-items: center; gap: 10px; height: 60px; padding: 0 26px 0 20px; border-radius: 30px;
            background: rgba(45,48,53,.97); font-size: 23px; font-weight: 600; box-shadow: 0 4px 16px rgba(0,0,0,.5); }
    .attrib { position: absolute; right: 10px; bottom: 6px; font-size: 13px; color: #c9ccd1; text-shadow: 0 0 3px #000; }
    """
    return page(body, css)


def detail_page():
    st = STATIONS[4]
    st["distance"] = distance_km(*HERE, st["lat"], st["lon"])
    others = []
    for key, label, *_ in [f for f in FUELS if f[0] not in CHOSEN]:
        price = st["prices"].get(key)
        if price is None and key not in st["prices"]:
            continue  # Not sold here.
        others.append(f"{label} {fmt_price(price)}" if price else f'{label} <span class="red">rupture</span>')
    lines = ["  ·  ".join(others[i : i + 3]) for i in range(0, len(others), 3)]
    rows = [
        (
            fuel_badge(next(f for f in FUELS if f[0] == "e10"), 64),
            f"SP95-E10 · {fmt_price(st['prices']['e10'])}/L",
            [f"SP98 {fmt_price(st['prices']['sp98'])}", "Mis à jour il y a 2 heures"],
        ),
        ('<div style="width:64px"></div>', "Autres carburants", lines),
        (f'<div class="ic">{svg(PLACE, 34)}</div>', st["address"], [f"35000 Rennes · {fmt_distance(st['distance'])}"]),
        (f'<div class="ic">{svg(CLOCK, 34)}</div>', st["status"][0], [st["extras"]]),
    ]
    rows_html = "".join(
        f'<div class="prow">{img}<div><div class="t">{t}</div>'
        + "".join(f'<div class="l secondary">{line}</div>' for line in texts)
        + "</div></div>"
        for img, t, texts in rows
    )
    body = f"""
    <div class="header" style="height:104px"><div class="round">{svg(BACK, 32)}</div>{html.escape(st['name'])}</div>
    <div class="pane">
      <div class="rows">{rows_html}</div>
      <div class="side">{logo(st, 236)}</div>
    </div>
    <div class="actions"><div class="primary">{svg(NAV, 30, '#0b0c0e')}Y aller</div></div>
    """
    css = """
    .pane { display: flex; gap: 40px; padding: 0 40px 0 40px; }
    .rows { flex: 1; }
    .prow { display: flex; align-items: center; gap: 24px; min-height: 120px; padding: 14px 0; border-bottom: 1px solid #3c4043; }
    .prow:last-child { border-bottom: none; }
    .ic { width: 64px; display: flex; justify-content: center; flex: none; }
    .t { font-size: 26px; font-weight: 600; }
    .l { font-size: 21px; margin-top: 5px; }
    .side { width: 300px; display: flex; justify-content: center; padding-top: 40px; }
    .side .tile { font-size: 96px !important; }
    .actions { position: absolute; left: 40px; right: 40px; bottom: 28px; display: flex; justify-content: flex-end; }
    .primary { display: flex; align-items: center; gap: 14px; height: 72px; padding: 0 44px 0 34px; border-radius: 36px;
               background: #8ab4f8; color: #0b0c0e; font-size: 26px; font-weight: 700; }
    """
    return page(body, css)


def fuel_page():
    rows = []
    for i, fuel in enumerate(FUELS):
        key, label, code, *_ , desc = fuel
        radio = '<div class="switch on"><div></div></div>' if key in CHOSEN else '<div class="switch"><div></div></div>'
        rows.append(
            f'<div class="frow">{fuel_badge(fuel, 64)}<div class="ft"><div class="t">{label}</div>'
            f'<div class="l secondary">{desc}{" · " + code if code else ""}</div></div>{radio}</div>'
        )
    body = f"""
    <div class="header" style="height:96px"><div class="round">{svg(BACK, 32)}</div>Carburants de votre véhicule</div>
    <div class="list">{''.join(rows)}</div>
    """
    css = """
    .list { padding: 0 40px; }
    .frow { display: flex; align-items: center; gap: 24px; height: 114px; border-bottom: 1px solid #3c4043; }
    .frow:last-child { border-bottom: none; }
    .ft { flex: 1; }
    .t { font-size: 26px; font-weight: 600; }
    .l { font-size: 21px; margin-top: 4px; }
    .switch { width: 64px; height: 36px; border-radius: 18px; background: #5f6368; position: relative; flex: none; }
    .switch div { position: absolute; top: 4px; left: 4px; width: 28px; height: 28px; border-radius: 14px; background: #c4c7c5; }
    .switch.on { background: #8ab4f8; }
    .switch.on div { left: 32px; background: #0b0c0e; }
    """
    return page(body, css)


def main():
    OUT.mkdir(exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": WIDTH, "height": HEIGHT}, device_scale_factor=1)
        page_ = ctx.new_page()
        for name, html_ in (("list", list_page()), ("detail", detail_page()), ("fuel", fuel_page())):
            page_.set_content(html_)
            page_.wait_for_load_state("load")
            page_.screenshot(path=str(OUT / f"{name}.png"))
            print(f"screenshots/{name}.png")
        browser.close()


if __name__ == "__main__":
    main()
