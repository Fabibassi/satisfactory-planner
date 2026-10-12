#!/usr/bin/env python3
"""Download Satisfactory item and building icons from the community wiki.

Source: https://satisfactory.wiki.gg/ file repository (in-game art published
for the community wiki). Artwork remains © Coffee Stain Studios.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "gamedata" / "game_data.json"
OUT = ROOT / "gamedata" / "icons"
WIKI = "https://satisfactory.wiki.gg/wiki/Special:FilePath/"
API = "https://satisfactory.wiki.gg/api.php"
UA = "FabrikplanerIconFetch/1.0 (unofficial fan project; Satisfactory icons via satisfactory.wiki.gg)"

# Wiki file titles that differ from the English display name.
ALIASES = {
    "miner_mk1": "Miner Mk.1",
    "oil_extractor": "Oil Extractor",
    "water_extractor": "Water Extractor",
    "aluminum_ingot": "Aluminum Ingot",
    "aluminum_scrap": "Aluminum Scrap",
    "aluminum_casing": "Aluminum Casing",
    "high_speed_connector": "High-Speed Connector",
    "ai_limiter": "AI Limiter",
    "heavy_oil_residue": "Heavy Oil Residue",
}


def wiki_name(entry_id: str, english: str) -> str:
    return ALIASES.get(entry_id, english)


def file_title(name: str) -> str:
    return name.replace(" ", "_") + ".png"


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
        final = response.geturl()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError(f"not a png: {final} ({len(data)} bytes)")
    return data


def search_file(name: str) -> str | None:
    query = urllib.parse.urlencode(
        {
            "action": "query",
            "list": "search",
            "srsearch": name,
            "srnamespace": 6,
            "srlimit": 8,
            "format": "json",
        }
    )
    request = urllib.request.Request(f"{API}?{query}", headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.load(response)
    for hit in payload.get("query", {}).get("search", []):
        title = hit.get("title", "")
        if title.startswith("File:") and title.lower().endswith(".png"):
            return title.removeprefix("File:")
    return None


def download_one(entry_id: str, english: str, folder: Path) -> dict:
    name = wiki_name(entry_id, english)
    title = file_title(name)
    url = WIKI + urllib.parse.quote(title)
    try:
        data = fetch(url)
        used = title
    except (urllib.error.HTTPError, urllib.error.URLError, RuntimeError):
        found = search_file(english)
        if not found:
            raise RuntimeError(f"no wiki file for {entry_id} ({english})")
        data = fetch(WIKI + urllib.parse.quote(found))
        used = found
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{entry_id}.png").write_bytes(data)
    return {"id": entry_id, "wikiFile": used, "bytes": len(data)}


def main() -> None:
    game = json.loads(DATA.read_text())
    items = [download_one(item["id"], item["name"]["en"], OUT / "items") for item in game["items"]]
    time.sleep(0.1)
    buildings = [
        download_one(building["id"], building["name"]["en"], OUT / "buildings")
        for building in game["buildings"]
    ]
    manifest = {
        "source": "https://satisfactory.wiki.gg/",
        "retrieved": "2026-10-12",
        "note": (
            "In-game item and building icons as published in the Satisfactory community wiki "
            "file repository. The artwork belongs to Coffee Stain Studios."
        ),
        "items": items,
        "buildings": buildings,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"items {len(items)} buildings {len(buildings)}")


if __name__ == "__main__":
    main()
