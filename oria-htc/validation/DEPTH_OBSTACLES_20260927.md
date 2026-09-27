# Occupation devant la caméra, indépendante des catégories

## Demande et changement de voie

L'utilisateur veut retrouver la fonction d'alerte générique de son application
iPhone : signaler un obstacle devant, à gauche ou à droite sans devoir reconnaître
un mur, une porte ou un meuble. La fonction Swift `analyzeForwardObstruction`
examine une carte de profondeur par zones ; elle utilisait des distances LiDAR
en mètres. Ses seuils de 1,85 m et 2,35 m ne sont **pas** applicables aux scores du
modèle monoculaire actuellement installé.

Le parcours principal du Lab devient **Relief seul · obstacles génériques** :
RGB → profondeur relative → occupation géométrique → confirmation temporelle.
Il ne charge pas SegFormer, ne lit pas de boîte YOLO et ne retire aucun obstacle
parce qu'il a déjà été reconnu. L'ancien mode surfaces + relief reste disponible
pour comparaison et pour les rapports historiques.

Cette version est exécutée dans Oria Lab sur Mac. Aucun changement d'APK ou de
sortie vocale HTC n'est effectué. Les propositions sont descriptives et sans son,
jamais un ordre d'avancer ou une certification de passage libre.

## Contrat et expérience géométrique

Le même Depth Anything V2 Small ONNX vérifié calcule une carte compacte 128×128,
orientée comme la PNG source. Il produit du relief inverse relatif ; sa
normalisation P2/P98 est propre à chaque image. Il n'y a ni distance métrique,
ni vitesse d'approche, ni pose/gravité disponible. Les sources primaires
présentent le modèle relatif et les modèles métriques comme des versions
distinctes : [dépôt officiel](https://github.com/DepthAnything/Depth-Anything-V2),
[article, section 5.2](https://arxiv.org/html/2406.09414v1).

La fenêtre normalisée x=[0,08;0,92], y=[0,18;0,72] reprend la région frontale
Swift, sans déduire une trajectoire du porteur. Le seuil relatif 0,65 conserve les
pixels au relief élevé dans cette image ; des composantes d'au moins 2,5 % de
la fenêtre éliminent les petits fragments. Ces valeurs ne sont pas des seuils de
proximité et ne permettent pas de comparer les distances entre images.

Un ajustement déterministe de plan dans la bande basse fournit une **hypothèse
de référence**, jamais un sol confirmé. Lorsqu'elle est acceptée, les pixels
compatibles avec ce plan sont retirés des candidats. Sans hypothèse exploitable,
l'occupation relative reste inspectable ; exiger ce repère avant tout candidat
faisait manquer la majorité des cloisons de la nouvelle capture. Une paroi
inclinée peut aussi ressembler à ce plan sans pose connue. Le diagnostic et les
comptages bruts/retirés restent dans chaque rapport.

La politique `rgb-depth-occupancy-v1-experimental` consomme uniquement les
fractions candidates par zone. Confirmation à partir de 12 % de la zone, trois
observations et 500 ms, rupture au-delà de 1 500 ms entre observations, répétition
espacée de 8 s par zone. Le centre confirmé est prioritaire. La médiane de relief
est un diagnostic ; elle ne classe pas les alertes comme si c'était une distance.
Les identités, horloges et gardes qualité restent strictes. Les images très sombres
ou uniformes suspendent l'interprétation sans inventer d'obstacle.

## Arbitrage comparatif

Les premières comparaisons réutilisent les cartes calculées des deux captures,
sans inférence supplémentaire. Trois expériences partagent exactement la même
région et la même politique : occupation brute, occupation moins pixels du plan
accepté, et résidus positifs seuls exigeant une référence. Le nombre de candidats
et l'abstention sont rapportés séparément : tout refuser n'est pas une réussite.

La voie exigeant une référence se révèle trop restrictive : seulement 14 images
avec candidats utilisables sur 92 nouvelles images, contre 78 pour l'occupation
brute après la garde qualité (84 masques non vides avant cette garde). La
soustraction des pixels du plan n'améliore pas les détections réelles mesurées
sur ce petit corpus ; elle est conservée comme heuristique testée contre le cas
synthétique d'un plan pur, avec sa limite explicitement documentée.

Les deux captures restent insuffisantes pour mesurer un taux de détection ou de
fausses alertes : elles contiennent peu de contrôles négatifs indépendants. Des
régions du sol peuvent encore produire des candidats. Les cloisons uniformes,
la séparation porte ouverte/fermée, les vitres, les marches et la proximité réelle
ne sont pas validées. Plus de candidats que la voie sémantique ne signifie pas
automatiquement une meilleure détection.

## Reproductibilité

Le mode fait partie des métadonnées immuables du job, de chaque ligne et du
rapport. L'API conserve l'ancien comportement lorsque `analysisMode` est absent ;
l'interface envoie explicitement `depth_only` pour le nouveau parcours. Son
admission dépend uniquement de l'installation du modèle de profondeur. Les temps
du seul runtime de profondeur sont distingués des temps de l'ancien parcours
combiné. Les captures, boîtes, modèle YOLO et seuils Android ne sont pas modifiés.

Les preuves brutes et images restent sous `validation/geometry-20260927/`,
ignoré par Git. Les rapports complets sont vérifiables avec
`validation/evaluate_obstacle_report.py`, qui sélectionne la politique selon le
mode et contrôle les empreintes PNG et horloges d'origine.

## Recette exécutée sur les deux captures

Après les comparaisons sur cartes en cache, les deux captures ont été **réinférées
avec le runtime profondeur seul**, via le serveur réel Oria Lab. Les 256 cartes
de profondeur sont exactement identiques à celles des précédents calculs combinés.
Tous les masques sémantiques et toutes les détections enregistrées sont `null`
dans ces nouveaux rapports ; aucun modèle de segmentation n'est chargé.

| Mesure Mac CPU | Nouvelle capture | Première capture |
|---|---:|---:|
| PNG traitées et empreintes vérifiées | 92 | 164 |
| Politique rejouée sans écart | 92 | 164 |
| Masques candidats non vides, avant qualité | 84 | 116 |
| Décisions incertaines, qualité limitée | 9 | 1 |
| Propositions descriptives | 7 | 13 |
| Durée totale du lot | 56,588 s | 100,877 s |
| Appel ONNX médian | 587,001 ms | 586,045 ms |
| Appel ONNX p95 | 595,503 ms | 622,399 ms |

Les fichiers sources contrôlés pendant la validation (97 et 172) restent
inchangés. Les propositions et cartes retrouvent le replay préalable en cache.
Ces nombres ne sont ni des taux de précision, ni des performances sur HTC.
Les agrégats sans images ni chemins privés sont publiés dans
`artifacts/depth-obstacles-validation.json`.

Les sept propositions de la nouvelle capture, aux indices 3, 25, 33, 56, 62, 76
et 84, ont été revues sur les PNG originales par un autre agent. Les régions
correspondent visuellement aux grandes cloisons blanches et jaunes. Les indices
56/62 ne prouvent **aucune détection de l'estrade** : la proposition vise la paroi,
alors que la marche est latérale ou sous la fenêtre d'analyse. Les annotations
visuelles réalisées avant consultation des prédictions ont été conservées.

**174 tests Python passent**, ainsi que les tests JS. Ils couvrent géométrie,
politique temporelle, données absentes/invalides, isolement de SegFormer/YOLO,
admission du mode, API, annulation et interface. La revue croisée ne trouve pas de
bloqueur de contrat. L'interface réelle a exécuté les 92 images, affiché
« Obstacle possible devant » sur la PNG 63 et exporté un rapport dont l'empreinte
est identique à la copie du serveur.

Onze sondes synthétiques indépendantes complètent les tests : un plan incliné
compatible avec la référence est supprimé ; un relief central ajouté est conservé
(IoU 1). **Un roulis important ou un gradient inversé produisent encore des faux
candidats de plan**. Une petite cible 4×4 et un obstacle entièrement sous la ROI
sont manqués ; une cible basse traversant la ROI n'est couverte qu'à 55,6 %.
Une porte coplanaire n'est pas distinguable d'un mur à partir d'une même carte.
Ces sondes testent l'algorithme sur cartes fabriquées, pas la fidélité du réseau
sur des scènes physiques.

## Suite avant une annonce sur les lunettes

Le parcours est consultable et testable dans le Lab. L'intégration Android avec
voix reste non exécutée : il faut d'abord mesurer les faux positifs sol/paroi,
les objets bas, les portes et les surfaces sans texture avec annotations
indépendantes, puis les temps et la fraîcheur sur HTC. Il faudra aussi traiter
les répétitions entre zones sans inventer d'identité d'objet. La réutilisation de
la région frontale Swift n'apporte ni son LiDAR, ni sa pose, ni ses distances.
