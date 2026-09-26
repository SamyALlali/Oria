# Débat — tour 1 : portage, décision RGB et annonces

26 septembre 2026. Proposition indépendante de l’agent portage. Inspection statique des quatre documents de cadrage, du moteur Swift, des tests et des documents Sprint 2 actuels. Aucun test ni essai matériel exécuté. Référence Swift : commit `8fbb4e03911bfc16e90e072f3446a492bc61c2ea`.

## Recommandation

Construire une **assistance descriptive RGB**, avec annonces d’objets appris et de leur direction par rapport à la caméra des lunettes. Réutiliser le noyau temporel et les formulations EchoNav, mais créer un contrat de capacités RGB explicite. La démonstration promet « Piéton devant », pas une distance physique, une trajectoire sûre ou une absence d’obstacles.

Le live Swift actuel exige profondeur et suivi AR avant la décision (`EchoNavApp.swift:1203`). Sa branche `qualifiesForVisualOnlySemanticCue` n’est donc pas un mode RGB live autonome. Faire artificiellement passer `hasFreshDepth` à vrai pour débloquer le moteur détruirait le sens des protections de session. En RGB, les conditions deviennent : session connectée, image récente réellement décodée, modèle valide ayant produit une sortie récente, chemin d’alerte disponible ou état audio explicitement dégradé.

### Ce qui doit être réutilisé

| Classe | Éléments | Limite / preuve à obtenir |
|---|---|---|
| Réutilisable presque tel quel | Catégories, phrases, zones 0,39/0,61, horloge monotone, expiration, reset de session/replay, plafond de mémoire, cadence et raisons de suppression | Tests de frontières et comparaison des séquences Swift/Kotlin sur les mêmes fixtures. La direction doit être définie après remise des boîtes dans l’image caméra orientée. |
| Adaptable | Qualification visuelle par confiance/aire/bas de boîte, priorité entre catégories, maintien/relâchement et remplacement, anti-répétition, états de session, sortie audio | Les seuils initiaux sont une référence à calibrer ; la caméra et le checkpoint changent. Les valeurs de risque ou de temporisation liées à une distance ne restent pas implicitement valides. |
| Inactif en RGB sans preuve capteur | Percentiles de profondeur, occlusions métriques, occupation LiDAR, mur générique, rapprochement 0,35 m/0,90 m, ancres AR du monde | Conserver leur spécification et leurs tests de référence sans remplir les entrées manquantes avec des nombres artificiels. |

### Politique RGB minimale proposée

1. Les classes `person`, `vehicle`, `bike_scooter`, `pole` peuvent produire une phrase catégorielle. `traffic_light` et `traffic_sign` restent affichées en diagnostic ; elles ne donnent ni couleur ni permission de traverser. `obstacle` est une catégorie métier Swift, pas une septième classe du checkpoint.
2. Initialiser la qualification avec les seuils visuels Swift : confiance 0,82/0,82/0,84/0,86, puis aire et position basse propres à chaque classe (`3378–3412`). La priorité finale RGB doit être un contrat explicite et testé ; les biais de catégorie seuls ne sont pas l’arbitre Swift complet.
3. Le signal aire/bas de boîte/zone centrale peut inspirer une **saillance visuelle sans unité**. Ne pas le stocker dans `distanceMeters` ni le transmettre aux branches métriques. Le proxy Swift `3414` est borné 0,72–2,85 et ne constitue pas une mesure des lunettes.
4. Éviter au MVP la terminologie « danger proche » et les niveaux de danger métriques pour les résultats caméra seule. Utiliser une priorité d’annonce. La confidence du modèle n’est pas une probabilité calibrée de collision. Commencer avec les phrases « Piéton devant », « Poteau avant-gauche », etc.
5. Réutiliser les mécanismes de stabilité, mais séparer sélection active et souvenir vocal. Une piste mémorisée absente ne doit pas pouvoir déclencher une phrase ancienne simplement parce que son délai de rappel est atteint. Toute émission exige une observation assez récente et une session encore valide.

### Identité : ne pas conserver `worldAnchor=nil`

Dans `SpatialAlertVoiceMemory.bestMatchingEntityIndex` (`EchoNavApp.swift:4031` environ ; symbole déterminant), une observation sans ancre fusionne avec la dernière entité de même catégorie. Deux personnes peuvent alors hériter du même silence de rappel. La mémoire vocale Swift n’est pas un tracker vidéo général.

Proposition : une association 2D minimale, à appariement un-à-un, classe + recouvrement + continuité temporelle, produit des identifiants de pistes locaux. Réutiliser les règles de cooldown sur ces identifiants. La durée de vie courte des pistes perdues est un paramètre RGB distinct des 18 s de rétention des annonces Swift : retenir une mémoire n’autorise pas à associer une nouvelle personne 18 s plus tard. Ne pas promettre l’identité à travers un grand mouvement de tête ou une occultation longue. L’IMU du téléphone ne représente pas la tête.

L’association précise (seuil IoU, délai de perte) doit être fixée sur fixtures documentées, pas choisie comme fait acquis. Tester au minimum deux personnes simultanées séparées, croisement, bref masquage, sortie/entrée et rotation de tête. Une détection nouvelle ne doit pas être supprimée uniquement parce qu’une personne différente a déjà été annoncée ; la cadence globale peut toujours la retarder.

### Annonce proposée, envoyée, confirmée

Le Swift marque `lastAnnouncedAt` dans `SpatialAlertVoiceMemory.evaluate`, **avant** le contrôle `isAudioEnabled` et l’appel asynchrone au synthétiseur (`emitVoiceCueIfNeeded`, `3461`). Cela convient à certains exports de replay, mais ne prouve pas qu’une phrase a été prononcée. Le starter HTC (`ViveGlassKitManager.kt:236`) ne fait que logger `SUCCESS`, `ERROR`, conflit et locale incompatible.

Le portage doit distinguer `eligible`, `submitted`, `sdkConfirmed`, `failed`, `expired`, `cancelled`. Réserver temporairement un envoi pour éviter les doublons ; ne consommer le cooldown « confirmé » qu’au retour SDK dont la signification a été vérifiée. Un succès SDK n’est pas à lui seul une mesure du son audible. Une erreur ne doit pas créer 6,5 s de faux silence « déjà annoncé », ni une boucle d’essais à chaque frame. Recommandation initiale : une seule requête vocale en vol, aucune FIFO de phrases historiques ; réévaluer le meilleur candidat récent après completion/erreur. Timeout et répétition bornés, sans réattribuer un callback ancien à une nouvelle session.

L’API exposée ne prouve pas un identifiant de requête ou un arrêt de parole. Un callback tardif ou anonyme après timeout peut rendre la confirmation ambiguë : l’adaptateur doit alors traiter l’état comme inconnu et ne pas certifier une nouvelle annonce. Tester ce point avant de définir la reprise. Séparer les traces de décision de celles de livraison audio.

### Arrêt : limite à exprimer dans le cahier des charges

Le Swift peut arrêter immédiatement son `AVSpeechSynthesizer` (`265–312`). Rien dans l’inspection du sample ne prouve la même capacité HTC. Exiger dès le départ qu’Arrêter invalide images, résultats, pistes et demandes **non envoyées** ; aucun nouvel envoi après l’arrêt. Mesurer séparément si la phrase déjà soumise continue. La promesse « silence instantané garanti » doit rester un objectif conditionné à une API prouvée. Des phrases brèves limitent la durée résiduelle, elles ne remplacent pas l’annulation.

## Alternative sérieuse

Si l’association 2D n’est pas fiable ou ne tient pas dans le temps disponible, employer une mémoire **par classe et zone** avec cadence globale, sans prétendre distinguer les individus. C’est beaucoup plus simple qu’un tracker et plus honnête que `nil` présenté comme une identité spatiale. Le compromis est observable : deux personnes de même zone partagent le rappel, changement de zone peut réannoncer la même personne. Accepter cette variante uniquement en démonstration explicitement collective (« présence d’un piéton devant »), sans compter les individus. Réouvrir vers des pistes dès qu’un test à deux objets montre une suppression gênante et que le budget permet de la corriger.

Je recommande les pistes 2D légères pour préserver au maximum le comportement utile d’EchoNav ; je conteste cependant toute obligation de réidentification persistante ou de tracker complexe au MVP.

## Actualisation : téléphone HTC disponible, piste iOS demain

L’utilisateur dispose maintenant du téléphone HTC prêté pour les essais et propose Kotlin. Je recommande donc Android/Kotlin natif dans le starter dès maintenant, avec cœur de politique Kotlin sans imports Android, interfaces simples et fixtures JSON communes. Les contrats transportent classe, boîte dans un repère documenté, score, timestamp, session, piste et provenance des mesures. Ils permettent de comparer Kotlin au Swift existant sans introduire un framework de partage de code.

La disponibilité éventuelle d’un développement iOS demain ne justifie pas Kotlin Multiplatform maintenant : il faudrait valider son outillage, des interfaces natives et l’existence d’un SDK HTC iOS utilisable. Conserver le Swift existant comme référence et réutiliser les fixtures est une option immédiatement exploitable. Réouvrir KMP uniquement si une application iOS connectée aux lunettes devient un livrable confirmé, avec SDK compatible et budget établi. Un iPhone LiDAR ne fournit pas automatiquement une profondeur ou une pose alignée sur la caméra déportée des lunettes.

## Exigences minimales acceptables et tests décisifs

| Exigence | Vérification minimale | Statut |
|---|---|---|
| Session RGB active sans fausse profondeur ; absence d’image/modèle frais interdit une nouvelle annonce d’objet | Fixtures de perte flux, ancienne génération, reprise sans nouveau résultat ; miroir des tests Swift de session | Non exécuté |
| Aucune distance métrique, couleur de feu ou voie libre issue des seules classes/boîtes | Inspection des messages, fixtures contenant feu/panneau, mur hors classes et scène vide | Non exécuté |
| Cohérence gauche/devant/droite dans le repère caméra | Objet placé successivement dans trois zones, portrait/paysage et transformations enregistrées | Non exécuté |
| Anti-répétition n’efface pas la décision ou les autres observations | Deux personnes ; politique vocale bloquée mais détections/choix restent traçables | Non exécuté |
| Erreur TTS ne vaut pas annonce entendue | Succès, erreur, conflit, timeout, callback tardif et audio désactivé injectés ; écoute réelle pour route haut-parleur | Non exécuté |
| Arrêt interdit toute nouvelle soumission et vide les demandes internes | Arrêt à chaque étape du pipeline ; mesurer durée de parole résiduelle sur lunettes | Non exécuté |
| Démonstration utile et reproductible | Au moins 3 catégories parmi les 4 alertables, directions vérifiées, objet maintenu sans spam, second objet distinct, perte/reprise ; noter tous les échecs et les catégories réellement démontrées | Non exécuté |

Ces critères décrivent un prototype d’assistance descriptive. Ils ne transforment pas un test de table ou une vidéo simulée en validation d’une navigation sûre. Ne pas annoncer « aucun obstacle » quand YOLO ne voit rien ; un mur, une marche ou un trou n’appartiennent pas aux six classes disponibles.

## Inconnues et conditions de réouverture

- Matériel/capteurs exacts non vérifiés. Réouvrir l’arbitrage profondeur uniquement avec API, alignement, timestamp et validation du capteur réellement disponibles.
- Seuils iPhone susceptibles de rejeter des poteaux vus depuis les lunettes. Réouvrir sur petit corpus annoté représentatif en séparant effet du modèle et politique ; ne pas baisser des seuils seulement pour réussir une scène choisie.
- Association 2D sensible aux mouvements de tête. Réouvrir vers mémoire classe/zone pour réduire complexité ou vers tracker démontré si les cas d’usage l’exigent.
- Annulation TTS et sens exact de `SUCCESS` non établis. Réouvrir le choix du chemin audio sur essai simultané vidéo/parole et interruption, pas sur simple existence de méthode.

## Sources primaires inspectées

- [Moteur EchoNav](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNav/EchoNavApp.swift:562) : `ProximitySessionSafetyState`, `session(_:didUpdate:)`, `semanticCandidateWithoutForwardObstruction`, `qualifiesForVisualOnlySemanticCue`, `visualOnlyProxyDistance`, `stabilizedCandidate`, `emitVoiceCueIfNeeded`, `spatialAlertWorldAnchor`, `SpatialAlertVoiceMemory`.
- [Tests moteur](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNavTests/EchoNavTests.swift:329) : rappels, escalade, ancres distinctes, rétention, pacing et reset ; leurs tests métriques ne valident pas le RGB.
- [Tests session](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/EchoNav_App/EchoNavTests/ProximitySessionSafetyTests.swift:49) : anciennes générations et epochs périmés.
- [Jalon 3](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/Documentation/SPRINT2_JALON3_SESSION_SAFETY.md), [jalon 4](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/Documentation/SPRINT2_JALON4_PRODUCT_MODE.md) : cycle de vie et interface produit actuels ; résultats historiques non reproduits ici.
- [Contrat de mémoire vocale](/Users/sam/Documents/ChatGPT/echonav/EchoNav_main/Documentation/SPATIAL_ALERT_MEMORY.md) : séparation voix/danger et association AR ; certaines formulations « effectivement émise » décrivent l’export, pas une preuve d’audibilité physique.
- [HTC callback synthèse](/Users/sam/Downloads/eagle-hackathon-starter-usb/android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/ViveGlassKitManager.kt:236), [envoi texte](/Users/sam/Downloads/eagle-hackathon-starter-usb/android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/ViveGlassKitManager.kt:534).
