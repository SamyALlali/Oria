# EchoNav Swift → Android HTC : audit de portage

Source fournie par l’utilisateur : `/Users/sam/Documents/ChatGPT/echonav/EchoNav_main`, branche `main`, commit `8fbb4e03911bfc16e90e072f3446a492bc61c2ea` du 17 septembre 2026. Copie propre à l’inspection, laissée inchangée. Le moteur a été lu et les empreintes des modèles comparées ; aucun build, export ML ou replay n’a été exécuté dans cet audit.

Les choix de réalisation ont ensuite été confrontés par trois agents en deux tours : voir le [registre des décisions](</Users/sam/Documents/ChatGPT/Hackathon SILMO/DECISIONS_ARCHITECTURE_ECHONAV_SILMO.md>) et le [cahier des charges](</Users/sam/Documents/ChatGPT/Hackathon SILMO/CAHIER_DES_CHARGES_ECHONAV_SILMO.md>). Cette matrice reste la provenance Swift ; les propositions ci-dessous sont précisées par ces arbitrages, notamment letterbox SILMO provisoire, identité temporaire et confirmation audio.

La consigne persistante du dossier parent cite une autre copie Sprint 2. La demande actuelle désigne explicitement `EchoNav_main` : c’est cette copie qui sert ici de référence. Ses documents Sprint 2 maintiennent le moteur Sprint 1. Les documents anciens non maintenus ne sont pas utilisés pour décrire le comportement courant.

## Ce qui est réellement exécuté

L’entrée `EchoNavApp` ouvre `FrontendIntegrationRootView`, qui conduit à `ProximityRootView`. Le moteur principal est dans [EchoNavApp.swift](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:676), environ 4 956 lignes. `ContentView.swift` n’est pas le cœur de perception.

`EchoTest/Sources/GeneratedEchoNavRuntime.swift` est un dérivé généré par `EchoTest/tools/generate_echonav_runtime.py`, avec un adaptateur de replay. Ne pas en faire une seconde source de vérité indépendante.

La chaîne iOS est : image ARKit → Vision/Core ML → attribution de profondeur aux boîtes → candidats sémantiques et obstacles LiDAR → arbitrage → stabilisation temporelle → son/haptique → mémoire d’annonces vocales. La mémoire vocale ne supprime pas la décision de danger ni les autres retours.

## Écart entre les deux modèles

| Élément | Dépôt iOS | Fichier fourni pour SILMO |
|---|---|---|
| Référence | Paris V1, `best.mlpackage` | `echonav_current_best.pt` |
| SHA-256 du checkpoint | `3661675b3ea931bbc6bed0892753912764ce6fc4bc2a8c13745ba8f0d0bc5dc4` selon le manifeste | `7dd79d15d1fe61200b19916c7d0b73136637fd768c9da53a94d0499f010a40c1`, mesuré |
| Famille | YOLO26s, six classes, 416 × 416 | YOLO26s, mêmes six classes, `imgsz=416` |
| Contrat d’export | `[1, 10, 3549]`, `endToEnd=false`, `nms=false`, documenté | Pas encore exporté ; configuration du checkpoint `end2end=True` |
| Post-traitement | NMS Swift à IoU 0,50 puis au plus huit boîtes | À définir d’après l’export réellement produit |

Les trois fichiers du package Core ML, dans **les deux** applications, correspondent exactement aux empreintes de `Documentation/GREENLIGHT_BASELINE.json`. Le checkpoint donné pour SILMO a une autre empreinte ; ses métadonnées d’entraînement citent un dataset `v11_actionable_pole_clean_6c`, tandis que la carte Paris V1 cite un autre entraînement.

Décision proposée : conserver le `.pt` explicitement fourni par l’utilisateur comme candidat SILMO et garder Paris V1 comme référence de comparaison. Ne pas modifier les artefacts iOS figés, ni attribuer au nouveau fichier leurs résultats historiques. Une différence de prédiction n’est pas forcément un défaut du portage si les poids ou la branche de sortie changent.

## Matrice de réutilisation

Les décisions ci-dessous sont des propositions de portage issues du code, pas des composants Android déjà implémentés.

| Composant vérifié | Source Swift | Réutilisation proposée | Limite ou test déterminant |
|---|---|---|---|
| Catégories et vocabulaire | [SemanticObjectCategory](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:11), [CorridorZone](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:163) | Porter catégories, priorités initiales et libellés | Feux/panneaux restent contextuels. `obstacle` est aussi une catégorie métier, pas une septième classe YOLO. |
| Exécution et géométrie du modèle | [setupVision](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1182), [runDetection](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1268) | Réimplémenter le contrat d’entrée avec le runtime mobile | Vision utilise `.scaleFill` et `.right`. Ne pas remplacer implicitement par letterbox ni recopier la rotation iPhone sur la caméra HTC. |
| Décodage YOLO et NMS | [processDetections](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1317), [outputLayout](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1489), [nms](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1803) | Garder filtre de confiance et mapping, adapter le parseur au nouvel export | Le parseur Swift suppose quatre coordonnées `xywh` suivies de scores de classes. Sa NMS est agnostique à la classe ; cela peut supprimer des objets de classes différentes qui se recouvrent. |
| Directions gauche/devant/droite | [corridorZone](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1562) | Porter les seuils normalisés comme point de départ | Gauche si `midX < 0,39`, droite si `midX > 0,61`. Vérifier le repère caméra avant le classement ; ne pas dépendre de la taille de l’aperçu téléphone. |
| Profondeur d’une boîte et occlusion personne/objet | [SemanticDetectionPolicy](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:98), [estimateDepth](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1717) | Porter les fonctions pures et leurs tests pour un adaptateur de profondeur éventuel | Ne pas activer sans profondeur alignée valide. Neuf points puis percentile 30 ; la règle d’occlusion dépend des distances. |
| Détection d’obstruction générique | [analyzeForwardObstruction](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1592) | Conserver la spécification et les tests de référence | LiDAR : percentile 20, occupation et largeur sur trois zones. Sans profondeur, pas de détection générique de mur équivalente garantie par YOLO. |
| Arbitrage sémantique/LiDAR | [DangerResolutionPolicy](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:4185), [resolvedDangerCandidate](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:1974) | Porter la politique pure ; appeler seulement les branches dont les entrées existent | Distances, occupation et protection d’un mur fort ne doivent pas recevoir des valeurs fictives. |
| Alertes fondées sur l’apparence seule | [semanticCandidateWithoutForwardObstruction](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:3330), [qualifiesForVisualOnlySemanticCue](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:3378) | Base prioritaire pour une adaptation caméra seule | Seuils, aire, position basse et zone sont réutilisables après calibration. `visualOnlyProxyDistance` produit une heuristique, pas une mesure métrique. |
| Mémoires sémantiques courtes | [SemanticMemoryLifetimePolicy](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:214), [updateSemanticAlertMemory](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:2658) | Porter expiration, provenance et confirmation | TTL 0,95 / 2,85 / 1,85 s selon la mémoire ; certaines cohérences requièrent LiDAR/pose. |
| Stabilisation du danger | [stabilizedCandidate](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:3005) | Porter identité, maintien, relâchement et remplacement par danger plus fort | Isoler les contrôles de cohérence métrique. Tester l’expiration et les changements de zone/sévérité. |
| Anti-répétition vocale | [SpatialAlertVoiceMemory](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:3892), [spatialAlertWorldAnchor](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:3551) | Porter cadence, réannonce, limites et reset ; adapter l’association des entités | Les ancres du monde dépendent de la pose ARKit et de la distance. Avec `worldAnchor=nil`, le Swift fusionne les observations non spatiales de même catégorie : inadapté pour distinguer plusieurs personnes. |
| Messages et audio | [voiceCueText](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:3423), [updateStatus](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:3173), [ProximityAudio](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:4827) | Réutiliser les phrases et événements audio, implémenter un adaptateur HTC | `speakText` ne prouve pas le contrôle du panoramique stéréo, des bips ou de l’interruption. Tester ces fonctions séparément sur lunettes. |
| Arrêt/reprise et résultats périmés | [ProximitySessionSafetyState](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:562), [ProximityViewModel](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:727) | Porter `generation`, `freshnessEpoch` et invalidation des nouveaux envois à l’arrêt | Adapter les prérequis au mode capteur ; ne pas déclarer une profondeur fraîche artificiellement. Mesurer séparément l’interruption HTC d’une phrase déjà soumise, non prouvée par le starter. |
| Replay, traces et diagnostics | [DecisionDebugSnapshot](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:341), [EchoNavReplayBridge](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoTest/Sources/EchoNavReplayBridge.swift:7), [SESSION_FORMAT.md](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoTest/SESSION_FORMAT.md) | Réutiliser horodatages, raisons de décision et schéma de traces en l’étendant si nécessaire | Séparer replay avec profondeur enregistrée et live HTC sans profondeur. Le simulateur ne démontre pas l’accès matériel aux capteurs. |

## Paramètres confirmés dans le code

Ces valeurs sont la référence iOS initiale. Leur reprise n’atteste pas leur validation avec la nouvelle caméra ou le nouveau modèle.

- Acquisition sémantique : intervalle 0,25 s ; analyse d’obstruction 4 Hz ; confiance minimum 0,70 ; NMS IoU 0,50 ; au plus huit boîtes après cette NMS.
- Priorités de catégorie : véhicule 1,50 ; deux-roues 1,35 ; personne 1,00 ; poteau 0,85 ; obstacle 0,75. Le score final utilise aussi zone, confiance et distance : ce n’est pas un classement complet autonome.
- Alerte visuelle sans profondeur d’objet : confiance minimum 0,82 véhicule/deux-roues, 0,84 personne, 0,86 poteau/obstacle, puis seuils d’aire et de position dans `qualifiesForVisualOnlySemanticCue`.
- Anti-répétition d’une entité : 4,8 s au niveau élevé, 6,5 s moyen, 9 s faible ; message capteurs limités à 7 s.
- Espacement global des annonces : 0,75 / 1 / 1,35 s selon la sévérité ; escalade autorisée à partir de 0,9 s.
- Mémoire vocale : expiration après plus de 18 s d’absence ; maximum 32 entités. Approche critique : délai ≥ 2 s, distance ≤ 0,90 m et réduction ≥ 0,35 m. Cette dernière règle nécessite une distance exploitable et ne doit pas être activée avec une simple aire de boîte.
- Réinitialisation de replay : changement de session ou retour temporel supérieur à 0,0005 s.

## Trois adaptations nécessaires au mode lunettes

### 1. Session caméra seule explicite

Le démarrage iOS exige world tracking, mesh et profondeur. Une frame sans profondeur fait passer la session en état limité **avant** la boucle de décision. La branche visuelle repérée ne constitue donc pas une application live déjà compatible avec une caméra seule.

Introduire un mode de capacités explicite. Pour le mode RGB, les prérequis deviennent connexion lunettes, nouvelles images décodées, modèle opérationnel et sortie audio définie. Porter le mécanisme de génération/fraîcheur, pas l’obligation d’avoir un LiDAR Apple. Conserver séparément le mode de référence avec profondeur enregistrée pour les comparaisons.

### 2. Distance et identité honnêtes

Faire porter à la donnée de distance sa provenance : mesurée, estimée ou inconnue. Les fonctions Swift de candidat exigent souvent un `Float` ; la couche Kotlin doit accepter l’absence de mesure au lieu d’y injecter un faux nombre de mètres.

La formule `visualOnlyProxyDistance` utilise aire de boîte, position basse et zone centrale, puis borne le résultat entre 0,72 et 2,85. Elle peut inspirer un score visuel qualitatif à tester, mais ne fournit pas une distance calibrée aux lunettes. Les règles de mur LiDAR et de rapprochement métrique restent conditionnelles à des capteurs adaptés.

Pour l’anti-répétition, évaluer une association en image par classe, recouvrement et continuité temporelle, avec identifiants de piste. Conserver les temporisations éprouvées dans les tests, mais ne prétendre ni à un ancrage dans le monde ni à une identité stable lors de grands mouvements de tête. Ne pas remplacer la pose des lunettes par l’IMU d’un téléphone porté ailleurs.

### 3. Contrat d’inférence explicite

Le parseur Swift lit `4 + classes` canaux, interprète les quatre coordonnées en centre/largeur/hauteur, choisit la meilleure classe, puis applique une NMS agnostique à la classe. Une sortie end-to-end peut avoir une autre structure ; ne pas brancher le nouveau tenseur sur ce parseur.

Conserver une base ONNX end-to-end du `.pt` fourni pour le premier essai, puis mesurer son contrat. Si une sortie non end-to-end est choisie pour faciliter la comparaison iOS, en faire une variante explicite avec tests et provenance. Évaluer séparément l’effet du modèle, de la branche, du prétraitement et de la politique d’alertes.

## Tests existants à reprendre

Le dépôt contient 76 déclarations de tests Swift : 56 dans `EchoNavTests.swift`, 8 pour la sécurité de session, 5 pour le mode produit et 7 pour les destinations. Ce comptage n’est pas un résultat d’exécution.

Priorités pour le hackathon :

1. [Tests moteur](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNavTests/EchoNavTests.swift:16) : vocabulaire, arbitrage, attribution de profondeur, occlusions, mémoires, frontières temporelles et reset. Reprendre les tests de profondeur pour la couche concernée sans les présenter comme des essais live HTC.
2. [Tests de session](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNavTests/ProximitySessionSafetyTests.swift:7) : données d’ancienne génération ignorées, arrêt propre et nouvelles preuves requises après panne. Adapter les prérequis au mode RGB.
3. [Tests du mode produit](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNavTests/ProximityProductModeTests.swift:7) : mode simple par défaut et arrêt répété sans effet indésirable.
4. [Contrat du corpus](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoTest/Corpus/sprint1_corpus.json) : sessions 3, 5, 6 et 10, soit 2 010 frames selon le manifeste. Les médias et `frames.jsonl` ne sont **pas présents dans cette copie du dépôt** ; seuls leurs contrats/références sont disponibles. Leur emplacement externe reste à fournir ou retrouver avant replay.

Comparer le portage des politiques sur des fixtures identiques, indépendamment des différences entre modèles. Ajouter des cas propres au mode RGB : deux personnes simultanées, rotations rapides de tête, absence de profondeur, flux figé et retour audio perdu. Les scénarios de distances LiDAR réussis sur replay ne valident pas une mesure de distance sur les lunettes.

## Périmètre prioritaire

Porter le cœur déterministe, les formulations, les réglages pertinents, l’arrêt/reprise et la méthode de replay. Adapter modèle, image, association et audio au matériel. Les écrans de destinations, Face ID, MapKit, backend et TestFlight ne sont pas nécessaires à la démonstration HTC demandée ; ils restent des éléments du projet de référence, pas des dépendances du MVP.

Les documents Sprint 2 les plus récents de cette copie sont utilisés pour l’état produit. `SPATIAL_ALERT_MEMORY.md`, `MODEL_CARD_PARIS_V1.md` et les documents Sprint 1 maintenus par Sprint 2 décrivent le moteur. Les résultats historiques qu’ils rapportent n’ont pas été rejoués ici.

## Réalisation Android et adaptation audio après preuve matérielle

La matrice initiale ci-dessus décrit les sources et les choix de portage, pas un verdict de fidélité intégrale. Le cœur Kotlin pur (`echonav/core/`) et 18 fixtures partagées reprennent les fonctions de direction, qualification et expiration exécutées en Swift ; les fonctions dépendant d’ARKit/LiDAR/pose restent désactivées en RGB_ONLY. Réalisation détaillée : `echonav-htc/validation/PORTAGE_IMPLEMENTATION.md`, preuves actuelles : `echonav-htc/validation/RECETTE.md`.

Pour la ligne « Messages et audio », l’adaptateur HTC `speakText` ne peut pas lire pendant la vidéo sur SDK 0.6.0. D12 substitue dans EchoNav un adaptateur Android `echonav/audio/` : voix française installée locale, PCM, AudioTrack dirigé vers VIVE Eagle. Les phrases et règles Kotlin pures restent identiques ; seule leur livraison change. La mémoire d’annonce n’est validée qu’après un résultat local corrélé par UUID ; cette fin logicielle n’est pas assimilée à une écoute humaine. Le diagnostic `speakText` reste accessible vidéo arrêtée et garde ses règles de callbacks anonymes. Cela ne présume aucun adaptateur audio iOS HTC équivalent : sa disponibilité dépend toujours des accès à obtenir.

**Complément D13 demandé par l’utilisateur :** l’adaptateur Android utilise maintenant LEFT/CENTER/RIGHT issu de `VoiceAlert.zone` pour la stéréo des annonces. Il préserve le vocabulaire Swift et la décision RGB, sans reconstruire `ProximityAudio`, HRTF ou distance. La normalisation PCM et l’isolation des canaux possèdent neuf tests JVM propres. L’option Swift peut reprendre le même contrat de zone ; aucune implémentation iOS HTC correspondante n’a été exécutée.

## EchoTest HTC et rejeu sur Mac réalisés

Le nouvel EchoTest suit la méthode du replay EchoNav, avec un contrat adapté au SDK HTC : H264 reçu, PNG analysables, index temporels, inférences et décisions, paramètres et événements vocaux. Ce schéma v1 ne prétend pas être directement compatible avec les anciennes sessions Swift/LiDAR ; les capteurs absents sont explicites. Contrat : `echonav-htc/validation/ECHOTEST_SESSION_FORMAT.md`.

Le Mac compile directement les sources du cœur Kotlin Android via `echotest-policy/`, au lieu de réécrire les règles en Python ou de dériver une nouvelle source Swift. Le lecteur Python utilise ce moteur avec une horloge virtuelle. Sur la première vraie capture HTC, les détections et horloges enregistrées donnent 48/48 décisions identiques. Les fixtures partagées Swift/Kotlin restent la preuve des fonctions Swift déjà portées, pas une validation d'iOS HTC.

Le recalcul ONNX sur Mac valide le prétraitement sur les six fixtures pixels et rejoue les 49 PNG réelles. Sept comparaisons brutes sur 48 ne passent pas à cause de lignes de très faible score ; aucune détection applicative ≥0,70 ni décision issue des entrées Android n'est affectée dans ce lot. Les tolérances et ces échecs sont conservés. La vidéo, les performances Android et l'audibilité des lunettes demeurent des preuves matérielles séparées du replay. Recette : `echonav-htc/validation/RECETTE.md`.
