# Fabrikplaner

Native Android-App (Kotlin, Jetpack Compose) zum Planen von Satisfactory-Fabriken. Die Daten sind Satisfactory **1.0**, die App arbeitet offline.

Zwei Modi teilen sich denselben Plan:

1. **Automatische Berechnung.** Zielgegenstände und Raten pro Minute wählen (zum Beispiel 10 Motoren/min). Der Solver löst die Kette bei 100 % Takt: Maschinen (auch anteilig), aufgerundete Stückzahl, Rohstoffe, Nebenprodukte und Leistung. Alternativrezepte lassen sich pro Gegenstand umschalten.
2. **Freier Graph.** Maschinen, Quellen, Senken, Splitter und Merger auf einer Fläche mit Verschieben und Zoom platzieren. Ausgänge auf Eingänge ziehen, Bänder (fest) und Rohre (flüssig) unterscheiden sich farblich. Die Raten laufen live durch und zeigen Über- oder Unterproduktion. Ein erzeugter Graph bleibt im selben Editor editierbar.

Pläne liegen als JSON unter dem App-Dateiverzeichnis und überstehen einen Neustart.

Die Oberfläche ist Deutsch. Ist die Gerätesprache Englisch, kommen die Texte aus `values-en`. Gegenstandsnamen folgen derselben Regel.

## Bauen und installieren

Voraussetzungen: JDK 17 oder neuer, Android SDK mit `platforms;android-35` und `build-tools;35.0.0`.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # Pfad zum SDK
./gradlew :core:test :app:assembleDebug
```

Die Debug-APK liegt unter `app/build/outputs/apk/debug/app-debug.apk`. Installation auf einem Gerät mit USB-Debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ohne Android Studio: In den Entwickleroptionen „Unbekannte Apps“ für den Dateimanager erlauben und die APK öffnen.

### GitHub Actions und Release

Der Workflow `.github/workflows/android.yml` läuft bei Push, Pull Request und manuellem Start. Er führt die Solver-Tests aus. Zusätzlich liegt ein Actions-Artifact `fabrikplaner-debug-apk` bereit (14 Tage, als ZIP).

Bei jedem Push aktualisiert derselbe Lauf das Pre-Release [latest](https://github.com/Fabibassi/satisfactory-planner/releases/tag/latest). Die Datei `fabrikplaner-debug.apk` hängt dort direkt an, ohne ZIP. In der GitHub-Android-App die Release-Seite öffnen und die APK antippen, oder diesen Link laden:

https://github.com/Fabibassi/satisfactory-planner/releases/download/latest/fabrikplaner-debug.apk

## Tests

```bash
./gradlew :core:test
```

Die Tests liegen im reinen JVM-Modul `:core` (kein Emulator). Abgedeckt sind unter anderem das lineare Gleichungssystem, 10 Motoren/min inklusive Erz- und Leistungsbilanz, Gussschraube, Eisendraht, reiner Eisenbarren, Kunststoff mit Schweröl, Resttreibstoff, die Recycling-Schleife Kunststoff/Gummi, die Aluminium-Wasserrückführung, der Graph-Abgleich und das lokale Speichern.

## Daten

Siehe [docs/gamedata.md](docs/gamedata.md). Kurz: `tools/build_game_data.py` erzeugt `gamedata/game_data.json` aus den Wiki-Raten von Satisfactory 1.0. Die Datei ist eine kuratierte Teilmenge (frühes bis mittleres Spiel plus gängige Alternativen), nicht jedes Quant-Rezept.

## Bekannte Grenzen

- Kein vollständiger 1.0-Datensatz (kein Tier 9, keine Kraftwerke als Verbraucher, keine Ausrüstung).
- Der Planer rechnet ohne Übertaktung. Im Editor ist der Takt 1–250 %; die Rate skaliert linear, die Leistung mit dem Spielexponenten 1,321928. Power Shards und Somersloops fehlen.
- Förderer-Stufen und Durchsatzgrenzen (Mk.1–Mk.6, Pipes) werden nicht geprüft.
- Kein Fundamentraster, keine Kollision, kein Gebäude-Footprint.
- Alternativen werden nicht automatisch optimiert. Vor dem Graph und später im Editor lässt sich das Rezept je Teil wählen.
- Das automatische Layout ist eine Sugiyama-Anordnung (Schichten von links nach rechts, Kreuzungen per Barycenter reduziert). Es bleibt ein Schema, kein Fabrikbau auf Fundamenten.
- Die Kantenanker und das Layout sind über die JVM-Tests abgesichert. Ein Emulator in der Build-Umgebung blieb vor dem Android-Start stehen, deshalb gibt es dazu eine Geometriezeichnung und keine Geräteaufnahme.

## Nächste Schritte

- Import aus den Docs-Dateien der Installation (`CommunityResources/Docs`), damit Patches ohne Handarbeit ankommen.
- Band- und Rohrkapazität, Förderer-Mk und Flüssigkeitsdruck.
- Übertaktung, Shards und Somersloops auch in der automatischen Berechnung.
- Besseres Layout, Kopieren, Gruppen und Blaupausen.
- Ziel „minimiere Rohstoffe“ oder „minimiere Leistung“ über die Alternativen.
- Offizielle Gegenstands-Icons, sobald Coffee Stain das Bündeln freigibt. Bis dahin zeigt die App eigene Kürzel.

Inoffizielles Fan-Tool. Satisfactory ist eine Marke von Coffee Stain Studios.
