#!/usr/bin/env python3
"""Check gamedata/game_data.json for broken references and non-positive rates.

Run after tools/build_game_data.py. The Gradle unit tests repeat these checks
and additionally lock known Satisfactory 1.0 rates.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "gamedata" / "game_data.json"


def main() -> int:
    data = json.loads(PATH.read_text(encoding="utf-8"))
    errors: list[str] = []
    items = {item["id"]: item for item in data["items"]}
    buildings = {b["id"]: b for b in data["buildings"]}
    recipes = {r["id"]: r for r in data["recipes"]}
    if len(items) != len(data["items"]):
        errors.append("duplicate item id")
    if len(buildings) != len(data["buildings"]):
        errors.append("duplicate building id")
    if len(recipes) != len(data["recipes"]):
        errors.append("duplicate recipe id")
    if data.get("gameVersion") != "1.0":
        errors.append("gameVersion must be 1.0")
    for item in data["items"]:
        if item["raw"]:
            if not item.get("extractorId") or item["extractorId"] not in buildings:
                errors.append(f"{item['id']}: raw item needs a known extractor")
            if not item.get("extractPerMinute") or item["extractPerMinute"] <= 0:
                errors.append(f"{item['id']}: extractPerMinute must be positive")
        default = item.get("defaultRecipeId")
        if default and default not in recipes:
            errors.append(f"{item['id']}: unknown defaultRecipeId {default}")
    for recipe in data["recipes"]:
        if recipe["buildingId"] not in buildings:
            errors.append(f"{recipe['id']}: unknown building")
        if recipe["cycleSeconds"] <= 0:
            errors.append(f"{recipe['id']}: cycleSeconds")
        if not recipe["outputs"]:
            errors.append(f"{recipe['id']}: no outputs")
        for stack in recipe["inputs"] + recipe["outputs"]:
            if stack["itemId"] not in items:
                errors.append(f"{recipe['id']}: unknown item {stack['itemId']}")
            if stack["amount"] <= 0:
                errors.append(f"{recipe['id']}: non-positive amount")
            per_min = stack["amount"] / recipe["cycleSeconds"] * 60.0
            if per_min <= 0:
                errors.append(f"{recipe['id']}: bad per-minute rate")
    standards: dict[str, list[str]] = {}
    for recipe in data["recipes"]:
        if recipe["alternate"]:
            continue
        primary = recipe["outputs"][0]["itemId"]
        standards.setdefault(primary, []).append(recipe["id"])
    for item_id, recipe_ids in standards.items():
        if len(recipe_ids) > 1:
            default = items[item_id].get("defaultRecipeId")
            if default not in recipe_ids:
                errors.append(f"{item_id}: multiple standard recipes {recipe_ids} need defaultRecipeId")
    if not any(r["alternate"] for r in data["recipes"]):
        errors.append("expected alternate recipes")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"ok: {len(items)} items, {len(recipes)} recipes, {len(buildings)} buildings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
