# RaritySystem (Paper 26.x)

Jedes Item bekommt eine farbige Seltenheit (Common, Uncommon, Rare, Epic, Legendary, Mythic, Divine ...),
sichtbar im Tooltip im Inventar, in Kisten und allen GUIs. In der Hand bleibt alles wie normal.

## Bauen
Voraussetzung: JDK 25 (Paper 26.x laeuft auf Java 25).

1. In `build.gradle.kts` die Zeile `paperVersion` an deine Paper-Version anpassen.
2. `gradle build`
3. `build/libs/RaritySystem-1.0.0.jar` in den `plugins/`-Ordner kopieren.

## Ohne lokales Java bauen (GitHub)
1. Neues GitHub-Repository anlegen und den Inhalt dieses Ordners hochladen (inkl. `.github`).
2. Reiter "Actions" -> Workflow "Build" laeuft automatisch.
3. Fertige JAR unter "Artifacts" -> "RaritySystem" herunterladen.

## Konfiguration
`plugins/RaritySystem/config.yml`: Namen, Farben (MiniMessage) und Material-Zuordnung pro Stufe.
`/rarity reload` laedt die Config neu (Permission: `raritysystem.admin`).
