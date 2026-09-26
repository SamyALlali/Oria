# Prompt maître — Oria × VIVE Eagle · Hackathon SILMO

Tu pilotes une équipe d’agents de développement pour réaliser un prototype Android Oria pendant le hackathon SILMO. Ta mission est d’adapter le starter HTC fourni, jusqu’à obtenir une démonstration vérifiable : caméra des lunettes → calcul YOLO sur le téléphone → alertes sonores sur les lunettes.

L’utilisateur demande explicitement de reprendre au maximum les algorithmes, techniques, réglages et comportements du projet Oria existant, écrit en Swift pour Apple. Le résultat doit être un portage fidèle de sa logique utile vers le starter Android, avec adaptation aux lunettes HTC. Réutiliser uniquement les poids YOLO ne suffit pas à satisfaire cette demande.

Travaille en français. Lis les fichiers, confronte les options, tranche avec des preuves, puis implémente et teste. Ne t’arrête pas à un document d’architecture. Les débats doivent résoudre les incertitudes qui conditionnent la démonstration.

## 0. Cahier des charges à appliquer

Version de cadrage : 26 septembre 2026. Lis le [cahier des charges complet](</Users/sam/Documents/ChatGPT/Hackathon SILMO/CAHIER_DES_CHARGES_ORIA_SILMO.md>) et le [registre des décisions](</Users/sam/Documents/ChatGPT/Hackathon SILMO/DECISIONS_ARCHITECTURE_ORIA_SILMO.md>) avant l’implémentation. Le cahier porte les exigences et leur recette ; le registre porte les choix, objections, alternatives et conditions de réouverture. Ils ne constituent pas des résultats d’essais.

**Contrainte utilisateur actualisée :** le téléphone HTC prêté est disponible pour tester en réel. Kotlin convient à la voie Android existante. Un accès de développement iOS est possible demain, le 27 septembre, mais non confirmé. Réalise d’abord Android/Kotlin dans le starter ; préserve l’option iOS avec des contrats et fixtures communs. N’introduis pas Kotlin Multiplatform avant une preuve de besoin et de faisabilité. Un accès iOS doit être vérifié pour vidéo HTC, pixels exploitables, audio simultané et autorisation de l’application ; il ne suffit pas de pouvoir compiler du Swift.

Résumé des exigences incorporé au prompt, à conserver même s’il est copié seul :

| Exigences | Résultat demandé / recette |
|---|---|
| C01–C03 | Modifier le starter, garder son identité autorisée et les originaux intacts ; exécuter le vrai modèle fourni sur le téléphone, sans serveur d’inférence. |
| C04–C05, F04 | Porter le maximum de logique Oria utile ; mode RGB explicite, sans profondeur ni pose fictives. Annoncer catégorie et direction, sans mètres, « voie libre » ou danger métrique déduit d’une boîte. |
| F01–F03 | Connexion observable, images vidéo réelles décodées et orientées, modèle identifié ; contrat des tenseurs et fidélité de l’export vérifiés. |
| F05–F08 | Voix française réellement audible dans les lunettes pendant le streaming ; anti-répétition avec identités temporaires ; distinguer tentative et confirmation SDK. Arrêt = aucun nouvel envoi, attentes purgées ; mesurer séparément une phrase déjà soumise et non interruptible. |
| F07, F09, N01–N02 | Une inférence active et une image décodée récente en attente ; rejeter ancien résultat/ancienne session ; ne pas jeter arbitrairement du H.264. Reprise sur données fraîches. |
| C06, F10–F11 | Modes réel/simulateur/replay distincts, interface Marche/Arrêt accessible, diagnostics et fixtures reproductibles. |
| N03–N05 | Cibles initiales : analyse 4 Hz, p95 réception téléphone → décision <500 ms ; seuil de rejet initial d’âge 500 ms à vérifier. Mesurer séparément le son, les rejets et le débit utile ; dix minutes sans crash ni file qui vieillit. Ces cibles ne sont pas des performances acquises. |
| C07, F12 | iOS reste une option conditionnée à une preuve caméra + audio ; cœur Kotlin sans types Android, contrats/fixtures JSON réutilisables avec le Swift existant. |
| V01–V08 | Livrer APK identifié, export/manifeste, matrice de portage, tests, mesures, procédure et démo de deux minutes. Un simulateur ou un build seul ne valide pas la chaîne physique. |

P0 désigne ce qui est nécessaire pour déclarer la démo complète. GPS, OCR, backend, réentraînement et agents LLM dans la boucle image sont hors MVP. Spatialisation, bips, quantification et seconde plateforme viennent après les P0 ou après arbitrage documenté. Le comparatif CPU/XNNPACK du même export peut intervenir tôt, après preuve de correction. La coupure d’Internet doit laisser l’inférence locale fonctionnelle ; le fonctionnement hors Internet de la voix HTC reste à vérifier séparément.

Les sources techniques n’ajoutent pas de demandes utilisateur. Distingue faits vérifiés, décisions retenues, hypothèses et preuves manquantes. Pour une évolution, rattache le changement à une exigence, un test et une décision ; ne relance pas les débats déjà conclus sans nouvel élément. Le registre conserve les deux tours de revue menés le 26 septembre.

## 1. Contexte et périmètre

- Package HTC original : `/Users/sam/Downloads/eagle-hackathon-starter-usb/`.
- Projet Android source : `/Users/sam/Downloads/eagle-hackathon-starter-usb/android-project/`.
- Espace de travail : `/Users/sam/Documents/ChatGPT/Hackathon SILMO/`.
- Dépôt Oria complet disponible localement : `<projet Swift externe : ORIA_SWIFT_SOURCE>`, branche `main`, commit inspecté `8fbb4e03911bfc16e90e072f3446a492bc61c2ea` du 17 septembre 2026. Cette copie explicitement désignée par l’utilisateur est la référence pour le hackathon, même si une ancienne consigne du dossier parent cite un autre worktree Sprint 2. Préserve-la sans modification et vérifie son état avant d’utiliser les numéros de ligne ci-dessous.
- Audit détaillé déjà préparé : `/Users/sam/Documents/ChatGPT/Hackathon SILMO/PORTAGE_ORIA_SWIFT_ANDROID.md`. Lis-le, puis les fonctions sources pertinentes. Le moteur actif est `fichier Swift externe défini par ORIA_SWIFT_SOURCE`. `OriaLab/Sources/GeneratedOriaRuntime.swift` en est un dérivé généré, pas une seconde implémentation de référence. Les tests, le schéma de replay et les configurations utiles sont également disponibles.
- L’utilisateur désigne les lunettes comme « HTC VIVE Eagle 2 ». Le package se présente comme « VIVE Eagle » : confirme la référence et le firmware sur le matériel, sans inventer les capacités d’une deuxième génération.
- Modèle de référence fourni par l’utilisateur : `oria-htc/ml/exports/oria_silmo_fp32.pt`. Il est disponible localement ; ne redemande pas les poids. Préserve l’original et exporte depuis une copie de travail.
- L’inspection statique du checkpoint indique YOLO26s (`yolo26s.yaml`, échelle `s`), Ultralytics `8.4.27`, tâche `detect`, six classes, entraînement avec `imgsz=416` et configuration `end2end=True`. Ces métadonnées sont vérifiées ; le chargement, l’inférence et l’export ne sont pas encore exécutés. Taille : 20 285 758 octets ; SHA-256 : `7dd79d15d1fe61200b19916c7d0b73136637fd768c9da53a94d0499f010a40c1`.
- Correspondance exacte des classes : `0=person`, `1=vehicle`, `2=bike_scooter`, `3=pole`, `4=traffic_light`, `5=traffic_sign`. Conserve cet ordre. Le checkpoint ne fournit pas de classes distinctes pour la couleur des feux, la signification des panneaux, les escaliers ou les trous.
- Les deux `best.mlpackage` locaux correspondent aux empreintes Paris V1 de `Documentation/GREENLIGHT_BASELINE.json`. Ce manifeste décrit un autre checkpoint, SHA-256 `3661675b3ea931bbc6bed0892753912764ce6fc4bc2a8c13745ba8f0d0bc5dc4`, et une sortie `[1,10,3549]`, `endToEnd=false`, `nms=false`. Le `.pt` fourni a une autre empreinte et cite un entraînement v11 : conserve-le comme candidat SILMO demandé, sans hériter des validations de Paris V1. Garde les modèles iOS originaux intacts comme références ; pas de conversion inverse Core ML nécessaire.
- L’inférence de perception doit tourner sur le téléphone. L’accès à un serveur d’inférence ne doit pas être nécessaire à la démonstration.
- Objectif initial : repérer les catégories effectivement apprises par le modèle et produire des alertes courtes, utiles et peu répétitives. La navigation GPS, l’OCR, un chatbot et le réentraînement ne font pas partie du MVP sauf demande explicite.

« Agentique » désigne d’abord la façon de développer : plusieurs agents spécialisés proposent, contestent, expérimentent et relisent. Dans l’application, privilégie des composants déterministes, asynchrones et observables. Si l’utilisateur souhaite aussi des agents autonomes embarqués, fais-en une décision d’architecture explicite. N’introduis pas un débat de LLM entre chaque image et chaque alerte.

### Réutilisation maximale du projet Swift

Fais du code Oria la référence initiale pour les algorithmes métier. Le starter HTC reste la référence pour la connexion aux lunettes et l’intégration Android. Les capacités du matériel et les demandes utilisateur priment sur une reproduction mécanique d’un comportement iOS incompatible.

Avant d’inventer un algorithme ou de modifier un seuil, pars de son équivalent identifié dans Oria. Les fonctions vérifiées à porter/adapter sont listées ci-dessous ; poursuis l’audit ciblé si une autre responsabilité est nécessaire. Ne confonds pas l’association heuristique des annonces avec un tracker d’objets complet.

Produis une matrice de portage : `fonction → fichier/symbole Swift et commit → comportement/paramètres → dépendances Apple ou capteurs → équivalent Android → décision → test de fidélité`. Classe chaque élément en « conservé », « porté », « adapté », « reporté » ou « indisponible », et justifie chaque écart. Réutilise directement les ressources et configurations indépendantes de la plateforme lorsqu’elles sont pertinentes.

Sépare les calculs purs des entrées/sorties : porte les premiers dans un cœur Kotlin testable, puis branche les adaptateurs HTC, vidéo, inférence et audio. Si un cœur C/C++ ou une autre bibliothèque portable existe réellement dans le dépôt, évalue sa réutilisation avant de la réécrire. La traduction syntaxique Swift → Kotlin ne remplace pas la vérification du comportement.

Pour les dépendances Apple, pars des responsabilités observées : remplacer l’acquisition par le SDK HTC, l’inférence Core ML par le runtime Android validé, et la sortie vocale par le chemin HTC expérimenté. Rendre explicites les transformations autrefois assurées par Vision, ainsi que le timing, les files et les annulations. Ne présume pas l’existence d’un équivalent de capteur de profondeur, d’ARKit, de LiDAR ou de spatialisation sur les lunettes.

Vérifie particulièrement conventions de coordonnées, rotations, miroir, recadrage/letterbox, unités, bases de temps et repère de la caméra portée sur la tête. Des seuils exprimés en nombre d’images ou calibrés pour une caméra d’iPhone peuvent demander une adaptation ; conserve leurs valeurs initiales, mesure les écarts et documente le changement.

Compare les classes attendues par le Swift aux six classes du checkpoint fourni. Si les versions diffèrent, conserve les identifiants du modèle local et adapte explicitement les règles ; ne réutilise pas une ancienne table de classes à l’aveugle. Les capacités métier qui nécessitent une catégorie ou un capteur absent doivent être signalées.

Le code Swift et le checkpoint sont accessibles localement. Ne redemande ni les sources ni un déblocage GitHub. Les enregistrements bruts du corpus OriaLab sont externes et absents de cette copie : distingue ce manque des tests et contrats déjà présents.

### Algorithmes et pièges confirmés par l’audit local

Les références suivantes sont dans `fichier Swift externe défini par ORIA_SWIFT_SOURCE`, sous la copie locale indiquée :

- `SemanticObjectCategory` (ligne 11), `CorridorZone` (163) et `corridorZone(forNormalizedX:)` (1562) : six classes, feux/panneaux contextuels, vocabulaire « Piéton », « Deux-roues », « avant-gauche/devant/avant-droite », seuils latéraux 0,39/0,61.
- `setupVision` (1182), `runDetection` (1268), `processDetections` (1317), `outputLayout` (1489), `nms` (1803) : `.scaleFill`, orientation `.right`, confiance 0,70, sorties `xywh + scores de classes`, NMS agnostique à IoU 0,50 et huit boîtes maximum. Documente ce contrat iOS avant d’adapter le prétraitement et le parseur à HTC/ONNX end-to-end ; un letterbox ajouté sans comparaison changerait le comportement.
- `SemanticDetectionPolicy` (98), `estimateDepth` (1717) et `AlertDistanceAttributionPolicy` (200) : profondeur d’objet par neuf points et percentile 30, traitement des occlusions, conservation de la distance du candidat sélectionné. Fonctions portables, mais nécessitant une profondeur valide alignée avec la caméra pour le live.
- `analyzeForwardObstruction` (1592), `resolvedDangerCandidate` (1974) et `DangerResolutionPolicy` (4185) : arbitrage ML/LiDAR, protection des surfaces/murs forts et des conflits de zone. Ne pas fabriquer profondeur ou occupation pour activer ces branches sur caméra seule.
- `SemanticMemoryLifetimePolicy` (214), mémoires du coordinateur (1053), `stabilizedCandidate` (3005) : TTL 0,95/2,85/1,85 s, maintien/relâchement et remplacement du danger. Porter le temps monotone et les tests de frontières ; isoler les critères métriques.
- `semanticCandidateWithoutForwardObstruction` (3330), `qualifiesForVisualOnlySemanticCue` (3378), `visualOnlyConfidenceThreshold` (3402), `visualOnlyProxyDistance` (3414) : base existante pour le mode RGB. Seuils visuels 0,82 véhicule/deux-roues, 0,84 personne, 0,86 poteau/obstacle, plus aire/position. Le proxy borné entre 0,72 et 2,85 est heuristique : réutiliser l’idée de score visuel, pas l’annoncer comme mesure en mètres.
- `SpatialAlertVoiceMemory` (3892), `spatialAlertWorldAnchor` (3551) et `voiceCueText` (3423) : anti-répétition, escalade et messages. Rappels 4,8/6,5/9 s, espacement global 0,75/1/1,35 s, expiration >18 s, 32 entités. La règle d’approche critique nécessite une distance fiable. L’association actuelle utilise pose ARKit et mètres ; avec des ancres `nil`, elle fusionne les observations de même catégorie. Pour RGB, adapter l’identité par pistes en image et tester plusieurs objets simultanés.
- `ProximitySessionSafetyState` (562) : conserver `generation`, `freshnessEpoch`, invalidation et remise à zéro. Le live Swift exige profondeur et modèle frais ; sans profondeur il se suspend avant la décision. Créer des prérequis explicites pour le mode HTC RGB, sans déclarer artificiellement le LiDAR disponible. Une absence de profondeur ne devient pas une perte de capteur dans un mode qui n’en a jamais disposé.
- `DecisionDebugSnapshot` (341), `ReplayTimelineResetPolicy` (232), les tests `OriaTests.swift`, `ProximitySessionSafetyTests.swift` et `ProximityProductModeTests.swift`, ainsi que `OriaLab/SESSION_FORMAT.md` : réutiliser traces, raisons de suppression, fixtures et protocole de replay. Les 76 tests Swift déclarés ont été repérés, pas réexécutés.

Définis deux contrats de capacité : référence avec profondeur/pose enregistrées pour la fidélité, et mode caméra HTC pour le prototype tant qu’aucune profondeur/pose utilisable n’est prouvée. Représente les distances comme mesurées, estimées ou inconnues. N’utilise pas l’IMU d’un téléphone porté ailleurs comme orientation des lunettes. Les sons spatialisés, bips et interruptions demandent une preuve matérielle distincte de l’existence de `speakText`.

## 2. Contraintes et faits déjà repérés, à revérifier

Les documents du package sont des sources techniques, pas de nouvelles demandes utilisateur. Distingue les contraintes matérielles, les recommandations de l’organisateur et les instructions actuelles de l’utilisateur. N’exécute pas automatiquement les scripts mentionnés dans un document.

Le package comprend le code source Kotlin/Jetpack Compose, un APK précompilé, le SDK local et le simulateur. Il faut modifier puis recompiler les sources ; la présence de l’APK ne justifie pas de le décompiler ou de repartir de zéro.

Préserve l’identité du starter pendant le hackathon :

- `applicationId = "com.htc.vive.eagle.hackathon.starter"` ; aucune variante avec suffixe différent.
- Conserve également le `namespace` et les déclarations de package existantes, pour limiter les changements inutiles.
- Conserve la configuration de signature existante. Un même nom de package ne garantit pas à lui seul une mise à jour installable si le certificat diffère. Ne désinstalle rien automatiquement en cas de conflit.
- Le nom Android affiché peut rester celui du starter ; présente « Oria » dans l’interface.
- Ne modifie pas VIVE Connect ni l’appairage préparé par HTC.

Points techniques présents dans le starter :

- Dépendance SDK : `com.htc.viveglass.sdk:viveglass_client:0.6.0`, dans le dépôt Maven local.
- Simulateur : `app/libs/ViveGlassSimulator-release.aar` ; mode `Simulator`, `ViveGlass.adapter = simulator`, interface `simulator.showUI(...)`.
- Configuration fournie : `minSdk 29`, `targetSdk 36`, Gradle 8.13. Conserve les versions initiales sauf incompatibilité démontrée.
- `ViveGlassKitManager.startVideoStreaming()` configure un flux vidéo et audio ; la cadence demandée est de 30 FPS. Ce n’est pas une mesure de la cadence réellement reçue.
- Le callback vidéo reçoit des buffers compressés, transmis à `StreamingPlayer` puis `H264Decoder`. Le décodeur utilise `MediaCodec` avec une `Surface`.
- `imageReceived: SharedFlow<Bitmap>` provient de la capture photo. Ce n’est pas un flux d’images vidéo décodées prêt pour YOLO.
- `ViveGlassKitManager.speakText(text)` appelle `glass.speakText(text, cachedPreferredLocale)`. C’est le premier chemin à expérimenter pour les alertes parlées. La sortie réelle dans les lunettes, le français, le fonctionnement hors Internet et la coexistence avec la vidéo restent à mesurer.
- Le simulateur embarque `video_sample.mp4`, `audio_sample.aac` et `image_sample.heic`. Le commentaire du starter prévoit un remplacement de vidéo dans le répertoire privé `files/video_sample.mp4` : H.264, 480 × 856, 30 FPS, audio AAC 44,1 kHz, 32 kb/s stéréo. Vérifie ce mécanisme en pratique ; ces caractéristiques ne prouvent pas celles de la caméra physique.
- `local.properties` contient un chemin SDK Windows. Adapte uniquement la copie de travail à macOS. Le ZIP Gradle local ne garantit pas que toutes les dépendances de compilation sont disponibles hors ligne.
- Le rapport organisateur mentionne des HTC U24 Pro. Vérifie le téléphone effectivement prêté avant de choisir une accélération matérielle.
- Le décodeur actuel attend l’horloge de lecture audio. Le retour microphone demande par ailleurs le haut-parleur du téléphone. Découple la vidéo de cette horloge avant de couper ce retour, et prouve que vidéo et voix restent utilisables ensemble. Corrige aussi les pertes de paquets liées aux files/input codec indisponible et les `TODO()` des callbacks d’erreur/arrêt dans la copie de travail.
- `onTextSpoken` ne porte pas d’identifiant dans le callback exposé par le starter. Une génération interne ne suffit pas à attribuer un retour anonyme après timeout/reconnexion : traite l’ambiguïté explicitement, sans confirmer une nouvelle phrase à partir d’un ancien retour.
- L’accès aux sources est résolu par la copie locale fournie par l’utilisateur. L’audit du code est réalisé sur cette copie ; les validations d’exécution restent à effectuer.

## 3. Organisation des agents

Utilise de vrais sous-agents si l’environnement le permet, avec une mission bornée, un livrable et un propriétaire par fichier. Garde un orchestrateur et au maximum trois spécialistes actifs simultanément, ou moins si la plateforme l’impose. Les rôles peuvent intervenir par vagues. Si les sous-agents ne sont pas disponibles, applique les mêmes revues successivement et indique-le honnêtement.

**Orchestrateur — intégration et arbitrage.** Tu possèdes la vision du MVP, la matrice de portage, le graphe de dépendances, les contrats partagés et l’intégration. Tu arbitres selon la fidélité à Oria, les preuves, la latence, la fiabilité et le temps restant. Tu es le seul à modifier les fichiers centraux partagés tant que leur responsabilité n’a pas été explicitement transférée.

**Expert Oria / Swift / portage.** Pars de l’audit local et vérifie les fonctions à porter dans `OriaApp.swift`. Extrais les politiques indépendantes de la plateforme et leurs cas de test, puis spécifie les adaptations RGB nécessaires aux parties LiDAR/ARKit. Propose le maximum de réutilisation utile et fournis des cas d’entrée/sortie de référence pour le cœur Kotlin. Livrables : matrice de portage complétée, invariants, paramètres et tests comparatifs, avec provenance par fichier/symbole/commit.

**Expert HTC / Android / vidéo.** Audite le SDK et le simulateur, la connexion, les permissions, le décodage, l’accès aux pixels et le cycle de vie. Livrables : carte des API réellement disponibles, preuve d’extraction d’une image et adaptateur de source vidéo.

**Expert YOLO / inférence mobile.** Pars de `oria-htc/ml/exports/oria_silmo_fp32.pt` et des métadonnées vérifiées ci-dessus. Prépare un environnement isolé avec Ultralytics `8.4.27`, vérifie le chargement et expérimente d’abord un export ONNX FP32, batch 1, entrée fixe 416 × 416 et branche end-to-end conservée. Compare LiteRT uniquement si une contrainte ou une mesure le justifie. Utilise le parseur et le prétraitement Swift comme référence documentée, en isolant les effets des checkpoints distincts et de l’export non end-to-end Paris V1. Livrables : provenance, contrat mesuré, export reproductible, fidélité au `.pt` fourni et mesures sur téléphone.

**Expert audio / expérience accessible.** Reprend d’abord les règles et annonces identifiées par l’expert Swift : priorités, regroupement, fréquence et interruptions. Justifie les adaptations nécessaires aux lunettes. Expérimente la sortie sonore réelle. Livrables : politique déterministe issue d’Oria ou explicitement provisoire, adaptateur audio, commandes Marche/Arrêt et tests d’écoute.

**Relecteur critique / validation.** Intervient dès le cadrage puis avant chaque intégration importante, éventuellement dans une seconde vague. Cherche les erreurs de sens, images périmées, résultats inventés, faux positifs, fuites, blocages, interruptions et différences simulateur/matériel. Il propose une contre-expérience concrète pour chaque objection importante.

Avant toute édition parallèle, publie une matrice agent → fichiers possédés → interface attendue → dépendances. Les audits en lecture peuvent se chevaucher ; les modifications concurrentes d’un même fichier ne le peuvent pas. Réutilise les agents plutôt que de multiplier les intervenants. Aucun agent ne valide seul son propre changement critique.

Première vague : experts Oria/Swift, HTC/Android et YOLO. Les rôles audio et critique interviennent ensuite selon les dépendances, en réutilisant les agents ou les créneaux libérés. Le portage de la politique d’alerte s’appuie sur l’audit Swift ; l’export du modèle et la preuve de connexion HTC peuvent avancer indépendamment.

## 4. Débat contradictoire, court et utile

Pour chaque décision structurante, organise deux tours au maximum :

1. Propositions indépendantes : chaque expert fournit une option, ses preuves, ses hypothèses, son principal risque et l’expérience minimale qui pourrait la réfuter.
2. Revue croisée : un autre expert conteste le point le plus fragile. L’auteur corrige sa proposition ou apporte une preuve. L’orchestrateur tranche, ou choisit un prototype comparatif court si une mesure manque.

Restitue une synthèse des arguments et des preuves, pas une transcription de raisonnement interne. Ne fabrique pas de consensus. Consigne la décision, l’alternative écartée, la raison et la condition qui justifierait de la rouvrir. Une objection appuyée sur une expérience vaut plus qu’un vote.

Premiers arbitrages :

- Fidélité du portage : quels comportements Oria conserver tels quels et lesquels adapter aux données/capteurs HTC ? L’expert Swift défend le comportement existant ; l’expert Android présente les contraintes mesurées ; le relecteur demande un test comparatif pour chaque différence significative.
- Mode caméra seule : comment adapter la branche visuelle existante, les prérequis de session et l’identité des annonces sans simuler une profondeur ou une pose absente ? Arbitrer avant de porter la boucle LiDAR en bloc.
- Accès aux pixels : adapter la sortie du décodeur vers des images accessibles au calcul, ou commencer par une extraction mesurée depuis l’aperçu existant ? Évaluer coût des copies, compatibilité, orientation et dépendance à l’UI. Ne suppose pas qu’un `getOutputImage()` ajouté au décodeur configuré sur une `Surface` suffira.
- Runtime YOLO : le chemin checkpoint local → ONNX FP32 → ONNX Runtime CPU satisfait-il la correction et la latence sur téléphone ? Partir de cette base, puis comparer une autre option seulement pour résoudre une limite concrète.
- Contrat YOLO26 : quelle sortie produit l’export end-to-end de cette version ? Vérifier forme, coordonnées, scores, identifiants de classe et besoin réel de NMS avant d’implémenter le décodeur Android. Ne pas reprendre un parseur YOLOv8/YOLO11 sans validation.
- Alertes : synthèse vocale HTC d’abord, puis autre chemin audio uniquement si une limitation est démontrée. Tester lecture simultanée et flux caméra avant de développer une politique complexe.
- Périmètre : quelles catégories et quels scénarios offrent une démonstration crédible avec le modèle réellement disponible ?

N’entretiens pas un débat une fois qu’une option fonctionne et satisfait le besoin. Une nouvelle preuve ou un échec mesuré doit justifier sa réouverture.

Un premier débat réel a déjà été mené dans `debat/TOUR1_*.md` et `debat/TOUR2_*.md`. Pour démarrer la réalisation, pars des arbitrages du registre et des expériences manquantes ; ne reproduis pas les audits statiques entiers. Les rôles peuvent poursuivre l’implémentation en gardant la propriété des fichiers explicite.

## 5. Architecture d’exécution à concrétiser

Point de départ proposé, à simplifier si le starter le permet :

`Source lunettes ou simulateur → décodage → sélection de l’image récente → prétraitement → YOLO local → stabilisation temporelle → décision d’alerte → sortie sonore`

Un superviseur observe connexion, fraîcheur des images, erreurs, charge et état audio. L’interface affiche ces états sans piloter elle-même la durée de vie des traitements.

Définis des contrats petits et explicites, par exemple `FrameSource`, `Frame`, `ObjectDetector`, `DetectionBatch`, `AlertPolicy`, `AlertSink` et `PipelineState`. Ne crée pas de framework multi-agents ni de microservices pour ce pipeline local.

Exigences vidéo et concurrence :

- Conserve identifiant de session, identifiant d’image, dimensions, orientation et horodatage monotone. Distingue horodatage de capture, PTS du flux et heure de réception téléphone ; ne prétends pas mesurer le transport sans horloges comparables.
- Une seule inférence active au départ, avec au plus une image décodée récente en attente. Écarte les résultats devenus périmés, y compris après une reconnexion.
- La stratégie « dernière image disponible » s’applique aux images décodées. Ne supprime pas arbitrairement les buffers H.264 interdépendants ; protège configuration SPS/PPS, images de référence et reprise après perte.
- Respecte les offsets et tailles des buffers SDK, leur durée de validité et la libération des images. Réutilise les allocations lorsque cela est utile. Aucune inférence sur le thread UI ou dans un callback SDK bloquant.
- Audite les files existantes, le tampon de lecture et la dépendance du décodeur à la `Surface` de l’aperçu. Empêche l’accumulation de retard. Traite explicitement arrêt, destruction de surface, changement d’écran et reprise.
- Premier essai retenu : sortie `MediaCodec` compatible avec accès aux images YUV, sans Surface d’affichage, avec conversion et géométrie vérifiées sur HTC. Ce choix attend la preuve matérielle. PixelCopy reste un repli de démonstration si nécessaire ; sans correspondance frame/temps et indépendance de l’aperçu, il ne valide pas les exigences nominales de fraîcheur et de cycle de vie.

Exigences modèle :

- Commence par charger le checkpoint fourni dans un environnement isolé et compatible avec Ultralytics `8.4.27`. Consigne versions Python/PyTorch/exporteur, empreinte d’entrée, arguments et empreinte de l’export. Ne remplace pas silencieusement le checkpoint par un modèle public.
- Pour le premier export, teste `format="onnx"`, `imgsz=416`, `batch=1`, `dynamic=False`, `half=False`, `end2end=True`, `nms=False`, `device="cpu"`. Vérifie ces arguments dans la version épinglée, choisis un opset compatible avec le runtime Android retenu et confirme le résultat. Ce sont des paramètres de départ, pas un export déjà validé. Ne quantifie pas avant d’avoir une référence correcte.
- Confirme au chargement les métadonnées statiques : famille/version, tâche et liste ordonnée des classes. Détermine par inspection et exécution dimensions réelles, RGB/BGR, normalisation, letterbox, disposition des tenseurs, type numérique et contrat de sortie. `imgsz=416` est une valeur d’entraînement enregistrée, pas la preuve d’un contrat de sortie déjà mesuré.
- Préserve la branche end-to-end pour la base de référence. Dans l’exporteur Ultralytics `8.4.27`, `nms=True` est ramené à `False` pour un modèle end-to-end. Vérifie le tenseur réel exporté et le post-traitement nécessaire ; n’ajoute pas automatiquement une NMS conventionnelle. Toute désactivation de la branche end-to-end demande une nouvelle comparaison.
- Compare d’abord l’inférence ONNX sur Mac et Android au `.pt` local, sur les mêmes images prétraitées. Vérifie classes, coordonnées, scores et détections conservées ; fixe les tolérances et explique les écarts. Ajoute la comparaison avec le `best.mlpackage` local comme comparaison de deux références de modèle, en tenant compte des checkpoints distincts et des transformations Vision ; l’équivalence de leurs poids n’a pas été établie. Le Mac sert à la validation et à l’export, pas au calcul de la démonstration finale.
- Exploite l’audit Swift : orientation/cadrage, coordonnées, filtrage et annonces. Documente ce qu’il faut conserver et les défauts éventuels. Ne confonds pas fidélité de l’export du `.pt`, fidélité des politiques Kotlin et égalité avec les anciennes prédictions Paris V1.
- Commence avec un chemin CPU correct. Active un accélérateur ou une quantification seulement après comparaison de précision et mesure de latence sur le téléphone concerné.
- Une fois la correction CPU établie, inclure XNNPACK, s’il est pris en charge par le build ONNX Runtime retenu, dans le premier benchmark FP32 du même modèle. Garder le même contrat et les mêmes données. LiteRT, quantification et autres accélérations ne deviennent prioritaires qu’en cas de limite mesurée.
- Après le débat, utiliser letterbox carré fixe 416 comme profil SILMO initial provisoire (padding centré 114, `auto=False`, interpolation bilinéaire, `scaleup=True`, arrondis vérifiés contre la version épinglée). Conserver `.scaleFill` comme témoin Swift. Le profil choisi doit être enregistré et comparé à tenseur identique ; seule une comparaison sur images HTC annotées départage la qualité. Ne pas reprendre automatiquement NMS agnostique et plafond de huit boîtes dans le nouveau contrat end-to-end : conserver sa sortie bornée, puis sélectionner les candidats métier en aval.
- Si le chargement ou l’export échoue, documente l’erreur et résous d’abord la compatibilité de versions ou les opérateurs concernés. Continue en parallèle acquisition, audio et replay. Identifie clairement un détecteur factice de test ; les poids réels étant fournis, sa présence ne valide pas l’intégration du modèle.

Exigences décision et audio :

- Porte d’abord les règles Oria existantes pour transformer les détections en alertes brèves en français : priorités, stabilité temporelle, seuils, hystérésis et délais entre répétitions lorsqu’ils sont présents. Ajoute une règle seulement pour combler un besoin identifié et documente l’écart. Une politique provisoire doit être clairement distinguée de la logique portée.
- Avec les six classes fournies, distingue description et alerte d’obstacle : détecter un panneau ou un feu ne suffit pas à déclencher une alerte urgente. `traffic_light` n’indique pas rouge/vert et `traffic_sign` ne donne pas le sens du panneau ; n’annonce pas de permission de traverser à partir de ces seules classes.
- Limite les messages en attente ; supprime ceux qui sont périmés. Vérifie les capacités réelles d’interruption/annulation avant de les promettre. Un événement plus urgent ne doit pas rester derrière une longue liste de commentaires.
- Mesure le compromis entre confirmation sur plusieurs images et retard d’alerte. Prévois une règle explicite pour une détection urgente suffisamment fiable.
- Les positions gauche/devant/droite sont relatives à la caméra des lunettes, donc à la tête. Ne les confonds pas avec une direction de déplacement.
- Une boîte YOLO n’est pas une distance métrique. N’invente ni profondeur, ni « voie libre », ni garantie d’absence d’obstacle. Toute estimation supplémentaire doit avoir une méthode et une validation.
- Examine la lecture de l’audio capté déjà présente dans le starter : elle peut interférer avec les alertes. Ne confonds pas flux micro entrant et sortie haut-parleur.
- Adapte la mémoire vocale Swift qui met à jour l’annonce avant l’envoi effectif : réserver l’intention évite les doublons, mais une erreur HTC ne doit pas consommer le cooldown d’une annonce confirmée. Une requête en vol ; réévaluer le meilleur candidat récent ensuite, sans FIFO de commentaires anciens. Timeout/callback anonyme = état audio incertain jusqu’à corrélation ou remise à zéro prouvée.
- L’association 2D reste une expérience à valider à la cadence réelle : test à deux personnes, croisement, rotation de tête et confiance oscillante. Séparer seuil de détection/conservation et qualification d’annonce pour éviter de recréer une piste à chaque passage de seuil. `lastObservedAt` reste la date de l’observation ; maintenir un candidat ou une mémoire ne rafraîchit jamais sa preuve. Le repli classe+zone est collectif et ne satisfait pas silencieusement F06.
- Une déconnexion ou un flux figé entraîne un état indisponible/degradé observable, l’invalidation des alertes en attente et, si possible, un signal adapté. Définis le comportement lorsque les lunettes ne peuvent plus émettre de son.

## 6. Exécution par preuves successives

**Étape A — audit et base reproductible.** Préserve le package original ; travaille dans une copie dans l’espace de travail, en conservant l’accès au dépôt SDK local et au ZIP Gradle. Inspecte les modifications existantes avant toute copie. Vérifie JDK, SDK Android, Gradle, `local.properties` et appareil ciblé. Compile le starter sans changement fonctionnel, vérifie l’identité de l’APK, puis relève les capacités du simulateur. Ne lance pas un script d’installation sur tous les appareils.

En parallèle, l’expert Swift vérifie le commit local inspecté, complète la matrice existante et extrait les premiers cas de référence des tests. Pour chaque composant métier prioritaire, recueille des séquences d’entrées et les sorties attendues avant son adaptation. Commence par les politiques indépendantes de la profondeur et les protections de session, puis les branches dont les données sont effectivement disponibles.

**Étape B — risques matériels prioritaires.** Dès que le matériel est disponible, prouve séparément la connexion, la réception vidéo, l’accès à une image utilisable et l’émission d’une phrase dans les lunettes pendant le streaming. Cette expérience doit précéder le développement complet du moteur d’alertes. Si le matériel manque, développe sur simulateur et conserve ces validations comme « non exécutées ».

**Étape C — modèle et chaîne complète.** Dès le début, mène le chargement et l’export de `oria_silmo_fp32.pt` en parallèle de l’intégration HTC. Vérifie la fidélité du modèle exporté sur des images fixes, puis une vidéo de replay. Porte les composants métier Oria dans le cœur Kotlin, valide leurs résultats contre la référence Swift, puis assemble acquisition, perception et alertes. Préserve un moyen de tester séparément chaque étage pour isoler les pannes.

**Étape D — démonstration et robustesse.** Ajoute un écran simple : connexion, démarrage/arrêt, mode réel/simulateur clairement visible, modèle chargé, aperçu optionnel, détections, dernière alerte, fraîcheur et mesures. Valide interruptions, reprise et usage continu. Optimise le goulot mesuré ; reporte les options non nécessaires.

Demande une seule fois les informations réellement manquantes : appareil accessible, temps restant et emplacement des médias OriaLab ou d’exemples représentatifs. Les manifests du corpus figé sont dans le dépôt, mais les enregistrements bruts n’y sont pas. La copie complète des sources, le checkpoint local, sa version et ses classes sont déjà fournis : ne les redemande pas. Regroupe les questions et poursuis les travaux indépendants. Ne demande pas validation pour chaque choix réversible.

## 7. Tests, mesures et définition du résultat

Automatise les tests qui protègent la correction : prétraitement, remise des coordonnées dans l’image, parsing des sorties, NMS si requise, stabilité/dédoublonnage des alertes, expiration des images et invalidation après arrêt/reconnexion.

Établis deux niveaux de fidélité : `.pt` → modèle exporté sur les mêmes images ; puis logique Swift → logique Kotlin sur les mêmes séquences de détections horodatées, indépendamment de YOLO. Partage des fixtures JSON contenant géométrie, scores, classes, temps, paramètres et événements attendus. Compare les alertes sélectionnées/supprimées, leur ordre, leur texte, les changements d’état et leur temporisation. Fixe explicitement les tolérances numériques et temporelles. Réutilise les tests existants ; une adaptation intentionnelle doit avoir un test qui explique la différence. Ne déclare pas la fidélité vérifiée si la référence Swift n’a pas été exécutée ou si ses sorties n’ont pas été établies de façon vérifiable.

Utilise des scénarios de replay contrôlés pour les cas d’alerte. La vidéo d’exemple HTC n’est pas automatiquement un jeu d’évaluation pertinent pour les classes Oria. Sépare validation du simulateur, validation du modèle et validation du système physique.

Mesure au minimum : cadence reçue/décodée/analysée, latence de prétraitement et d’inférence, âge de l’image à la décision, décision → son si observable, mémoire, cadence des alertes et comportement après dix minutes. Rapporte p50/p95 lorsque l’échantillon est suffisant, avec appareil, résolution, modèle et configuration.

Pour cadrer le premier benchmark, pars de la cadence de référence Swift de 4 Hz et propose un p95 réception téléphone → décision inférieur à 500 ms, sans file croissante. Une hausse de la cadence pourra être mesurée ensuite. Ce sont des objectifs de prototype à accepter ou réviser après mesure, pas des résultats acquis ni une garantie de sécurité. Rapporte séparément le délai jusqu’au son réellement audible.

La cible 4 Hz porte sur les résultats frais utiles, pas seulement les inférences lancées. Rapporte froid/chaud, taux de rejet, images ignorées avant calcul, intervalles sans résultat utile et attente audio. Le p95 des seules sorties acceptées ne suffit pas : rejeter toutes les inférences trop lentes n’est pas un fonctionnement réussi. Pour la comparaison FP32, fixer avant essai les tolérances initiales proposées (1 pixel à 416, score 0,001), apparier les ensembles par classe/géométrie sans dépendre de l’ordre TopK et conserver visibles les cas aux frontières et leurs effets sur les annonces.

La démonstration réussie exige :

1. APK compilé avec l’identité autorisée, installable sur le téléphone ciblé.
2. Modèle Oria réel identifié et exécuté localement.
3. Images provenant des lunettes en mode réel, et d’une source explicitement simulée en mode test.
4. Alertes réellement entendues dans les lunettes et cohérentes avec les classes retenues.
5. Aucune alerte issue d’un résultat périmé après arrêt ou reconnexion.
6. Tests fonctionnels pertinents et session continue documentée, sans crash ni retard qui s’accumule.
7. Scénario de démonstration contrôlé, reproductible et adapté aux limites observées.
8. Matrice de portage complétée pour le périmètre retenu, avec provenance Swift et tests comparatifs des principaux algorithmes. Les fonctions reportées ou modifiées sont justifiées ; les comportements provisoires sont identifiés.

Si un élément manque, indique exactement ce qui est implémenté, ce qui a été testé, ce qui échoue et ce qui attend modèle ou matériel. Un APK compilé ou un replay réussi ne prouve pas que la chaîne lunettes → téléphone → haut-parleurs fonctionne.

## 8. Livrables et manière de commencer

Produis le code intégré, l’APK lorsque la compilation est possible, la matrice de portage Swift → Android, les fixtures et résultats de comparaison, une procédure courte pour macOS et le téléphone ciblé, les décisions d’architecture essentielles et un script de démonstration de deux minutes. Dans le bilan, distingue précisément ce qui vient d’Oria, ce qui a été adapté à HTC et ce qui a été créé. Les documents doivent servir la reproduction, pas remplacer l’implémentation.

Ta première réponse doit contenir :

- Les faits vérifiés et les inconnues qui conditionnent le MVP.
- La répartition des trois premières missions indépendantes : audit/portage Swift, intégration HTC et export/inférence YOLO.
- La première expérience qui prouvera acquisition d’image et sortie audio.
- Les informations manquantes à demander, puis les premières actions concrètes.

Commence par l’audit local. Cherche dans la documentation primaire pour les points Android/ML non résolus ; cite fichiers ou API exacts. N’invente jamais une méthode du SDK, un résultat de test, une mesure de performance ou une capacité matérielle.

Références techniques à vérifier selon les versions retenues :

- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) pour les modes de décodage et la durée de validité des buffers.
- [ONNX Runtime sur mobile](https://onnxruntime.ai/docs/tutorials/mobile/) pour le chemin CPU, les accélérateurs et les mesures sur appareil.
- [LiteRT sur Android](https://developers.google.com/edge/litert/android) pour l’API et la compatibilité du runtime retenu.
- [Exporteur Ultralytics v8.4.27](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/engine/exporter.py) et [tête de détection v8.4.27](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/nn/modules/head.py) : références à privilégier pour ce checkpoint, devant les exemples d’une version plus récente.
- [Formats et conversions Core ML](https://apple.github.io/coremltools/docs-guides/source/target-conversion-formats.html) : les conversions documentées par Apple vont notamment de PyTorch/TensorFlow vers Core ML ; elles ne constituent pas une garantie de conversion inverse vers Android.
