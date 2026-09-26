# Modèle SILMO — export et vérification

Le checkpoint original reste `oria-htc/ml/exports/oria_silmo_fp32.pt`. Le script travaille sur sa copie dans `exports/` ; les packages Core ML Paris V1 ne sont pas modifiés.

Depuis `oria-htc/`, environnement utilisé :

```sh
/opt/homebrew/bin/python3.11 -m venv ml/.venv
ml/.venv/bin/python -m pip install -r ml/requirements.lock.txt
ml/.venv/bin/python ml/export_and_validate.py
```

L’export existant est réutilisé pour éviter de le recréer à chaque préparation des fixtures. Pour reproduire **un nouvel export**, déplacer l’ancien dossier `ml/exports/` vers un dossier d’archive puis relancer. Conserver son manifeste et son empreinte dans l’archive ; ne pas écraser un résultat de validation antérieur après modification des paramètres.

Le contrat mesuré est FP32 `images[1,3,416,416]` → `output0[1,300,6]`. Chaque ligne de sortie est `x1,y1,x2,y2,score,class_id` en pixels de l’entrée letterbox. Le code Android applique l’inverse de letterbox, borne à l’image caméra et conserve les détections ≥0,70. Il n’ajoute ni NMS ni plafond à huit objets ; la politique décide ensuite ce qui devient une annonce.

La projection `raster_inverse_v2` utilise les dimensions redimensionnées **après arrondi** : `(x-paddingX)/resizedWidth`, `(y-paddingY)/resizedHeight`. Elle inverse les bords continus de l’image sans offset de demi-pixel supplémentaire. Ce choix diffère légèrement du gain uniforme employé par `Ultralytics.scale_boxes` et empêche l’erreur d’arrondi de déplacer artificiellement une boîte de l’autre côté des zones0,39/0,61. Voir `../validation/LETTERBOX_GEOMETRY_REVIEW.md`. Il ne change pas les tenseurs ni le modèle.

Le resize Android emploie une interpolation bilinéaire déterministe au centre des pixels avec arrondi uint8 explicite. Ce choix évite de dépendre du resize Bitmap/GPU du téléphone. Il partage la même géométrie que `LetterBox` d’Ultralytics, mais l’arrondi des pixels n’est pas présumé identique à OpenCV. Les tests comparent les tenseurs issus de cette implémentation Python/Kotlin. Cela ne prouve pas que letterbox est meilleur que le stretch Swift sur les lunettes.

Artefacts :

- `exports/oria_silmo_fp32.onnx` et `exports/model_manifest.json` : modèle et provenance.
- `parity_protocol.json` : tolérances figées avant comparaison.
- `parity_results.json` : sorties/écarts par fixture ; `export.log` : journal.
- `requirements.lock.txt` : toutes les versions installées.
- `../android-project/app/src/main/assets/oria/` : copie empaquetée du modèle/manifeste.
- `../android-project/app/src/androidTest/assets/ml/` : six images, tenseurs et sorties ONNX de référence.

Les trois frames du simulateur HTC et l’image synthétique ne produisent aucune détection à 0,70. Deux exemples publics installés avec Ultralytics (`bus.jpg`, `zidane.jpg`) couvrent des sorties positives personne/véhicule ; leur provenance est conservée dans `fixtures.json`. Ils ne constituent pas un jeu d’évaluation HTC et ne couvrent pas le rappel des six classes.

Tests Android (depuis `android-project/`, appareil explicitement ciblé par l’orchestrateur) :

```sh
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxObjectDetectorInstrumentedTest
```

Le test instrumenté vérifie les tenseurs pixels puis les 300 sorties en appariement un-à-un, tolérances coordonnées ≤1 pixel et scores ≤0,001. Il compare CPU et, si disponible, XNNPACK avec CPU en repli pour les opérations non déléguées. Chaque fournisseur exécuté doit passer la même parité. Les rapports sont écrits dans le dossier privé de l’application `files/ml_validation/` ; benchmark court d’inférence seulement, trois échauffements puis vingt mesures. Il ne valide ni la fraîcheur de la vidéo, ni l’audio, ni l’endurance thermique.

En cas de divergence, chaque fixture conserve désormais la sortie brute du téléphone (`<provider>_<fixture>.output.f32`) et son diagnostic (`.parity.json`). Le rapport complet est sauvé avant l’assertion finale. Garder les rapports du run précédent avant toute relance. Le premier run physique a validé XNNPACK sur les six fixtures, mais a échoué sur CPU : voir `../validation/ML_IMPLEMENTATION.md`. Les tolérances ne sont pas relâchées pour effacer cet écart.

Le test `sustainedXnnpackModelBenchmark` est activé uniquement avec l’argument du runner `mlEnduranceSeconds=600`. Il alterne deux scènes publiques positives et deux images SDK négatives à quatre offres synthétiques par seconde, avec le même prétraitement et détecteur. Il écrit un progrès toutes les dix secondes puis `onnx_xnnpack_endurance.json` : latence, résultats frais, slots perdus, PSS/heap, statut thermique Android et température batterie (qui n’est pas la température du SoC). Il n’utilise ni caméra, ni décodeur, ni voix. Une réussite mesure ce traitement soutenu seulement ; l’âge synthétique des fixtures ne valide pas la fraîcheur du flux des lunettes. Un statut thermique critique interrompt explicitement le test.

API : `OnnxObjectDetector(context, useXnnpack=false, numThreads=2)`, `detect(bitmap)`, `inferTensor(values)` pour validation et `close()`. Construction et inférence hors UI/callback caméra. Les appels sont sérialisés. L’appelant conserve la propriété du bitmap et fournit une image déjà orientée/non miroir ; le détecteur n’invente aucune rotation. Les métriques `lastPreprocessMs` et `lastInferenceMs` ne représentent pas la latence lunettes→son.

Sur le HTC U24 pro testé, **l’intégration du prototype passe explicitement `useXnnpack=true`**. Le défaut CPU de l’API reste réservé aux comparaisons et à l’investigation ; il ne constitue pas le choix validé sur cet appareil. Une création XNNPACK indisponible doit être rapportée, pas remplacée silencieusement par un CPU présenté comme validé.
