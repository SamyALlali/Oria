# Implémentation ML — état du 26 septembre 2026

> Rapport historique de l’implémentation initiale et des essais sur **CN4B53M00860**. Les décisions de navigation/installations anciennes sont conservées comme historique ; la reprise actuelle ouvre EchoNav directement avec Diagnostic HTC secondaire. État courant et preuves du nouveau **CN46V3M00284** : [RECETTE.md](RECETTE.md) et `new-device-CN46V3M00284/`.

## Réalisé

- Environnement isolé Python 3.11.14 dans `ml/.venv`, Ultralytics 8.4.27, torch 2.8.0, ONNX 1.17.0, ONNX Runtime 1.22.0 ; dépendances complètes figées dans `ml/requirements.lock.txt`.
- Checkpoint utilisateur chargé, ordre des six classes confirmé, export ONNX réel FP32/opset 17/batch 1/416 fixe/end-to-end, sans NMS ajoutée ni simplification. `onnx.checker.check_model` réussi.
- Checkpoint original inchangé, SHA-256 `7dd79d15d1fe61200b19916c7d0b73136637fd768c9da53a94d0499f010a40c1`.
- ONNX : **38 030 922 octets**, SHA-256 `c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d`.
- Contrat réellement exécuté : entrée `images` FP32 `[1,3,416,416]`, sortie `output0` FP32 `[1,300,6]` = coordonnées `xyxy` en pixels du tenseur, score et identifiant de classe.
- Modèle/manifeste présents dans les assets Android. Détecteur synchrone avec checksum, vérification des formes/types/noms et table des classes ; letterbox centré, RGB/NCHW/255, sortie normalisée caméra. Pas de rotation arbitraire, de NMS supplémentaire ni de top huit.
- API exposant CPU et XNNPACK pour comparaison ; le prototype sélectionne explicitement XNNPACK sur les résultats physiques décrits ci-dessous, CPU restant expérimental.
- **Huit tests JVM ML réussis** dans la recette finale, et smoke tests de parité/prétraitement XNNPACK réussis sur téléphone, y compris après correction de projection.

## Résultat exécuté sur Mac

Six entrées : trois frames de la vidéo embarquée dans le simulateur HTC, deux exemples publics fournis avec Ultralytics et une mire RGB synthétique de dimensions impaires. Comparaison de **300 sorties par entrée** avec appariement un-à-un de même classe, indépendant de l’ordre TopK. Tolérances fixées avant la comparaison : 1 pixel par coordonnée, 0,001 de score ; aucun franchissement du seuil 0,70 toléré silencieusement.

| Fixture | Parité des 300 sorties PyTorch/ONNX | Objets ≥0,70 | Écart maximum coordonnées | Écart maximum score |
|---|---|---:|---:|---:|
| Simulateur, frame 5 % | Réussie | 0 | 0,005493 px | 0,00000431 |
| Simulateur, frame 50 % | Réussie | 0 | 0,002488 px | 0,000000142 |
| Simulateur, frame 90 % | Réussie | 0 | 0,002228 px | 0,000000090 |
| Ultralytics bus | Réussie | 3 : deux personnes, un véhicule | 0,000382 px | 0,00000525 |
| Ultralytics zidane | Réussie | 2 personnes | 0,000977 px | 0,00000148 |
| Mire RGB | Réussie | 0 | 0,002518 px | 0,000000104 |

Aucun résultat ne franchit différemment le seuil 0,70. Détails vérifiables dans `ml/parity_results.json`, protocole dans `ml/parity_protocol.json`, journaux dans `ml/export.log`.

## Première validation physique — HTC U24 pro, Android 14

L’orchestrateur a exécuté les tests sur le téléphone dans un harness de validation distinct pour préserver le starter installé. Les résultats initiaux sont archivés, sans écrasement, dans `validation/device-run-01/` : sortie Gradle, résultats XML/proto, logs par test, HTML et rapport XNNPACK extrait.

| Fournisseur demandé | Prétraitement et parité | Benchmark d’inférence seul | Statut |
|---|---|---|---|
| XNNPACK avec CPU en repli, 2 threads | Six fixtures passées, 300 sorties chacune et tenseurs pixels comparés ; positifs bus=3, zidane=2 | 3 échauffements +20 mesures ; p50 152,22 ms, p95 152,82 ms, maximum 153,06 ms. Prétraitement observé 24,01–32,27 ms sur les fixtures. | **RÉUSSI sur ce petit lot** ; candidat pour la chaîne live |
| CPU EP, 2 threads | Échec `Unmatched model row 291` ; l’ancien diagnostic n’indiquait pas le nom de fixture ou les valeurs | Non atteint lors du premier run | **ÉCHOUÉ**, pas de validation CPU Android revendiquée |

Le premier log ne suffit pas à conclure que l’écart CPU concerne uniquement des scores bas ou une permutation TopK. La ligne 291 désigne l’index de référence, pas une preuve de la cause. Le test a été instrumenté pour une seconde exécution : nom de fixture, toutes les sorties brutes, appariement maximal, lignes non appariées avec classe/score/coordonnées, trois voisins de même classe, écarts, franchissements de seuil et nombre d’objets applicatifs affectés. Le rapport est enregistré **avant** l’assertion finale ; les six fixtures restent évaluées même si l’une échoue. Les tolérances 1 px/0,001 n’ont pas été modifiées et une divergence sous seuil reste un échec de la parité complète.

XNNPACK ne signifie pas que toutes les opérations lui sont déléguées : le log ORT indique explicitement un repli CPU pour certains nœuds. Le petit benchmark est un candidat de performance, pas une mesure caméra→son ou une validation thermique de dix minutes. **Le provider CPU Android par défaut n’est pas validé ; utiliser explicitement le candidat XNNPACK dans une future intégration seulement en conservant cette provenance.** Aucun changement silencieux des poids, de la branche ou des tolérances n’a été fait.

## Seconde validation physique et choix du provider

Le run 02 a identifié l’échec : **CPU échoue seulement sur `sdk_sample_1`, 299/300 sorties appariées**. Une ligne `vehicle` à score 0,000004202127 est remplacée par une ligne `bike_scooter` à 0,000004112720 ; toutes deux sont très loin sous 0,70. Ce n’est pas une simple permutation. Aucun objet applicatif n’est perdu/ajouté dans ce lot et aucun seuil applicatif ne change de côté, mais la parité complète CPU reste échouée. Les tenseurs de pixels sont identiques pour les six fixtures. Le [diagnostic détaillé](CPU_XNNPACK_DIAGNOSTIC.md) distingue substitution observée, explication TopK probable et limites de l’observation des tenseurs intermédiaires.

XNNPACK réussit à nouveau les six fixtures et le prétraitement : p50 152,00 ms, p95 152,22 ms, maximum 152,67 ms sur vingt inférences ; CPU mesure p95 175,56 ms mais garde le statut de parité `FAILED`. L’orchestrateur choisit explicitement XNNPACK pour le prototype ; CPU reste expérimental. Le test sauvegarde maintenant les sorties et diagnostics avant de lever l’assertion d’échec. Les tolérances restent inchangées.

Le manifeste exporté et celui des assets portent maintenant `android_parity_executed=true` avec un sous-objet `android_validation` séparant réussite XNNPACK et échec CPU, téléphone, portée et empreinte des rapports. L’ONNX lui-même est inchangé. Cette valeur signifie que les essais Android ont été exécutés, pas que tous les providers sont validés ni que la chaîne lunettes est validée.

## Correction de projection après revue croisée

La revue HTC a révélé qu’une dimension redimensionnée arrondie ne s’inverse pas exactement avec le seul facteur idéal uniforme. Cas reproductible : 467×832→234×416, centre source 0,3898 reconstruit auparavant 0,390635, franchissant la frontière 0,39. La projection `raster_inverse_v2` normalise désormais par les dimensions réellement redimensionnées, par axe, avec convention de bords continus `xyxy`. Elle ajoute des tests autour des deux frontières, en portrait/paysage et pour le padding ; **les huit tests ML, dont ces nouveaux cas, ont réussi sur le build final**. Voir [revue géométrique](LETTERBOX_GEOMETRY_REVIEW.md).

Cette correction a été appliquée après le lancement du test soutenu : **l’endurance a utilisé l’ancien inverse**, tandis que son modèle, sa tensorisation et son prétraitement sont strictement identiques. Ses mesures de calcul restent pertinentes pour ces composants ; elles ne valident pas la nouvelle géométrie. Les smoke tests de parité/prétraitement et les tests JVM sont une recette distincte sur le build final. Aucun seuil, poids, sortie brute ou tolérance de parité n’a été modifié.

## Endurance du calcul — terminée

Le [test soutenu](ML_ENDURANCE.md) XNNPACK + prétraitement a terminé **600,026 s mesurées, 2 400 résultats frais, aucun résultat périmé ni offre sautée** à une cadence de 3,99983 Hz. L’intervalle offre synthétique→résultat atteint p95 **194,99 ms**, maximum 355,30 ms ; p95 inférence 159,01 ms et prétraitement 38,07 ms. La première et la dernière minute ont un p95 offre→résultat respectivement 192,70 et 192,95 ms. Le statut thermique Android reste 0 ; température batterie 30→34 °C. La mémoire native reste stable et la PSS ne montre pas de croissance continue sur ces dix minutes, avec les limites de comptabilité du test documentées.

Le test emploie quatre fixtures alternées et une horloge d’offre synthétique : **aucun flux des lunettes, décodeur, SDK ou audio n’est validé par ces chiffres**. Il utilise l’ancien inverse géométrique ; le modèle et la préparation des tenseurs sont identiques à la version corrigée. CPU garde son échec de parité. Les détails et le SHA du rapport brut sont conservés dans `ML_ENDURANCE.md` et `validation/device-endurance/summary.json` ; aucun asset n’a été modifié pour ce compte rendu.

## Recette finale après correction géométrique

Compilation du projet principal et de ses tests Android réussie. La campagne JVM finale comprend 39 tests réussis, dont **8 tests ML sans échec ni test ignoré**, vérifiables dans `validation/unit-tests-final/TEST-com.htc.vive.eagle.hackathon.starter.echonav.ml.YoloTensorContractTest.xml`.

Sur le téléphone, le nouveau smoke XNNPACK avec `raster_inverse_v2` réussit les **six prétraitements et six ensembles de 300 sorties**, avec les mêmes effectifs positifs (bus : 3, zidane : 2). Le petit benchmark final mesure p50 **153,52 ms**, p95 **153,68 ms**, maximum 153,87 ms. Rapport : `validation/device-final/files/ml_validation/onnx_xnnpack.json`. Le log `validation/device-combined-final.log` atteste deux tests physiques réussis : ce smoke ML et le test combiné de replay de 60 s conduit par l’agent HTC ; le périmètre de ce dernier est décrit dans son rapport.

Les assets ont été figés pour l’APK final avant cette actualisation documentaire. Le champ historique de portée de projection dans le manifeste mentionne encore la recette finale à venir ; **le présent rapport daté, le XML JVM et le JSON appareil ci-dessus apportent cette preuve finale**, sans modifier l’asset et provoquer un nouvel APK. Aucun poids, tenseur, seuil ou tolérance n’a été changé pour obtenir ces verdicts. L’échec CPU complet et les limites de validation lunettes/voix restent conservés.

Le test instrumenté écrit `files/ml_validation/onnx_cpu.json` et `onnx_xnnpack.json` dans le stockage privé de l’application. Il compare les tenseurs de prétraitement et les ensembles de sorties, puis effectue un benchmark d’inférence court. Si XNNPACK ne peut pas être créé, ce provider est déclaré indisponible ; une session créée doit passer les mêmes assertions que CPU. « XNNPACK demandé » n’atteste pas que tous les opérateurs lui sont délégués.

Les six fixtures établissent une parité numérique sur un petit lot, **pas** la qualité sur les images réelles des lunettes, le rappel/mAP, l’absence d’obstacles ou une cadence live de 4 Hz. La mire vérifie une transformation, pas une aptitude de détection. Les originaux Swift/Core ML n’ont pas été modifiés et leur équivalence avec le nouveau checkpoint n’est pas affirmée.

Le chemin de pixels adopte explicitement un arrondi bilinéaire Python/Kotlin commun ; il ne présume pas une identité pixel par pixel avec le resize OpenCV ni avec Vision `.scaleFill`. Un comparatif stretch/letterbox sur lot HTC annoté reste à faire pour sélectionner la meilleure qualité. Le pipeline live doit mesurer séparément extraction/copie YUV, prétraitement, inférence, décision et voix, ainsi que les résultats trop anciens et les périodes sans résultat frais.
