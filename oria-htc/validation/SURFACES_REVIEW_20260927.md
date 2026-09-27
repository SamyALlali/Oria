# Revue Surfaces — politique descriptive expérimentale — 27 septembre 2026

## Périmètre de ce compte rendu

La politique ajoutée au laboratoire Mac produit uniquement des candidats textuels pour inspection. Elle ne joue aucun son, ne modifie pas le moteur Kotlin de production, les seuils YOLO, les images originales ou le modèle Android. L’objectif a été élargi des murs à des obstacles visuels que les catégories YOLO pourraient ne pas expliquer. Cet élargissement ne prétend pas reconnaître tous les obstacles.

Fichiers possédés par l’agent de politique : `oria-lab-desktop/surface_policy.py`, `test_surface_policy.py`, `fixtures/surfaces/` et ce compte rendu. Les inférences, la fusion géométrique, les jobs et l’interface appartiennent aux autres agents. Leurs mesures d’exécution doivent être consignées séparément par l’orchestrateur.

## Contrat déterministe

Une instance de `SurfaceObstaclePolicy` par traitement, ou un appel explicite à `reset()` avant une nouvelle passe. `process(session_id, frame_index, observed_at_ms, zones)` reçoit l’identité vidéo et l’horloge **de la capture**, jamais l’heure du Mac. L’identité est une chaîne non vide ou un entier non négatif de 64 bits ; `7` et `"7"` sont distincts. Index et horloge sont des entiers de même domaine, sans coercition de booléen, chaîne ou flottant.

Les zones forment une liste complète de trois objets nommés ou une carte stricte `LEFT/CENTER/RIGHT`. Les doublons, noms inconnus, conflits de nom, nombres non finis, mesures hors `[0,1]` sont refusés. Une observation absente, une liste vide, une zone ou une mesure manquante cassent la confirmation. Elles ne sont pas transformées en observation à zéro. Une observation complète à zéro casse également la confirmation mais conserve le statut distinct `no_candidate`, qui ne veut jamais dire « voie libre ».

Pour chaque zone générique, la fusion fournit :

- `obstructionFraction` : pixels hors fond sémantique, divisés par la surface de l’intersection ROI/zone ;
- `unrecognizedFraction` : composantes à relief relatif suffisant, hors rectangles YOLO, divisées par la même surface ;
- `depthRelativeSupport` : part des pixels hors fond atteignant le seuil relatif, avant masquage YOLO.

Ces trois grandeurs sont des fractions géométriques. Ce ne sont ni des mètres ni des probabilités. La politique vérifie aussi que la fraction restante ne dépasse pas `obstructionFraction × depthRelativeSupport`, avec une tolérance numérique de `1e-9`.

Si le relief ou l’observation YOLO sont indisponibles, l’appelant remet `zones=None`. Une inférence YOLO réellement enregistrée avec une liste vide reste une observation disponible. Le module de politique ne peut pas déduire cette provenance à partir de nombres seuls ; l’admission relève aussi des gardes du job et du module de fusion.

## Paramètres provisoires visibles dans chaque résultat

| Paramètre | Politique générique | Mur seul, conservé pour comparaison |
| --- | ---: | ---: |
| Fraction hors fond minimale | 0,20 | — |
| Fraction restante hors YOLO minimale | 0,12 | — |
| Support du relief relatif minimal | 0,60 | — |
| Fraction de mur minimale | — | 0,35 |
| Softmax moyen des pixels classés mur | — | 0,60, non étalonné |
| Observations consécutives | 3 | 3 |
| Temps minimal entre première et dernière | 500 ms | 500 ms |
| Écart temporel maximal conservant la preuve | 1 500 ms | 1 500 ms |
| Délai minimal de répétition d’une zone | 8 000 ms | 8 000 ms |

Ces valeurs servent à une expérience Lab. Elles ne résultent pas d’une validation utilisateur ou d’une calibration de danger. La proposition générique est « Obstacle possible devant / à gauche / à droite ». `SurfaceWallPolicy` conserve « Mur probable… » pour des comparaisons distinctes ; le parcours générique n’exige pas que la surface soit un mur.

La preuve est indépendante pour chaque zone. Un changement de côté ne réutilise pas les observations du côté précédent. Parmi les zones confirmées, le centre est prioritaire, puis le score `unrecognizedFraction × depthRelativeSupport`, puis gauche avant droite en cas d’égalité. Le mur seul utilise `wallFraction × wallMeanConfidence`. Une zone prioritaire en délai de répétition ne cède pas sa place à un côté uniquement pour produire un autre message.

Les indices ou horloges répétés/inversés sont rejetés et cassent la confirmation sans remonter les derniers index/temps acceptés. Un saut d’index source casse la preuve même si le temps écoulé est court. Un écart temporel supérieur à 1 500 ms expose `resetGap:true`. Un changement d’identité vidéo vide preuve et répétitions. Les trous et observations manquantes effacent les preuves mais conservent les délais de répétition de la même session. Aucun état n’est conservé après `reset()`.

## Vérification exécutée par cet agent

Commande : `python3 -m unittest discover -s oria-htc/oria-lab-desktop -p test_surface_policy.py -v`.

Résultat : **27 tests réussis**, fixtures entièrement synthétiques, aucune capture privée publiée. Sont couverts : égalité aux seuils, minima d’observations et de durée, maximum de gap exact, trous d’indices, doublons, horloges inversées, nouvelle session typée, remise à zéro, absence/vide/invalide, zones dupliquées, valeurs numériques extrêmes, score et départage stable, preuve séparée par côté, répétition à l’échéance exacte, priorité du centre pendant le délai de répétition, immutabilité des entrées, cohérence des fractions et sérialisation JSON sans NaN.

Cela prouve le comportement de la politique sur ces entrées, pas l’exactitude des masques, la fidélité des alertes dans la rue, une proximité ou une validation HTC. Aucun test matériel ou essai d’écoute n’a été effectué par cet agent dans cette mission.

## Relecture croisée

La fusion `surface_obstacles.py` a été relue : ROI, connexité avant et après masquage des boîtes, absence distincte d’une liste YOLO vide et conservation des données sont cohérentes. Trois durcissements ont été signalés puis annoncés corrigés par l’orchestrateur : très grands entiers provoquant `OverflowError`, dimensions de relief acceptant implicitement des flottants/booléens et dictionnaire de configuration partagé par référence. La recette finale de la fusion relève de sa suite dédiée.

La première lecture de `surface_jobs.py` et de l’interface a confirmé les identités capture/job/image, l’invalidation des requêtes obsolètes, les réservations de capture, les rapports partiels et l’absence de sortie audio. Deux corrections ont été demandées au propriétaire : revérifier l’annulation après la fusion, avant la politique/publication ; garantir la libération de la capture même si la fermeture du fichier de rapport échoue. Une empreinte de la PNG effectivement analysée et une vérification après calcul ont également été proposées pour renforcer la provenance. **Résolution relue dans la source** : contrôles d’annulation après fusion et avant écriture, gestion distincte des échecs de résumé/fermeture, libération des réservations même en cas d’échec de fermeture, empreinte SHA256 lue sur le descripteur PNG puis revalidation après inférence. Les rapports dont la fermeture échoue sont non exportables. Le propriétaire a exécuté des tests dédiés d’annulation pendant fusion, d’échecs de résumé/fermeture et de PNG modifiée ; ces tests n’ont pas été relancés par cet agent.

La première lecture du runtime a confirmé RGB, étirement bilinéaire, sortie argmax native 128×128, softmax mur non étalonné, contrôle des empreintes et absence de téléchargement implicite. Le contrôle des sorties après conversion float32 et la validation stricte des dimensions ont été demandés au propriétaire. **Résolution relue dans la source** : logits de segmentation exigés en float32, finitude contrôlée, dimensions entières positives hors booléens ; contrat du prétraitement et convention du relief vérifiés. Une profondeur relative dégénérée est déclarée indisponible. Le propriétaire a exécuté 12 tests runtime, qui n’ont pas été relancés par cet agent. La fidélité de conversion publiée dans le manifeste est une mesure distincte de la justesse sémantique.

## Limites et admission

Le masque compact perd des détails de bord ; le relief monoculaire est normalisé séparément pour chaque image. Il n’offre aucune distance absolue, aucune vitesse d’approche et aucune comparaison temporelle de distance. Un rectangle YOLO peut recouvrir à tort une surface ; « hors boîte » ne prouve pas un objet inconnu. L’absence de candidat ne prouve pas que le passage est libre.

Le manifeste SegFormer local indique un usage recherche/évaluation non commercial, avec source et licence enregistrées. Ce candidat reste dans le laboratoire ; aucune inclusion de poids ni activation de ces propositions dans la distribution Android de production n’est réalisée ici. La présence d’une seconde dépendance ne supprime pas les conditions de la première.

Avant toute proposition vocale réelle : annoter des séquences représentatives, comparer faux positifs/faux négatifs et stabilité, vérifier les cas sol/mur/escalier/personne/objet partiellement masqué, puis effectuer des essais accompagnés avec les lunettes. Aucun résultat de cette note ne remplace cette évaluation.
