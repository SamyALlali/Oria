# Fixtures communes Swift / Kotlin

`policy_cases.jsonl` contient 18 cas déterministes : directions et frontières 0,39/0,61, seuils et géométrie des quatre classes alertables, absence d’annonce feu/panneau, fraîcheur à 500 ms et expiration stricte après 18 s.

Exécution depuis `android-project/` :

```sh
./gradlew :app:testDebugUnitTest --tests '*RgbAlertEngineTest' --tests '*RgbSwiftFixturesTest' --console=plain
```

Puis, depuis ce dossier :

```sh
python3 run_swift_reference.py
```

Le test Kotlin lit ce même fichier JSONL et produit `results_kotlin.jsonl`. Le script Python extrait les corps de fonctions du **source Swift original** en lecture seule, génère `GeneratedSwiftPolicyReference.swift`, l'exécute puis produit `results_swift.jsonl` et `policy_parity_report.json`. Seule la visibilité `private` est retirée pour permettre au petit adaptateur d'appeler les méthodes ; leurs corps sont inchangés. Les empreintes du source et des extraits sont consignées.

R04 ajoute `danger_resolution_cases.jsonl` et `run_danger_resolution_reference.py`. Dix cas communs exercent directement les corps Swift non modifiés de `DangerResolutionPolicy` et leur port Kotlin : mur fort, ambiguïté, propriété sémantique/visuelle et capteurs limités. Les sorties et empreintes sont conservées dans les fichiers `danger_resolution_*`.

Le programme Swift utilise des types de données adaptés autour de ces fonctions pures ; il ne lance pas ARKit, Core ML, le runtime iOS complet ni les replays historiques OriaLab. Les 22 autres tests Kotlin couvrent les adaptations nouvelles (identité locale, arrêt/reprise, confirmations et erreurs audio), sans prétendre à une équivalence de ces adaptations avec la boucle iOS complète.

Les fichiers `results_*` et le rapport sont des résultats réellement exécutés, pas des sorties attendues rédigées manuellement. Lors d’un changement de source, de fixtures ou de paramètres, relancer les deux côtés. Le parseur de fixtures côté test est volontairement limité aux objets JSON plats avec scalaires utilisés ici.
