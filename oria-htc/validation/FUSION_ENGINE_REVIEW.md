# Revue fusion — moteurs, profondeur et sessions

Date : 27 septembre 2026. Revue statique, sans modification du runtime, sans Gradle, ADB ou nouvelle validation matérielle.

## Références et périmètre

- Base conservée : `b650dad8e96ef7a9b3ca8bd26c8f04471dedf2db` (`main`, Oria 1.7).
- Apport examiné : `40e8018` puis `96d524a41986f3b6799c1743055a7a7e4f18fec9` (`origin/rayan_work`). Le second commit ne change que build/UI/rapport ; les moteurs viennent du premier.
- Ancêtre commun : `f3454f6`, antérieur aux obstacles Android et à l'assistance unifiée. Une absence dans la branche de Rayan n'est donc pas une suppression délibérée validée.
- Sources lues : `RAYAN_WORK_MERGE.md`, R01–R04, R11 et code/tests associés. Leurs nombres de tests sont des preuves historiques déclarées par cette branche, pas des tests exécutés sur `fusion`.

## Décision recommandée

Conserver le runtime 1.7, son service automatique, la perception à deux workers, ses deux politiques vocales et son arbitrage équitable. Reprendre d'abord les ajouts de diagnostic au moteur RGB ; intégrer ensuite les contrats de résolution en comparaison silencieuse seulement, après fermeture des problèmes ci-dessous. Ne pas remplacer `OriaController.kt` ni changer le modèle de profondeur par une fusion intégrale.

1.7 apporte déjà : YOLO XNNPACK sur les images décodées à intervalle 333 ms ; Depth Anything V2 Small FP32 252 sur un worker séparé, CPU4, une image proposée sur deux ; files de taille 1 après décodage ; fraîcheur YOLO 500 ms et obstacles 1 500 ms ; phrase obstacle devant/avant-gauche/avant-droite ; stéréo 70/30 ; service automatique sans limite de durée. Les images H.264 dépendantes ne sont pas supprimées avant décodage.

La preuve 1.7 existante est décrite dans `UNIFIED_ASSISTANCE_20260927.md` : deux sessions SDK de 4 s avec les deux modèles, absence de soumission audio simulée et de publication après arrêt. Ce n'est pas une preuve de veille physique ni d'écoute de la fusion sur lunettes.

## Matrice de reprise par fichier

Les chemins de code sont relatifs à `android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/`.

| Fichier / ensemble Rayan | Décision | Apport, limite et preuve nécessaire |
|---|---|---|
| `core/Contracts.kt` | Partiel | Alias `DetectionFrame.generation = sessionId` utile pour adaptateurs ; nommer explicitement session vidéo et époque interne du moteur pour éviter leur confusion. Aucun changement de format des captures historiques requis. |
| `core/RgbAlertEngine.kt` | Partiel prioritaire | Expose tous les candidats frais confirmés, retraits de pistes avec causes, état visible/occulté, session/époque/frame. Les seuils, suivi par défaut et politique d'annonce ne changent pas dans le diff examiné. À reprendre avec compatibilité des constructeurs et preuve d'égalité des sorties préexistantes. |
| `core/DangerResolution.kt` — contrats | Reprise isolée possible | Provenances, champs relatifs/métriques séparés, raisons de rejet et propriétaire sont utiles. Conserver `metricDistanceMeters=null` pour la caméra Eagle. Ne pas assimiler candidat mémoire et observation fraîche. |
| `core/DangerResolution.kt` — politique Swift pure | Reprendre pour comparaison/fixtures | Rapport versionné : 10 fonctions de décision sur fixtures concordent avec des corps Swift extraits. Cela ne prouve ni le moteur temporel complet, ni la qualité RGB, ni son intégration vocale. |
| `core/DangerResolution.kt` — stabilisation/adaptateur | Partiel, pas de promotion live | Génération, horloge, scores entre sources, mémoire et synchronisation restent à corriger/qualifier. L'adaptateur ne couvre que le corridor central, pas nos trois zones d'obstacles. |
| `core/DepthEvidence.kt` | Partiel expérimental | Agrégation robuste dans une boîte et indisponibilité explicite utiles pour diagnostics. Ne pas substituer ses sorties aux géométries/politiques actuelles. Historique par piste sans retrait explicite, répétition des tris et tendance relative demandent travail avant sessions longues. |
| `ml/MidasV21DepthEstimator.kt` + manifeste/poids MiDaS | Conserver notre modèle ; ne pas reprendre live | Autre modèle 256, autre prétraitement bilinéaire ; R03 reconnaît l'écart avec son prétraitement historique et mesure seulement Mac. Pas de comparaison probante avec notre modèle déjà essayé par l'utilisateur. Peut devenir une expérience séparée ultérieure. |
| `core/DepthObstacleGeometry.kt`, `DepthObstaclePolicy.kt`, `FusionVoiceArbiter.kt` présents seulement sur main | Conserver entièrement | Leur absence côté Rayan vient de sa base plus ancienne. Préserver confirmation temporelle, tickets invalidables, cooldown après livraison et absence de famine entre sources. |
| `ml/OnnxDepthDetector.kt`, `DepthImageProcessing.kt` présents seulement sur main | Conserver entièrement | Contrat 252, hash du modèle et parité numérique déjà contrôlés ; aucune raison comparative de remplacement. |
| `lifecycle/OriaRuntimeState.kt` | Partiel pour diagnostics | États et dépendances utiles, mais `DEPTH` optionnel et `active()` RGB seul ne décrivent pas notre assistance unifiée. Adapter sans allouer une deuxième génération ni réutiliser une horloge unique pour deux workers asynchrones. |
| `lifecycle/PocketSessionService.kt`, `PocketSessionPolicy.kt` | Conserver main | La version Rayan réintroduit `pocketEnabled`, retire `canPrepare` et ne vérifie qu'un modèle. Régression face à la demande utilisateur et aux contrôles 1.7 modèles/voix/visibilité/stockage. |
| `OriaController.kt` | Refuser remplacement complet | Importer ultérieurement des blocs ciblés de diagnostic/contrats. La version Rayan conserve profondeur optionnelle, calcul séquentiel, repère manuel et voix RGB ; elle ne contient ni notre worker depth, ni ses tickets ni ses trois phrases. |
| `video/VideoContracts.kt` | Partiel si utile | Alias de génération diagnostique possible, sans modifier propriété des pixels, horloges, corrélation PTS ni files après décodage. |
| `video/OriaVideoDecoder.kt` | Conserver main | Le diff retire des possibilités d'échantillonnage ajoutées récemment. Aucun gain mesuré justifiant de les enlever ; aucun changement transport requis par cette fusion. |
| `audio/OriaAudioScheduler.kt` | Revue audio indépendante requise | Priorités navigation/commandes peuvent compléter le transport unique ; doivent accepter les tickets depth avec leur limite 1 500 ms, conserver 70/30 et jamais préempter à partir d'une mémoire non fraîche. Ne pas confondre cette reprise avec la résolution géométrique. |
| Tests RGB/session/R02 de Rayan | Partiel | Reprendre les scénarios supplémentaires avec la même politique par défaut. Le suivi V2 reste candidat, aucune promotion. Adapter les cas « verrouillage sans poche » devenus anciens. |
| Fixtures Swift/R04 et générateur | Reprendre séparément avec attribution de portée | Garder hash/source et distinguer résultats anciens de nouvelles exécutions. Le générateur écrit des résultats dans les fixtures ; prévoir sorties de validation dédiées afin de ne pas modifier les références lors d'un test. |

## Écarts bloquant une promotion immédiate

### 1. La résolution sélectionnée ne choisit pas effectivement la phrase

Dans Rayan `OriaController.kt:466–470`, seule `evaluation.eligibleAlert` issue de `RgbAlertEngine` appelle `offerDangerSpeech`. À `927–964`, texte, zone et ticket restent ceux de cette alerte RGB. `resolution.selected` intervient dans `dangerPriority`, pas dans la sélection du texte ou du propriétaire annoncé. Une profondeur générique sans objet YOLO ne crée donc pas sa propre phrase par ce chemin.

C'est un motif concret pour conserver notre voix obstacles indépendante. Le titre « moteur complet » dans un rapport ne démontre pas une livraison des décisions de toutes ses sources.

### 2. Garde de session manquante après une suspension supplémentaire

Rayan `OriaController.kt:350` contrôle génération et observation après l'inférence. À `379–385`, un nouveau `withContext(worker)` calcule les preuves de profondeur, puis `dangerEngine.resolve` et les mises à jour d'interface arrivent sans nouveau contrôle de session après cette suspension. Un arrêt/redémarrage pendant ce calcul peut faire publier un snapshot d'une ancienne session. Les dernières gardes audio limitent l'envoi vocal, mais ne réparent pas ce snapshot tardif.

Expérience requise avant reprise : bloquer le calcul des preuves, arrêter/redémarrer, libérer l'ancien résultat ; aucun état courant, candidat, motif sonore ou demande vocale ne doit être affecté.

### 3. Le moteur de résolution ne refuse pas une ancienne génération ni une horloge inversée

`DangerResolutionEngine.resolve:253` réinitialise sur n'importe quelle génération différente, y compris inférieure. Il ne valide pas non plus l'ordre de frame ni l'horloge d'évaluation. Le test existant vérifie un passage vers une génération supérieure, pas un callback ancien après ce passage. Le Controller doit rester l'autorité ; le moteur diagnostic devra rejeter ces entrées, sans réinitialiser la session récente.

Le champ `RgbEvaluation.generation` ajouté par Rayan est l'époque interne de `RgbAlertEngine`, incrémentée indépendamment ; `sessionId` reste la session vidéo. `OriaDangerAdapter.input` utilise actuellement la première. Ces nombres peuvent diverger après remplacement de moteur : ne pas les afficher comme une seule identité.

### 4. Mémoire/stabilisation n'est pas fraîcheur vocale

La mémoire visuelle accepte 2 850 ms ; les durées de maintien/relâchement peuvent conserver un ancien propriétaire après disparition de sa preuve. `stabilize()` peut retourner cette sélection même quand elle n'est plus dans les candidats acceptés. Cela peut être utile au diagnostic, mais ne donne pas le droit à une nouvelle alerte YOLO à 500 ms. Les bips Rayan consomment la sélection et doivent eux aussi respecter une preuve courante avant promotion.

De plus, les candidats visuels sont mémorisés avant validation d'âge/confiance (`DangerResolution.kt:264–275`). La mémoire ne doit pas transformer une entrée refusée en autre source plus tard sans contrat explicite.

Tests manquants : candidat sélectionné périmé mais tenu ; apparition d'un concurrent frais pendant maintien ; disparition en frames vides ; ancienne mémoire après changement de session ; aucune réservation/émission sur la seule mémoire.

### 5. Échelles non comparables pour notre fusion RGB + obstacles

`OriaDangerAdapter` utilise les scores visuels RGB (biais catégorie et indices de boîte, généralement au-delà de 1) face à la proximité générique bornée `[0,1]`. `shouldPreferSemantic` ajoute encore une marge de 0,22. Cela favorise structurellement le sémantique sans démontrer que l'obstacle simultané sera annoncé. Le fallback n'a que `CENTER` ; notre perception annonce aussi avant-gauche et avant-droite.

Autre différence : `hasRepeatedVisualSemanticContext = current.size >= 2` compte deux candidats simultanés, pas plusieurs observations d'un même contexte. Ne pas l'interpréter comme une confirmation temporelle. Conserver d'abord notre arbitrage équitable et mesurer ensuite les divergences sur les scènes réelles disponibles.

### 6. Risque de latence et de coût croissant

Rayan exécute YOLO puis MiDaS sur le même worker, avant de rejeter l'ensemble à 500 ms. Une profondeur lente peut donc faire perdre une détection YOLO qui aurait été fraîche seule. 1.7 garde les deux modèles indépendants et des bornes différentes. Aucune nouvelle mesure combinée Eagle/HTC de MiDaS ne justifie ce retour au séquentiel.

`RelativeDepthEvidenceAdapter.analyze` trie toute la carte pour chaque piste puis pour le corridor, et conserve un historique par identifiant qui n'est supprimé qu'au reset ou à une nouvelle analyse invalide de ce même identifiant. Une succession de pistes mortes durant une session sans limite peut donc faire croître cet historique. À adapter : statistiques globales calculées une fois par carte et retrait des historiques à la retraite des pistes, avec test de churn prolongé.

La tendance entre proximités renormalisées image par image peut changer avec le cadrage ; elle n'est ni vitesse métrique ni TTC. Son activation par défaut reste refusée.

## Compatibilité Swift, RGB et profondeur

- Les branches nécessitant mètres, occupation métrique et pose monde doivent rester inactives pour l'Eagle. La séparation de provenance de Rayan est bonne et mérite d'être reprise.
- Le rapport `danger_resolution_parity_report.json` fixe 10 résultats de politique booléenne, corps Swift extraits et hashes. Il ne compare pas la totalité de `DangerResolutionEngine`, ses transitions temporelles, ni notre Depth Anything.
- Les 18 fixtures RGB historiques et le suivi `LEGACY_IOU` restent les références de comportement. Les preuves R02 n'autorisent pas la promotion de V2 : neuf identifiants supplémentaires sur la scène historique sont encore une limite documentée.
- Une observation depth fraîche peut arriver après une observation YOLO plus récente. Le `lastEvidenceAtMs` unique de `OriaRuntimeState` ne doit pas être partagé aveuglément entre ces sources, sinon un résultat valide serait rejeté à cause de l'autre worker. Suivre ordre et âge par source, tout en gardant une seule génération de session.

## Premier commit isolé proposé

**Ajouter la visibilité des candidats et retraits RGB sans changer les annonces.**

Portée : parties diagnostic de `RgbAlertEngine.kt`, contrats nécessaires, tests de session et de retrait ; aucun changement de Controller, modèle, seuil, choix V2, audio, service ou UI. Conserver les champs et constructeurs historiques compatibles ; expliciter `sessionId` et l'époque du moteur.

Recette exécutable attendue :

1. Tests cœur existants et nouvelles assertions de session/observation/retrait.
2. Entrées permutées, expiration, capacité maximale et ambiguïté V2 diagnostiquées sans modifier l'association par défaut.
3. Comparaison des champs historiques des décisions, tickets, annonces et silences avec la base 1.7 sur toutes les fixtures existantes et les deux captures privées disponibles, sans publier les captures.
4. Arrêt/reprise : aucun candidat de l'ancienne session ; époque moteur distincte de la session vidéo si un moteur est recréé.
5. Build/JVM de l'application ; une recette d'écoute n'est pas nécessaire pour ce premier changement de diagnostic sans nouveau dispatch.

Ensuite seulement : ajouter les classes pures de résolution avec tests adversariaux ; les faire tourner sur replay Mac en comparaison silencieuse, sans influencer la voix. Une promotion live sera une décision séparée fondée sur écarts observés, latence et annonces génériques conservées.

## Statut de cette revue

Implémentation : aucune, hors ce rapport. Tests nouvellement exécutés : aucun. Constats : lecture du diff et des corps de fonctions aux références indiquées. Aucun résultat historique n'est réattribué à la branche `fusion`, au HTC actuel ou à une écoute humaine. Les changements critiques devront être relus après chaque petit commit.

## Tranche diagnostique intégrée après accord de l'orchestrateur

Les constats ci-dessus restent l'audit de la branche Rayan d'origine. La tranche suivante corrige les problèmes nécessaires à un module de diagnostic isolé ; elle ne promeut aucune nouvelle décision vocale.

- `RgbAlertEngine` expose maintenant candidats courants confirmés, retraits et états visible/occulté. Les nouveaux paramètres sont ajoutés en fin de constructeurs avec valeurs par défaut ; les signatures d'utilisation historiques restent compatibles. `generation` est documenté comme l'époque interne RGB, différente du `sessionId` vidéo.
- `DangerResolutionEngine` nécessite un `reset(sessionId)` explicite ; ni une entrée plus ancienne, ni une entrée d'une génération inconnue ne peut démarrer/reset le moteur. `stop()` le rend inactif. Une horloge d'évaluation inversée ou une observation future est rejetée sans mutation.
- L'ordre des observations est suivi séparément pour RGB et profondeur relative. Les durées sont 500 ms pour la preuve RGB et 1 500 ms pour la preuve relative. Le contexte d'obstruction est lui aussi filtré par âge et confiance avant les fonctions de politique.
- `selected` demeure un propriétaire **diagnostique**, éventuellement mémorisé/maintenu. `freshSelected` exige une preuve courante acceptée du même propriétaire et du même instant ; aucune mémoire seule ni ancien maintien ne peut y apparaître. Aucun de ces champs n'est une réservation audio.
- Seuls des candidats visuels acceptés alimentent la mémoire, limitée à 128 propriétaires. L'adaptateur utilise `evaluation.sessionId` et reconnaît un contexte répété à partir de confirmations de piste, pas du simple nombre d'objets.
- `DepthEvidence.kt` reprend seulement les contrats nécessaires, aucun estimateur MiDaS ni l'ancien adaptateur de statistiques/coûts/historique. Notre pipeline depth demeure inchangé.
- Les fonctions pures `DangerResolutionPolicy` sont conservées pour les fixtures Swift. La stabilisation et l'adaptateur modifiés ne sont pas déclarés en parité intégrale Swift.

### Contrat d'appel

Appels sérialisés sur le même propriétaire que le Controller :

```kotlin
val diagnostic = DangerResolutionEngine()
// Après création de la session vidéo :
diagnostic.reset(videoSessionId)
// Après évaluation RGB fraîche, sans nouvelle suspension :
val input = OriaDangerAdapter.input(evaluation, emptyMap(), null, evaluatedAtMs, false)
val snapshot = diagnostic.resolve(input)
// Tracer ce snapshot uniquement. Les tickets/annonces existants restent les seuls émetteurs.
// À chaque arrêt/invalidation :
diagnostic.stop()
```

Ne pas remplacer le `reset` de Controller par celui d'un callback. Si des preuves depth asynchrones sont ajoutées ultérieurement, fournir leur véritable `observedAtMs` et une provenance relative, jamais des mètres. La combinaison des scores reste un diagnostic à comparer ; son défaut d'échelle constaté dans l'audit interdit de l'employer pour supprimer les annonces d'obstacles live.

### Vérifications exécutées sur Mac

- **99 tests du cœur JVM réussis** en 0,791 s, compilation isolée du vrai cœur Kotlin sans Gradle ni téléphone. Ce total comprend les tests historiques, 4 tests de contrat diagnostic RGB, 10 cas de sécurité du resolver, les 12 cas de moteur repris/adaptés et le test exécutant les 10 fixtures booléennes. Rapport local `/tmp/oria-fusion-engine-core-tests.json`.
- **10/10 fonctions/cas Swift réussis** avec Swift 6.2.1 sur ce Mac. Extraction suivie SHA-256 `a6fe20bdcab45e54f0eb2ff667e424cc3a5fd69532827f8f13205897710e95c7`, fixtures `18d5f60f59a6585ced9e0d3a726745f3fa8bf5dc9da61226d833ecd7d080026d`. Les résultats Kotlin sont identiques aux dix attendus. Rapport local `/tmp/oria-danger-swift-fusion-report.json`. Aucun fichier de référence n'est réécrit par ces tests.
- **Non-régression directe contre `b650dad` compilé isolément** : trois sessions synthétiques par mode, **900 frames LEGACY / 900 frames V2** ; **1 178 / 1 174 commandes** ; **133 / 131 propositions d'annonce et confirmations/échecs simulés**. Tous les champs JSON historiques de chaque réponse, décisions, pistes, tickets et silences sont exactement identiques ; seuls les champs diagnostics nouvellement ajoutés sont exclus de la projection. Rapport local `/tmp/oria-fusion-rgb-parity.json`.
- Ces comparaisons sont synthétiques et n'activent pas V2 par défaut. Les deux captures privées n'ont pas été rejouées dans cette tranche. Aucun build Android, flux de lunettes, service en veille ou test d'écoute exécuté par cet agent.

Reproduction :

```sh
python3 oria-htc/oria-lab-policy/run_core_tests.py --report /tmp/oria-fusion-engine-core-tests.json
python3 oria-htc/fixtures/verify_danger_resolution_swift.py --output /tmp/oria-danger-swift-fusion-report.json
python3 oria-htc/fixtures/verify_rgb_diagnostic_parity.py --output /tmp/oria-fusion-rgb-parity.json
```

Les scripts utilisent les dépendances JVM déjà présentes et les références Git locales. L'orchestrateur garde compilation Android, branchement Controller diagnostic et relecture indépendante avant commit.
