# R04 — Résolution et stabilisation des dangers

Date : 27 septembre 2026

Verdict : **porte logicielle franchie ; promotion audio volontairement reportée à R05**. Le nouveau moteur calcule et journalise sa décision sur chaque frame acceptée. Pour mesurer les écarts sans casser les preuves physiques existantes, `RgbAlertEngine` reste la source des annonces pendant R04.

## Portage Swift

Le cœur Kotlin représente explicitement les sept sources de `DangerResolutionPolicy` :

- `semantic_current` et `semantic_memory`, réservées à une vraie mesure métrique corrélée ;
- `semantic_visual_proxy` pour une piste RGB confirmée ;
- `semantic_visual_memory` pour une mémoire visuelle encore fraîche ;
- `generic_fallback` et `wide_fallback` pour une obstruction non sémantique ;
- `limited_sensors` pour un état dégradé explicite.

Chaque candidat porte sa provenance, son propriétaire, son niveau, sa zone, son score, sa confiance et ses valeurs métriques ou relatives dans des champs distincts. Une proximité MiDaS n'est jamais placée dans `metricDistanceMeters`.

Les responsabilités Swift suivantes sont portées : ordre des sources sémantiques, protection d'un mur fort, ambiguïté du fallback, priorité des capteurs limités, remplacement par niveau supérieur, marge de score, maintien, relâchement, changement de propriétaire et reset de génération. Les durées et marges proviennent de `stabilizedCandidate`, `holdDuration`, `releaseDuration` et `replacementThreshold`.

## Adaptation Eagle

Le téléphone ne reçoit ni LiDAR, ni mesh, ni pose monde. L'adaptateur applique donc ces règles :

| Entrée Eagle | Source R04 | Limite |
|---|---|---|
| Piste RGB confirmée courante | `semantic_visual_proxy` | score visuel sans unité |
| Piste RGB mémorisée | `semantic_visual_memory` | identité locale, jamais réidentification monde |
| MiDaS sur une piste | même source visuelle, provenance `RGB_WITH_RELATIVE_DEPTH` | proximité relative seulement |
| Corridor MiDaS | `generic_fallback` | aucun mur, largeur ou mètre déduit |
| Entrée métrique absente | branches `semantic_current`, `semantic_memory`, mur métrique inactives | aucune valeur fictive |

Le snapshot Oria Lab contient entrées, candidats acceptés, rejets et motifs, sélection avant stabilisation, sélection finale, score, provenance, propriétaire, niveau, zone, raisons de politique et de stabilisation. La carte MiDaS 256 × 256 n'est pas sérialisée.

## Approche et TTC

L'approche est un signal séparé, visible dans le snapshot et désactivé par défaut. Lorsqu'elle est activée, seule une tendance `APPROACHING` fraîche et suffisamment confiante ajoute un petit bonus borné au candidat qui possède la piste. Elle ne remplace jamais l'arbitrage. La caméra monoculaire ne produit aucun TTC : `ttcMs` reste `null` tant qu'aucune vitesse métrique fiable n'existe.

## Parité et non-régression

- 10/10 nouvelles fixtures exécutent le même JSON côté Kotlin et dans les corps Swift non modifiés de `DangerResolutionPolicy` : résultats strictement identiques.
- Les 18 anciennes fixtures Swift/Kotlin, les seuils RGB et la livraison audio ne sont pas modifiés.
- 116/116 tests JVM réussis ; le banc du cœur isolé réussit 63/63 tests.
- 6/6 tests Python de politique et 4/4 tests Python de qualification R02 réussis.
- Build debug et lint Android réussis.
- La scène réelle historique 0548b68a n'est pas réattribuée au nouveau moteur : ses 164 décisions restent une preuve du moteur RGB antérieur. Son archive brute n'est pas versionnée ici, donc aucun faux rejeu R04 n'est revendiqué. Comme R04 reste en comparaison silencieuse, ses annonces et silences historiques ne changent pas.

Le rapport reproductible [danger_resolution_parity_report.json](../fixtures/danger_resolution_parity_report.json) fixe l'empreinte du Swift, des fonctions extraites et des fixtures.

## Reproduction

```bash
cd oria-htc/android-project
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest lintDebug assembleDebug

cd ..
python3 oria-lab-policy/run_core_tests.py --report /tmp/r04-core-tests.json
ORIA_SWIFT_SOURCE="/Users/rayan/Desktop/EchoNav/EchoNav_Part_Scene_Understanding/EchoNav/EchoNavApp.swift" \
  python3 fixtures/run_danger_resolution_reference.py
```

## Porte restante avant R05

Enregistrer une nouvelle scène Eagle avec le snapshot R04, mesurer les divergences `ownerKey`/zone/niveau face à `RgbAlertEngine`, puis décider explicitement quelles sorties alimentent l'ordonnanceur audio. Cette promotion ne doit pas modifier rétroactivement les preuves 1.4-night ni activer les branches métriques sans nouveau capteur.
