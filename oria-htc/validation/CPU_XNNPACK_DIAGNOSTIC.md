# Diagnostic de parité CPU / XNNPACK sur le HTC

26 septembre 2026. Téléphone : HTC U24 pro, Android 14, arm64-v8a. ONNX Runtime Android 1.22.0, modèle FP32 SHA-256 `c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d`, deux threads. Exécution dans le harness de validation distinct piloté par l’orchestrateur ; aucun changement du starter installé nécessaire à ces essais.

## Verdict

**XNNPACK avec CPU en repli est le candidat retenu pour le prototype**, sur la preuve de deux tests physiques réussis du prétraitement et des sorties. **Le fournisseur CPU seul reste expérimental : il échoue à la parité complète sur une fixture**, même si l’écart observé ne change aucune détection au seuil applicatif 0,70 dans ce lot.

| Essai | CPU EP | XNNPACK avec CPU en repli |
|---|---|---|
| Run 01, six fixtures prévues | Arrêt à `Unmatched model row 291`, diagnostic incomplet | Six fixtures réussies ; p95 inférence 152,82 ms |
| Run 02, six fixtures conservées même après divergence | Cinq réussies ; une avec 299/300 lignes appariées | Six réussies, 300/300 chacune |
| Run 02, tenseurs de prétraitement | Identiques aux références pour les six cas | Identiques aux références pour les six cas |
| Run 02, détections ≥0,70 | Mêmes nombres et aucune ligne applicative non appariée, aucun franchissement différent du seuil | Idem |
| Run 02, 20 inférences après 3 échauffements | p50 174,14 ms ; p95 175,56 ms ; max 178,03 ms | p50 152,00 ms ; p95 152,22 ms ; max 152,67 ms |

La comparaison de performances concerne un court traitement de tenseur fixe, pas le flux vidéo/audio. Le temps de prétraitement mesuré séparément sur les images est d’environ 24–33 ms. Le benchmark soutenu de dix minutes a ensuite réussi et dispose d’un rapport distinct, `ML_ENDURANCE.md`. Le smoke XNNPACK final après correction géométrique réussit également six fixtures ; il ne réhabilite pas le CPU expérimental.

## Écart CPU exact

Fixture `sdk_sample_1`, frame prise à 50 % de la vidéo fournie dans le simulateur. Aucun objet de ce cliché ne dépasse le seuil 0,70 dans les références ou sur le téléphone.

| Élément | Référence ONNX CPU Mac absente du résultat CPU téléphone | Résultat CPU téléphone supplémentaire |
|---|---|---|
| Rang de sortie, index à partir de zéro | 291 | 299 |
| Classe | 1 — `vehicle` | 2 — `bike_scooter` |
| Score | 0,000004202127456665039 | 0,000004112720489501953 |
| Boîte `xyxy` dans l’entrée 416 | `[90.3802338, 5.3180084, 248.5981140, 401.2824707]` | `[59.7374382, 409.1825562, 75.0832748, 415.8771362]` |
| Seuil applicatif 0,70 franchi | Non | Non |

Ce n’est pas une simple permutation des mêmes lignes : les classes et les boîtes sont différentes. L’appariement un-à-un de même classe ne peut donc pas associer les 300 sorties dans les tolérances fixées. Les 299 paires communes de cette fixture diffèrent au plus de 0,000321 pixel et de 0,000000149 de score. La parité complète reste **ÉCHOUÉE** ; ni le seuil de comparaison, ni les boîtes difficiles n’ont été retirés pour rendre le test vert.

Le décodage de l’image ne cause pas cet écart : le tenseur est identique, et le test d’inférence reçoit directement le fichier de tenseur de référence. Le modèle ONNX et son SHA sont identiques ; seuls l’appareil et le fournisseur d’exécution changent.

## Diagnostic de la sélection TopK

Le graphe ONNX contient deux TopK dans sa tête de détection. Une exécution de diagnostic **sur Mac seulement** a exposé les boîtes/scores avant TopK, sans remplacer le modèle embarqué :

- La boîte absente du CPU téléphone est exactement l’ancre 3462 de la référence, score `vehicle` = 0,000004202127 ; rang 179 selon le meilleur score de classe.
- La boîte supplémentaire du téléphone est exactement l’ancre 2660 de la référence. Sur Mac, son score `bike_scooter` = 0,000004053116 ; sur le CPU téléphone, la sortie fournit 0,000004112720, soit un écart d’environ 0,0000000596. Son meilleur score Mac place cette ancre au rang 186.
- Les deux ancres appartiennent donc à la sélection initiale des 300 ancres sur Mac. Le score minimal des 300 sorties finales Mac vaut 0,000004082918. Le score de la boîte supplémentaire se trouve de part et d’autre de ce seuil entre les sorties observées.

Ces faits sont **compatibles avec une substitution en fin de TopK causée par de minuscules écarts numériques de scores**, particulièrement près de la coupure. Ils ne prouvent pas les scores de toutes les ancres avant TopK sur le CPU du téléphone : ces tenseurs intermédiaires n’ont pas été collectés. Aucune correction des opérateurs CPU ni équivalence universelle des sorties n’est revendiquée.

XNNPACK restitue la boîte de référence absente du CPU, avec score 0,000004112720 et coordonnées à environ 0,0001 pixel de la référence. Le graphe ORT rapporte que certains nœuds restent sur CPU ; « XNNPACK » désigne donc une préférence de provider avec repli, pas une délégation intégrale.

## Correction apportée

Le premier test échouait avant d’avoir enregistré le nom et les données du cas fautif. Le test conserve maintenant toutes les sorties binaires et des diagnostics par fixture, puis échoue après rédaction du rapport complet. Il calcule un appariement maximal, identifie chaque ligne non appariée et ses voisins de même classe, distingue leur score du seuil applicatif et rapporte les franchissements. Les tolérances restent **1 pixel par coordonnée et 0,001 de score**.

L’orchestrateur a sélectionné explicitement XNNPACK pour le prototype. Ce choix est justifié par une parité réussie deux fois sur le lot et une meilleure latence courte mesurée. CPU demeure disponible pour investigation, avec son échec conservé. Changer de provider n’est ni modifier les poids ni déclarer le problème CPU résolu.

L’analyse hors appareil réapparie les ensembles à coût minimal dans les mêmes tolérances. Elle retrouve exactement les mêmes verdicts. Les écarts maximaux des paires ainsi choisies sont de 0,000519 pixel / 0,000000536 de score pour CPU hors ligne non appariée, et de 0,001816 pixel / 0,000002653 pour XNNPACK. Le premier matching Android est maximal mais pas à coût minimal : son écart maximal parmi des paires admissibles peut donc être plus grand sans changer le verdict.

## Preuves conservées

- `validation/device-run-01/` : premier run intact, y compris échec initial et rapport XNNPACK.
- `validation/device-run-02/files/ml_validation/onnx_cpu.json` et `onnx_xnnpack.json` : rapports complets du second run.
- Même dossier : fichiers `<provider>_<fixture>.output.f32` et `.parity.json` pour les douze cas.
- `validation/device-run-02/offline_parity_analysis.json` : comparaison hors appareil des sorties enregistrées.
- `validation/device-run-02/topk_diagnostic_mac.json` : valeurs et limites du diagnostic pré-TopK sur Mac.
- `ml/exports/model_manifest.json` et manifeste d’asset Android : `android_parity_executed=true`, avec résultats CPU/XNNPACK séparés, SHA des rapports, appareil et portée des essais.

La qualité réelle sur les lunettes, les autres quatre classes non positivement couvertes ici, l’absence d’obstacles et le fonctionnement vidéo+voix restent hors de la preuve apportée par ce lot.
