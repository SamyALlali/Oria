# Deuxième scène réelle — 27 septembre 2026

## Import et preuves conservées

La dernière capture finalisée du HTC U24 pro `CN46V3M00284` a été copiée par le
transfert USB existant puis importée dans Oria Lab. Capture `4bb1b9dd-b5d5-42e3-b797-ea3503acd535` :
35,690 s, 92 PNG, 1 015 paquets H264 et 352 événements. Les compteurs du manifeste
correspondent aux fichiers observés ; aucune image absente, identité ambiguë ou
horloge invalide n'a été détectée. L'archive, les PNG, les annotations et les
rapports bruts restent locaux, hors Git. Aucune capture ni installation lancée sur
le téléphone dans cette étape ; ses fichiers restent en place.

SHA-256 de l'archive transférée :
`cd520cf3e0ac180502554702575d5b06ee8011577911773ff03919351d850d41`.

## Inspection et résultats de référence

Un agent a inspecté les 92 images avant de consulter les nouvelles prédictions,
avec planches contact et gros plans. Ses annotations figées décrivent les objets
visibles et leurs incertitudes, pas une vérité terrain humaine de collision ou de
distance. Deux autres agents ont vérifié le moteur déterministe et la fusion RGB.
Les indices ci-dessous commencent à zéro (l'interface affiche index + 1).

| Cas observé | Résultat et limite |
| --- | --- |
| Panneaux blancs et assises, indices 1–32 | Candidats surtout sur le panneau. La détection de toutes les assises n'est pas démontrée. |
| Ouverture et panneau latéral, 33–35 | Proposition à droite sur le panneau ; cela ne certifie pas un passage libre au centre. |
| Cloison jaune, 36–44 | Surface retenue dans plusieurs zones, une proposition à gauche. |
| Cloison jaune uniforme, 45–52 | Échec : faux ciel sur 45–50 et 52 ; autres classes et relief insuffisant sur 51. Aucun obstacle proposé. |
| Estrade et pan jaune, 53–71 | La proposition à droite sur 61 vise le pan vertical, **pas** la marche en bas à gauche. |
| Personnes dans l'allée, 72–73 | Observation YOLO suffisamment forte sur une seule image ; confirmation non obtenue. |
| Cloison et nombreuses assises, 74–91 | Deux propositions gauche puis centre sur une même cloison plausible, à 450 ms d'intervalle. |

Analyse expérimentale de référence : **92/92 PNG** en **67,657 s** sur Mac,
temps des appels ONNX combinés médian **696,114 ms**, p95 **710,407 ms**.
78 images contiennent des pixels candidats. Sept propositions descriptives,
indices 3, 25, 33, 39, 61, 77, 78 ; aucune émission sonore. Les masques, empreintes
PNG et décisions sont conservés pour comparaison. La dernière PNG n'a pas
d'inférence YOLO enregistrée : la fusion l'indique comme observation manquante,
sans inventer une liste de détections vide.

## Moteur YOLO et voix enregistrés

Le vrai cœur Kotlin rejoué avec les détections et horloges originales reproduit
**91/91 décisions** : 90 `NO_FRESH_CANDIDATE`, une `NEEDS_CONFIRMATION`, aucune
alerte éligible et aucun événement audio. La comparaison ignore uniquement les
identifiants arbitraires de pistes et accepte 1e-6 sur les flottants. Aucun ticket
vocal fictif n'est confirmé.

Les temps YOLO **enregistrés sur le HTC** sont distincts du calcul de surfaces Mac :
médiane 158,832 ms, p95 172,343 ms. La confiance du piéton passe de 0,925 à 0,780
entre 72 et 73, sous le seuil d'annonce ; le paramétrage n'a pas été abaissé.
`orientationVerified=false` dans les 91 décisions : même avec une alerte éligible,
le contrôleur aurait bloqué la voix. Ce point doit être revérifié dans l'application
avant un essai audible, sans déduire une validation du repère de ce replay.

## Correction : distinguer une observation incertaine

Une couleur ou une mémoire de mur n'est pas une preuve fraîche d'obstacle. Le
correctif ajoute un diagnostic RGB indépendant des deux modèles : image très
sombre ou pauvre en structure spatiale. Les masques et fractions bruts restent
inspectables. Une image signalée limitée suspend l'interprétation de la politique
expérimentale, casse la série de confirmations et expose `uncertain` au lieu de
laisser une absence de candidat être interprétée comme un résultat fiable.

Contrat `rgb-quality-v1` : luminance Pillow sur grille 128×128, sans rotation ;
gradient local moyen des différences absolues horizontale et verticale. Faible
lumière si P95 < 24/255 ou au moins 90 % des pixels ≤ 8/255. Faible texture si
gradient moyen ≤ 1,5/255/pixel **et** au plus 1 % des gradients ≥ 8/255. La lumière
prend priorité sur la texture. Contraste et percentiles sont conservés pour audit.
Ces heuristiques ont été choisies après observation : elles ne constituent pas un
protocole préenregistré ni une probabilité de justesse étalonnée.

Comparaison des 256 PNG disponibles, sans relancer les modèles : neuf images
signalées limitées sur la nouvelle capture (0 sombre, 45–52 peu structurées), une
sur l'ancienne capture (0 sombre). **Cela ne récupère pas huit obstacles** : cela
expose explicitement l'incertitude. `usable` signifie uniquement que ces deux
heuristiques n'ont pas détecté de limite ; il ne garantit pas la segmentation.
L'erreur de ciel sur 53 reste notamment non signalée par ces critères.

Le diagnostic et l'état de politique sont visibles dans les trois vues du Lab,
avec compatibilité des anciens rapports qui n'ont pas ces champs. Ils suivent
l'identité de l'image et sont effacés pendant le chargement ou un changement de
session. Aucun changement du modèle YOLO, de ses seuils, du suivi Android ou de
l'APK.

## Vérifications du correctif

- **144 tests Python réussis** (suite Lab complète, 14,489 s), dont neuf tests de
  diagnostic sur images synthétiques et six nouveaux cas de politique. Un test
  d'intégration vérifie qu'une image limitée suspend la politique tout en gardant
  les pixels candidats bruts dans le rapport.
- Tests JavaScript réussis : états limitée/utilisable/non évaluée, trois vues,
  résultats périmés, changement de session, absence de relief et maintien du
  message d'incertitude après chargement asynchrone de l'image.
- Relecture croisée des critères, de la garde de confirmation et de l'interface.
- Rejeu sur résultats mis en cache : **92/92 et 164/164** identités/empreintes
  vérifiées ; les listes complètes des sept et deux propositions restent
  identiques avant/après. Neuf et un états `uncertain` remplacent uniquement des
  observations de mauvaise qualité, sans inférence supplémentaire dans ce test.
- Recette finale dans le navigateur avec **nouvelle inférence réelle de 92 PNG** :
  lot complet en **67,427 s**, masques sémantiques et fusion brute identiques sur
  92/92 images ; décisions finales identiques au test de comparaison ci-dessus.
  Image sombre puis cloison uniforme vérifiées visuellement dans l'interface.
  Export natif JSONL de 34 858 528 octets, empreinte
  `54749cd7de02f93beafc933d8ab824f32613431d367f6c3661c3acef1ab00cf0`.

## Reproduction et limites restantes

`validation/evaluate_obstacle_report.py` contrôle l'identité des PNG et le rejeu
exact du rapport. `validation/evaluate_image_quality.py` compare la nouvelle garde
sur les sorties mises en cache, avec empreintes et décisions avant/après. Les
preuves locales sont dans `validation/scene-4bb1b9dd/` (ignoré par Git).

Il manque des scènes de contrôle sans gros obstacle et un corpus indépendant
annoté. La double proposition latérale/centrale à 450 ms reste une limite connue,
comme sur la première capture ; aucune conversion directe en voix. Les meubles
bas, marches, vitres, distance et approche ne sont pas validés. L'échec du modèle
sur les aplats est documenté et signalé, pas résolu par un nouveau détecteur.
