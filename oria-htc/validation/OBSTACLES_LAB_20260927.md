# Obstacles RGB hors couverture YOLO — expérience du 27 septembre

## Périmètre livré

La demande utilisateur a élargi la détection de murs à de gros obstacles devant la caméra, même hors des six classes de YOLO. La fonction **Obstacles · expérimental** est implémentée dans Oria Lab sur Mac, sur les captures enregistrées. Trois agents ont réalisé modèles/export, interface/jobs et politique/revue ; l’intégrateur a réalisé la fusion et la recette réelle. Aucun changement de l’APK, du modèle YOLO, du moteur d’alertes Android ni émission audio.

Les deux modèles officiels sont préparés localement : SegFormer B0 ADE20K (150 classes) et Depth Anything V2 Small (relief relatif). Entrées carrées, masques 128×128, révisions/empreintes, fidélité PyTorch/ONNX et licences sont décrits dans [surface-ml](../surface-ml/README.md). Les poids restent hors Git. SegFormer est limité à la recherche/évaluation non commerciale ; ce prototype ne vaut pas admission en distribution commerciale.

## Fonctionnement et sens des résultats

1. Segmenter les surfaces et calculer le relief relatif de la même PNG, sans rotation/miroir implicites.
2. Examiner la région normalisée x=[0,20;0,80], y=[0,18;0,92]. Écarter les classes de fond/sol explicites. Conserver les pixels au-dessus de 0,65 dans le relief normalisé P2/P98 de cette image, puis les composantes connexes d’au moins 2,5 % de cette région.
3. Retirer les pixels couverts par les boîtes YOLO enregistrées de **cette image**, confiance ≥0,70 ; appliquer de nouveau le minimum de composante pour écarter les petits fragments. Une inférence absente n’est jamais remplacée par une liste vide.
4. Par zone : fraction de surfaces éligibles ≥0,20, fraction candidate hors boîtes ≥0,12, soutien relatif ≥0,60 ; confirmer sur trois observations consécutives couvrant au moins 500 ms, avec gaps ≤1 500 ms. Centre prioritaire, puis score et départage stables. Répétition descriptive espacée de 8 s par zone. Tous ces seuils sont **expérimentaux**, sans étalonnage de danger.

« Hors boîtes YOLO » ne signifie pas que l’objet est certainement inconnu : une boîte peut recouvrir à tort un autre élément. La caméra ne donne pas la trajectoire du porteur. Une grande surface relativement proche dans l’image peut rester loin en mètres. Les valeurs de relief ne sont pas comparables entre images et ne donnent ni distance, ni vitesse d’approche, ni passage libre.

Le laboratoire permet les vues **surfaces**, **relief relatif**, **régions candidates**, le pas image par image synchronisé avec la sélection globale, l’annulation et l’export JSONL. L’analyse conserve identités, horloges et SHA-256 des PNG ; les rapports partiels sont identifiés. Un seul job actif, deux rapports temporaires, 256 Mio par rapport, 2 Mio par ligne. Ces limites concernent les analyses dérivées, pas la durée de capture. Une nouvelle analyse peut évincer un ancien rapport : exporter pour le conserver.

## Résultats exécutés

| Contrôle | Résultat |
| --- | --- |
| Suite Python Lab complète | **128 tests réussis**, dont 61 tests des nouvelles fonctions |
| Régressions JavaScript | Réussies, y compris réponses périmées, isolation capture, trois vues et transport local |
| Fidélité SegFormer | Accord argmax 100 % sur cinq échantillons ; ce n’est pas une mesure d’exactitude sémantique |
| Fidélité Depth Anything | Quatre échantillons ; erreur maximale du relief normalisé ≈3,994×10⁻⁶ |
| Capture réelle de 60,022 s | **164/164 PNG analysées**, identités/horloges/empreintes contrôlées |
| Rejeu des décisions à partir du rapport | **164/164 identiques**, sans relancer les modèles |
| Préservation | 172 fichiers locaux préexistants identiques avant/après |
| Résultats | 49 images avec des pixels candidats ; deux propositions descriptives |
| Temps du lot sur ce Mac | **119,446 s**, pour 164 images |
| Temps des deux appels ONNX seuls | médiane **691,260 ms**, p95 **699,334 ms**, hors pré/post-traitement |
| Export réel depuis le navigateur | JSONL de **63 231 339 octets**, identique au rapport serveur |

SHA-256 de l’export vérifié : `771d488e4c0c3528e4ec97f6cd65a0d511a4bdd65370dca5f30cc70a874b4f12`. Les preuves, PNG et rapports bruts restent locaux sous `validation/surfaces-20260927/`, exclus de Git. Le [résumé publiable](../artifacts/obstacles-lab-validation.json) contient les mesures et limites. La [revue indépendante](SURFACES_REVIEW_20260927.md) décrit les corrections de valeurs invalides, sessions, annulation, fermeture et intégrité.

Une inspection parcellaire préparée **avant** lecture des prédictions portait sur 21 points de trois images : 20 concordent avec les étiquettes de l’assistant. Une cloison jaune était classée « signboard » au lieu de « wall ». Cet échantillon minuscule, sans annotation humaine indépendante ni masque dense, ne fournit pas un taux de précision général.

## Observation concrète et décision du débat

Les propositions apparaissent aux indices source 132 (« Obstacle possible à droite ») et 133 (« Obstacle possible devant »), séparés de **363 ms**. L’inspection visuelle montre le même ensemble cloison/comptoir blanc ; l’inférence YOLO enregistrée à l’indice 133 ne contient aucune détection. Le masque candidat frontal apparaît sur cette surface. Cela illustre un complément à YOLO sur cet exemple, sans mesurer sa distance ou un risque de collision.

La répétition droite→centre révèle une limite : cette politique descriptive n’a pas d’identité d’objet. Le débat conclut à conserver les deux propositions pour l’inspection et à **ne pas les convertir directement en voix**. Un cooldown global aurait masqué le centre devenu prioritaire. La prochaine expérience comparera un regroupement temporel permettant au centre de remplacer une proposition latérale encore en attente, en mesurant doublons et délais.

## Non validé et suite

La chaîne tourne dans le laboratoire, pas sur le HTC. Le téléphone CN46V3M00284 était détecté, mais aucun essai caméra, voix, installation ou benchmark Android de ces deux modèles n’a été réalisé. Le temps Mac dépasse déjà la fenêtre de fraîcheur de 500 ms du moteur actuel ; il ne doit pas être transposé à une alerte directe. Optimisation/runtime mobile, consommation et qualité sur d’autres scènes doivent être mesurés avant intégration.

Il manque notamment un jeu annoté diversifié : cartons/meubles/cloisons, sol seul, objets déjà connus, vitres/reflets, obscurité, mouvements de tête et obstacle occupant presque toute l’image. La segmentation et le relief peuvent tous deux se tromper ; l’absence de candidat ne prouve pas l’absence d’obstacle.
