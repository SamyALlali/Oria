# Revue de l’inverse letterbox aux frontières de direction

26 septembre 2026. Revue croisée HTC/ML, calcul analytique confirmé ; correction appliquée dans `LetterboxTransform.toCameraBox` avec accord de l’orchestrateur. **Recette finale réussie : huit tests JVM ML et smoke XNNPACK sur six fixtures physiques.** Ce document n’est pas un test de précision YOLO.

Le prétraitement calcule un facteur idéal uniforme `r=min(416/largeur,416/hauteur)`, puis arrondit les dimensions du bitmap redimensionné. Le resize bilinéaire effectif utilise les dimensions entières obtenues. En conséquence, son facteur horizontal réel peut différer légèrement du facteur vertical et de `r`.

Exemple : source 467×832, `r=0,5`, image redimensionnée 234×416, padding gauche 91. Le facteur effectif horizontal vaut 234/467, pas 0,5.

| Centre normalisé dans la source | Coordonnée X dans l’entrée modèle | Inverse uniforme actuel | Inverse des dimensions effectives |
|---|---:|---:|---:|
| 0,3898, côté gauche de la frontière 0,39 | 182,2132 | 0,39063469 | 0,3898 |
| 0,3902, côté droit de la frontière 0,39 | 182,3068 | 0,39103555 | 0,3902 |
| 0,6098, côté gauche de la frontière 0,61 | 233,6932 | 0,61110578 | 0,6098 |
| 0,6102, côté droit de la frontière 0,61 | 233,7868 | 0,61150664 | 0,6102 |

Une erreur subpixel dans l’entrée 416 suffit donc à faire changer une direction lorsque le centre est proche d’une frontière. Ce test ne prétend pas que YOLO localise un objet à cette précision ; il vérifie que la transformation déterministe n’ajoute pas son propre biais.

## Convention et décision recommandée

Les boîtes `xyxy` sont traitées comme coordonnées continues de bords d’image. L’inverse normalisée exacte du rectangle redimensionné est :

```text
x_normalisé = (x_modèle - padding_gauche) / largeur_redimensionnée
y_normalisé = (y_modèle - padding_haut) / hauteur_redimensionnée
```

Puis borner à [0,1]. Ne pas ajouter un décalage de demi-pixel aux boîtes. Les décalages `+0,5/-0,5` du resize décrivent la correspondance des **centres des pixels échantillonnés**, tandis que l’étendue continue des bords suit les facteurs effectifs.

Ultralytics 8.4.27 `utils/ops.py:102` emploie bien le gain uniforme dans `scale_boxes`, conformément à sa convention de mise à l’échelle des labels. Garder ce comportement reproduirait cette approximation historique. Le contrat Android annoncé est cependant une boîte normalisée dans l’image caméra après inversion du prétraitement réellement exécuté. **Recommandation : facteurs effectifs pour cette projection finale, écart à Ultralytics explicitement documenté.** Le modèle, son tenseur d’entrée, ses sorties brutes et le choix letterbox restent inchangés.

## Vérification exécutée

Les tests `exactRasterInverseKeepsBothSidesOfDirectionBoundaries` et `paddedCoordinatesAreClippedInBothOrientations` ont été ajoutés dans `YoloTensorContractTest`. Ils couvrent les centres 0,3898/0,3902/0,6098/0,6102 et les dimensions 467×832, 480×856, 832×467 et 856×480 : portrait/paysage, arrondis vers le haut/bas. Ils vérifient retour au centre source, maintien du côté de chaque frontière et clipping du padding. Les autres tests conservent classes multiples, sortie bornée et absence de NMS supplémentaire.

Le manifeste décrit maintenant `postprocessing_revision=raster_inverse_v2` et les formules d’inversion. L’ONNX, les tenseurs de fixtures et le prétraitement sont inchangés. Les huit tests du contrat ML réussissent dans `validation/unit-tests-final/TEST-com.htc.vive.eagle.hackathon.starter.oria.ml.YoloTensorContractTest.xml` ; le smoke sur téléphone réussit les six fixtures dans `validation/device-final/files/ml_validation/onnx_xnnpack.json`. Le manifeste ayant été figé pour l’APK final avant ces résultats, ces rapports constituent la preuve datée finale et n’ont pas entraîné de modification d’asset.

La parité des sorties brutes reste réussie avec XNNPACK après correction. L’endurance achevée de dix minutes concerne le même modèle/prétraitement et reste informative ; elle n’est pas présentée comme une validation rétrospective de la nouvelle projection géométrique. Celle-ci dispose de ses propres tests finaux.
