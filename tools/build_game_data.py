#!/usr/bin/env python3
"""Build gamedata/game_data.json for the Fabrikplaner app.

Source: public Satisfactory 1.0 crafting tables on https://satisfactory.wiki.gg
(items, buildings and recipes, including alternate recipes). Numbers are the
in-game per-cycle amounts and machine cycle times, cross-checked against the
wiki "per minute" column (amount / cycleSeconds * 60).

Notable 1.0 changes versus Update 8 that this file follows:
- Rotor is 4/min (5 iron rods + 25 screws, 15 s), not the old 5/min recipe.
- Modular Frame is 2/min (60 s).
- Heavy Modular Frame costs 20 steel pipes and 120 screws per cycle (patch 1.0).
- Rubber yields 20 m³ Heavy Oil Residue / min, Plastic yields 10.

This is an unofficial fan compilation. Satisfactory and its item names are
trademarks of Coffee Stain Studios. No game assets are redistributed.

Usage:
    python3 tools/build_game_data.py
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "gamedata" / "game_data.json"


def name(de: str, en: str) -> dict:
    return {"de": de, "en": en}


def amount(item_id: str, value: float) -> dict:
    return {"itemId": item_id, "amount": value}


def item(
    item_id: str,
    de: str,
    en: str,
    form: str,
    category: str,
    raw: bool = False,
    default_recipe: str | None = None,
    extractor: str | None = None,
    extract_per_minute: float | None = None,
) -> dict:
    data = {
        "id": item_id,
        "name": name(de, en),
        "form": form,
        "raw": raw,
        "category": category,
    }
    if default_recipe:
        data["defaultRecipeId"] = default_recipe
    if extractor:
        data["extractorId"] = extractor
        data["extractPerMinute"] = extract_per_minute
    return data


def recipe(
    recipe_id: str,
    de: str,
    en: str,
    building: str,
    seconds: float,
    inputs: list[tuple[str, float]],
    outputs: list[tuple[str, float]],
    alternate: bool = False,
) -> dict:
    return {
        "id": recipe_id,
        "name": name(de, en),
        "alternate": alternate,
        "buildingId": building,
        "cycleSeconds": seconds,
        "inputs": [amount(i, a) for i, a in inputs],
        "outputs": [amount(i, a) for i, a in outputs],
    }


def build() -> dict:
    miner = ("miner_mk1", 60)
    items = [
        item("iron_ore", "Eisenerz", "Iron Ore", "solid", "raw", True, extractor=miner[0], extract_per_minute=miner[1]),
        item("copper_ore", "Kupfererz", "Copper Ore", "solid", "raw", True, extractor=miner[0], extract_per_minute=miner[1]),
        item("limestone", "Kalkstein", "Limestone", "solid", "raw", True, extractor=miner[0], extract_per_minute=miner[1]),
        item("coal", "Kohle", "Coal", "solid", "raw", True, extractor=miner[0], extract_per_minute=miner[1]),
        item("caterium_ore", "Caterium-Erz", "Caterium Ore", "solid", "raw", True, extractor=miner[0], extract_per_minute=miner[1]),
        item("raw_quartz", "Rohquarz", "Raw Quartz", "solid", "raw", True, extractor=miner[0], extract_per_minute=miner[1]),
        item("bauxite", "Bauxit", "Bauxite", "solid", "raw", True, extractor=miner[0], extract_per_minute=miner[1]),
        item("crude_oil", "Rohöl", "Crude Oil", "liquid", "raw", True, extractor="oil_extractor", extract_per_minute=120),
        item("water", "Wasser", "Water", "liquid", "raw", True, extractor="water_extractor", extract_per_minute=120),
        item("iron_ingot", "Eisenbarren", "Iron Ingot", "solid", "ingot"),
        item("copper_ingot", "Kupferbarren", "Copper Ingot", "solid", "ingot"),
        item("caterium_ingot", "Cateriumbarren", "Caterium Ingot", "solid", "ingot"),
        item("steel_ingot", "Stahlbarren", "Steel Ingot", "solid", "ingot"),
        item("aluminum_ingot", "Aluminiumbarren", "Aluminum Ingot", "solid", "ingot"),
        item("iron_plate", "Eisenplatte", "Iron Plate", "solid", "basic"),
        item("iron_rod", "Eisenstange", "Iron Rod", "solid", "basic"),
        item("screw", "Schraube", "Screw", "solid", "basic"),
        item("wire", "Draht", "Wire", "solid", "basic"),
        item("cable", "Kabel", "Cable", "solid", "basic"),
        item("concrete", "Beton", "Concrete", "solid", "basic"),
        item("copper_sheet", "Kupferblech", "Copper Sheet", "solid", "basic"),
        item("quartz_crystal", "Quarzkristall", "Quartz Crystal", "solid", "basic"),
        item("silica", "Quarzsand", "Silica", "solid", "basic"),
        item("quickwire", "Schnelldraht", "Quickwire", "solid", "basic"),
        item("reinforced_iron_plate", "Verstärkte Eisenplatte", "Reinforced Iron Plate", "solid", "industrial"),
        item("rotor", "Rotor", "Rotor", "solid", "industrial"),
        item("modular_frame", "Modularer Rahmen", "Modular Frame", "solid", "industrial"),
        item("steel_beam", "Stahlträger", "Steel Beam", "solid", "industrial"),
        item("steel_pipe", "Stahlrohr", "Steel Pipe", "solid", "industrial"),
        item("encased_industrial_beam", "Ummantelter Industriebalken", "Encased Industrial Beam", "solid", "industrial"),
        item("stator", "Stator", "Stator", "solid", "industrial"),
        item("motor", "Motor", "Motor", "solid", "industrial"),
        item("smart_plating", "Intelligente Panzerung", "Smart Plating", "solid", "industrial"),
        item("versatile_framework", "Vielseitiges Gerüst", "Versatile Framework", "solid", "industrial"),
        item("heavy_modular_frame", "Schwerer modularer Rahmen", "Heavy Modular Frame", "solid", "advanced"),
        item("plastic", "Kunststoff", "Plastic", "solid", "oil", default_recipe="plastic"),
        item("rubber", "Gummi", "Rubber", "solid", "oil", default_recipe="rubber"),
        item("heavy_oil_residue", "Schwerölrückstand", "Heavy Oil Residue", "liquid", "oil"),
        item("fuel", "Treibstoff", "Fuel", "liquid", "oil", default_recipe="fuel"),
        item("polymer_resin", "Polymerharz", "Polymer Resin", "solid", "oil"),
        item("petroleum_coke", "Petrolkoks", "Petroleum Coke", "solid", "oil"),
        item("empty_canister", "Leerer Kanister", "Empty Canister", "solid", "oil"),
        item("circuit_board", "Platine", "Circuit Board", "solid", "electronics"),
        item("computer", "Computer", "Computer", "solid", "electronics"),
        item("ai_limiter", "KI-Begrenzer", "AI Limiter", "solid", "electronics"),
        item("high_speed_connector", "Hochgeschwindigkeitsstecker", "High-Speed Connector", "solid", "electronics"),
        item("automated_wiring", "Automatisierte Verkabelung", "Automated Wiring", "solid", "electronics"),
        item("alumina_solution", "Aluminiumlösung", "Alumina Solution", "liquid", "aluminum"),
        item("aluminum_scrap", "Aluminiumschrott", "Aluminum Scrap", "solid", "aluminum"),
        item("aluminum_casing", "Aluminiumgehäuse", "Aluminum Casing", "solid", "aluminum"),
    ]
    buildings = [
        {"id": "miner_mk1", "name": name("Miner Mk.1", "Miner Mk.1"), "powerMw": 5},
        {"id": "water_extractor", "name": name("Wasserpumpe", "Water Extractor"), "powerMw": 20},
        {"id": "oil_extractor", "name": name("Ölförderer", "Oil Extractor"), "powerMw": 40},
        {"id": "smelter", "name": name("Schmelze", "Smelter"), "powerMw": 4},
        {"id": "constructor", "name": name("Konstruktor", "Constructor"), "powerMw": 4},
        {"id": "assembler", "name": name("Montierer", "Assembler"), "powerMw": 15},
        {"id": "foundry", "name": name("Gießerei", "Foundry"), "powerMw": 16},
        {"id": "manufacturer", "name": name("Manufaktor", "Manufacturer"), "powerMw": 55},
        {"id": "refinery", "name": name("Raffinerie", "Refinery"), "powerMw": 30},
        {"id": "blender", "name": name("Mischer", "Blender"), "powerMw": 75},
        {"id": "packager", "name": name("Verpacker", "Packager"), "powerMw": 10},
    ]
    recipes = [
        recipe("iron_ingot", "Eisenbarren", "Iron Ingot", "smelter", 2, [("iron_ore", 1)], [("iron_ingot", 1)]),
        recipe("copper_ingot", "Kupferbarren", "Copper Ingot", "smelter", 2, [("copper_ore", 1)], [("copper_ingot", 1)]),
        recipe("caterium_ingot", "Cateriumbarren", "Caterium Ingot", "smelter", 4, [("caterium_ore", 3)], [("caterium_ingot", 1)]),
        recipe("iron_plate", "Eisenplatte", "Iron Plate", "constructor", 6, [("iron_ingot", 3)], [("iron_plate", 2)]),
        recipe("iron_rod", "Eisenstange", "Iron Rod", "constructor", 4, [("iron_ingot", 1)], [("iron_rod", 1)]),
        recipe("screw", "Schraube", "Screw", "constructor", 6, [("iron_rod", 1)], [("screw", 4)]),
        recipe("wire", "Draht", "Wire", "constructor", 4, [("copper_ingot", 1)], [("wire", 2)]),
        recipe("cable", "Kabel", "Cable", "constructor", 2, [("wire", 2)], [("cable", 1)]),
        recipe("concrete", "Beton", "Concrete", "constructor", 4, [("limestone", 3)], [("concrete", 1)]),
        recipe("copper_sheet", "Kupferblech", "Copper Sheet", "constructor", 6, [("copper_ingot", 2)], [("copper_sheet", 1)]),
        recipe("quartz_crystal", "Quarzkristall", "Quartz Crystal", "constructor", 8, [("raw_quartz", 5)], [("quartz_crystal", 3)]),
        recipe("silica", "Quarzsand", "Silica", "constructor", 8, [("raw_quartz", 3)], [("silica", 5)]),
        recipe("quickwire", "Schnelldraht", "Quickwire", "constructor", 5, [("caterium_ingot", 1)], [("quickwire", 5)]),
        recipe("reinforced_iron_plate", "Verstärkte Eisenplatte", "Reinforced Iron Plate", "assembler", 12, [("iron_plate", 6), ("screw", 12)], [("reinforced_iron_plate", 1)]),
        recipe("rotor", "Rotor", "Rotor", "assembler", 15, [("iron_rod", 5), ("screw", 25)], [("rotor", 1)]),
        recipe("modular_frame", "Modularer Rahmen", "Modular Frame", "assembler", 60, [("reinforced_iron_plate", 3), ("iron_rod", 12)], [("modular_frame", 2)]),
        recipe("steel_ingot", "Stahlbarren", "Steel Ingot", "foundry", 4, [("iron_ore", 3), ("coal", 3)], [("steel_ingot", 3)]),
        recipe("steel_beam", "Stahlträger", "Steel Beam", "constructor", 4, [("steel_ingot", 4)], [("steel_beam", 1)]),
        recipe("steel_pipe", "Stahlrohr", "Steel Pipe", "constructor", 6, [("steel_ingot", 3)], [("steel_pipe", 2)]),
        recipe("encased_industrial_beam", "Ummantelter Industriebalken", "Encased Industrial Beam", "assembler", 10, [("steel_beam", 3), ("concrete", 6)], [("encased_industrial_beam", 1)]),
        recipe("stator", "Stator", "Stator", "assembler", 12, [("steel_pipe", 3), ("wire", 8)], [("stator", 1)]),
        recipe("motor", "Motor", "Motor", "assembler", 12, [("rotor", 2), ("stator", 2)], [("motor", 1)]),
        recipe("smart_plating", "Intelligente Panzerung", "Smart Plating", "assembler", 30, [("reinforced_iron_plate", 1), ("rotor", 1)], [("smart_plating", 1)]),
        recipe("versatile_framework", "Vielseitiges Gerüst", "Versatile Framework", "assembler", 24, [("modular_frame", 1), ("steel_beam", 12)], [("versatile_framework", 2)]),
        recipe("heavy_modular_frame", "Schwerer modularer Rahmen", "Heavy Modular Frame", "manufacturer", 30, [("modular_frame", 5), ("steel_pipe", 20), ("encased_industrial_beam", 5), ("screw", 120)], [("heavy_modular_frame", 1)]),
        recipe("plastic", "Kunststoff", "Plastic", "refinery", 6, [("crude_oil", 3)], [("plastic", 2), ("heavy_oil_residue", 1)]),
        recipe("rubber", "Gummi", "Rubber", "refinery", 6, [("crude_oil", 3)], [("rubber", 2), ("heavy_oil_residue", 2)]),
        recipe("fuel", "Treibstoff", "Fuel", "refinery", 6, [("crude_oil", 6)], [("fuel", 4), ("polymer_resin", 3)]),
        recipe("residual_fuel", "Resttreibstoff", "Residual Fuel", "refinery", 6, [("heavy_oil_residue", 6)], [("fuel", 4)]),
        recipe("residual_plastic", "Restkunststoff", "Residual Plastic", "refinery", 6, [("polymer_resin", 6), ("water", 2)], [("plastic", 2)]),
        recipe("residual_rubber", "Restgummi", "Residual Rubber", "refinery", 6, [("polymer_resin", 4), ("water", 4)], [("rubber", 2)]),
        recipe("petroleum_coke", "Petrolkoks", "Petroleum Coke", "refinery", 6, [("heavy_oil_residue", 4)], [("petroleum_coke", 12)]),
        recipe("circuit_board", "Platine", "Circuit Board", "assembler", 8, [("copper_sheet", 2), ("plastic", 4)], [("circuit_board", 1)]),
        recipe("computer", "Computer", "Computer", "manufacturer", 24, [("circuit_board", 4), ("cable", 8), ("plastic", 16)], [("computer", 1)]),
        recipe("empty_canister", "Leerer Kanister", "Empty Canister", "constructor", 4, [("plastic", 2)], [("empty_canister", 4)]),
        recipe("ai_limiter", "KI-Begrenzer", "AI Limiter", "assembler", 12, [("copper_sheet", 5), ("quickwire", 20)], [("ai_limiter", 1)]),
        recipe("high_speed_connector", "Hochgeschwindigkeitsstecker", "High-Speed Connector", "manufacturer", 16, [("quickwire", 56), ("cable", 10), ("circuit_board", 1)], [("high_speed_connector", 1)]),
        recipe("automated_wiring", "Automatisierte Verkabelung", "Automated Wiring", "assembler", 24, [("stator", 1), ("cable", 20)], [("automated_wiring", 1)]),
        recipe("alumina_solution", "Aluminiumlösung", "Alumina Solution", "refinery", 6, [("bauxite", 12), ("water", 18)], [("alumina_solution", 12), ("silica", 5)]),
        recipe("aluminum_scrap", "Aluminiumschrott", "Aluminum Scrap", "refinery", 1, [("alumina_solution", 4), ("coal", 2)], [("aluminum_scrap", 6), ("water", 2)]),
        recipe("aluminum_ingot", "Aluminiumbarren", "Aluminum Ingot", "foundry", 4, [("aluminum_scrap", 6), ("silica", 5)], [("aluminum_ingot", 4)]),
        recipe("aluminum_casing", "Aluminiumgehäuse", "Aluminum Casing", "constructor", 2, [("aluminum_ingot", 3)], [("aluminum_casing", 2)]),
        recipe("cast_screw", "Gussschraube", "Cast Screw", "constructor", 24, [("iron_ingot", 5)], [("screw", 20)], True),
        recipe("iron_wire", "Eisendraht", "Iron Wire", "constructor", 24, [("iron_ingot", 5)], [("wire", 9)], True),
        recipe("solid_steel_ingot", "Massiver Stahlbarren", "Solid Steel Ingot", "foundry", 3, [("iron_ingot", 2), ("coal", 2)], [("steel_ingot", 3)], True),
        recipe("steel_rod", "Stahlstange", "Steel Rod", "constructor", 5, [("steel_ingot", 1)], [("iron_rod", 4)], True),
        recipe("steel_screw", "Stahlschraube", "Steel Screw", "constructor", 12, [("steel_beam", 1)], [("screw", 52)], True),
        recipe("stitched_iron_plate", "Genähte Eisenplatte", "Stitched Iron Plate", "assembler", 32, [("iron_plate", 10), ("wire", 20)], [("reinforced_iron_plate", 3)], True),
        recipe("bolted_iron_plate", "Verschraubte Eisenplatte", "Bolted Iron Plate", "assembler", 12, [("iron_plate", 18), ("screw", 50)], [("reinforced_iron_plate", 3)], True),
        recipe("bolted_frame", "Verschraubter Rahmen", "Bolted Frame", "assembler", 24, [("reinforced_iron_plate", 3), ("screw", 56)], [("modular_frame", 2)], True),
        recipe("copper_rotor", "Kupferrotor", "Copper Rotor", "assembler", 16, [("copper_sheet", 6), ("screw", 52)], [("rotor", 3)], True),
        recipe("steel_rotor", "Stahlrotor", "Steel Rotor", "assembler", 12, [("steel_pipe", 2), ("wire", 6)], [("rotor", 1)], True),
        recipe("iron_pipe", "Eisenrohr", "Iron Pipe", "constructor", 12, [("iron_ingot", 20)], [("steel_pipe", 5)], True),
        recipe("encased_industrial_pipe", "Ummanteltes Industrierohr", "Encased Industrial Pipe", "assembler", 15, [("steel_pipe", 6), ("concrete", 5)], [("encased_industrial_beam", 1)], True),
        recipe("steeled_frame", "Stahlrahmen", "Steeled Frame", "assembler", 60, [("reinforced_iron_plate", 2), ("steel_pipe", 10)], [("modular_frame", 3)], True),
        recipe("heavy_encased_frame", "Schwerer ummantelter Rahmen", "Heavy Encased Frame", "manufacturer", 64, [("modular_frame", 8), ("encased_industrial_beam", 10), ("steel_pipe", 36), ("concrete", 22)], [("heavy_modular_frame", 3)], True),
        recipe("heavy_flexible_frame", "Schwerer flexibler Rahmen", "Heavy Flexible Frame", "manufacturer", 16, [("modular_frame", 5), ("encased_industrial_beam", 3), ("rubber", 20), ("screw", 104)], [("heavy_modular_frame", 1)], True),
        recipe("pure_iron_ingot", "Reiner Eisenbarren", "Pure Iron Ingot", "refinery", 12, [("iron_ore", 7), ("water", 4)], [("iron_ingot", 13)], True),
        recipe("pure_copper_ingot", "Reiner Kupferbarren", "Pure Copper Ingot", "refinery", 24, [("copper_ore", 6), ("water", 4)], [("copper_ingot", 15)], True),
        recipe("wet_concrete", "Nassbeton", "Wet Concrete", "refinery", 3, [("limestone", 6), ("water", 5)], [("concrete", 4)], True),
        recipe("recycled_plastic", "Recycelter Kunststoff", "Recycled Plastic", "refinery", 12, [("rubber", 6), ("fuel", 6)], [("plastic", 12)], True),
        recipe("recycled_rubber", "Recyceltes Gummi", "Recycled Rubber", "refinery", 12, [("plastic", 6), ("fuel", 6)], [("rubber", 12)], True),
        recipe("diluted_fuel", "Verdünnter Treibstoff", "Diluted Fuel", "blender", 6, [("heavy_oil_residue", 5), ("water", 10)], [("fuel", 10)], True),
        recipe("heavy_oil_residue", "Schwerölrückstand", "Heavy Oil Residue", "refinery", 6, [("crude_oil", 3)], [("heavy_oil_residue", 4), ("polymer_resin", 2)], True),
        recipe("polymer_resin", "Polymerharz", "Polymer Resin", "refinery", 6, [("crude_oil", 6)], [("polymer_resin", 13), ("heavy_oil_residue", 2)], True),
        recipe("coke_steel_ingot", "Koks-Stahlbarren", "Coke Steel Ingot", "foundry", 12, [("iron_ore", 15), ("petroleum_coke", 15)], [("steel_ingot", 20)], True),
        recipe("copper_alloy_ingot", "Kupferlegierungsbarren", "Copper Alloy Ingot", "foundry", 6, [("copper_ore", 5), ("iron_ore", 5)], [("copper_ingot", 10)], True),
        recipe("iron_alloy_ingot", "Eisenlegierungsbarren", "Iron Alloy Ingot", "foundry", 12, [("iron_ore", 8), ("copper_ore", 2)], [("iron_ingot", 15)], True),
        recipe("coated_iron_plate", "Beschichtete Eisenplatte", "Coated Iron Plate", "assembler", 8, [("iron_ingot", 5), ("plastic", 1)], [("iron_plate", 10)], True),
        recipe("adhered_iron_plate", "Haftende Eisenplatte", "Adhered Iron Plate", "assembler", 16, [("iron_plate", 3), ("rubber", 1)], [("reinforced_iron_plate", 1)], True),
        recipe("quickwire_stator", "Schnelldraht-Stator", "Quickwire Stator", "assembler", 15, [("steel_pipe", 4), ("quickwire", 15)], [("stator", 2)], True),
        recipe("caterium_wire", "Cateriumdraht", "Caterium Wire", "constructor", 4, [("caterium_ingot", 1)], [("wire", 8)], True),
        recipe("steel_cast_plate", "Stahlgussplatte", "Steel Cast Plate", "foundry", 4, [("iron_ingot", 1), ("steel_ingot", 1)], [("iron_plate", 3)], True),
    ]
    return {
        "gameVersion": "1.0",
        "source": (
            "Satisfactory 1.0 crafting rates compiled from the public wiki tables at "
            "satisfactory.wiki.gg (October 2026). Includes standard recipes and a curated "
            "set of alternate recipes. Unofficial fan data; not affiliated with Coffee Stain "
            "Studios. Regenerate with tools/build_game_data.py."
        ),
        "items": items,
        "buildings": buildings,
        "recipes": recipes,
    }


def main() -> None:
    data = build()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(data['items'])} items, {len(data['recipes'])} recipes)")


if __name__ == "__main__":
    main()
