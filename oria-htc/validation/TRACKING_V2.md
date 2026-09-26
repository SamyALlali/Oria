# Suivi RGB V2 — comparaison hors téléphone du 26 septembre 2026

Le suivi expérimental conserve une piste dans le cas de répétition repéré à 537–620 et refuse de transférer une confirmation dans le cas ambigu 1509–1521. **Il reste désactivé par défaut** : sur cette seule scène, il crée aussi davantage de pistes. Il faut comparer d'autres captures et essayer le téléphone avant de décider de son activation générale.

## Contrat et décision

`RgbAlertConfig.trackingMode` accepte `LEGACY_IOU` (comportement par défaut) ou `STABLE_RGB_V2`. Le JSONL du lecteur Mac expose le même paramètre ; tout autre nom est rejeté. Les deux modes exécutent le vrai cœur Kotlin utilisé par Android.

Le V2 conserve le seuil IoU à **0,25**. Il ajoute :

- Une prédiction de translation fondée sur les deux dernières observations consécutives. Elle est bornée à 1,5 intervalle de mesure et 0,25 de l'image par axe ; elle ne prédit ni taille, ni profondeur, ni pose.
- Une récupération sous le seuil IoU courant uniquement si la boîte prédite atteint le même seuil et que les rapports de dimensions restent au plus à 1,5. Les liens ordinaires imposent un rapport au plus à 2.
- Une association réciproque avec séparation de score de 0,12. Plusieurs continuations d'une piste sans histoire de mouvement, des scores trop proches, ou une divergence entre overlap et mouvement sont traités comme une ambiguïté.
- La suppression des identifiants dont les liens restent ambigus, puis de nouvelles pistes qui doivent confirmer leurs propres observations. Aucune mémoire vocale d'un ancien identifiant n'est transférée arbitrairement.

Ces constantes sont des choix géométriques provisoires, pas des distances physiques. Les scores de classe, seuils d'annonce, délais vocaux, fraîcheur, invalidation de session, borne des registres et règles de callbacks sont inchangés. Une récupération conserve l'identifiant et sa mémoire vocale mais **ne contourne pas la qualification** : une confiance faible remet les confirmations à zéro. Une réapparition après occultation exige de nouvelles confirmations. Aucune boîte prédite n'est fournie à la sélection ou à la voix.

Le champ descriptif `RgbTrack.associationStatus` distingue `NEW`, `LEGACY_IOU`, `UNAMBIGUOUS_IOU`, `MOTION_RECOVERY` et `AMBIGUOUS_NEW`. Il décrit la dernière observation de la piste ; sur une piste invisible il n'est pas une nouvelle observation. Les identifiants représentent une continuité géométrique locale et ne prouvent pas l'identité physique d'une personne.

## Comparaison de la scène disponible

Capture privée `0548b68a-b9e3-445f-a072-0182e9ce98d6`, 164 décisions et 240 détections admises. Entrées : détections enregistrées, horloges `observedAtMs` et `evaluatedAtMs` du téléphone, paramètres configurables conservés dans le manifeste. Les constantes de catégories/zones restent celles du même cœur compilé ; elles sont consignées séparément du paramétrage dans le rapport. Aucun nouveau passage YOLO. Deux processus Kotlin indépendants appliquent exactement la même règle audio : soumission à l'instant de décision, confirmation simulée **2 000 ms plus tard**, appliquée avant la décision suivante si elle est échue. Une demande encore en vol à la fin est annulée à l'arrêt. Les callbacks audio réels de la capture ne sont utilisés par aucun des deux moteurs.

| Mesure de cette comparaison | LEGACY_IOU | STABLE_RGB_V2 |
|---|---:|---:|
| Décisions acceptées | 164 | 164 |
| Observations de pistes visibles | 240 | 240 |
| Identifiants distincts créés | 81 | 90 |
| Observations confirmées | 102 | 101 |
| Annonces avec cet audio simulé | 12 | 12 |
| Récupérations par mouvement | 0 | 1 |
| Nouvelles pistes explicitement ambiguës | 0 | 5 |

Le nombre total d'annonces ne diminue pas. La création de **9 identifiants supplémentaires** est une contrepartie défavorable potentielle : ce résultat ne suffit pas à recommander le V2 partout.

| Cas inspecté lors de l'audit antérieur | Baseline rejouée | Candidat rejoué |
|---|---|---|
| Grande personne, images 537 → 598 → 620 | Piste 16, puis nouvelle piste 34 à 598 | Piste 29 conservée ; récupération à 598 malgré IoU courant inférieur à 0,25 |
| Faible confiance à 598 | Zéro confirmation sur la nouvelle piste | Zéro confirmation sur la piste conservée ; les images 606 et 620 doivent reconfirmer |
| Image 632 | Réannonce synthétique de la grande personne, « devant » | Mémoire de cette piste conservée ; annonce synthétique d'une autre piste à gauche |
| Ambiguïté 1509 → 1521 | Piste 71 transmise à la personne de gauche, passe de 1 à 2 confirmations et devient annonçable | Ancienne piste retirée ; deux nouvelles pistes ont chacune 1 confirmation, aucune annonce héritée |
| Image suivante, 1531 | Voix précédente simulée encore en cours | Deux continuités uniques confirmées ; annonce synthétique de la piste à droite |

Les identifiants diffèrent de l'audit enregistré, car chaque moteur démarre ici sans les compteurs de sessions précédentes. Cette expérience mesure le comportement des règles sur les entrées conservées. Elle ne mesure ni précision/rappel terrain, ni nombre réel d'erreurs d'identité, ni qualité acoustique. Le constat visuel des deux cas vient de l'audit existant ; aucune nouvelle annotation complète de vérité terrain n'a été inventée.

## Vérifications exécutées

- **32 tests JUnit du cœur réussis** : 22 tests existants et 10 nouveaux tests couvrant récupération et mémoire, confiance basse, ambiguïté à deux continuations, croisement, occultation, expiration/saut, bornes de prédiction, ordre des détections, session/fraîcheur/audio ambigu et densité bornée.
- **6 tests de l'adaptateur Python/Kotlin réussis**, dont validation des deux modes et préparation concurrente sur cache vide. Le cache utilise un verrou de fichier et une publication atomique du JAR pour les lancements A/B simultanés.
- Comparaison au cœur gelé du commit `c7b5b3ed768580499ffde75d0981924272454462` : **164/164 évaluations et 12/12 annonces synthétiques identiques** en mode LEGACY_IOU, après exclusion du seul nouveau champ descriptif `associationStatus`.
- Compilation du nouvel adaptateur Kotlin exécutée sur ce Mac. Les essais HTC, la charge/batterie et l'audio réel ne sont pas exécutés pour ce changement.
- Relecture croisée par l'agent du lecteur Mac : aucun blocage relevé dans l'association, le retrait d'identifiants ambigus ou les gardes de fraîcheur/session/audio. La fragmentation supplémentaire reste une limite explicite.

Les résultats structurés locaux sont `TRACKING_V2_COMPARISON.json` (empreintes des sources, entrées, compteurs et cas ciblés) et `TRACKING_V2_BASELINE_PARITY.json`. Ils ne contiennent ni images ni son. Les fichiers privés de la capture ne sont pas modifiés.

## Reproduire

Depuis `oria-htc` :

```bash
python3 -m unittest discover -s oria-lab-policy -v
python3 validation/tracking_compare.py \
  "$HOME/Library/Application Support/Oria Lab/sessions/11ee4f61eaa44ea1946fdf17305286a7" \
  --confirmation-delay-ms 2000 --output /tmp/oria-tracking-comparison.json
```

Le script exige le dossier local déjà importé ; il ne télécharge ni ne publie la scène. Pour la suite, comparer des passages croisés, occultations, rotations rapides et réapparitions sur plusieurs scènes, puis contrôler sur HTC la latence et les répétitions effectivement entendues avant tout changement du défaut.
