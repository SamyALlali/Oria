# Captures longues : suivi Android et transfert USB

État du 27 septembre 2026. Étude ciblée, sans commande ADB ni Gradle dans cette mission. Les fichiers Android sont restés figés. Les mesures utilisent exclusivement des fixtures synthétiques temporaires sur le Mac ; aucune scène privée n’a été lue pour ce microbenchmark.

## Blocage USB reproduit et corrigé

Le transfert conservait une borne de 2 Gio et 10 000 fichiers. Avant correction, deux flux entièrement simulés ont reproduit le refus « Capture supérieure à 2 Gio » dès l’en-tête d’une vidéo de **2 400 Mio** puis **9 600 Mio**. Ces volumes représentent une hypothèse synthétique de 80 Mio/min sur 30 min et 2 h ; les plusieurs Gio n’ont pas été matérialisés. Un TAR réel synthétique contenant 10 001 fichiers vides a reproduit l’autre refus. Chaque échec a laissé le dossier de destination sans fichier partiel.

`oria-lab-transfer/pull_capture.py` applique désormais les mêmes bornes techniques que le lecteur Mac : **128 Gio / 100 000 fichiers**, sans limite de durée. La réserve de **512 Mio libres** protège l’intégralité du ZIP, métadonnées comprises. Les données sont copiées par blocs de 1 Mio et ZIP64 est activé. Le script vérifie le nombre d’octets copié pour chaque fichier, les chemins, types et doublons, le CRC de toutes les entrées, le manifeste complet et l’empreinte SHA-256 finale.

Le `.part` est acquis exclusivement avant le démarrage du transport. Son nettoyage concerne uniquement la tentative qui l’a créé. La publication par lien atomique dans le même dossier refuse d’écraser une archive apparue en parallèle. Les systèmes de fichiers sans liens matériels échouent explicitement ; le téléphone conserve sa capture. Les messages d’erreur du transport utilisent un fichier temporaire, évitant le blocage d’un tube stderr plein. Les métadonnées TAR déjà consommées sont libérées ; 200 032 en-têtes TAR au maximum empêchent de contourner la borne avec des répertoires seuls.

**11 tests ciblés passent** : admission des tailles 30 min/2 h, bornes exactes, 10 003 fichiers effectivement convertis et vérifiés, réserve disque, transport simulé complet et reçu, échecs/troncature, collisions concurrentes `.part` et destination, identité du manifeste, chemins/liens/doublons, borne des répertoires. Relecture indépendante favorable par l’agent de suivi.

Commande reproductible, sans téléphone :

```sh
cd oria-htc/oria-lab-transfer
python3 -m unittest -v test_pull_capture.py
```

L’orchestrateur a séparément indiqué une recette USB réelle réussie de **91 739 169 octets**, avec 168 charges utiles identiques par SHA-256 à la copie Mac existante. Les preuves de cette autre recette sont dans `validation/night-20260927-CN46V3M00284/final/usb-transfer/`. Ce contrôle ne démontre pas un transfert physique de 30 min ou 2 h.

## Galerie Android : mesures ciblées hors appareil

La sélection actuelle est configurée à 333 ms. Les fixtures représentent donc 5 406 et 21 622 images, chacune avec trois détections, une décision, et 1 800 valeurs numériques de sortie brute par événement d’inférence. Les fichiers d’image servent uniquement à vérifier l’existence des chemins : aucun décodage Bitmap, aucune vidéo et aucune inférence ne sont chronométrés.

La fonction `loadGallery` a été extraite de `OriaLabScreen.kt` et compilée seule avec Kotlin 2.0.21 et les contrats métier existants, sur JBR 21.0.11 (`-Xms32m -Xmx512m`), sans Gradle. Le parseur Android `JSONObject` n’étant pas exécutable directement sur cette JVM, une mince adaptation Gson 2.11.0 fournit les accès JSON utilisés. Il s’agit donc d’un **microbenchmark de l’algorithme sur Mac**, pas d’une mesure de latence ou mémoire Android.

Un prototype exclusivement temporaire conserve exactement le parcours et les données utiles de la galerie, mais ignore `rawModelOutput`/`rawOutput` au niveau du parseur en flux. Ces tableaux sont inutilisés pour afficher les boîtes enregistrées. Chaque variante a été exécutée dans trois JVM séparées. Le temps indiqué est la médiane ; la mémoire est l’augmentation de heap conservée après GC, pas le pic ni la mémoire totale de l’application.

| Durée synthétique | Images | events.jsonl | Parcours actuel | Prototype sans parsing brut | Gain mesuré | Heap conservée, actuel |
|---|---:|---:|---:|---:|---:|---:|
| 30 min | 5,406 | 57.96 Mio | 489.0 ms | 354.2 ms | 27.6 % | 2.80 Mio |
| 120 min | 21,622 | 231.83 Mio | 1669.0 ms | 1224.0 ms | 26.7 % | 10.57 Mio |

Les deux parcours produisent le même checksum sur **toutes** les images, identifiants, horodatages, noms d’image, états analysés, motifs et détections de chaque fixture. La mémoire conservée reste pratiquement identique : l’optimisation enlève du travail de parsing, pas les listes de galerie.

Checksum de la fonction extraite avant ajustement de visibilité pour le banc : `1d0ae26e263ffefd76d9c56e954342b51f34e3fd17d23bcbc54cf2f8f262cf71`.

| Fixture | SHA-256 des résultats, identique dans les six exécutions |
|---|---|
| 30 min | `c6609bcf1adb6172720baa7ad755e51366fc5830985505a5472811ed879488e6` |
| 120 min | `b41cd6de0549a12725e0f7688214ee5db6e3bbb63bbac77ec906a3f608dd739e` |

## Limites restantes et prochain passage

- La galerie Android n’impose pas de borne en secondes. Elle parcourt cependant l’intégralité des événements puis des frames avant d’afficher la première image. Sa liste de frames et ses détections occupent O(n) mémoire. Un seul Bitmap est demandé à la fois. Le heap retenu de ce banc atteint environ 10,6 Mio pour 2 h, mais les allocations transitoires, l’app Android, ONNX et les bitmaps s’ajoutent sur le téléphone.
- Une image manquante ou une ligne invalide est ignorée par `mapNotNull/runCatching`. Reproduction supplémentaire : 20 entrées de frames, une PNG retirée dans une fixture temporaire → **19 frames affichables, `error = null`**. Ce comportement peut masquer une capture endommagée. Il faut afficher les comptes « attendues / lisibles / ignorées » et les causes, plutôt que réduire silencieusement la galerie.
- Le prochain changement Android le plus petit et mesuré est de lire les événements en flux en sautant les tableaux bruts inutilisés. Le prototype réduit ce parcours d’environ 27 % sur Mac avec un résultat identique pour les fixtures. À vérifier ensuite sur l’appareil avant de promettre ce gain au HTC.
- Pour les captures nettement plus longues, indexer les offsets des événements et charger une fenêtre de frames évitera de retenir tous les objets et de reparser tout le fichier à chaque ouverture. L’index doit conserver le couple `(videoSessionId, frameId)` et les horodatages, afficher les entrées invalides explicitement et rester annulable quand l’utilisateur quitte une relecture. Aucun plafonnement ou découpage silencieux de durée n’est proposé.
- La version Mac préparée par l’autre agent possède désormais des index paresseux pour frames, événements et paquets, avec caches bornés. Ses offsets/descripteurs restent O(n) et les bornes techniques restent explicites ; cela ne constitue pas une preuve de mémoire constante pour une durée arbitraire.

Les fixtures volumineuses et le banc ont été produits sous `/tmp/oria-long-followup-measure`, hors données utilisateur et hors dépôt. Seuls ce rapport, le transfert, ses tests et son README sont livrés par cette mission.
