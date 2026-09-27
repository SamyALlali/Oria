# R02 — Qualification du suivi RGB V2

Date : 27 septembre 2026
Verdict : **qualification logicielle renforcée, porte terrain non franchie**. `LEGACY_IOU` reste le mode par défaut et `STABLE_RGB_V2` reste un candidat explicite.

## Ce qui a été contrôlé

- `StableRgbAssociation` et ses constantes n'ont pas été modifiés : aucun seuil YOLO, IoU, confiance, catégorie ou annonce n'a été ajusté aux scènes.
- Les garanties existantes sont conservées : expiration, registres bornés, génération de session, fraîcheur, mémoire vocale et rejet des callbacks anciens.
- Les états sont maintenant observables sans prétendre reconnaître une personne : `VISIBLE`, `OCCLUDED`, ainsi que les retraits `AMBIGUOUS`, `EXPIRED` et `CAPACITY`.
- Un retrait signifie uniquement que la continuité géométrique locale est perdue. Ce n'est ni une identité monde ni une réidentification.

## Scène réelle historique 0548b68a

Le rapport versionné du 26 septembre reste la preuve disponible : 164 décisions, 240 observations visibles, 81 identifiants en LEGACY contre 90 en V2, 102 contre 101 observations confirmées et 12 annonces synthétiques dans chaque mode. V2 corrigeait le cas de continuité 537–620 et le transfert de confirmation 1509–1521, mais créait neuf identifiants supplémentaires.

La capture privée n'est plus présente au chemin documenté sur ce Mac et le téléphone HTC connecté ne contient aucune session Oria exportable. **Ces chiffres n'ont donc pas été rejoués pendant R02** et ne sont pas présentés comme une nouvelle mesure.

## Deux scènes annotées reproductibles

Les fixtures `r02_synthetic_scenes.json` sont des trajectoires 2D synthétiques annotées. Elles ferment la couverture déterministe, mais ne remplacent pas des essais lunettes.

| Scène / mesure | LEGACY_IOU | STABLE_RGB_V2 |
|---|---:|---:|
| Croisement, occultation, sortie/retour — fragments après la première piste | 3 | 3 |
| Croisement — transferts d'une piste entre annotations A/B | 2 | 0 |
| Croisement — annonces / silences simulés | 1 / 9 | 2 / 8 |
| Rotation de tête simulée, occultation, retour — fragments | 1 | 0 |
| Rotation — transferts entre annotations | 0 | 0 |
| Rotation — annonces / silences simulés | 2 / 6 | 1 / 7 |

Le V2 évite les deux transferts du croisement et réduit la fragmentation sous rotation. La seconde annonce du croisement est une différence comportementale bornée à vérifier à l'écoute ; elle interdit de conclure à une amélioration globale sur les seules fixtures.

## Vérifications exécutées

- 93 tests JVM Android : 93 réussis, 0 échec.
- Cœur Kotlin isolé : 42 tests réussis.
- Adaptateur Python/Kotlin : 6 tests réussis.
- Qualification R02 : 4 tests réussis, y compris les métriques de fragmentation, transfert, occultation, alertes et silences.
- `assembleDebug` et `lintDebug` : réussis.

Reproduction depuis `oria-htc` :

```bash
python3 validation/r02_tracking_qualification.py --output /tmp/r02-tracking-qualification.json
python3 -m unittest discover -s validation -p 'test_r02*.py' -v
python3 oria-lab-policy/run_core_tests.py --report /tmp/r02-core-tests.json
python3 -m unittest discover -s oria-lab-policy -v
cd android-project
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Porte restante

Avant toute activation par défaut, il faut encore exporter puis annoter au moins deux captures Eagle réelles comprenant croisement, occultation, sortie/retour et rotations de tête, rejouer la scène 0548b68a si elle est récupérée, puis écouter sur lunettes les alertes et silences. La fragmentation supplémentaire observée sur 0548b68a doit rester bornée. En attendant, aucune promotion de V2 n'est justifiée.
