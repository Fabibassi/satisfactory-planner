# Spieldaten Satisfactory 1.0

Die App liest `gamedata/game_data.json`. Die Datei ist eine kuratierte Teilmenge der Satisfactory-1.0-Rezepte (Standard und häufige Alternativen), nicht der komplette Spieldump.

## Herkunft

Die Mengen stammen aus den öffentlichen Crafting-Tabellen auf [satisfactory.wiki.gg](https://satisfactory.wiki.gg) (Stand der Auswertung: Oktober 2026). Gespeichert sind die Mengen **pro Maschinentakt** und die Taktzeit. Die Rate pro Minute ist `Menge / cycleSeconds * 60`.

Berücksichtigt sind die Rezeptänderungen von Patch 1.0, unter anderem:

- Rotor: 4/min (5 Eisenstangen + 25 Schrauben, 15 s)
- Modularer Rahmen: 2/min (60 s)
- Schwerer modularer Rahmen: 20 Stahlrohre und 120 Schrauben pro Takt
- Gummi erzeugt 20 m³ Schwerölrückstand/min, Kunststoff 10 m³

Gebäudeleistungen bei 100 % Takt: Miner Mk.1 5 MW, Wasserpumpe 20 MW, Ölförderer 40 MW, Schmelze 4 MW, Konstruktor 4 MW, Montierer 15 MW, Gießerei 16 MW, Manufaktor 55 MW, Raffinerie 30 MW, Mischer 75 MW, Verpacker 10 MW. Förderung fester Rohstoffe rechnet mit Miner Mk.1 bei normaler Reinheit (60/min), Öl und Wasser mit 120/min.

Deutsche Anzeigenamen sind die üblichen Bezeichnungen aus der deutschen Spielversion.

## Icons

Gegenstands- und Gebäudeicons liegen unter `gamedata/icons/items/<id>.png` und `gamedata/icons/buildings/<id>.png`. Die Zuordnung und die Wiki-Dateinamen stehen in `gamedata/icons/manifest.json`.

Herkunft: Dateibestand des Community-Wikis [satisfactory.wiki.gg](https://satisfactory.wiki.gg/) (Abruf 12. Oktober 2026, `tools/fetch_icons.py`). Das Wiki veröffentlicht die Grafiken aus dem Spiel unter den englischen Gegenstandsnamen (`Special:FilePath`, zum Beispiel `Iron_Ingot.png`, `Constructor.png`). Es ist dieselbe Bildquelle, die Community-Werkzeuge wie das Wiki selbst und darauf aufbauende Rechner verwenden. Die Dateien sind Spielgrafiken von Coffee Stain Studios, kein eigenes Artwork dieser App.

Fehlt eine Datei oder lässt sie sich nicht lesen, zeichnet die Oberfläche weiter das zweibuchstabige Kürzel.

Satisfactory ist eine Marke von Coffee Stain Studios. Dieses Projekt ist ein inoffizielles Fan-Tool und steht in keiner Verbindung zu Coffee Stain Studios.

## Aktualisieren

1. Werte in `tools/build_game_data.py` anpassen (Items, Gebäude, Rezepte).
2. `python3 tools/build_game_data.py` ausführen.
3. `python3 tools/validate_game_data.py` und `./gradlew :core:test` ausführen.

Neue Rezepte brauchen eine stabile `id`, ein Gebäude und Mengen pro Takt. Hat ein Gegenstand mehrere Standardrezepte (Kunststoff, Gummi, Treibstoff), muss `defaultRecipeId` am Item gesetzt sein. Alternativen stehen mit `"alternate": true` und werden nur benutzt, wenn der Plan sie auswählt.

Die Kotlin-Tests in `GameDataIntegrityTest` und `ProductionSolverTest` prüfen Referenzen und bekannte Raten (unter anderem 10 Motoren/min = 315 Eisenerz, 80 Kupfererz, 9 Kohle je Motor, also 90 Kohle).
