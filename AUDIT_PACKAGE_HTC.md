# Repérage du package HTC pour le prompt EchoNav

Inspection statique effectuée le 26 septembre 2026. Le projet HTC original n’a pas été modifié. Aucun build, lancement de simulateur ou essai sur lunettes n’a été effectué dans cette préparation du prompt.

## Ce que le package confirme

| Point | Preuve locale | Conséquence |
|---|---|---|
| Le code source est fourni avec l’APK | `android-project/app/src/main/` et `apk/VIVE_Eagle_Hackathon_Starter_debug.apk` | Adapter et recompiler le projet existant. |
| Identité de whitelist | `README.md`, `docs/setup-guide.md`, `app/build.gradle.kts:8` et `:14` | Conserver `com.htc.vive.eagle.hackathon.starter`. |
| SDK local 0.6.0 et simulateur | `app/build.gradle.kts:65` et `:66` | Les deux dépendances HTC sont dans le package. |
| Simulateur intégré à l’application | `MainActivity.kt:105`, `ViveGlassKitManager.kt:523`, `ui/tab/Screens.kt:130` | Bascule d’adaptateur et interface de simulation présentes. |
| Médias de simulation embarqués | Inspection ZIP de `app/libs/ViveGlassSimulator-release.aar`, ressources `res/raw/` | Vidéo MP4, image HEIC et audio AAC disponibles. |
| Remplacement du média de simulation prévu | Commentaire de `ViveGlassKitManager.kt:638` et noms présents dans les classes du simulateur | Chemin privé `files/video_sample.mp4`, à tester. |
| Callback vidéo compressée | `ViveGlassKitManager.kt:351`, `startVideoStreaming` à `:643` | Le callback ne fournit pas directement une image RGB. |
| Décodage vers une surface d’affichage | `util/H264Decoder.kt:273` et `:312` | Il faut choisir et valider un accès aux pixels pour YOLO. |
| Le flux Bitmap actuel concerne les photos | `ViveGlassKitManager.kt:187` | Ne pas brancher YOLO sur `imageReceived` en croyant traiter la vidéo. |
| Synthèse vocale déjà exposée | `ViveGlassKitManager.kt:534` | Premier chemin à tester pour les alertes, sans garantie de comportement matériel. |
| Dépendance au cycle de vie de l’aperçu | `ViveGlassKitManager.kt:453` et `:472` | Extraction/inférence à découpler ou à gérer explicitement. |
| Points de robustesse à auditer | `ViveGlassKitManager.kt:370` et `:377` contiennent des `TODO()` ; files dans `util/H264Decoder.kt:30` et `:42` | Ne pas considérer le sample comme un pipeline temps réel déjà robuste. |
| Tampon et lecture audio du flux | `util/StreamingPlayer.kt:23` et `:141` | Vérifier latence et coexistence avec les alertes. |
| Vidéo dépendante de l’horloge audio | `util/StreamingPlayer.kt:26`, `:56`, `util/H264Decoder.kt:224` | Découpler cette horloge avant de désactiver la restitution micro. |
| Retour microphone vers le téléphone | `util/AudioDecoder.kt:321` demande le haut-parleur intégré | Ce chemin ne prouve pas la sortie des alertes dans les lunettes. |
| Pertes possibles de données encodées | `util/H264Decoder.kt:218`, `:235`, `:293` | Gérer saturation et absence de buffer d’entrée ; ne pas perdre un paquet déjà dépilé. |
| Retour de synthèse sans identifiant de requête | `ViveGlassKitManager.kt:236` | Un callback tardif ne peut pas être corrélé par la seule génération applicative ; prévoir un état audio incertain. |
| Logs désactivés par défaut | `MainActivity.kt:82` | Prévoir une instrumentation de développement limitée. |
| Configuration de poste Windows | `android-project/local.properties` et scripts `.bat` | Adapter la copie de travail au Mac. |

Les chemins de code abrégés dans le tableau sont relatifs à `android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/`, sauf mention explicite de `app/`, `android-project/` ou d’un document racine.

## Sources et modèles disponibles

La copie locale complète est `/Users/sam/Documents/ChatGPT/echonav/EchoNav_main`, branche `main`, commit `8fbb4e03911bfc16e90e072f3446a492bc61c2ea`. L’accès aux sources est résolu ; aucun déblocage GitHub n’est requis pour poursuivre le travail local. Le dépôt est resté inchangé pendant cet audit.

Les packages `EchoNav_App/best.mlpackage` et `EchoTest/Sources/best.mlpackage` ont été inspectés : leurs trois fichiers correspondent exactement aux empreintes Paris V1 consignées dans `Documentation/GREENLIGHT_BASELINE.json`. Ce manifeste décrit un checkpoint source SHA-256 `3661675b3ea931bbc6bed0892753912764ce6fc4bc2a8c13745ba8f0d0bc5dc4`, distinct de l’empreinte du `.pt` fourni pour SILMO.

Le checkpoint `.pt` permet de préparer directement un export Android. Le contrat Core ML Paris V1 est documenté comme `[1,10,3549]`, non end-to-end, avec NMS dans le Swift. La première proposition d’export du candidat SILMO conserve sa branche end-to-end : le parseur doit être adapté et vérifié. Les références ne doivent pas être déclarées équivalentes sur la seule base de leurs six classes communes.

## Réutilisation du projet EchoNav complet

Le moteur principal identifié est `EchoNav_App/EchoNav/EchoNavApp.swift`, avec un dérivé généré pour EchoTest. L’audit des sources a confirmé arbitrage des dangers, attribution de profondeur, branche d’alerte visuelle, stabilisation temporelle, mémoire anti-répétition, formulations vocales et protection des sessions. La [carte de portage détaillée](</Users/sam/Documents/ChatGPT/Hackathon SILMO/PORTAGE_ECHONAV_SWIFT_ANDROID.md>) fournit fonctions, lignes, paramètres, dépendances matérielles et tests.

Le prompt exige désormais un expert EchoNav/Swift, une matrice de portage avec fichier/symbole/commit, la reprise des paramètres existants et une justification des adaptations. Les calculs métier seront portés vers un cœur Kotlin testable ; les entrées/sorties dépendantes d’Apple seront adaptées aux capacités vérifiées du starter HTC. Toute bibliothèque réellement portable trouvée dans le dépôt sera évaluée avant réécriture.

Adaptations principales : remplacer la profondeur/pose ARKit uniquement par des données réellement disponibles, expliciter un mode RGB avec prérequis propres, adapter l’association des entités vocales et traiter la distance heuristique comme telle. La branche visuelle existante est utile, mais le démarrage live iOS exige toujours le LiDAR/profondeur.

La validation comparera séparément `.pt`/export Android et politiques Swift/Kotlin sur des entrées communes. Les 76 déclarations de tests Swift ont été comptées, sans exécution. Les contrats des quatre sessions EchoTest sont présents, mais aucun `frames.jsonl` ni média brut correspondant n’a été trouvé dans cette copie ; leur emplacement externe reste à préciser avant replay.

## Checkpoint local fourni et inspecté

Fichier : `/Users/sam/Downloads/echonav_current_best.pt`. Taille : 20 285 758 octets, soit environ 19,35 Mio. SHA-256 : `7dd79d15d1fe61200b19916c7d0b73136637fd768c9da53a94d0499f010a40c1`.

Méthode : inspection de l’archive PyTorch et lecture statique des opcodes de `best/data.pkl` avec `pickletools`, sans charger ni exécuter le modèle. Le contrôle CRC des 716 entrées ZIP ne signale aucune erreur. Cela confirme l’intégrité de l’archive, pas la validité de l’inférence.

| Propriété | Valeur enregistrée |
|---|---|
| Type de modèle | `ultralytics.nn.tasks.DetectionModel` |
| Architecture | `yolo26s.yaml`, échelle `s` |
| Version Ultralytics | `8.4.27` |
| Tâche | `detect` |
| Taille d’entraînement | `imgsz=416` |
| Configuration | `end2end=True`, `reg_max=1`, 3 canaux |
| Nombre de classes | 6 |
| Maximum de détections enregistré | 300 |

| Identifiant | Classe du checkpoint | Libellé français proposé |
|---|---|---|
| 0 | `person` | Personne |
| 1 | `vehicle` | Véhicule |
| 2 | `bike_scooter` | Vélo ou trottinette |
| 3 | `pole` | Poteau |
| 4 | `traffic_light` | Feu de signalisation |
| 5 | `traffic_sign` | Panneau de signalisation |

Le dictionnaire `train_metrics` contient précision 0,61526, rappel 0,43043, mAP50 0,47688 et mAP50–95 0,26573. Ce sont des métriques historiques embarquées dont le protocole et le jeu d’évaluation n’ont pas été vérifiés ; aucune nouvelle évaluation n’a été exécutée. Elles ne mesurent pas les performances sur les images des lunettes.

L’exporteur officiel `8.4.27` expose ONNX et conserve la branche end-to-end sauf modification explicite ou restriction du format. Pour une architecture end-to-end, il désactive l’ajout de NMS via `nms=True`. Le premier chemin proposé est ONNX FP32, batch 1, entrée fixe 416 × 416, `end2end=True`, `nms=False`, puis comparaison `.pt`/ONNX avant intégration Android. Il reste à vérifier le chargement réel, l’export, les formes de tenseurs et la latence. [Code de l’exporteur v8.4.27](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/engine/exporter.py).

Les classes `traffic_light` et `traffic_sign` ne distinguent ni couleur du feu ni signification du panneau. Aucun comportement de traversée ne peut être déduit de leurs seuls identifiants.

## Ce qui reste inconnu

- Aucun modèle n’était inclus dans le starter HTC ; le `.pt` est maintenant disponible séparément au chemin ci-dessus. Aucun export Android n’a encore été généré dans cette préparation du prompt.
- Chargement effectif du checkpoint, contrat ONNX exporté, comparaison des deux références de modèle et validation des politiques portées à effectuer. Le Swift utilise explicitement `.scaleFill`, orientation `.right`, confiance 0,70 et NMS IoU 0,50 ; ces choix doivent être adaptés et vérifiés pour la caméra HTC et l’export retenu.
- L’appellation « Eagle 2 » vient de la demande utilisateur ; les fichiers inspectés parlent de VIVE Eagle.
- Le rapport organisateur mentionne des HTC U24 Pro et une connexion réussie lors de leur préparation. Ce rapport ne constitue pas un test effectué ici et ne confirme pas le téléphone actuellement disponible.
- Fonctionnement réel du simulateur, formats réellement reçus, qualité vidéo, latence, température et autonomie à mesurer.
- Synthèse française, son dans les lunettes, coexistence avec le streaming et dépendances éventuelles à Internet à vérifier sur le matériel.
- Compatibilité du certificat de signature du futur APK avec l’APK déjà installé et étendue exacte des critères d’autorisation HTC à confirmer si nécessaire.

## Orientation proposée

L’utilisateur a confirmé disposer d’un téléphone HTC prêté et envisager Kotlin ; des accès de développement iOS sont possibles le 27 septembre, sans confirmation. Les deux tours de revue sont désormais consignés dans le [registre des décisions](</Users/sam/Documents/ChatGPT/Hackathon SILMO/DECISIONS_ARCHITECTURE_ECHONAV_SILMO.md>) ; le [cahier des charges](</Users/sam/Documents/ChatGPT/Hackathon SILMO/CAHIER_DES_CHARGES_ECHONAV_SILMO.md>) précise priorités et recette. La voie initiale est Android/Kotlin, avec option iOS conditionnée à un essai réel caméra/audio.

Utiliser des agents spécialisés pour le développement et la revue contradictoire. Garder la boucle perception → décision → alerte locale, bornée et mesurable. Un système de LLM dialoguant à chaque image n’est pas nécessaire au MVP décrit ; si une fonction agentique embarquée est souhaitée, la spécifier séparément.

Pour les choix ML, ONNX Runtime documente une base CPU et des accélérateurs dont le bénéfice dépend du modèle et du téléphone : commencer par mesurer une base correcte. [Documentation ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/).

Pour l’extraction vidéo, choisir un mode de sortie `MediaCodec` compatible avec l’accès voulu aux images ; ajouter une lecture CPU au chemin actuel de rendu ne doit pas être présumé suffisant. [Référence Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).

LiteRT fournit aussi un runtime Android ; le choix doit tenir compte du modèle disponible et de l’API/version réellement intégrée. [Documentation LiteRT Android](https://developers.google.com/edge/litert/android).

Le prompt maître est dans `PROMPT_ECHONAV_SILMO_MULTI_AGENTS.md` et contient les rôles, le protocole de débat, les étapes d’implémentation et les critères de validation.
