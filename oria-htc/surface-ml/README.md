# Surfaces et relief relatifs — expérience locale Oria Lab

Deux modèles RGB complètent l'analyse des replays sur le Mac, sans modifier le YOLO existant ni l'APK : SegFormer B0 attribue une des 150 catégories ADE20K aux pixels ; Depth Anything V2 Small produit un relief **relatif**. Ni les classes ni la carte relative ne donnent une distance en mètres, une proximité garantie, une vitesse d'approche ou un passage libre.

Le parcours **Relief seul · obstacles génériques** utilise uniquement le second modèle et fonctionne sans SegFormer. Après préparation de l'environnement, exécuter seulement `export_depth_model.py` pour ce parcours. `export_model.py` reste utile au mode de comparaison surfaces + relief.

Les modèles ne sont pas intégrés au lancement du serveur : aucune requête réseau n'est émise par `surface_inference.py`, son import, `surface_model_status()` ou `SurfaceDetector`. La conversion/téléchargement ci-dessous est une action explicite. Les modèles absents ou altérés ont un état explicite ; un checksum incorrect empêche leur chargement. Une profondeur absente peut laisser la segmentation utilisable avec `relativeDepth.available=false`.

## Installation reproductible

Depuis la racine du dépôt, avec Python 3.11 :

```sh
python3.11 -m venv oria-htc/surface-ml/.venv
oria-htc/surface-ml/.venv/bin/pip install -r oria-htc/surface-ml/requirements.lock.txt
oria-htc/surface-ml/.venv/bin/python oria-htc/surface-ml/export_model.py
oria-htc/surface-ml/.venv/bin/python oria-htc/surface-ml/export_depth_model.py
```

L'environnement de conversion est indépendant de `ml/.venv`. Le runtime de replay nécessite seulement ses dépendances existantes NumPy/Pillow/ONNX Runtime. Les téléchargements sont épinglés à une révision et un SHA-256 par fichier dans `source-lock.json` et `depth-source-lock.json`. Seuls les checkpoints Safetensors sont chargés. Les poids source et ONNX restent dans `weights/`, ignoré par Git, environ 221 Mio au total avec les deux sources et les deux exports. Les poids exportés font respectivement 15 172 775 et 99 117 601 octets (vérifier les manifestes, qui font autorité).

`--image /chemin/local/image.png` est répétable pour comparer PyTorch et ONNX sur des captures locales. Les images restent sur ce Mac et leurs chemins ne figurent pas dans le manifeste publié. Les téléchargements vont uniquement vers les URL de poids publics ; aucune capture n'est envoyée.

Un export vérifie les empreintes des sources, l'architecture, ONNX checker, le prétraitement du processeur Hugging Face et la fidélité des sorties. Il n'installe l'export et son manifeste qu'après réussite. L'export FP32 CPU utilise ONNX opset 17 et le mode `eval()` de PyTorch, y compris sur le wrapper exporté. Les exigences chiffrées et résultats sont dans chaque manifeste. Les nombres de performance sont des mesures ponctuelles Mac, pas une validation temps réel ou HTC.

## Contrat des pixels

- SegFormer : image source déjà orientée, conversion RGB, étirement bilinéaire Pillow à 512 × 512, division par 255, moyenne `[0.485,0.456,0.406]`, écart-type `[0.229,0.224,0.225]`, NCHW FP32 `[1,3,512,512]`. Pas de rotation, miroir, recadrage ou letterbox. Ce prétraitement est identique au processeur officiel sur les échantillons comparés.
- Sortie SegFormer : logits `[1,150,128,128]`, argmax natif 128 × 128. Les IDs de sortie ADE20K sont utilisés directement (mur 0, sol 3, fenêtre 8, porte 14, escaliers 53) ; aucun décalage lié au `reduce_labels` d'entraînement. La classe 59 « stairway » et les autres classes restent présentes dans le masque complet, sans être fusionnées.
- Le masque s'étire sur les limites exactes de l'image source, avec interpolation au plus proche pour l'affichage. Un centre de pixel `(x+0.5)/128` conserve son côté ; gauche `<0.39`, centre `[0.39,0.61]`, droite `>0.61`. Cela préserve les coordonnées normalisées, mais la distorsion d'aspect et les frontières grossières restent à évaluer.
- `wallFraction` est la fraction des pixels dont le gagnant est mur dans chaque zone. `wallMeanConfidence` est la moyenne du softmax du mur **sur ces pixels uniquement**, ou 0 si aucun. Ce score n'est pas une probabilité calibrée de présence ou de danger.
- Depth Anything : étirement **bicubique** à 518 × 518, mêmes moyenne/écart-type ; `keep_aspect_ratio=False` est un choix explicite différent du défaut officiel `True`, vérifié contre le processeur officiel avec ce même réglage. Sortie brute FP32 `[1,518,518]`, compactée à 128 × 128 avec Pillow bilinéaire en flottants.
- Le relief est normalisé par les percentiles P2 et P98 de **la carte compacte de l'image courante**, puis borné à `[0,1]`. Plus grand indique plus proche relativement dans l'image. Ces valeurs ne sont ni métriques ni comparables entre images. Les min/max bruts, percentiles et fractions écrêtées restent disponibles pour audit. Une carte presque constante est explicitement indisponible ; on n'amplifie pas son bruit numérique.

## API

Parcours sans catégories :

```python
from surface_inference import DepthOnlyDetector, depth_model_status
from depth_obstacles import compute_depth_obstacle_evidence
status = depth_model_status()
model = DepthOnlyDetector()  # ne construit aucune session de segmentation
result = model.infer(image_rgb)
geometry = compute_depth_obstacle_evidence(result['relativeDepth'])
```

Le résultat conserve l'enveloppe des rapports du Lab : `mask=None`, `classes={}`, `inferenceMs=0` (pas de segmentation), `relativeDepth`, `imageQuality` et `totalInferenceMs` (appel ONNX de profondeur seul). `analysisWallMs` inclut aussi ses pré/post-traitements ; la géométrie et la politique sont calculées ensuite. `DepthObstaclePolicy` confirme les fractions d'occupation par zone sans consulter les catégories ni les boîtes YOLO. Le plan éventuel est une hypothèse de référence, pas un sol confirmé. [Contrat et mesures](../validation/DEPTH_OBSTACLES_20260927.md).

Parcours surfaces + relief conservé :

```python
from surface_inference import SurfaceDetector, surface_model_status
status = surface_model_status()  # lit et vérifie seulement ; ne charge pas ONNX
model = SurfaceDetector()        # sessions CPU locales, 2 threads chacune
provenance = model.describe()
result = model.infer(image_rgb)  # image PIL déjà orientée, source non modifiée
```

`result` contient `width`, `height`, `maskWidth`, `maskHeight`, `mask`, `classes`, `zones`, `inferenceMs` (SegFormer), `relativeDepth` et `totalInferenceMs` (somme des deux appels ONNX uniquement, hors pré/post-traitement). `relativeDepth` fournit `available`, `values`, `width`, `height`, `convention`, `normalization`, `metric=false`, `temporallyComparable=false`, `rawStats` et son `inferenceMs`. `describe()` contient les deux provenances et licences. `SurfaceDetector(include_depth=False)` permet de désactiver explicitement le second modèle. Un modèle de profondeur corrompu présent est refusé, sans dégradation silencieuse.

Tests sans réseau :

```sh
oria-htc/ml/.venv/bin/python -m unittest discover -s oria-htc/oria-lab-desktop -p test_surface_inference.py -v
```

La fidélité numérique ne mesure pas l'exactitude sémantique. Une évaluation terrain avec annotations d'obstacles connus/inconnus, surfaces, reflets, obscurité et changements de repère reste requise avant toute annonce utilisable pour se déplacer. Aucun changement de seuil YOLO, de suivi V2 ni de politique Android n'est inclus ici.

## Provenance et licences

- [SegFormer B0 ADE20K, NVIDIA](https://huggingface.co/nvidia/segformer-b0-finetuned-ade-512-512), révision `489d5cd81a0b59fab9b7ea758d3548ebe99677da`. La [licence NVIDIA SegFormer](https://github.com/NVlabs/SegFormer/blob/65fa8cfa9b52b6ee7e8897a98705abf8570f9e32/LICENSE) limite l'usage à la **recherche et à l'évaluation non commerciale**. Copie complète : `LICENSE-SegFormer.txt`. Ce prototype ne doit pas être distribué comme un produit commercial avec ces poids sans résoudre cette contrainte.
- [Depth Anything V2 Small, version officielle Transformers](https://huggingface.co/depth-anything/Depth-Anything-V2-Small-hf), révision `5426e4f0f36572d16453bbda7a8389317b1bef99`. Le [projet officiel](https://github.com/DepthAnything/Depth-Anything-V2) confirme Apache-2.0 pour **Small** ; les variantes plus grandes ont une autre licence. Copie : `LICENSE-DepthAnythingV2.txt`.
- [Processeur SegFormer officiel](https://huggingface.co/docs/transformers/model_doc/segformer) et [modèle Depth Anything V2 officiel](https://huggingface.co/docs/transformers/model_doc/depth_anything_v2). Le dépôt original avertit que les chemins OpenCV et Pillow peuvent différer ; la référence mesurée ici est Transformers/Pillow.
