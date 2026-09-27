# Fusion — audit navigation, voix et interface

Audit du 27 septembre 2026, branche de travail `fusion`, base stable `main=b650dad`, source lue `origin/rayan_work=96d524a41986f3b6799c1743055a7a7e4f18fec9` (commits `40e8018` et `96d524a`).

**Décision proposée : reprendre les modules isolables, adapter leur orchestration et conserver le runtime 1.7 de main.** Aucun fichier runtime modifié pendant cet audit. Aucun Gradle, ADB, test réseau de route ni essai matériel exécuté. Les rapports de Rayan sont des références de ses essais antérieurs, pas une recette de la fusion ni de nouvelles consignes utilisateur.

## Base à préserver

Main 1.7 combine déjà YOLO et Depth Anything V2 252 FP32, arbitre leurs annonces, conserve les confirmations de lecture et leurs UUID, utilise la voix Bluetooth 70/30 et fonctionne écran éteint automatiquement après « Démarrer Oria ». L’interface ne demande plus de choix poche, mode d’obstacles ni confirmation manuelle des directions. Les erreurs vocales sont visibles et la reprise locale ne remet pas à zéro un transport HTC ambigu. Les captures Oria Lab restent au premier plan. Le nom autorisé du package ne change pas.

Le front Rayan vient d’une base plus ancienne. Sa reprise entière réintroduirait les options et les gardes manuelles explicitement retirées, ferait revenir une profondeur optionnelle MiDaS et écarterait les preuves récentes de main. Le nom historique de l’ancien projet figure à nouveau dans son parser et son front : ne pas le réintroduire.

## Matrice de reprise

Les chemins ci-dessous sont relatifs à `android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/`, sauf indication contraire.

| Élément | Décision | Fichiers / dépendances | Preuves présentes et travail nécessaire |
| --- | --- | --- | --- |
| Contrats destination, géocodage, position, route, événements | Reprendre entier, puis renforcer les invariants | `navigation/NavigationContracts.kt` | Types purs et séparation des fournisseurs utiles. Ajouter identité de manœuvre, âge maximal du fix et retour explicite de livraison vocale. |
| Simulation déterministe | Reprendre partiel | `navigation/SimulatedNavigation.kt`, contrats, moteur | Aucun GPS ni appel réseau. Scénario JVM fourni. Corriger l’annonce d’arrivée potentiellement produite avant ses deux confirmations. |
| Progression, pause, reprise, recalcul et arrivée | Reprendre partiel | `navigation/NavigationEngine.kt` | Tests JVM couvrent 2 manœuvres, générations, route remplacée, précision, pause, GPS perdu et arrivée. Renforcer les cas détaillés ci-dessous avant qualification live. |
| Orchestration asynchrone | Reprendre partiel | `navigation/OriaNavigationCoordinator.kt`, coroutines, fournisseurs | Bons tokens contre les résultats d’une ancienne destination. Manquent requête en vol unique, limite réseau, timeout de géocodage et silence GPS. Tests fournis ne couvrent pas un routage lent avec fixes continus. |
| Destinations enregistrées | Reprendre partiel | `SharedPreferencesDestinationRepository` dans `AndroidNavigationAdapters.kt` | Stockage privé, normalisation, UUID conservé en modification, échec de commit explicite. Écritures synchrones à déplacer hors Main ; erreurs CRUD visibles ; aucune destination réelle dans les fixtures/publiées. |
| Recherche texte / suggestions / confirmation | Reprendre partiel | Bloc navigation de `ui/OriaScreen.kt`, coordinator | Garde de confirmation utile. Extraire une section dédiée dans le front main, cibles ≥56 dp, erreurs visibles, permission liée à l’ID de destination et à sa génération. |
| Dictée Android de destination | Reprendre partiel | `RecognizerIntent` dans le front | Appel explicite, français. Vérifier disponibilité du reconnaisseur, traiter annulation/erreur et résultat d’une ancienne requête ; expliquer le fournisseur utilisé avant dictée. Ne pas faire dépendre vidéo d’un micro téléphone. |
| Géocodeur Android | Reprendre partiel | `AndroidPlatformGeocoder` | API asynchrone Android 33+, continuation vérifie `isActive`. Aucun délai maximal, cache ni limitation de demandes ; `runCatching` absorbe aussi l’annulation. Ajouter cancellation correcte et bornes. |
| Itinéraire OSRM piéton | Reprendre partiel | `OsrmFootRouteProvider`, permission INTERNET | Endpoint dédié foot vérifié par les sources exploitant ; aucune clé embarquée. Ajouter throttle global ≤1 requête/s, réponse bornée, annulation réelle du HTTP, tests JSON, consentement réseau partagé et attribution complète. |
| Localisation Android | Reprendre partiel | `AndroidLocationProvider`, permissions coarse/fine | Horloge elapsedRealtime correcte ; pas d’AudioRecord. Ajouter fraîcheur/qualité avant premier routage, watchdog absence de fixes et nettoyage si un provider échoue après l’autre. |
| Parser français et séquenceur bouton | Reprendre partiel | `interaction/OriaVoiceInteraction.kt` | Code pur, ambiguïtés sans action, tests accents/double appui. Retirer alias de l’ancien nom, garder texte original de destination, tester minuterie/arrêt de session. |
| Transaction SDK transcription | Refuser telle quelle | `ViveGlassKitManager.kt` et méthodes du Controller Rayan | Attribution erronée possible d’un callback anonyme ancien à une nouvelle demande ; UUID local ne résout pas l’absence d’ID SDK. Voir P1 ci-dessous. |
| Commandes mains libres / restauration caméra | Reprendre partiel après adaptation | Controller, MainActivity, service main | Le code source collecte les touches seulement quand Activity STARTED et appelle `start()` directement après transcription : aucune preuve écran éteint. Garder le propriétaire de service de main, arrêt volontaire et générations. |
| Ordonnanceur audio pur | Reprendre partiel | `audio/OriaAudioScheduler.kt` et tests | File bornée, priorités et invalidation intéressantes. Séparer le scheduler des nouveaux sons de danger. Ajouter handoff d’annulation confirmé et retour de livraison aux politiques existantes ; conserver `FusionVoiceArbiter` et leurs réservations. |
| Bips continus de danger / modifications PCM | Conserver main pour cette fusion | Partie `DangerSoundPattern`, `BluetoothPcmPlayer`, `BluetoothSpeechBackend` Rayan | Changement perceptif et de sortie non requis pour introduire navigation. Risque de concurrence avec voix déjà vérifiée ; pas de nouvelle qualification matérielle ici. |
| Front final Rayan / dashboard | Reprendre seulement composants utiles | `ui/OriaDashboardCards.kt`, bloc navigation | Garder thème, accueil simplifié main et erreurs vocales. Ne pas réintroduire poche, choix profondeur, tests direction, checkbox orientation ni label « Protection active ». Éviter un résumé TalkBack republié au rythme de télémétrie. |
| MainActivity, Controller et manifeste entiers | Refuser remplacement global | Racine app, service poche main | Reprise chirurgicale des points d’entrée et seules permissions requises. Garder modèles, package, signature et données. |

## Problèmes concrets à résoudre avant branchement live

Les numéros de ligne suivants désignent la source `96d524a`, consultée avec `git show`, pas le fichier fusion en cours.

1. **P1 — callback de transcription anonyme réattribué.** Dans `ViveGlassKitManager.kt:onSpeechTranscribed`, le callback SDK est associé à la valeur courante de `activeOriaTranscriptionId`. Après timeout/annulation A puis début B, un terminal tardif A sera étiqueté B. `TranscriptionRequestGate` ne peut détecter ce faux étiquetage. Conserver une incertitude bloquante ou obtenir un reset de transport prouvé avant une nouvelle requête ; ne pas prétendre résoudre cela en générant un UUID. Test nécessaire avec terminal A reçu pendant B, plus duplication après succès.

2. **P1 — préemption audio sans attente du backend.** Controller Rayan lignes 941–964 appelle `cancelLocalSpeech()` puis `sendSpeech()` dans le même passage Main. Le backend garde sa requête `active` jusqu’au callback Main, malgré `stop()` : `submit()` refuse la suivante tant que `active != null`. La préemption peut donc couper la navigation puis refuser l’alerte prioritaire. Attendre le terminal corrélé de l’ancienne lecture, puis revalider fraîcheur et réservation avant d’émettre. Ajouter un test d’intégration transport factice où l’annulation termine plus tard.

3. **P1 — relances de calcul sur chaque position.** Coordinator `onLocation` demande une route tant que le moteur reste IDLE/RECALCULATING, et `requestRoute` annule l’opération précédente. Les positions sont demandées chaque seconde ; un calcul >1 s peut ne jamais être accepté. Garder une seule opération par destination/version, conserver seulement la position récente et replanifier après résultat si nécessaire. Vérifier 10 fixes pendant un calcul suspendu : un seul HTTP et un résultat accepté.

4. **P1 — instructions invalidées encore répétables et fraîcheur PCM absente pour navigation.** `activeInformation()` (Controller 1278–1284) répète `currentInstruction` aussi en PAUSED, RECALCULATING ou LIMITED ; le moteur conserve cette chaîne lors d’invalidation. La répétition contourne donc la garde fraîcheur via COMMAND_RESPONSE. De plus `sendSpeech`/`localRequestCurrent` (1392–1462) ne contrôlent pas `expiresAtMs`, navigationGeneration, routeVersion ou instructionVersion pour un ticket null ; une synthèse TTS lente peut jouer une instruction périmée. Exiger même identité et même fraîcheur pour répétition et premier PCM ; répondre par l’état de pause/recalcul/GPS si instruction indisponible.

5. **P1 — passage en arrière-plan incomplet pendant un calcul.** MainActivity Rayan appelle seulement `pauseNavigation()` dans onStop ; `NavigationEngine.pause()` ne traite que ACTIVE/LIMITED. Une recherche/calculation peut donc terminer en arrière-plan et activer une route. Même en PAUSED, le provider continue ses demandes. Introduire une suspension explicite des opérations et des positions au changement de visibilité pour la première version foreground-only. La perception main doit continuer indépendamment dans son service automatique.

6. **P2 — arrivée et progression.** Le fournisseur fournit une manœuvre textuelle « Vous êtes arrivé ». Avant `arrivalSamples == 2`, le moteur peut l’émettre comme un `Speak` normal lorsqu’il est près de la dernière manœuvre. Le test d’arrivée actuel ignore l’événement du premier fix. Réserver toute annonce d’arrivée au seul événement `Arrived`. Ajouter route réelle avec étape terminale, point proche imprécis et retour arrière. Les distances affichées sont à vol d’oiseau vers la prochaine manœuvre, pas une distance restante le long du trajet.

7. **P2 — intention d’annonce considérée comme déjà dite.** `spoken.add()` dans `NavigationEngine` se produit avant dispatch audio. Refus de sortie, expiration ou file remplacée ne rendent pas l’instruction répétable ; les IDs déterministes peuvent en plus être rejetés comme doublons après `audioInterrupted` alors que le moteur les propose de nouveau. Porter `instructionVersion` dans l’identité du message et distinguer proposition / réservation / lecture terminée, comme dans main.

8. **P2 — qualité GPS et perte silencieuse.** Un premier fix vieux/imprécis peut déclencher HTTP avant toute validation. Seule la désactivation explicite de tous les providers déclenche `onUnavailable` ; aucune minuterie n’invalide les instructions si plus aucun fix ne vient. Ajouter âge monotone maximal, précision utile, temporisateur et test provider silencieux. `LocationFix` et `NavigationRoute` devraient rejeter les nombres non finis (NaN/Infinity).

9. **P2 — commandes dictées et service.** `beginVoiceCommand()` appelle `stop()`, qui libère le service poche ; restauration via `start()` directe ne redémarre pas ce propriétaire. `onEagleAiButton` est collecté dans `repeatOnLifecycle(STARTED)`, donc pas d’activation écran verrouillé. Ne pas importer cette régression dans main. Une pause microphone doit être une transaction de ressource bornée, avec preuve de propriété et reprise seulement si l’utilisateur n’a pas arrêté entre-temps.

10. **P2 — voix destination non équivalente à saisie locale.** `GuideTo` force REAL et lance le géocodeur sans passer par le texte d’information du formulaire ; la dictée Android peut aussi utiliser le fournisseur du système. Toutes les entrées doivent partager l’information et l’autorisation réseau avant envoi. Le boolean `confirmAfterPermission` doit être remplacé par une confirmation liée à la destination exacte ; un retour de permission tardif ne doit pas confirmer un nouveau choix.

## Réseau, données et profils : vérifications externes

Le service FOSSGIS annonce des profils voiture, vélo et piéton. Son frontend associe `Foot` à `https://routing.openstreetmap.de/routed-foot/route/v1`, et le profil publié utilise `mode.walking`. Cela confirme le choix du serveur dédié ; le dernier segment `/driving` présent dans le client n’est pas, seul, une preuve que le graphe est automobile. Conserver un test de réponse dont les étapes portent le mode piéton, car la configuration distante peut évoluer. Sources primaires : [configuration frontend de l’exploitant](https://raw.githubusercontent.com/fossgis-routing-server/osrm-frontend/master/src/leaflet_options.js), [profil foot publié](https://raw.githubusercontent.com/fossgis-routing-server/cbf-routing-profiles/master/foot.lua).

L’exploitant demande au plus une requête par seconde, un User-Agent, attribution et lien de correction de carte. Les demandes de route sont journalisées sur son serveur. Le client Rayan ne limite pas la cadence et son attribution n’inclut pas le lien de correction. Aucun jeton ou secret n’est embarqué dans ces nouveaux adaptateurs. Source primaire : [informations et conditions du service](https://routing.openstreetmap.de/about.html).

Le géocodeur Android peut contacter le réseau ; il ne faut pas annoncer une recherche entièrement locale. Le code appelle la recherche au clic, sans frappe automatique, mais ne déduplique/cache pas les demandes ni ne borne le temps d’une réponse API33. Ajouter un petit cache mémoire limité des requêtes normalisées, des délais et une opération en vol ; ne pas persister des recherches implicites. Source primaire : [Geocoder Android](https://developer.android.com/reference/android/location/Geocoder).

Le service automatique actuel déclare connectedDevice/mediaPlayback. Une vraie navigation GPS poursuivie écran éteint demanderait une intégration `location`, permission de service correspondante et permissions runtime acquises au premier plan. La première reprise peut rester explicitement foreground-only et suspendre réellement GPS et requêtes lorsque l’Activity quitte l’écran, tout en préservant les alertes objets/obstacles du service main. Source primaire : [types de foreground services Android](https://developer.android.com/develop/background-work/services/fgs/service-types#location).

Conserver la vidéo H.264 indépendante du retour microphone et ne jamais réintroduire une horloge AudioRecord dans son décodage. Les adaptateurs de navigation n’en ont pas. Les commandes SDK doivent seulement suspendre la ressource caméra quand le matériel l’exige, puis la rétablir explicitement. Les textes de recherche, destinations et transcriptions sont tracés en clair dans le Controller source : passer par le filtre confidentialité retenu avant export ; ne pas publier de captures ou trajets privés.

## Premier commit isolé recommandé

Ajouter uniquement `NavigationContracts.kt`, `NavigationEngine.kt`, `SimulatedNavigation.kt`, leurs tests purs et fixtures synthétiques. Corriger arrivée anticipée, identité de manœuvre/instruction, fraîcheur et contrat proposition/livraison dans cette couche avant branchement Android. Aucun remplacement du Controller, des modèles ou du front. Puis intégrer coordinator corrigé et fournisseurs, puis une section navigation dans l’UI main ; brancher audio et bouton séparément après tests de transport.

Contrat d’intégration minimal : état navigation distinct de perception ; `NavigationSpeech` porte génération de navigation + route + instruction + source fraîche + expiration ; une seule sortie Bluetooth propriétaire ; résultats et annulations corrélés jusqu’au backend ; navigation invalidée à pause/recalcul/arrêt/visibilité ; alertes YOLO/profondeur conservent leurs politiques et confirmations existantes. Une requête navigation ne doit pas pouvoir remplacer une réservation d’objet/profondeur ni allonger son délai de fraîcheur.

## Recette à exécuter après intégration

- JVM : simulation, route lente + positions continues, requêtes anciennes/non annulables, perte GPS silencieuse, pause pendant recherche/calcul, arrivée avant/deuxième fix, erreur/récupération audio, préemption différée, expiration durant TTS et répétition d’une instruction invalidée.
- UI : permission refusée/accordée tardivement, nom/adresse longue à grande police, TalkBack sans récitation de télémétrie, distinction trajet réel/simulé et attribution visible, formulaire sans ancien réglage poche/direction/profondeur.
- Matériel borné : navigation réelle au premier plan avec deux manœuvres ; alertes perception pendant itinéraire ; préemption et non-reprise d’ancienne phrase ; simple/double bouton, erreur SDK et retour tardif ; verrouillage conserve la perception et suspend la navigation selon contrat affiché.

Les essais déclarés dans `R06_GUIDED_NAVIGATION.md`, `R07_VOICE_BUTTON_VALIDATION.md` et `RAYAN_WORK_MERGE.md` concernent le runtime de Rayan. Le premier rapporte un blocage de signature et une sonde depuis Mac ; le dernier rapporte une installation ultérieure. Aucun des deux ne prouve les comportements physiques de cette fusion. Aucun nouveau résultat d’exécution n’est revendiqué par le présent audit.

## Phase 2 — reprise sélective implémentée

Après attribution explicite des fichiers par l’orchestrateur, les modules `navigation`, `interaction` et le panneau additif `ui/OriaNavigationPanel.kt` ont été repris. Le Controller, MainActivity, les modèles et le backend audio restent intégrés séparément par l’orchestrateur. Aucune transcription SDK HTC anonyme n’a été importée.

Corrections présentes dans ces modules :

- Calcul de route unique en vol ; les positions nouvelles ne le relancent plus. Arrêt et changement de destination invalident génération et opération.
- Pause/arrière-plan arrêtent le provider et annulent recherche/calcul, même avant la première route. Une route tardive n’active pas le trajet. Reprise explicite au retour.
- Fixs âgés de plus de 6 s, futurs ou de précision >35 m refusés. Le silence GPS invalide les instructions. Arrivée : deux fixs distincts de précision ≤10 m dans le rayon 15 m ; aucune manœuvre ordinaire ne dit « arrivé » avant cet événement.
- Proposition vocale, consommation après livraison et répétition fraîche séparées. Identité incluant générations/route/instruction/proposition. Permis immuable publié par le propriétaire Main et lu sans mutation par le worker audio.
- Réseau désactivé avant consentement visible dans la session. Démo sans réseau. Recherche géocodeur bornée et cache mémoire 16 entrées/5 min ; appels espacés. Itinéraires FOSSGIS `/routed-foot/.../foot`, au plus 1 requête/s dans le processus, annulation HTTP, réponse bornée à 2 Mio et vérification des modes d’étapes compatibles avec le profil piéton.
- Le provider ne trace pas adresses, coordonnées, texte recherché ou transcription. Le panneau nomme dictée téléphone, GPS au premier plan, mode simulé et destinataire réseau ; attribution et lien de correction OSM restent visibles pour un trajet réel.
- Parser sans alias de l’ancien nom ; conservation des accents et traits d’union de la destination dictée. Bouton IA et parsing restent purs : aucune promesse de reconnaissance lunettes complète ou écran verrouillé.

### API remise à l’orchestrateur

`OriaNavigationCoordinator(context, scope, onSpeech, onInstructionsInvalidated, onTrace, clock)` expose `state: StateFlow<NavigationUiState>` et :

- `setNetworkConsent(Boolean)`, `setForeground(Boolean)` ; consentement non persistant.
- `updateQuery`, `search(query, NavigationMode)`, `select(place)`, `confirmSelected(expectedId: String? = null)`, `startSaved`, `saveDestination`, `deleteDestination`.
- `pause`, `resume`, `stop`, `advanceSimulation`, `repeatFreshInstruction`, `onDangerPreemptedNavigation`.
- `canSpeak(speech)` : garde finale lecture seule, utilisable sur le worker audio ; contrôle ID exact, foreground, fermeture, expiration et âge du dernier GPS.
- `onSpeechResult(speech, completed)` : appeler sur Main après terminal réel ou abandon de la proposition pour que le moteur ne marque pas une instruction non jouée comme prononcée.

`OriaNavigationPanel(state, OriaNavigationActions(...))` est indépendant du Controller. Le host réalise la demande de permission GPS et la dictée Android corrélée ; le callback de confirmation reçoit l’ID exact sélectionné. Les actions de commande vocale globale restent au host. Permissions nouvelles : INTERNET, ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION ; aucun service de localisation en arrière-plan ni nouvelle dépendance Gradle pour ces modules.

### Tests livrés et limites

30 tests JVM sont présents dans `NavigationEngineTest`, `NavigationFusionTest` et `OriaVoiceInteractionTest`. Les nouveaux cas couvrent notamment route lente avec dix positions, annulation et retour tardif, génération après stop, consentement, précision/fraîcheur, silence GPS, arrivée anticipée, expiration PCM, répétition invalide et échec puis reprise de livraison. Aucun Gradle concurrent n’a été lancé par cet agent ; résultats finaux à consigner par l’orchestrateur après sa compilation.

La reprise ne prétend pas certifier la qualité cartographique, une marche réelle, l’écoute, le GPS écran éteint ou la reconnaissance vocale des lunettes. La persistance locale des destinations garde l’interface synchrone de Rayan ; ses petites écritures de préférence restent à observer pour leur coût si une latence est mesurée. Les contrats réseau et la traduction JSON du serveur doivent être exercés avec une fixture Android et une sonde publique bornée avant de revendiquer un trajet réel validé.

### Vérification ciblée après revue croisée

L’agent moteur a relevé un callback de localisation ancien encore attribuable après pause/reprise. Un jeton d’abonnement distinct de la génération de route est désormais incrémenté au démarrage et à l’arrêt du provider ; les deux callbacks (fix et indisponibilité) le vérifient. Un test conserve les callbacks précédents et les déclenche après une nouvelle position fraîche : état et permis vocal restent inchangés.

Le premier lancement global a également révélé qu’un premier fix directement proche de la destination pouvait répéter l’ancienne manœuvre de départ. Le moteur suspend maintenant toute consigne ancienne pendant la première confirmation d’arrivée ; le test de silence n’a pas été affaibli.

Exécution ciblée autorisée après arrêt du Gradle orchestrateur : `:app:testDebugUnitTest --tests '*oria.navigation.*' --tests '*oria.interaction.*'` : **30 tests, zéro échec/erreur**, compilation réussie. Log local : `validation/fusion-20260927/navigation-tests.log`. Aucune preuve terrain ni écoute ajoutée.
