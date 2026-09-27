# Décisions et débat — Oria × HTC · SILMO

26 septembre 2026. Registre associé au [cahier des charges](</Users/sam/Documents/ChatGPT/Hackathon SILMO/CAHIER_DES_CHARGES_ORIA_SILMO.md>) et au [prompt maître](</Users/sam/Documents/ChatGPT/Hackathon SILMO/PROMPT_ORIA_SILMO_MULTI_AGENTS.md>).

**Portée initiale :** décisions de conception issues de deux tours réels entre trois agents spécialisés et un orchestrateur. Au moment des débats, aucun export, build Android, benchmark ou essai lunettes n’avait été exécuté. « Retenu » signifie choix de travail, pas capacité matériellement validée. Les résultats d’implémentation du 26 septembre sont ajoutés ci-dessous ; le détail exécuté fait autorité dans [la recette](oria-htc/validation/RECETTE.md).

## Participants et méthode

| Participant | Responsabilité | Contributions possédées |
|---|---|---|
| `htc_integration` | SDK HTC, décodage, horloge, audio et cycle de vie | [Tour 1](</Users/sam/Documents/ChatGPT/Hackathon SILMO/debat/TOUR1_HTC.md>), [tour 2](</Users/sam/Documents/ChatGPT/Hackathon SILMO/debat/TOUR2_HTC.md>) |
| `modele_mobile` | Checkpoint, export, prétraitement, runtime et parité | [Tour 1](</Users/sam/Documents/ChatGPT/Hackathon SILMO/debat/TOUR1_ML.md>), [tour 2](</Users/sam/Documents/ChatGPT/Hackathon SILMO/debat/TOUR2_ML.md>) |
| `portage_decision` | Moteur Swift, adaptation RGB, identité et annonces | [Tour 1](</Users/sam/Documents/ChatGPT/Hackathon SILMO/debat/TOUR1_PORTAGE.md>), [tour 2](</Users/sam/Documents/ChatGPT/Hackathon SILMO/debat/TOUR2_PORTAGE.md>) |
| Orchestrateur | Besoin, recette, contradictions et arbitrage | Cahier, présent registre et mise à jour du prompt |

Tour 1 : propositions indépendantes, alternatives et sources. Tour 2 : chaque expert a reçu les deux rapports des autres, le cahier et des objections ciblées de l’orchestrateur. Le tableau ci-dessous retient les conclusions et les preuves restant nécessaires. Il n’y a pas de vote remplaçant un essai ni de consensus déclaré sur une performance inconnue.

La précision utilisateur concernant le téléphone HTC disponible et l’accès iOS possible demain a été transmise aux trois experts pendant le premier tour. Le temps restant et le modèle exact du téléphone ne sont pas connus.

## Vue des arbitrages

| ID | Décision | Statut | Exigences / essai | Responsable de la prochaine preuve |
|---|---|---|---|---|
| D01 | Android Kotlin dans le starter maintenant ; contrats/fixtures communs pour iOS | Retenu ; iOS conditionnel | C01, C07, F12 / V01, V09 | HTC + portage |
| D02 | Sortie du codec vers pixels traçables, indépendante de l’aperçu | Premier essai retenu ; API/couleurs à tester | F02, N04, N07 / V02 | HTC |
| D03 | Vidéo indépendante de l’horloge micro ; files et paquets traités correctement | Correction nécessaire dans la copie | N01–N02 / V02, V06, V07 | HTC |
| D04 | `.pt` SILMO → ONNX FP32 end-to-end ; CPU puis XNNPACK mesuré | Chemin initial ; export non exécuté | C02–C03, F03, N06 / V04, V07 | ML |
| D05 | Prétraitement versionné ; profil Swift distinct du profil HTC | Expérience comparative requise | F02–F04 / V02, V04, V05 | ML + HTC |
| D06 | Assistance RGB descriptive, reprise des politiques applicables | Retenu ; qualité à tester | C04–C05, F04 / V05, V08 | Portage |
| D07 | Pistes 2D temporaires ; mémoire vocale séparée de la fraîcheur | Premier choix ; limites à éprouver | F06–F07, N02 / V05, V06 | Portage |
| D08 | `speakText` en premier ; livraison/ambiguïté et arrêt explicitement gérés | Premier essai ; capacités non prouvées | F05, F07–F09 / V03, V06 | HTC + portage |
| D09 | Agents pour développer ; pipeline local déterministe à l’exécution | Retenu | C02, N01, N08 / V05–V07 | Orchestrateur |
| D10 | Preuves distinctes modèle, métier et matériel ; objectifs mesurables | Retenu ; essais non exécutés | N03–N06 / V01–V09 | Revue croisée |

## D01 — Kotlin maintenant, iOS préparé sans seconde migration

**Arguments.** HTC : le starter et le téléphone prêté rendent Kotlin immédiatement exploitable. Portage : un cœur Kotlin sans types Android et des fixtures JSON permettent la comparaison avec le Swift existant. ML : conserver checkpoint et manifestes permet un éventuel export Core ML SILMO séparé.

**Alternative discutée :** adopter Kotlin Multiplatform tout de suite pour éviter deux cœurs. Objection commune : cela ne fournit ni SDK HTC iOS ni accès vidéo/audio et ajoute une intégration avant qu’une deuxième plateforme soit confirmée. L’arrivée possible d’accès demain n’est pas une preuve que ces API existent et sont autorisées pour l’application.

**Arbitrage.** Adapter le starter Kotlin natif avec identité conservée. Partager contrats, paramètres justifiés et fixtures ; garder le Swift comme référence. Pas de migration KMP maintenant, pas d’attente d’iOS pour avancer. Si iOS devient utilisable, réemployer le Swift existant et produire un artefact du modèle SILMO distinct de Paris V1.

**Réouverture.** V09 réussi : autorisation/identité, vidéo HTC exploitable et son pendant le streaming, sur appareil identifié. Décider ensuite, avec le temps restant, si iOS est une extension, une meilleure voie de démo ou un futur chantier. Aucune pose/profondeur iPhone n’est automatiquement celle des lunettes.

## D02 — Accéder aux pixels sans faire dépendre l’inférence de l’écran

**Faits.** Le callback fournit du H.264 ; `imageReceived` concerne la photo. Le codec actuel rend vers une Surface ; création et arrêt sont liés à l’aperçu. Sources précises dans le tour 1 HTC.

**Proposition HTC.** Premier essai `MediaCodec` dans un mode offrant des `Image` YUV accessibles, sans Surface d’affichage ; convertir crop/strides/couleurs et libérer rapidement les buffers. Ce n’est pas un simple ajout de `getOutputImage()` au décodeur actuel. [Contrat Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec#getOutputImage(int)).

**Objection et évolution.** PixelCopy a été proposé comme repli court. L’orchestrateur a contesté son identité temporelle et sa dépendance à l’aperçu ; HTC a accepté : une copie de surface ne prouve pas qu’elle représente le dernier paquet reçu. Lui attribuer ce timestamp masquerait une image ancienne. [Contrat PixelCopy](https://developer.android.com/reference/android/view/PixelCopy#request(android.view.Surface,android.graphics.Bitmap,android.view.PixelCopy.OnPixelCopyFinishedListener,android.os.Handler)).

**Arbitrage.** Essai direct du codec en premier. Associer sortie/PTS/réception de manière vérifiée et documenter l’unité reçue si le SDK fragmente les frames. Si ce mode échoue, évaluer une sortie hors écran compatible. PixelCopy peut débloquer une démonstration partielle, mais ne valide pas N07 ni la fraîcheur de F02/N03 sans preuves supplémentaires.

**Réouverture.** V02 sur le HTC : images asymétriques, couleurs/crop, rotation, 60 secondes continues et libération de buffers. Échec de format ou coût excessif documenté → choisir un autre adaptateur ; ne pas construire trois chemins complets simultanément.

## D03 — Corriger le transport et découpler le microphone

**Découverte HTC.** Le décodage attend une horloge audio non nulle (`H264Decoder.kt:224`, `StreamingPlayer.kt:26`, `:56`). Le retour microphone demande le haut-parleur du téléphone (`AudioDecoder.kt:321`). Une coupure naïve de cette lecture peut figer la vidéo. De plus, une frame encodée dépilée est perdue si aucun buffer codec n’est libre (`H264Decoder.kt:235`, `:293`) ; certains `queue.offer` ne traitent pas leur échec.

**Arbitrage.** Horloge et session vidéo indépendantes de la restitution du micro. Gérer correctement l’attente des buffers codec et la saturation ; observer taille et âge des files encodées. Appliquer « dernière image » après décodage, jamais comme purge arbitraire de H.264. Préserver configuration et références ; reprise contrôlée du flux si nécessaire. Remplacer les `TODO()` d’erreur/arrêt dans la copie de travail.

**Alternative écartée.** Garder tout le player tel quel et optimiser uniquement YOLO : cela conserve un blocage fonctionnel et une source de retard avant le modèle. Couper simplement l’audio entrant ne résout pas ce problème.

**Preuve / réouverture.** V02 vidéo continue sans restitution micro, V03 voix simultanée, V06 surcharge/reprise et V07 endurance. Un blocage matériel justifie une adaptation du décodeur, pas des images anciennes présentées comme live.

## D04 — Un seul modèle d’origine, export vérifié avant optimisation

**Accord de départ.** Le `.pt` fourni est le candidat demandé ; ONNX FP32 fixe 416, batch 1, `end2end=True`, `nms=False` constitue le premier export à essayer avec Ultralytics 8.4.27. Forme, unités et post-traitement doivent être mesurés ; `[1,300,6]` est une attente issue du code de la tête, pas un résultat d’export. [Exporteur 8.4.27](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/engine/exporter.py), [tête correspondante](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/nn/modules/head.py).

**Désaccord utile.** Le prompt initial privilégiait CPU. ML a demandé XNNPACK dès le premier comparatif FP32. L’orchestrateur et HTC ont retenu un ordre précis : CPU pour établir la correction, puis XNNPACK si disponible dans le build, même ONNX et même protocole. La base CPU n’est pas déclarée gagnante avant mesures. [ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/).

**Arbitrage.** Garder branche end-to-end et sortie bornée ; ne pas importer automatiquement NMS agnostique et top huit de l’ancien parseur. La sélection métier intervient ensuite, après géométrie et qualification explicites. Même tenseur d’entrée pour comparer PyTorch/export/Android ; ne pas comparer un `predict()` à prétraitement implicite à un autre tenseur Android.

**Alternatives / réouverture.** Export non end-to-end si incompatibilité prouvée ou régression mesurée, avec comparaison sur la même branche ; LiteRT/quantification si limite de latence ou thermique persiste. Toute variante a un manifeste et une preuve de fidélité. Les checkpoints Paris V1 et SILMO ont des empreintes distinctes ; cela ne prouve pas à soi seul des poids tensoriels différents et ne transfère aucune validation historique.

## D05 — Séparer fidélité historique et préparation des images HTC

**Proposition ML initiale.** Utiliser `.scaleFill` comme point de comparaison avec Swift. **Objection HTC/orchestrateur :** cela n’isole pas un runtime si checkpoint, branche et caméra changent en même temps. Le profil peut aussi déformer une image allongée ; letterbox n’est cependant pas validé par sa seule plausibilité.

**Révision ML au tour 2 et arbitrage.** Retenir `htc_letterbox` fixe 416 comme défaut SILMO **provisoire**, et `swift_scale_fill` comme témoin historique. Le prédicteur versionné emploie LetterBox ; cela motive un premier contrat reproductible, sans prouver sa supériorité avec les lunettes ni reconstituer tout l’entraînement. Paramètres de départ : proportions conservées, padding centré 114, `auto=False`, interpolation bilinéaire et `scaleup=True` ; arrondis/transformation inverse à reproduire et vérifier. [Prédicteur 8.4.27](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/engine/predictor.py), [LetterBox 8.4.27](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/data/augment.py).

La qualité reste non validée : départager les profils sur un lot HTC fixe annoté couvrant les classes, directions, tailles, lumières et scènes négatives, puis vérifier sur des séquences distinctes du réglage. Aucune rotation `.right` héritée de l’iPhone. La transformation inverse ramène les boîtes au repère caméra, indépendamment de l’aperçu. L’objection du portage est conservée : le défaut permet de commencer les essais, pas de déclarer la qualité live validée.

**Preuve.** V02 vérifie géométrie et orientation ; V04 compare runtime à tenseur identique, puis compare les deux prétraitements sur le même checkpoint/branche. V05 vérifie l’effet sur les alertes. Coins, poteaux fins, bords et limites 0,39/0,61 doivent figurer dans les cas. Une mire seule valide la géométrie, pas le rappel des objets.

**Réouverture.** Échecs annotés ou dérive des alertes ; modifier un facteur à la fois. Sans lot représentatif, conserver un profil déclaré provisoire et ne prétendre ni supériorité ni équivalence iOS.

## D06 — Reprendre Oria sans simuler ses capteurs absents

**Argument portage.** Le live Swift requiert profondeur/suivi avant la décision. Sa branche visuelle n’est pas déjà un mode live RGB. Transformer l’aire d’une boîte en nombre de mètres pour débloquer cette boucle changerait le sens du système.

**Arbitrage.** Mode `RGB_ONLY` avec prérequis de connexion, image/modèle frais et état audio distinct. Porter catégories, vocabulaire, zones, mécanismes temporels et génération/fraîcheur. Adapter qualification et priorité d’annonce avec les seuils Swift comme départ documenté. Employer une saillance visuelle sans unité, pas une pseudo-distance. Dire « Piéton devant » ; pas « danger proche » dérivé de la seule aire. Feux/panneaux restent contextuels.

**Alternative écartée.** Traduire intégralement la boucle LiDAR et injecter de fausses profondeurs/ancres. Les fonctions de profondeur, mur et approche métrique restent spécifiées/testables sur fixtures adaptées, mais inactives en live RGB tant que leurs entrées manquent.

**Preuve / réouverture.** V05 sur détections identiques pour les politiques conservées, puis fixtures RGB où les divergences intentionnelles sont attendues. V08 vérifie l’utilité des annonces. Réactiver une branche métrique seulement avec profondeur/pose réelles, alignées et horodatées, puis tests associés.

## D07 — Identités temporaires et cadence, sans faux ancrage spatial

**Constat portage.** Avec `worldAnchor=nil`, la mémoire Swift fusionne les observations de même classe. Cela peut faire taire une seconde personne. **Objection croisée :** une association IoU à 4 Hz reste fragile lors des mouvements de tête ; un nouveau numéro de piste ne doit pas produire une répétition en boucle.

**Arbitrage.** Essayer un appariement 2D un-à-un par classe/recouvrement/continuité, avec pistes locales, durée de perte courte et ambiguïtés explicites. Ne pas importer les 18 secondes de mémoire vocale comme délai d’association d’un individu. Conserver cadence globale et règles de confirmation bornées ; toute annonce exige une observation encore fraîche, pas seulement un souvenir dont le cooldown arrive à échéance. Les temps de piste, mémoire, attente audio et observation sont distincts.

**Objection ML retenue.** L’association reçoit les détections avant la qualification vocale stricte : franchir alternativement 0,84 ne doit pas détruire/recréer une personne à chaque frame. Séparer seuil de conservation des pistes et seuil d’annonce ; tester cette hystérésis. Les valeurs initiales issues du Swift restent à calibrer. Sélectionner les images décodées à analyser avant conversion lourde afin de ne pas gaspiller le budget de cette cadence.

**Alternative discutée.** Mémoire classe + zone : plus simple, mais deux personnes d’une même zone partageraient le rappel. Acceptable seulement comme mode collectif dégradé déclaré ou révision explicite de F06 ; ce n’est pas un suivi individuel conforme. Pas de tracker complexe ou de réidentification durable imposé au MVP.

**Preuve / réouverture.** V05 : deux personnes séparées, croisement, masquage, sortie/entrée et rotation rapide à la cadence visée. Exiger appariement un-à-un quand non ambigu, absence de rafale sur rotations et absence de suppression d’une nouvelle personne uniquement par l’ancienne mémoire. Un échec commande un changement de politique documenté, pas l’invention d’une pose du téléphone assimilée à celle de la tête.

## D08 — Parole : séparer intention, retour SDK et écoute

**Découvertes.** Le Swift actualise la mémoire d’annonce avant l’envoi effectif ; le starter expose `onTextSpoken(event)` sans ID de commande. Un succès anonyme après timeout peut concerner une ancienne demande. Aucun code lu ne prouve la préemption de la parole HTC.

**Arbitrage.** Premier chemin `speakText` avec phrases brèves en français. Une requête en vol, pas de FIFO historique ; après son issue, réévaluer le meilleur candidat encore frais. Réserver l’intention pour éviter les doublons, consommer la confirmation seulement si le retour est corrélé sans ambiguïté. Un refus n’est pas une annonce réussie ; ni rafale de retries ni faux long cooldown.

États minimaux : `Ready`, `InFlight(commandId interne, transportEpoch)`, `Uncertain`, `Unavailable`. Timeout ou rupture de corrélation → `Uncertain`, aucun nouvel envoi tant qu’un ancien callback pourrait confirmer le nouveau. Une simple génération applicative ou une attente fixe ne suffit pas. Revenir à `Ready` après une procédure d’annulation/drain ou d’isolation de client/listener démontrée par contrat et test ; sinon rester audio dégradé dans cette session. La perception peut continuer pour diagnostic sans afficher « audio prêt ».

**Arrêt.** Garantir absence de nouveau dispatch et purge/invalidation des demandes locales. Mesurer la parole résiduelle déjà soumise. Ne pas annoncer le silence instantané tant qu’une méthode d’annulation n’est pas prouvée.

**Alternative / réouverture.** TTS Android ou sons enregistrés si le chemin HTC ne remplit pas le besoin, seulement après preuve de routage dans les lunettes et coexistence vidéo. V03 : français, écoute réelle, réseau, conflit, délai, arrêt ; V06 : timeout A → reconnexion → callback tardif A, qui ne doit jamais confirmer B. Le son entendu demeure une preuve distincte du retour SDK.

## D09 — Développement agentique, exécution déterministe

**Arbitrage commun.** Orchestrateur + spécialistes pour proposer, contester, expérimenter et relire. Dans l’application : `FrameSource → Decoder → ObjectDetector → AlertPolicy → AlertSink`, avec état de session et traces. Interfaces simples ; pas de réseau de LLM pour commenter chaque frame.

**Raison.** Cela conserve la logique Oria, la localité du calcul et une temporalité testable. Ni microservices ni bibliothèque d’orchestration d’agents ne sont nécessaires au pipeline Android. Un LLM ne fournit pas les capteurs manquants.

**Réouverture.** Seulement si une fonctionnalité agentique embarquée distincte est explicitement souhaitée et possède un cas d’usage, un budget et une validation propres. Elle ne doit pas retarder les alertes courantes par défaut.

## D10 — Critères de preuve et règle de clôture

**Arbitrage.** Vérifier séparément (1) conversion du `.pt`, (2) politiques Kotlin contre entrées/réponses Swift, (3) chaîne physique HTC. Les replays iOS avec profondeur ne valident pas une mesure de distance caméra seule ; le simulateur SDK n’est pas une preuve radio/haut-parleur réel.

Cadence initiale 4 Hz, cible p95 réception de l’image → décision <500 ms, âge maximal initial de résultat 500 ms. Ce sont des paramètres de départ du prototype, pas des performances obtenues. La correspondance temporelle doit être prouvée. Mesurer en outre sorties utiles, résultats rejetés et intervalle maximal sans décision fraîche : supprimer toutes les sorties lentes ne suffit pas à obtenir un pipeline viable. Observer aussi démarrage à froid et dix minutes de régime soutenu.

Pour la parité FP32 : tolérances initiales proposées ≤1 pixel à 416 et ≤0,001 sur les scores des boîtes appariées, à figer avant l’exécution. Comparer les ensembles par classe et géométrie, pas ligne à ligne si top-k/ex æquo permutent l’ordre. Toute apparition/disparition aux seuils est rapportée ; aucun assouplissement silencieux après un échec. La conformité exacte de ces seuils reste à confirmer expérimentalement.

**Clôture.** Les P0 du cahier doivent posséder une preuve. Une dérogation/démo partielle est nommée et laisse l’exigence non satisfaite. Les médias bruts OriaLab étant absents, les fixtures disponibles permettent de progresser mais ne sont pas un replay historique exécuté. Le temps restant doit prioriser les essais décisifs, pas autoriser des résultats inventés.

## Ce que le débat a réellement changé

1. Kotlin devient la voie explicite immédiate ; l’ouverture iOS déclenche un test court au lieu d’une migration KMP anticipée.
2. L’extraction PixelCopy passe de « repli rapide » à **démo partielle sans preuve de fraîcheur/indépendance UI**.
3. Le découplage de l’horloge audio et les pertes possibles de paquets H.264 deviennent des corrections prioritaires avant optimisation de YOLO.
4. CPU devient une référence de correction ; XNNPACK est inclus comme deuxième configuration du premier benchmark FP32 si pris en charge.
5. Prétraitement, parser/NMS et sélection métier sont distingués ; recopier le Swift n’est plus présumé correct pour l’export end-to-end.
6. Le proxy de distance devient une saillance sans unité ; les annonces RGB décrivent catégorie/direction.
7. La mémoire vocale devient transactionnelle au niveau de l’adaptateur : tentative ≠ confirmation ; retour anonyme ambigu → audio incertain, sans réaffectation à une autre phrase.
8. Stop garantit l’invalidation locale ; l’arrêt du son déjà soumis reste une capacité à mesurer. Le cahier ne promet plus une préemption HTC non prouvée.

## Prochaines preuves, dans l’ordre utile

- **HTC, en premier :** starter compilable/autorisé, pixels orientés et phrase audible pendant la vidéo, avec horloge découplée du micro ; vérifier aussi le sens des callbacks vocaux.
- **ML, en parallèle :** chargement et export reproductibles, contrat mesuré et parité sur mêmes tenseurs ; ensuite benchmark sur HTC.
- **Portage, en parallèle :** contrats/fixtures, session RGB, protections de fraîcheur et mémoire d’annonce ; tester avec détections connues avant d’ajouter le bruit du modèle.
- **Intégration :** brancher la chaîne, exécuter panne/endurance, enregistrer verdicts et limites. Évaluer iOS via V09 seulement quand les accès sont effectifs.

Pour modifier une décision, ajouter date, preuve nouvelle, option remplacée, exigence/test touchés et effet sur la démo. Une panne mesurée peut rouvrir un arbitrage ; une préférence sans preuve ne transforme pas un choix provisoire en succès.

## Mise en œuvre et arbitrages après mesure — 26 septembre 2026

Les trois agents ont implémenté leurs composants avec propriétés de fichiers consignées dans `oria-htc/IMPLEMENTATION_COORDINATION.md`. Le cœur, le contrôleur, la vidéo et le runtime ont été relus entre spécialistes. Cette section complète les débats historiques ; elle ne transforme pas les validations restantes en succès.

| Décision concernée | Nouvelle preuve et décision | Conséquence / limite |
|---|---|---|
| D01, D10 / V01 | Le starter de référence et le prototype compilent. Le HTC U24 pro Android 14 est autorisé en ADB. Le certificat installé diffère de celui du Mac ; l’utilisateur demande de conserver le starter installé. | Identité de production conservée. Aucun remplacement. Un harness distinct, sans SDK ni permissions caméra/micro, mesure les mêmes sources ML/vidéo/métier. L’installation du prototype attend une signature compatible ou une autre identité explicitement autorisée par HTC. |
| D04 / V04 | ONNX FP32 réellement exporté, entrée `[1,3,416,416]`, sortie `[1,300,6]`. PyTorch/ONNX Mac passent les six cas ; XNNPACK Android aussi. Sur 20 inférences run02 : p95 XNNPACK 152,224 ms, CPU 175,558 ms. | XNNPACK avec repli CPU pour les opérateurs non délégués devient le défaut. Cela ne prouve pas que tout le graphe est exécuté par XNNPACK. |
| D04 / V04 | CPU Android échoue à la parité stricte : 299/300 boîtes appariées sur un cas, substitution de deux boîtes de scores proches de 0,000004 ; aucun écart observé au-dessus de 0,70. | Échec conservé, tolérances inchangées, CPU exposé seulement en diagnostic expérimental. L’explication numérique autour de TopK est plausible, pas causalement démontrée sur les tenseurs internes du téléphone. Voir `CPU_XNNPACK_DIAGNOSTIC.md`. |
| D05 / V04 | Resize bilinéaire déterministe Python/Kotlin : tenseurs pixels identiques sur six cas, padding/transformée inverse testés. | `htc_letterbox` reste provisoire pour la qualité. Arrondi uint8 explicite, sans présumer identité avec OpenCV ou `.scaleFill`. Aucun jeu HTC annoté disponible pour départager le rappel. |
| D05 / F02, F04 | Revue HTC/ML : une image 467×832 est redimensionnée en 234×416 ; inverser avec le facteur idéal 0,5 décalait un centre 0,3898 vers 0,390635, changeant sa zone. | Projection corrigée avec largeur/hauteur réellement arrondies (`raster_inverse_v2`), tests portrait/paysage aux frontières 0,39 et 0,61 et sur padding. Tenseurs et modèle inchangés. Convention de boîtes à bords continus ; pas d’offset de centre de pixel ajouté à l’inverse. |
| D02, D03 / V02 | Premier essai décodeur en échec : conversion YUV faisait vieillir l’image après la garde. Copie des plans et strides optimisée, seconde garde après conversion. Relance réussie : 100 images décodées, 13 livrées, âge maximal 176 ms, erreur RGB moyenne 11,57/255. | MediaCodec sans Surface retenu ; âge maximal inchangé à 500 ms. Le replay utilise le codec matériel HTC, sans radio ni caméra des lunettes. La preuve live reste requise. |
| D06, D07 / V05 | 23 tests métier passent et 18 fixtures communes concordent avec des fonctions extraites/exécutées en Swift. | Les politiques RGB sont intégrées ; l’identité par IoU et les paramètres de stabilité restent des adaptations à éprouver en mouvement. Pas de profondeur, pose, mètres ou garantie de réidentification. |
| D08 / V03, V06 | Revue du contrôleur : persistance avant dispatch, recontrôle de fraîcheur après écriture, vérification des retours du moteur, exception SDK traitée comme livraison inconnue. | Une demande en vol ; inconnue après timeout/rupture. Un redémarrage de l’Activity ne purge pas cette incertitude. Aucune procédure de reset ni sémantique de callbacks anonymes n’est déclarée matériellement validée. |

L’endurance du modèle et le replay combiné sont consignés dans la recette avec leur durée et leur périmètre réels. Ils ne remplacent pas V03, V07 complet ni V08 : il manque encore la caméra physique, la simultanéité vidéo/voix, l’écoute dans les lunettes et le comportement sous permissions/cycle de vie du prototype installé.

Résultats finaux : 39 tests JVM réussis ; modèle soutenu 600,026 s, 2 400 résultats frais à 4 Hz, p95 prétraitement+inférence 194,99 ms ; replay combiné 60,169 s, 224 décisions, p95 réception replay→décision 313 ms et cadence 3,72 Hz (sous la cible initiale 4 Hz). Le test d’arrêt rejette l’inférence retenue après invalidation. La voix du replay est factice et la vidéo embarquée ne produit pas d’annonce dans cet essai. Nouvelle tentative du starter installé : écran de vérification VIVE Connect puis ANR Android, sans acquisition validée ; cause à établir avec HTC, original conservé.

**Précision utilisateur ultérieure — F11 :** l’application HTC peut être modifiée et doit conserver ses onglets, avec ajout d’un onglet Oria. Le shell original est donc rétabli : Glasses initial, Chat, Audio, Camera et Oria. Ce changement rouvre seulement l’intégration de navigation, sur demande utilisateur ; les choix modèle/métier sont conservés. Les démarrages en attente et callbacks des fonctions historiques sont invalidés lors des changements de propriétaire. Les paroles de Chat et Oria passent par la même réservation pour ne pas mélanger les callbacks anonymes.

**Nouvelle preuve matérielle après rétablissement par l’utilisateur :** état `Eagle / Connected` lu dans le starter d’origine, puis image réelle affichée dans son aperçu vidéo. La capture de contrôle suivante affiche `Disconnected`, puis un second ANR est observé. Le flux complet soutenu et l’audio n’en sont pas déduits ; preuves et limites dans la recette. Le prototype modifié avec onglet Oria reste non installé du fait de la signature.

**Correction de l’interprétation de la signature :** relu à la demande utilisateur, le README original ligne 14 impose le package inchangé ; ses lignes 64–68 prévoient explicitement désinstallation du starter puis réinstallation en cas de signature différente. Le dépannage lignes 23–32 et le script d’installation lignes 44–49 confirment cette procédure. L’exigence précédemment présentée d’obtenir une signature spéciale HTC était donc trop restrictive. Le blocage actuel est le choix utilisateur de conserver l’installation existante, et non l’absence d’une voie documentée. La procédure est une référence technique ; elle ne remplace pas son accord pour revenir sur ce choix et effacer les données locales du starter.

**Accord et installation effectuée :** l’utilisateur a ensuite autorisé explicitement « applique la procédure et installe notre version ». Le seul starter a été désinstallé, notre APK installé et lancé avec succès. VIVE Connect est toujours présent. Les cinq onglets sont visibles ; Oria · Samy affiche ensuite « Lunettes connectées » et « Perception active · caméra seule ». La signature ne bloque plus cette installation ; les prochaines mises à jour emploient notre même clé. L’USB a été déconnecté avant récupération des métriques de la session réelle, et l’écoute de la voix reste à confirmer. Ces nouvelles preuves complètent les constats historiques sans les effacer.

## D11 — Reprise dédiée Oria et nouvel appareil (26 septembre 2026)

**Déclencheur : demande utilisateur actuelle, adoptant PROMPT_REPRISE_ORIA_HTC.md.** Elle remplace la décision précédente de cinq onglets principaux et de séparation personnelle. Oria devient l’accueil ; Diagnostic HTC conserve Glasses/Chat/Audio/Camera secondairement ; Démarrer/Arrêter restent fixes hors contenu défilant. Package, SDK, signature debug locale, modèle, seuils et contrats inchangés.

**Revue et corrections concrètes.** Agent UI : MainActivity/navigation/écran ; agent HTC : manager et relecture. Le mode Oria est activé avant le premier contenu. Quitter vers le diagnostic invalide sa session ; retour sans démarrage implicite. La revue a trouvé un HandlerThread d’aperçu non libéré au changement de Surface, des médias legacy pouvant survivre à l’arrière-plan et une demande MICROPHONE susceptible de succéder à une permission CAMERA devenue périmée. Le manager libère l’ancien décodeur, invalide/arrête les médias en onStop et vérifie le propriétaire entre permissions. La voix manuelle et automatique garde le même contrôleur.

**Nouveau téléphone réellement identifié :** HTC U24 pro, Android 14, arm64-v8a, ADB `CN46V3M00284`. Le starter signé d’origine a été sauvegardé (APK et fichiers privés accessibles), puis remplacé après accord explicite propre à cet appareil. VIVE Connect conserve ses chemins d’installation. APK compilé et 39 tests JVM réussis ; accueil Oria observé sur l’appareil.

**Nouvelles mesures distinctes :** XNNPACK six fixtures réussies, p95 inférence 152,765 ms ; CPU strict de nouveau échoué sur la même substitution à très faible score. Replay combiné 60,135 s, 224 décisions fraîches, 3,725 Hz, p95 302 ms ; audio factice. Le modèle et les tolérances ne changent pas. Une première session physique a fourni des pixels, détections et décisions ; cela ne confirme pas encore le son ni dix minutes complètes. Résultat courant : `oria-htc/validation/RECETTE.md`.

**Preuves historiques préservées :** les chiffres de l’ancien `CN4B53M00860` restent dans leurs rapports ; l’APK et le manifeste précédents sont archivés sous `validation/archive-CN4B53M00860/`. Chaque nouvelle validation porte le serial courant. Les inconnues de D08 (callbacks anonymes, annulation, audibilité) restent ouvertes tant qu’aucune preuve distincte ne les résout.

## D12 — Voix locale Bluetooth après échec matériel HTC (26 septembre 2026)

**Nouvelle preuve rouvrant D08.** Sur le nouveau `CN46V3M00284`, le SDK 0.6.0 refuse `speakText` pendant la vidéo avec `ERROR_RESOURCE_CONFLICT`. Son bytecode exige l’état IDLE, même avec la surcharge vidéo seule. Vidéo arrêtée, le callback réussit et l’utilisateur confirme la phrase dans les lunettes. Le rapport `oria-htc/validation/SDK_VIDEO_VOICE_CONFLICT.md` conserve la preuve et ses limites.

**Décision.** Oria utilise la surcharge vidéo seule (permission caméra), puis synthèse française locale Android vers PCM et AudioTrack dirigé explicitement vers la sortie Bluetooth VIVE Eagle. Le diagnostic conserve le TTS HTC à l’arrêt. Aucun contournement de l’état privé du SDK, aucune interruption cachée de la vidéo ni sortie volontaire sur le haut-parleur du téléphone. La voix française (~23 Mo) a été installée sur ce nouveau téléphone ; les phrases usuelles sont préparées silencieusement. Le modèle reste inchangé.

**Preuve intermédiaire, APK `aef0571aeb6ea6662fbb478413ab3924640ca4dde5eafca8d68ba8a01ffe2a18`.** L’utilisateur confirme la phrase Bluetooth dans les lunettes pendant la vidéo ainsi que gauche/droite dans l’aperçu. La trace observe 13 décisions entre soumission et résultat vocal COMPLETED ; ce résultat logiciel n’est pas à lui seul une preuve acoustique. Ces confirmations sont consignées dans `validation/new-device-CN46V3M00284/human-confirmations.json`.

**Échec conservé.** Les 35 premières annonces automatiques du snapshot expirent avant lecture : l’observation est déjà âgée à la soumission et ouvrir une nouvelle piste audio à chaque phrase ajoute une attente incompatible avec la garde de 500 ms. Le délai soumission→résultat EXPIRED s’étend de 309 à 821 ms ; ce n’est pas une mesure isolée de latence AudioTrack. La phrase manuelle n’avait pas ce critère de fraîcheur objet.

**Correction à vérifier.** Préparer et maintenir la route audio par de petits blocs de silence pendant la session ; la libérer à l’arrêt. Réutiliser la piste pour les phrases, contrôler génération/UUID/route et fraîcheur immédiatement avant le premier PCM vocal. Aucun relèvement du seuil, aucune actualisation artificielle du temps d’observation. Une seule demande en vol, fin de lecture logicielle distincte de l’écoute. Une course résiduelle lors d’un changement de route Android et le délai Bluetooth jusqu’à l’oreille restent à mesurer. La recette courante donnera le verdict de cette correction et de l’endurance.


**Résultat de la correction D12.** Le premier amorçage (`b682e0…`) échoue : route choisie sans progression suffisante du lecteur. Le seuil Android déclaré ne suffit pas ici. La correction (`a56bfa…`) remplit le tampon réel de silence tant que la tête de lecture n’avance pas, puis attend une file résiduelle ≤40 ms ; préparation maximale 3 s, indépendante de l’âge de l’objet. Mesure : route prête après 448 ms, puis annonces d’objets COMPLETED avec gardes à 294 et 288 ms. L’utilisateur les entend mais relève une restitution des deux côtés : le signal était volontairement mono. L’essai de collecte froid de 600 s comporte une interruption SDK après 507,038 s et ne satisfait donc pas dix minutes continues. Aucun plafond vidéo de 507 s n’est établi : le motif interne REACH_MAX_TIME trouvé appartient au mode réunion. La recette physique finale reste distincte.

## D13 — Direction vocale stéréo demandée par l’utilisateur (26 septembre 2026)

**Déclencheur explicite.** Après avoir entendu les alertes mono, l’utilisateur choisit « Voix à gauche ou à droite selon l’objet, centrée devant ». Cette précision rend la spatialisation prioritaire pour sa démonstration.

**Implémentation.** `VoiceAlert.zone` porte la zone du candidat réellement annoncé, qui peut différer de l’objet prioritaire affiché pendant un cooldown. Le contrôleur la traduit explicitement en LEFT/CENTER/RIGHT ; aucun texte vocal n’est parsé. La synthèse est normalisée en PCM16 stéréo centré avant le cache et avant l’amorçage AudioTrack. Une copie par demande conserve le canal gauche et met le droit à zéro, ou l’inverse ; devant, les deux canaux sont identiques. Pas de gain supérieur à un, de HRTF ni de distance simulée. Le worker réalise cette copie avant la garde finale de 500 ms. UUID, réservation unique et annulation restent inchangés.

**Revue et tests.** Revue indépendante HTC ; 48 tests JVM réussis, dont neuf vérifiant échantillons PCM signés, ordre des canaux, normalisation, durée et absence de mutation du cache. Le test métier vérifie la zone du second candidat annoncé pendant le cooldown du premier. UI : tests Gauche/Centre/Droite et confirmation du repère image + audio avant les annonces.

**Preuve physique.** APK `412eec7f9106b9faadda77c62dd3f41102994d66003fa7d29e8dfdfc64781199` installé sur `CN46V3M00284`. L’utilisateur confirme « Oui, les côtés sont corrects » sur les annonces automatiques pendant la vidéo. Les traces conservent pan et observation d’origine. Les haut-parleurs ouverts ne garantissent pas une isolation acoustique absolue d’une oreille. L’endurance et les transitions sont consignées séparément dans la recette, sans être déduites de cette écoute.


## D14 — Cadence live après mesure prolongée (26 septembre 2026)

**Preuve nouvelle.** Sur l’APK stéréo `412eec…`, XNNPACK reste sélectionné. L’inférence médiane passe d’environ 187 ms sur les deux premières minutes à 262 ms ensuite, plus ~46 ms de prétraitement. L’offre bitmap toutes les 250 ms dépasse alors la capacité de service ~310 ms. Le snapshot à 437 s contient 1 419 inférences, 767 acceptées par âge et 652 rejetées ; âge p95 657 ms toutes inférences incluses. Le résidu âge moins calcul augmente aussi : compatible avec attente et conversion, pas mesure isolée du temps en file. Android ne signale pas de limitation thermique (statut 0), ce qui ne prouve pas une fréquence CPU constante.

**Expérience décidée avant mesure.** Espacer à **333 ms** la sélection de sorties décodées pour la production live (~3 Hz maximum). Conserver la consommation de tous les paquets H.264, le slot dernier bitmap, les horodatages et la garde 500 ms. Appliquer explicitement dans le manager ; le décodeur garde son défaut 250 ms pour les replays historiques, dont les chiffres ne sont pas réattribués à cette nouvelle configuration. La trace start indique sampleIntervalMs. Aucun changement de modèle, seuil métier, format, voix ou canaux.

**Critères fixés avant l’essai.** À chaud, obtenir au moins 2,5 décisions fraîches/s et 90 % d’inférences acceptées par minute pleine, avec gardes vocales ≤500 ms et arrêt strict. Faire un premier constat court puis une mesure continue séparée ; conserver tout échec. Cette cible d’expérience ne transforme pas l’objectif initial 4 Hz en succès : il restera explicitement non atteint si la cadence réelle est inférieure. La seconde observation de confirmation peut arriver environ 83 ms plus tard. La cadence exacte dépend des sorties codec ; 333 ms est un minimum entre sélections, pas une garantie de trois images par seconde.

**Résultat final D14 : adopté.** APK `40175f…`, nouveau HTC `CN46V3M00284` : session réelle 620,346 s, collecte instrumentée 600,053 s (21/21 relevés). 1 633 inférences, 1 632 décisions fraîches, **2,631 Hz**, âge p95 toutes inférences **406 ms** ; un rejet à 534 ms. Les dix minutes pleines satisfont les critères fixés (première : 2,50 Hz / 99,34 %, suivantes : 2,63–2,67 Hz / 100 %). 100 fins de lecture Android, gardes autorisées au plus 427 ms. La cible initiale 4 Hz demeure non atteinte. Rapports `live-paced-summary.json` et `endurance-paced-summary.json`, écoute stéréo confirmée auparavant sur `412eec…` dont l’audio est inchangé. Cette preuve précède OriaLab et ne mesure pas la surcharge d’enregistrement.

## D15 — OriaLab demandé par l’utilisateur (26 septembre 2026)

**Demande nouvelle.** Ajouter un onglet pour enregistrer une scène sur le téléphone, exporter ses données et la rejouer sur Mac, image par image et avec recalcul. Elle autorise la capture explicite, sans activer un enregistrement par défaut.

**Contrat choisi.** Session locale versionnée : H.264 reçu et index PTS/réception/offset ; PNG sans perte des images proposées à l’analyse ; résultats YOLO, décisions, événements audio et paramètres. Les horodatages de réception restent distincts du temps de capture inconnu. Une file d’écriture bornée et des limites de durée/espace arrêtent la capture avec une raison explicite en cas de saturation. Le pipeline live reste propriétaire de ses images ; l’enregistreur conserve ses copies. Aucun flux micro, pose ou profondeur n’est inventé : absents du mode caméra actuel.

**Rejeu.** Vue des observations enregistrées et recalcul ONNX séparés. La politique est le cœur Kotlin Android directement recompilé pour JVM, avec horloge virtuelle et transactions vocales simulées explicitement. Le Mac reproduit les entrées et les règles ; il ne reproduit pas les délais du codec, du processeur HTC, du SDK ou du Bluetooth. Revenir en arrière dans le lecteur consulte un calcul depuis le début, pas un moteur avec une mémoire incohérente. Première recette : vraie capture téléphone → transfert ZIP → ouverture Mac → frame par frame → réinférence et politique.

**Résultat D15 : implémenté et recette réalisée.** APK `9dbaec…` installé sur CN46V3M00284, 50 tests JVM. Capture réelle 60,067 s / 155 PNG / 1 746 paquets, 154 décisions fraîches ; export USB et SAF identiques, galerie téléphone, lecteur Mac et recalcul utilisés. Sur une première capture de 20,219 s : 49 PNG réinférées et politique Kotlin identique pour les 48 décisions enregistrées. Parité brute ONNX 41/48, sept substitutions de lignes très peu confiantes conservées comme échecs ; toutes les 57 détections ≥0,70 concordent. Home finalise une troisième capture et le retour reste arrêté. Les 3 tests recorder sur téléphone, 11 tests desktop et 4 tests JVM de replay passent. Rapports : `oria-htc/validation/new-device-CN46V3M00284/oria-lab-validation.json` et `oria-lab-real-replay.json`.

**Limites inchangées.** Ces captures ont l'orientation non confirmée, donc aucune annonce automatique : la preuve d'écoute reste celle du build412eec. Le Mac simule les transactions audio. Aucune mesure isolée du coût de l'enregistrement ni nouvelle endurance de dix minutes ; aucune équivalence temporelle matérielle déclarée. La vidéo intégrale est reconstruite à 30 fps pour inspection, les PNG/horodatages enregistrés restent la référence de l'analyse.

## D16 — Stéréo 70/30 demandée et audit de la scène utilisateur

Le 26 septembre, l'utilisateur demande une restitution moins séparée entre oreilles. Choix : gains d'amplitude PCM LEFT=(0,7;0,3), RIGHT=(0,3;0,7), CENTER=(1;1), ce dernier conservant le volume précédent. Il ne s'agit pas d'un partage de puissance acoustique ni d'une normalisation de sonie. Aucune modification du choix d'objet, du texte, de la fraîcheur, de la route ou des UUID. Quantification entière au dixième, arrondi symétrique signé ; source centrée du cache jamais modifiée.

APK `5109439f…`, installation compatible sur CN46V3M00284, 51 tests JVM réussis dont dix PCM, avec balayage des 65 536 valeurs PCM16. L'écoute confirmée sur `412eec…` concernait 100/0 ; la perception du nouveau mélange reste à confirmer humainement. L'utilisateur demande simultanément un audit exhaustif de la capture `0548b68a-b9e3-445f-a072-0182e9ce98d6` (60,022 s, 164 images, annonces activées), conservée intacte et ouverte sur Mac. Les résultats seront consignés dans `oria-htc/validation/user-scene-0548b68a/`.

**Résultat de l'audit D16.** Rejeu exact164/164 décisions,25/25 transactions vocales ; contrôle visuel des164PNG par l'orchestrateur et un second agent. Les13directions annoncées correspondent aux boîtes, mais le suiviIoU présente fragmentation et permutation apparente de personnes ; aucune identité persistante n'est garantie. Faux positifs mobilier/deux-roues et affiche/personne bloqués par les critères temporels dans ce lot seulement. La13e annonce est annulée à l'arrêt après la fenêtreZIP,12sont terminées logiciellement. Rapport `validation/user-scene-0548b68a/AUDIT.md`. Décision : conserver modèle/tri/seuils actuels pendant l'audit, garder ces contre-exemples pour comparer une future correction sur séquences annotées ; ne pas augmenter ou diminuer globalement le seuilIoU pour masquer un seul cas.


## D17 — Nom public Oria et laboratoire Oria Lab

**Demande utilisateur explicite :** noms Oria et Oria Lab validés le 26 septembre 2026. Renommer le nom Android, les onglets, titres, messages et la phrase de test manuel, ainsi que le lecteur et son lanceur Mac. Le modèle utilisateur et les politiques issues de Swift conservent leur provenance technique.

**Compatibilité :** conserver le package HTC autorisé, signature, routes, classes, assets, préférences, format de capture v1 et dossiers existants. Installer avec `adb install -r` ; aucun effacement de données. Les exports Android futurs portent le nom OriaLab ; les ZIP précédents restent lisibles. Le nouveau lanceur Mac est canonique, l’ancien redirige vers lui. Modèle, suivi, seuils et gains PCM 70/30 inchangés.

**Validation :** APK `2fc0b36f173e8f1b3352f765377450a0d60fa293760056d43ff9983b253b64a8` installé sur CN46V3M00284 ; 51 tests JVM et 11 tests Mac réussis ; navigation et réouverture de la capture utilisateur vérifiées. Ce changement de nom ne constitue pas une nouvelle validation vidéo/son physique. Preuve `oria-htc/validation/new-device-CN46V3M00284/oria-branding.json`.


## D18 — Fichiers et dossiers Oria

L’utilisateur demande explicitement l’harmonisation des noms de fichiers sous Oria. Le dépôt adopte `oria-htc`, `oria-lab-desktop`, `oria-lab-policy`, `oria-lab-transfer`, les documents `*_ORIA_*`, les classes Oria et le sous-package Kotlin `starter.oria`. Le modèle devient `oria_silmo_fp32.onnx`/`.pt`, sans changement de ses octets. Le package autorisé `com.htc.vive.eagle.hackathon.starter` demeure inchangé.

Les identifiants de stockage Android, préférences de livraison audio, format de capture v1, dossiers Mac existants et journaux historiques conservent leurs valeurs pour éviter toute migration ou perte de données. Les scripts pointent vers les nouveaux chemins ; le lecteur Mac est relancé sur le même port. Le projet Swift source et le checkpoint original de Downloads ne sont pas renommés. Voir `oria-htc/validation/ORIA_RENAMING.md` pour les preuves.


## D19 — Nommage complet Oria / Oria Lab

La demande utilisateur étend D17/D18 à toutes les occurrences dans la version courante. Les anciens noms sont retirés des sources, documents, classes, formats, stockages, métadonnées descriptives et fichiers livrés. Le package `com.htc.vive.eagle.hackathon.starter` et la signature restent requis pour l’application HTC.

Cette décision remplace la conservation des identifiants internes prévue par D18. Après sauvegarde hors du projet, migration de cinq captures Android, sept caches Mac, cinq exports ZIP et des préférences audio ; UUID, images et événements conservés. Le format reste version 1 avec le marqueur `oria-lab-session`. Le lecteur ne prétend pas importer directement les originaux non migrés.

Les noms étaient aussi présents dans les métadonnées du checkpoint et de l’ONNX. Leur modification change les empreintes de fichiers, mais 708 tenseurs PyTorch et le graphe ONNX sérialisé ont été vérifiés identiques. Le lecteur accepte exclusivement les deux empreintes validées de ce graphe. Aucune nouvelle performance numérique ou acoustique n’est déduite du renommage. Preuves et limites : `oria-htc/validation/ORIA_RENAMING.md`.


## D20 — Suivi V2 expérimental, comparaison sans changer le défaut

La scène auditée fournit deux contre-exemples de suivi : fragmentation sous rotation et transfert apparent de confirmation entre personnes. Une association géométrique conservatrice ajoute une prédiction de translation bornée et abandonne les identités ambiguës. Le seuil IoU reste inchangé. Les sessions, gardes de fraîcheur et transactions vocales restent contrôlées par le même moteur. Le mode `STABLE_RGB_V2` est opt-in ; `LEGACY_IOU` demeure la référence. Les essais ciblés et la contrepartie en fragmentation sont consignés dans `oria-htc/validation/TRACKING_V2.md`.

## D21 — Gestion réversible des captures

Le nom utilisateur est un sidecar facultatif `session-label.json`, version 1, avec `displayName` en NFC et 1 à 80 points Unicode après nettoyage commun Android/Mac. Les données capturées ne sont pas réécrites. Retirer signifie déplacer vers `.trash`, avec restauration possible ; cette opération ne libère pas d’espace. Aucun effacement automatique. Capture/export/mutations doivent être sérialisés, et l’export inclut le sidecar actuel.

## D22 — Mode poche borné avant recette matérielle

**État historique : la durée maximale est remplacée par D24 ci-dessous.** Option désactivée par défaut, service Android explicite avec notification et arrêt accessible, maintien CPU au plus 15 minutes dans le candidat du 26 septembre, aucune reprise automatique après interruption. Oria Lab conserve son arrêt en arrière-plan. La destruction de l’Activity termine la perception. La compatibilité du flux HTC écran verrouillé, l’arrêt acoustique, les permissions et la consommation restent à prouver sur matériel. Cette préparation hors téléphone ne transforme pas ces points en succès. Voir `oria-htc/validation/OFFLINE_PROGRESS.md`.

## D23 — A/B du même moteur et aperçu audio local

Le laboratoire compare deux configurations Kotlin indépendantes sur les mêmes détections et horloges, avec la même règle de confirmation simulée. Les détections proviennent soit de l’enregistrement soit d’un unique calcul ONNX partagé entre les deux variantes. Les identités internes, changements de sélection et textes proposés sont distingués. La synthèse Mac avec gains 70/30 est un aperçu déclenché explicitement ; elle ne confirme aucune transaction du moteur et ne représente pas le son entendu dans les lunettes.


## D24 — Sessions sans limite de durée (27 septembre, demande utilisateur)

La demande explicite remplace le plafond expérimental D22 : aucune limite de durée de perception en mode poche ni de capture Oria Lab. Le maintien CPU est une réservation technique de 2 minutes renouvelée toutes les 30 secondes tant que propriétaire, token et session valide correspondent ; ce n’est pas un compteur maximal de session. Un verrou perdu n’est pas réarmé pour une ancienne session. Arrêt explicite, perte de flux/Bluetooth, destruction Activity et invalidation des callbacks restent applicables.

Le plafond de 250 Mio par capture et le quota global de 1 Gio sont aussi retirés : à 74–87 Mio/min ils auraient réintroduit un arrêt pratique vers 3 minutes. La protection porte désormais sur 512 Mio réellement libres, les files bornées, les erreurs disque et l’arrêt propre. Les nouveaux manifestes portent des limites durée/taille nulles explicites. Ni purge automatique ni enregistrement automatique. L’enregistrement reste finalisé si l’application passe en arrière-plan ; le mode poche concerne la perception. Validation physique liée au build et au téléphone dans `oria-htc/validation/NIGHT_WORK_20260927.md`.

## D25 — Horloges protégées contre les événements rejetés (27 septembre)

Des tests adversariaux ont reproduit trois familles d’erreurs : ancien ticket/intention rejeté avançant l’horloge d’une nouvelle session ; frame rejetée consommant une intention pourtant valide ; snapshot demandé avec un temps inversé pouvant exposer une ancienne sélection. Validation d’identité/session et mutation d’état sont séparées, et un snapshot à horloge inversée ne fournit pas de candidat. Les tests de sessions de 24 h virtuelles conservent les bornes mémoire et les deux suivis ; LEGACY_IOU reste le défaut. Les observations refusées ne doivent pas modifier les transactions valides de la session courante.

### D26 — Relecture des longues captures sans charger tous les résultats

**Décision vérifiée le 27 septembre :** Oria Lab Mac indexe `frames.jsonl`, événements et paquets par offsets et lit les données détaillées à la demande. Les rapports de calcul sont écrits progressivement dans un fichier partiel, puis publiés atomiquement après finalisation ; leur téléchargement est un flux. Cache de 16 lignes JSONL et de 8 résultats d’image, index et événements vocaux encore proportionnels au nombre d’éléments. Une réservation d’usage empêche renommage/corbeille pendant lecture/export ; restauration réactive la même identité de session pour ses anciens rapports.

Sur la capture de 164 images : index HTTP 11 436 595 → 34 595 octets ; pic Python de chargement 70,4 → 3,15 Mio ; job A/B 2,39 Mio, mêmes 164 politiques et empreinte d’entrée. Bornes structurelles explicites : 100 000 fichiers/images, 128 Gio transport/expansion, 8 Gio par JSONL, 2 Mio par ligne, 2 millions de lignes. Ces bornes refusent un fichier avec un motif explicite ; elles ne tronquent pas une scène et ne créent pas de minuterie. Réserve disque de 512 Mio. Le blob ZIP restant à cette étape est traité dans D28.

## D27 — Galerie Android fidèle aux positions d’origine (27 septembre)

**Défaut reproduit :** une entrée JSON invalide et une PNG absente disparaissaient sans diagnostic, décalant le rang apparent des images. Le chargeur conserve désormais une entrée par ligne non vide, son numéro de ligne, les identités vidéo/image et l’horloge observée. Une identité absente, dupliquée ou ambiguë ne reçoit pas de valeur inventée. Une horloge d’inférence contredisant celle de l’image empêche leur association ; les temps ultérieurs de calcul/enregistrement ne sont pas confondus avec le temps d’observation.

Le chargement conserve un index compact et les offsets des inférences ; les tenseurs bruts sont sautés et seules les détections de l’image sélectionnée sont lues. PNG et détections sont validées à la sélection : le compte de références PNG présentes ne prétend pas que toutes les images ont été décodées. Annulation coopérative et clés de sélection empêchent une ancienne lecture de publier ses résultats sur une nouvelle image. Les limites de ligne et de détails diagnostiques sont explicites ; les entrées suivantes restent accessibles. Les fichiers source ne sont jamais réécrits. Recette et limites dans `oria-htc/validation/GALLERY_LONG_SESSIONS.md` et le bilan de nuit.

## D28 — ZIP Mac préparé sur disque et téléchargé nativement (27 septembre)

**Défaut reproduit :** le navigateur demandait un blob contenant toute l’archive. L’export prépare désormais un ZIP dérivé par flux, puis fournit un lien de téléchargement natif avec reprise HTTP Range. Un jeton local protège sa création et son annulation ; l’identifiant aléatoire du lien comporte 256 bits. La capture est réservée pendant la préparation. Ensuite le ZIP immuable possède ses propres réservations de lecture : renommer ou archiver la capture ne change ni son contenu ni son nom figé.

Un export dérivé expire après 30 minutes d’inactivité, avec horloge monotone et expiration suspendue pendant sa lecture. Ce délai ne concerne ni les captures originales ni le mode poche. Deux préparations et quatre ZIP vivants au plus, réserve disque de 512 Mio, nettoyage des seuls caches marqués dont l’instance propriétaire n’est plus active. Une annulation retire le lien ou arrête la préparation ; elle ne prétend pas arrêter un transfert natif déjà pris en charge par le navigateur. Le dernier lecteur libère le ZIP. Les tests de concurrence, disque, annulation et téléchargement sont consignés dans la revue de la deuxième livraison.

## D29 — Intégrité de la capture avant comparaison Mac (27 septembre)

**Défauts reproduits :** le lecteur supprimait un EOF JSON incomplet, triait les images sur des temps parfois inventés, choisissait la dernière inférence dupliquée et convertissait certaines détections invalides en observation vide. Les vrais moteurs Kotlin pouvaient ainsi terminer une comparaison apparemment normale sur une entrée ambiguë. Un événement vocal pouvait aussi être attribué à la mauvaise session par sa seule proximité temporelle.

La consultation garde désormais chaque ligne non vide à sa position source, avec numéro de ligne, offset, diagnostic et identités/horloges absentes représentées par `null`. Les conversions implicites d’identifiants et nombres non finis sont refusées. JSON/UTF-8/clé dupliquée, ambiguïtés de jointure, chronologie contradictoire et détections invalides empêchent le lancement d’un recalcul global. Aucune portion saine n’est silencieusement sélectionnée comme si elle représentait toute la capture. La déclaration `complete` du manifeste reste distincte de l’intégrité observée.

Une PNG absente ou illisible seule n’invalide pas une inférence enregistrée cohérente : ses données peuvent être inspectées et comparées avec `visualCoverageComplete=false`, mais le recalcul ONNX est interdit. Une position normalement non analysée garde son statut `not_recorded` ; elle n’est ni transformée en observation vide ni présentée comme une égalité A/B. Les callbacks sans identité de session fiable restent non attribués. La lecture automatique est indisponible si les horloges de la timeline ne permettent pas une cadence réelle ; les flèches restent disponibles.

Les chemins sortant de la capture et liens symboliques restent refusés à l’import. Les changements de JSONL ou PNG après indexation sont détectés ; rouvrir la capture est nécessaire avant de réutiliser ses associations. Les fixtures communes sous `oria-htc/fixtures/capture-integrity/` distinguent cohérence des données et disponibilité du visuel. Elles ne prétendent pas une identité d’interface Android/Mac : la galerie Android 1.3 refuse encore d’afficher l’analyse lorsque sa PNG manque. Aucun changement du modèle, de ses seuils, du cœur Kotlin ou de l’APK Android dans cette étape.

## D30 — Données Oria Lab conservées en cas d’image manquante (27 septembre)

La galerie HTC distingue maintenant l’intégrité des lignes d’observation de la disponibilité des PNG. Si les données capturées ont une identité et une horloge cohérentes, l’inférence stockée peut être consultée sous forme textuelle malgré une PNG absente ; aucune surimpression ni image factice n’est produite. Les données ambiguës restent bloquées sans association arbitraire. Fixtures communes et 12 tests galerie instrumentés passent (1 autre est ignoré) sur CN46V3M00284 ; revue indépendante sans bloqueur. Les octets capturés ne sont pas réécrits. L’intégration ne modifie ni le moteur de tri, ni ONNX, ni les seuils. Voir le bilan du quatrième passage dans `oria-htc/validation/NIGHT_WORK_20260927.md`.

## D31 — Accueil lisible et contrôles d’accessibilité (27 septembre)

**Demande utilisateur :** nettoyer le visuel et adapter en priorité Oria aux déficients visuels, avec trois agents. L’accueil affiche les états utiles, les tests vocaux et une commande principale fixe d’au moins 64 dp. Aperçu et réglages techniques sont repliables ; les commandes se disposent verticalement et le texte conserve l’agrandissement Android. Oria Lab et les onglets partagent la même palette claire/sombre. Titres, rôles, noms et états sont exposés aux services d’accessibilité ; chaque interrupteur forme un seul contrôle. Les mises à jour de détections ne sont pas des régions vocales automatiques.

**Arbitrage :** conserver la confirmation du repère. La vérification visuelle peut être accompagnée et la vérification auditive reste humaine. Les causes de suspension vocale prennent la priorité sur un message de disponibilité. Aucun changement du modèle, du tri, des gains audio ni de la gestion des captures.

**État :** candidat 1.5 compilé, 83 tests JVM et revue indépendante sans bloqueur. Contrastes de palette calculés ; rendu agrandi, TalkBack et essai utilisateur non exécutés. HTC débranché avant installation : 1.4 reste installé, sauvegarde préalable conservée. Voir `oria-htc/validation/ACCESSIBILITY_DELIVERY_20260927.md`.

## D32 — Obstacles RGB au-delà des classes YOLO, d’abord mesurés dans Lab

**Demande utilisateur précisée :** reconnaître de gros obstacles devant la caméra même s’ils ne sont pas détectés par YOLO, pas uniquement des murs. Un prototype Lab combine SegFormer B0 ADE20K, Depth Anything V2 Small relatif et couverture des boîtes YOLO de l’image exacte. Les régions non-sol soutenues par le relief relatif sont inspectables, avec propositions déterministes séparées du moteur Android. Aucune distance ni trajectoire n’est déduite des pixels ; aucune voix automatique.

**Mesure :** 164 images traitées en 119,446 s sur Mac, temps ONNX combiné médian 691,260 ms, deux propositions sur un même ensemble cloison/comptoir non couvert par YOLO. La répétition droite→centre à 363 ms d’intervalle reste visible dans Lab : pas de déduplication d’objet inventée ni de cooldown global masquant arbitrairement le centre. Avant voix réelle, comparer un regroupement temporel avec priorité centrale, puis mesurer latence HTC et qualité sur un corpus diversifié.

**Admission :** recherche/évaluation locale seulement, poids non publiés ; licence SegFormer non commerciale et Depth Anything Small Apache-2.0 documentées. Le modèle YOLO, ses seuils et l’APK sont inchangés. Tests, contrats et limites : `oria-htc/validation/OBSTACLES_LAB_20260927.md`.
