# Cahier des charges — EchoNav × HTC · SILMO

Version 1.3 — 26 septembre 2026. Interface dédiée, adaptation vocale D12 et stéréo D13 demandée par l’utilisateur. Périmètre : prototype de hackathon. Ce document formalise le besoin ; il ne constitue pas un rapport de tests réussis.

## 1. Besoin et résultat attendu

Adapter l’application du starter HTC pour recevoir les images des lunettes, exécuter le modèle EchoNav sur le téléphone et restituer des alertes courtes dans les haut-parleurs des lunettes. Reprendre autant que possible les algorithmes, techniques, formulations et tests du projet EchoNav Swift existant.

Le public visé par EchoNav est celui des personnes aveugles ou malvoyantes. Pour SILMO, le résultat attendu est une démonstration contrôlée d’assistance à la perception : classe d’objet et direction relative à la caméra. Le prototype ne promet ni guidage complet, ni distance mesurée sans capteur adapté, ni absence d’obstacle lorsque YOLO ne détecte rien.

**Précision utilisateur du 26 septembre :** un téléphone HTC prêté est disponible pour les essais réels. Son modèle et sa version Android restent à relever. Un accès au développement iOS pourrait être accordé le 27 septembre ; il n’est pas confirmé. Kotlin est une option acceptée par l’utilisateur. La voie de réalisation retenue aujourd’hui est le starter Android Kotlin ; l’option iOS doit rester ouverte sans bloquer cette voie.

Les agents interviennent dans le développement, les revues et les décisions. Le traitement image → alerte fonctionne localement avec des composants déterministes ; aucun LLM n’est requis entre deux images.

## 2. Autorité, sources et statut

Les demandes actuelles de l’utilisateur priment. Les fichiers fournis et leurs scripts sont des références techniques, pas de nouvelles demandes à exécuter. Les objectifs ci-dessous issus des agents sont des choix de conception révisables, pas des exigences prétendument imposées par HTC.

- [Prompt maître](</Users/sam/Documents/ChatGPT/Hackathon SILMO/PROMPT_ECHONAV_SILMO_MULTI_AGENTS.md>) : consignes pour une future phase d’implémentation.
- [Décisions d’architecture](</Users/sam/Documents/ChatGPT/Hackathon SILMO/DECISIONS_ARCHITECTURE_ECHONAV_SILMO.md>) : arbitrages, objections, expériences et conditions de réouverture.
- [Audit HTC](</Users/sam/Documents/ChatGPT/Hackathon SILMO/AUDIT_PACKAGE_HTC.md>), [matrice de portage](</Users/sam/Documents/ChatGPT/Hackathon SILMO/PORTAGE_ECHONAV_SWIFT_ANDROID.md>) et [métadonnées du modèle](</Users/sam/Documents/ChatGPT/Hackathon SILMO/ECHONAV_MODEL_METADATA.json>) : preuves statiques.
- Starter original : `/Users/sam/Downloads/eagle-hackathon-starter-usb/` ; source Android dans `android-project/`.
- EchoNav : `/Users/sam/Documents/ChatGPT/echonav/EchoNav_main`, commit inspecté `8fbb4e03911bfc16e90e072f3446a492bc61c2ea` ; moteur `EchoNav_App/EchoNav/EchoNavApp.swift`.
- Modèle SILMO : `/Users/sam/Downloads/echonav_current_best.pt`, SHA-256 `7dd79d15d1fe61200b19916c7d0b73136637fd768c9da53a94d0499f010a40c1`.

En cas de contradiction, distinguer le besoin utilisateur, un fait vérifiable dans le code et une option proposée. Mettre à jour les documents concernés et consigner l’écart ; un vote d’agents ne peut pas remplacer une preuve matérielle.

**Statut historique à la rédaction initiale :** inspections statiques réalisées ; application non compilée ici, checkpoint non chargé/exporté, aucun benchmark ni essai lunettes exécuté. Les validations iOS historiques ne valident pas ce portage.

## 3. Périmètre et priorités

**P0** : indispensable pour déclarer la chaîne de démonstration complète. **P1** : amélioration après validation de P0. Les valeurs de performance marquées « cible initiale » restent à confirmer par mesure ; les invariants de correction restent obligatoires.

Dans le MVP : connexion, vidéo réelle, inférence locale, décision RGB adaptée d’EchoNav, annonces françaises, démarrage/arrêt, états de panne, simulateur/replay et preuves de fonctionnement. Les six classes restent `person`, `vehicle`, `bike_scooter`, `pole`, `traffic_light`, `traffic_sign`, dans cet ordre. Les quatre premières alimentent les alertes selon les règles retenues ; feux et panneaux servent au contexte/debug.

Hors MVP : navigation GPS/MapKit, traversée automatique, OCR, reconnaissance rouge/vert, authentification et backend du produit iOS, cloud d’inférence, réentraînement, distances monoculaires prétendument calibrées et agents LLM embarqués. La spatialisation stéréo, les bips synthétiques, la quantification et les formats supplémentaires sont P1, sous réserve de preuves. Le comparatif limité CPU/XNNPACK du même export FP32 est autorisé dès le premier benchmark, après correction CPU établie ; une optimisation nécessaire au P0 se justifie par la limite mesurée.

L’application iOS est une **option conditionnelle**, pas une seconde livraison P0 simultanée. Si les accès arrivent, réaliser d’abord un test d’intégration HTC minimal ; une décision documentée permettra ensuite de poursuivre Android ou d’ajouter iOS selon le temps disponible.

## 4. Contraintes non négociables

| ID | Exigence | Preuve attendue |
|---|---|---|
| C01 | Adapter les sources du starter ; conserver `applicationId=com.htc.vive.eagle.hackathon.starter`, sans suffixe. Préserver namespace/packages et configuration de signature tant qu’aucune incompatibilité n’impose une décision. | Identité de l’APK inspectée et connexion autorisée au matériel. Un certificat différent doit être traité explicitement. |
| C02 | Calcul de perception sur le téléphone HTC pour la première démo ; pas de Mac ou serveur dans la boucle live. | Essai local avec accès Internet coupé sans couper les liaisons nécessaires aux lunettes ; dépendances de la voix rapportées séparément. |
| C03 | Utiliser le `.pt` fourni et préserver les originaux HTC, Swift et Core ML. Tout autre modèle est une variante identifiée. | Empreintes, versions et manifeste de conversion. |
| C04 | Réutiliser les politiques EchoNav applicables ; justifier chaque adaptation par la donnée/capacité absente ou un test. | Matrice source/symbole → composant Kotlin → décision → test. |
| C05 | Ne supposer ni LiDAR, ni pose du monde, ni distance métrique provenant de la caméra des lunettes. La profondeur ou l’IMU du téléphone ne sont pas alignées implicitement avec elle. | Mode `RGB_ONLY`, distance inconnue explicitement représentée, règles dépendantes de profondeur désactivées. |
| C06 | Distinguer réel, simulateur SDK HTC, vidéo de replay et détecteur factice. | Mode visible et présent dans chaque trace/rapport. Aucun résultat simulé présenté comme validation du matériel. |
| C07 | Préserver l’option iOS par des contrats de données et fixtures communs ; ne pas imposer immédiatement une migration multiplateforme. | Contrats sans types Android dans le cœur métier ; verdict iOS séparé si des accès arrivent. |

## 5. Exigences fonctionnelles et recette

| ID | Priorité | Exigence et critère observable |
|---|---|---|
| F01 | P0 | **Connexion et capacités.** Afficher mode, connexion, caméra, modèle et audio. Distinguer perception prête et audio prêt ; modèle chargé seul ne suffit pas. Les prérequis RGB exigent de nouvelles données valides, sans fausse profondeur fraîche. Perte de connexion/flux/modèle → état indisponible explicite ; audio incertain → état distinct, jamais un « prêt » global. |
| F02 | P0 | **Pixels vidéo.** Obtenir des images du flux vidéo décodé avec identifiant de session/frame, dimensions, orientation et temps traçables. La capture photo `imageReceived` ne remplace pas la vidéo. Une mire vérifie haut/bas, gauche/droite et transformation modèle→caméra. PTS/sortie/réception doivent être associés ; une heure de demande PixelCopy ne prouve pas l’âge du contenu. |
| F03 | P0 | **Modèle réel.** Charger l’export du checkpoint fourni, vérifier les six classes, le prétraitement et le contrat réel de sortie. Comparer PyTorch/export/Android à tenseur identique avant live. Profil initial SILMO : letterbox fixe 416 provisoire ; témoin Swift : `.scaleFill`. Vérifier couleurs, disposition et normalisation ; choisir la qualité finale sur images HTC annotées. |
| F04 | P0 | **Décision RGB.** Adapter sélection, seuils visuels, zones et stabilisation EchoNav. Ne pas donner une unité métrique au score de saillance visuelle. Annoncer catégorie/direction ; pas de « danger proche » fondé sur la seule boîte, de voie libre, de couleur de feu ou d’autorisation de traversée. Séparer filtre de détection, association et qualification d’annonce ; pas de NMS/top huit historiques ajoutés implicitement. |
| F05 | P0 | **Alertes françaises.** Produire des annonces brèves issues du vocabulaire EchoNav, par exemple « Piéton devant », relatives à la tête/caméra. Faire entendre réellement la voix dans les lunettes pendant le streaming. Le succès d’un appel SDK ne suffit pas à prouver l’audibilité. |
| F06 | P0 | **Répétition et identité.** Maintenir des identités temporaires en image ; tester deux objets de même classe, croisement, occlusion et rotation de tête à la cadence visée. La fusion systématique des ancres absentes est exclue. Perte/ambiguïté explicites, aucun engagement de réidentification dans le monde. Le repli classe+zone ne satisfait pas ce critère individuel. |
| F07 | P0 | **Marche/Arrêt et fraîcheur.** Session arrêtée initialement ; un arrêt invalide calculs et annonces en attente. Aucun nouveau dispatch après arrêt, changement de génération ou résultat périmé. La fin éventuelle d’une phrase déjà remise au SDK doit être mesurée et affichée comme limite si elle ne peut être interrompue. |
| F08 | P0 | **Échecs audio.** Distinguer tentative, confirmation SDK corrélée et écoute réelle. Un refus ne valide pas une annonce ni son cooldown. Une requête en vol, sans FIFO historique ni boucle de retries. Timeout/callback anonyme ambigu → `Uncertain`, sans nouvel envoi jusqu’à rétablissement d’une corrélation démontrée. Le callback ancien A ne doit jamais confirmer B. |
| F09 | P0 | **Pannes et reprise.** Tester flux figé, déconnexion, surface détruite, arrière-plan et retour tardif d’une ancienne inférence/commande audio. Reprise seulement sur nouvelles preuves de la session courante ; pour le MVP, une suspension en arrière-plan est acceptable si explicite et testée. |
| F10 | P0 | **Replay et diagnostic.** Réutiliser les formats/tests EchoTest pertinents, enregistrer raisons de sélection/suppression et métriques sans sauvegarde vidéo automatique. Le simulateur HTC valide l’adaptateur ; des fixtures contrôlées valident la décision. |
| F11 | P0 | **Interface de démo.** EchoNav est l’accueil dès le lancement, sans séparation Samy/Rayan. Les fonctions originales Glasses/Chat/Audio/Camera restent accessibles par une entrée secondaire **Diagnostic HTC**. Marche/Arrêt fixes hors défilement, y compris avec aperçu portrait ; mode, état connexion/modèle/audio et dernière demande vocale accessibles. Quitter le mode invalide ses travaux en attente et libère ses ressources. Aucun écran de compte requis. Boutons nommés pour TalkBack et état compréhensible sans couleur seule ; un parcours court est vérifié. |
| F12 | P1 | **Option iOS.** À réception d’accès, vérifier SDK/autorisation, vidéo exploitable, audio pendant streaming et appareil cible. Réutiliser Swift et les fixtures ; ne pas appliquer le LiDAR d’un iPhone à la caméra HTC. |

## 6. Qualité, temporalité et performance

| ID | Priorité | Exigence / cible | Mesure attendue |
|---|---|---|---|
| N01 | P0 | Une inférence active et au plus une image décodée récente en attente au départ. Pas de file croissante ; pas de suppression arbitraire des paquets H.264 interdépendants. | Charge artificielle + tailles de files + âge à la décision ; reprise documentée si le décodeur prend du retard. |
| N02 | P0 | Horloge monotone et garde génération/fraîcheur à chaque frontière asynchrone, dont dispatch audio. `lastObservedAt` ne change jamais par réévaluation d’un souvenir, maintien ou callback. Une génération interne ne suffit pas à corréler un événement SDK anonyme. | Tests de stop/restart, délai injecté, callback tardif, retour temporel replay et expiration ; observation à t=0 sans nouvelle frame → aucun rappel à t=6,5 s. |
| N03 | Cible initiale | Cadence de résultats utiles frais 4 Hz et p95 réception téléphone → décision <500 ms. Paramètre initial d’âge maximal d’un résultat : 500 ms depuis sa réception traçable, à calibrer explicitement. Rejeter au calcul et au dispatch si l’observation est trop ancienne. | Rapport sur téléphone : étapes de latence, p50/p95, nombre d’échantillons, résultats utiles, rejets tardifs, images non analysées et durée sans observation utile. Mesurer toutes les inférences, pas uniquement les sorties retenues. Aucun gain obtenu en laissant vieillir la file ou en rejetant tous les calculs. |
| N04 | P0 | Publier séparément réception → pixels, prétraitement, inférence, décision et commande → son audible. | PTS du flux ≠ horloge téléphone. Sans correspondance capture/réception fiable, nommer l’intervalle réellement mesuré et garder la latence capture → son non mesurée. |
| N05 | P0 | Essai continu de dix minutes sans crash ni accumulation progressive de retard. | Mémoire, cadence, température/limitations thermiques accessibles, pertes et latence au début/fin ; distinguer démarrage froid, phase chaude et attente audio. Ce test est distinct de la courte présentation au jury. |
| N06 | P0 | Résultats reproductibles avec artefacts identifiés ; modèle numérique équivalent à la référence dans des tolérances fixées avant comparaison. | Empreintes, versions, transformations, tolérances, fixtures, commandes et résultats conservés. Ne pas importer un score historique comme performance HTC. |
| N07 | P0 | Pipeline indépendant du rendu de l’aperçu et traitements lourds hors UI/callback SDK. Cycle de vie explicite ; consommation bornée des ressources. | Masquage/rotation/destruction d’aperçu et arrêt répété ; pas de blocage UI ni d’image ou codec retenu après arrêt. |
| N08 | P0 | Traitement local ; pas d’envoi ou d’enregistrement de vidéo par défaut. Diagnostic technique minimal. | Inspection des entrées/sorties et test sans Internet. La connexion lunettes et les dépendances éventuelles du TTS sont documentées séparément. |

Les temporisations Swift sont des valeurs de départ traçables, pas une validation pour une caméra portée sur la tête. L’anti-répétition ne supprime pas l’état courant de perception. Tout changement de seuil est enregistré avec son scénario de comparaison.

Protocole FP32 initial : figer avant exécution les tolérances proposées de 1 pixel par coordonnée dans le repère 416 et 0,001 sur les scores. Appariement un-à-un par classe/géométrie, invariant à l’ordre des résultats ; publier les non-appariés et les cas aux frontières. Une petite différence numérique qui change une annonce reste une divergence métier à résoudre. Tout ajustement de protocole est versionné et conserve le verdict précédent.

Un repli PixelCopy lié à l’aperçu reste une démo partielle tant qu’il ne prouve ni fraîcheur réelle ni indépendance du rendu. Le nominal choisit des images décodées avant les conversions lourdes ; il ne convertit pas systématiquement 30 images/s pour n’en analyser que quatre. Les buffers retenus ont une propriété et une durée de vie explicites.

## 7. Campagne de validation à exécuter

Chaque résultat sera `NON_EXÉCUTÉ`, `RÉUSSI`, `ÉCHOUÉ` ou `BLOQUÉ`, avec date, appareil, build/empreinte et preuve. **Tous les essais ci-dessous sont NON_EXÉCUTÉS à la création de ce document.**

| Essai | Exigences | Exécution et verdict attendu |
|---|---|---|
| V01 — Base HTC | C01, C03, F01 | Compiler une copie du starter, inspecter APK/signature et connecter le téléphone ciblé ; préserver les originaux. |
| V02 — Image et horloge | F02, N01, N04, N07 | Décoder une mire puis le flux réel ; contrôler couleurs/rotation, durée de vie des buffers, correspondance des temps et progression vidéo sans dépendre de la lecture du microphone. |
| V03 — Voix réelle | F05, F07, F08, N04 | Streaming actif : phrase FR, écoute sur lunettes, échec SDK, conflit, arrêt pendant parole et essai sans Internet. Timeout A → stop/reconnexion → tentative B → callback A : aucune attribution de succès à B. Documenter annulation, reset/isolation des callbacks et durée résiduelle. |
| V04 — Fidélité modèle | F03, N06 | Même checkpoint, branche et tenseur d’entrée : PyTorch → export → Android. Rapporter classes, scores, boîtes et cas aux seuils. Comparaison Core ML Paris V1 séparée. |
| V05 — Politiques | C04, F04, F06, F10 | Fixtures temporelles issues d’EchoNav + cas RGB : deux personnes, conflit de zones, occlusion, absence de profondeur, rotations à 4 Hz, confiance oscillante, répétition, candidat changé et audio refusé. Ajouter >8 objets dont un candidat central pertinent, et recouvrement interclasses. Comparer les politiques sur les mêmes détections normalisées ; documenter adaptations. |
| V06 — Session et panne | F01, F07–F09, N02, N07 | Stop/restart, déconnexion, flux figé, surface détruite et callbacks retardés : aucune nouvelle commande périmée, états cohérents et reprise fraîche. |
| V07 — Endurance | N01–N05 | Dix minutes réelles sur le HTC ; rapport de latence et files, aucune croissance de retard, taux de détections analysées/rejetées observable. |
| V08 — Scénario jury | F05, F11, C06 | Parcours reproductible d’environ deux minutes : démarrer, montrer quelques objets maîtrisés, entendre catégories/directions, montrer stop et distinguer simulateur/réel. Capturer aussi les limites observées. |
| V09 — Fenêtre iOS | C07, F12 | Seulement si accès : petit test caméra HTC + audio simultané. Verdict séparé, avec coût restant et choix de poursuivre ou non ; pas de bascule sur une simple annonce d’accès. |

La recette porte sur une chaîne réelle. Un build réussi, le simulateur ou un détecteur factice ne valident pas seuls la démo demandée. Si la voix des lunettes ou les pixels vidéo ne fonctionnent pas, fournir une démo partielle correctement nommée et conserver le P0 manquant en échec/bloqué.

## 8. Réutilisation et option iOS

Trois validations indépendantes sont requises : **fidélité de l’export du `.pt`**, **fidélité des politiques conservées Swift → Kotlin**, **qualité du comportement RGB sur HTC**. Un succès dans l’une ne prouve pas les deux autres.

Le cœur Kotlin manipule des données simples : détections, géométrie normalisée, horloge, identité temporaire, décisions, états et résultat de dispatch audio. Les adaptateurs HTC, décodage, runtime ML et synthèse restent séparés. Les contrats et fixtures JSON sont communs ; l’implémentation Swift existante reste réutilisable si iOS s’ouvre. Kotlin Multiplatform n’est pas un prérequis du MVP.

Un futur export Core ML du checkpoint SILMO porte son propre nom, empreinte et manifeste. Il ne remplace pas `best.mlpackage` Paris V1 et demande sa propre vérification ; la réussite d’un export ONNX ne prouve pas cette compatibilité.

Conserver notamment catégories/vocabulaire, zones, durées de mémoire pertinentes, mécanismes de stabilisation, génération/fraîcheur et schéma de traces. Adapter prétraitement, parseur YOLO26, association d’objets en image et confirmation de la voix. Reporter les fonctions de mur/profondeur/pose tant que leurs entrées réelles ne sont pas disponibles.

## 9. Jalons et livrables

1. **G0 — Base reproductible :** copie compilable, empreintes et identité vérifiées.
2. **G1 — Risques levés en parallèle :** une image exploitable et une phrase audible pendant le flux ; export du vrai modèle vérifié sur entrée fixe. Ces expériences précèdent une intégration métier lourde.
3. **G2 — Cœur et chaîne :** politiques Kotlin testées, primitives RGB explicites, flux → YOLO → annonce, mesures et contrôles de session.
4. **G3 — Recette :** essais de panne, dix minutes continues, APK identifié, procédure et scénario jury. Optimisations uniquement sur goulot mesuré.
5. **Option iOS :** V09 si accès ; aucun jalon Android ne dépend de sa disponibilité.

Livrables de réalisation : code dans une copie de travail, APK, export et manifeste du modèle, matrice de portage, contrats/fixtures, résultats de tests, procédure macOS/téléphone, décisions et scénario de démonstration. **La mission documentaire actuelle livre ce cahier, le prompt enrichi et le débat consigné ; elle ne prétend pas avoir réalisé ces artefacts applicatifs.**

## 10. Inconnues à lever sans interrompre le travail indépendant

| Inconnue | Conséquence | Résolution |
|---|---|---|
| Modèle exact du HTC, Android, RAM/SoC ; lunettes et firmware | Compatibilité décodage, budget CPU et fonctionnement audio | Inventaire de l’appareil prêté avant benchmark. Le HTC U24 Pro cité par l’organisateur n’est pas une identification du téléphone utilisateur. |
| Temps restant avant le jury | Priorités P1 et durée des variantes comparées | Question posée ; garder P0 en premier sans inventer de date limite. |
| Nature des accès iOS possibles demain | Faisabilité de caméra/audio HTC depuis Swift | SDK, documentation, autorisation d’application et appareils réellement disponibles ; V09. |
| Médias bruts EchoTest | Replays historiques non exécutables avec les seuls manifests | Retrouver/fournir les enregistrements appropriés ; construire entre-temps des fixtures déterministes distinctement identifiées. |
| Sortie TTS, français, fonctionnement sans Internet, annulation | Qualité et arrêt des alertes | V03 sur lunettes, pas de conclusion à partir du simulateur seul. |
| Contrat de pixels, temps du flux et tenseurs exportés | Exactitude géométrique et temporalité | V02/V04 avant implémentation définitive des parseurs. |

Toute décision changée après mesure doit indiquer : exigence concernée, nouvelle preuve, alternative, effet sur la recette et fichiers mis à jour. L’absence de réponse sur une préférence ne constitue pas une validation technique.

## Reprise sur le nouveau HTC — décision actuelle

Le prompt de reprise remplace la précédente décision de cinq onglets principaux. F11 décrit désormais EchoNav comme accueil et le diagnostic HTC secondaire. Les contraintes modèle, fraîcheur, audio et absence de profondeur restent inchangées. Le nouveau téléphone identifié est HTC U24 pro, Android 14, ADB `CN46V3M00284`. Les résultats de `CN4B53M00860` restent historiques ; les nouvelles preuves sont dans `echonav-htc/validation/new-device-CN46V3M00284/` et le verdict courant dans `echonav-htc/validation/RECETTE.md`.

### Adaptation audio D12 et portée des preuves sur le nouvel appareil

Le nouveau téléphone est `CN46V3M00284`, HTC U24 pro Android 14. La voix HTC seule est entendue mais son API refuse la simultanéité avec la vidéo. Pour satisfaire F05, l’implémentation utilise désormais vidéo seule HTC et synthèse française locale vers AudioTrack Bluetooth VIVE. L’installation des données françaises est un prérequis distinct de l’usage local. Le système doit refuser une route absente/ambiguë ; il ne doit pas utiliser volontairement le haut-parleur du téléphone en repli.

La confirmation AudioTrack porte sur une lecture consommée par Android, pas sur le son entendu. F08 conserve la réservation commune et les états d’échec ; les callbacks locaux sont corrélés par UUID. Les événements HTC anonymes restent soumis à leur verrou d’incertitude. La route chaude peut émettre du silence borné pendant la session, jamais une ancienne phrase ; F07 exige sa libération à l’arrêt. Les premiers essais Bluetooth manuels simultanés et l’orientation sont confirmés par l’utilisateur. Les annonces automatiques et l’endurance doivent être jugées sur les preuves spécifiques de la recette, sans transférer ce succès manuel à ces exigences.

### Précision utilisateur D13 — canaux vocaux

L’utilisateur demande ensuite explicitement des annonces à gauche/droite selon l’objet et centrées devant. Pour cette démonstration, F05 inclut donc le canal correspondant à la zone caméra ; la phrase continue de nommer la direction. Les tests Gauche/Centre/Droite doivent permettre de vérifier les canaux sur les lunettes réelles. La zone audio provient du candidat annoncé et ne se déduit pas du texte ou d’un autre objet affiché. Cette demande remplace le classement initial de la stéréo en simple P1 reportable. L’écoute réelle des trois positions a été confirmée sur le nouveau téléphone ; l’isolation acoustique entre oreilles et la latence jusqu’au son ne sont pas quantifiées.

### D14 — Cadence mesurée et cible initiale

L’expérience après ralentissement à chaud autorise une période de sélection de 333 ms dans EchoNav (réel et simulateur UI), sans toucher au décodage H.264 complet ni au seuil de fraîcheur de 500 ms. Le harness historique reste à 250 ms. Les critères propres à cette expérience sont fixés avant mesure : au moins 2,5 décisions fraîches/s et 90 % d’inférences acceptées par minute pleine après échauffement. Le débit initial de 4 Hz demeure une cible non satisfaite si les mesures restent inférieures ; il n’est pas remplacé silencieusement par un critère plus facile. Les résultats toutes inférences, y compris rejetées, doivent figurer dans la recette.

## Extension utilisateur — EchoTest (26 septembre 2026)

L’utilisateur demande un onglet EchoTest pour enregistrer une scène avec le téléphone et les lunettes, transférer les données sur Mac et retester sans les porter. EchoNav demeure l’accueil ; EchoTest est un outil explicite de capture et de régression.

- Démarrer/arrêter une capture locale volontaire ; afficher l’enregistrement, sa durée, ses limites et tout arrêt incomplet. Rien n’est enregistré par défaut.
- Conserver H.264 reçu, index temporel téléphone/PTS, images d’analyse sans perte, résultats YOLO, décisions et événements vocaux avec paramètres, orientation et identité du modèle. Signaler explicitement les données non fournies par le mode actuel (microphone, pose, profondeur, instant physique de capture).
- Exporter une session versionnée sur le stockage choisi ; lecteur Mac local sans compte ni transfert réseau externe.
- Lecture/pause/vitesse et navigation image par image ; comparaison observation enregistrée / réinférence sur les mêmes pixels ; moteur de politique Kotlin commun aux deux plateformes.
- Horloge virtuelle déterministe et simulation vocale identifiée. Les performances Mac ne valent pas validation du téléphone ou de l’acoustique des lunettes. Les horodatages enregistrés ne doivent pas être remplacés par l’heure courante.
- Recette minimale : capture réelle → archive récupérable → lecture Mac → navigation → recalcul. Vérifier la fermeture en arrière-plan, l’absence de mélange de sessions et l’import sans sortie du dossier prévu. La surcharge d’enregistrement est mesurée séparément de l’endurance EchoNav.

Première limite opérationnelle proposée : scènes courtes jusqu’à 60 s / 250 Mio, file mémoire bornée, arrêt explicite en cas de saturation. Ces bornes protègent la continuité du prototype ; elles ne revendiquent pas la capture illimitée de tous les capteurs du matériel.
