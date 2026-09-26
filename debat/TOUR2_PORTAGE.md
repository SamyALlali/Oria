# Débat — tour 2 : objections, réponses et décision révisée

26 septembre 2026. Agent `portage_decision`. Documents contradictoires lus : `TOUR1_HTC.md`, `TOUR1_ML.md` et version 1.0 du cahier des charges. Réponse aux cinq objections de l’orchestrateur. Aucun code applicatif modifié, aucun essai exécuté.

## 1. Le suivi 2D reste une expérience, pas un choix déjà validé

**Objection acceptée.** À 4 Hz, un mouvement de tête peut déplacer un objet hors du recouvrement de sa boîte précédente. Une association classe/IoU/temps peut fragmenter une seule personne en plusieurs pistes et provoquer de nouvelles annonces. Elle ne garantit ni identité physique après occultation, ni réidentification lors du retour du regard.

Je révise ma recommandation : retenir **le contrat d’identités temporaires** et tester d’abord un appariement léger, sans figer aujourd’hui son seuil IoU, son délai de perte ou une bibliothèque de tracker. Un appariement un-à-un et un départage déterministe empêchent certaines fusions accidentelles ; ils ne résolvent pas l’ambiguïté des objets qui se croisent. La cadence réellement obtenue, les frames manquantes et l’âge des observations doivent entrer dans l’expérience.

### Porte de validation de F06

Construire des séquences annotées indépendantes du modèle, puis rejouer les mêmes boîtes à 4 Hz et avec pertes d’images contrôlées. Vérifier ensuite sur une petite vidéo réelle de lunettes. Le cas synthétique teste le contrat ; il ne suffit pas à prouver le comportement réel.

| Cas | Attendu vérifiable | Échec qui rouvre le choix |
|---|---|---|
| Deux personnes séparées présentes simultanément | Deux identifiants temporaires distincts, association un-à-un ; le cooldown de l’une n’est pas attribué à l’autre | Fusion systématique par classe, changement d’identité dans une séquence où l’association est non ambiguë |
| Une personne stable, même boîte puis petits déplacements | Même identité pendant le segment observable ; aucune répétition avant son délai applicable | Création d’une identité à chaque inférence et spam d’annonces |
| Croisement et occultation ambiguës | Incertitude/perte explicitement représentée ; aucune prétention de réidentification certaine. Les deux detections séparées restent deux pistes locales lorsqu’elles redeviennent distinctes | Une identité fabriquée ou fusion durable après séparation ; règle non déterministe |
| Rotation de tête puis retour, plusieurs fois | Tracer perte/recréation de pistes et annonces induites ; toujours respecter la cadence globale et la fraîcheur. Comparer les réannonces indues au rappel EchoNav retenu sur le même scénario | Une nouvelle phrase à chaque retour du regard alors que l’objet est le même et le rappel n’est pas dû ; débit d’annonce dominé par fragmentation |
| Deuxième personne nouvelle après une première annonce | Son observation et son identité restent indépendantes ; la priorité/cadence peut retarder sa phrase, mais le journal explique le motif | Cooldown hérité automatiquement d’une autre personne |

Les passages avec ambiguïté peuvent produire une nouvelle identité ; F06 ne doit pas demander une continuité impossible à justifier sans signal supplémentaire. Si le test de rotation révèle un flot de réannonces, documenter l’échec et comparer une stratégie explicite de stabilisation/association. Ne pas empiler arbitrairement des seuils jusqu’à réussir la vidéo de démonstration.

**Statut de l’alternative classe + zone :** il s’agit d’un MVP dégradé, avec une information de présence collective, qui **ne valide pas F06** telle qu’écrite. Si elle devient nécessaire faute de temps, conserver F06 non satisfait ou faire une modification explicite du périmètre. Ne pas rendre l’exigence verte en changeant silencieusement le sens d’« identité ».

## 2. La mémoire de 18 secondes n’autorise jamais une annonce ancienne

**Objection acceptée et intégrée au contrat.** Séparer trois notions :

- `lastObservedAt` : date de l’image effectivement mesurée qui confirme la boîte et sa direction ;
- rétention d’identité/annonces : historique pour l’association et l’anti-répétition, pouvant vivre plus longtemps ;
- admissibilité d’un envoi : une preuve perceptive encore fraîche, la session/epoch courante et un chemin audio dans un état connu.

Réévaluer un souvenir, reconstruire un candidat, finir un callback ou attendre le cooldown ne change jamais `lastObservedAt`. À chaque envoi, reprendre une observation fraîche de la scène courante. Un TTL de 18 s est une limite de mémoire, pas une durée de validité de la direction d’un objet. De même, les durées de maintien/relâchement Swift peuvent stabiliser l’affichage mais ne doivent pas outrepasser l’âge maximal d’émission configuré.

Test décisif : présence à `t=0`, silence ensuite, déclenchement d’un rappel à `t=6,5 s`. Le journal peut garder l’entité, mais aucune nouvelle phrase d’objet n’est soumise. Refaire avec `t=0,501 s` pour un âge maximal configuré à 500 ms et avec stop/restart entre observation et envoi. Un nouveau résultat réellement frais peut réautoriser l’évaluation ; un timestamp de traitement plus récent ne suffit pas.

**Réponse HTC :** la provenance de l’horodatage doit accompagner la donnée. Si un chemin PixelCopy ne permet pas d’identifier l’image réellement copiée et son âge, `now()` au moment de la copie n’est pas une preuve que le contenu est nouveau. Cette limite peut empêcher de satisfaire N02/N03 ; ne pas valider ces critères avec une horloge d’échantillonnage seule.

## 3. Corrélation TTS : la génération seule est insuffisante

**Objection acceptée ; le tour HTC la confirme.** Une seule requête en vol évite des ambiguïtés ordinaires. Elle ne résout pas un callback anonyme de A reçu après timeout/reconnexion alors que B a été envoyé. Numéroter B localement ne numérote pas le callback HTC.

Je resserre la proposition : ne pas lancer une nouvelle requête réputée corrélable tant que l’état de la précédente est ambigu, sauf mécanisme de reset/cancellation réellement démontré. Un timeout produit `AUDIO_UNKNOWN` et libère les calculs de perception, pas l’autorisation d’empiler des commandes audio. Sortie de cet état par événement terminal corrélé, réinitialisation démontrée ou reprise explicitement déclarée sans confirmation fiable. Le SDK exact peut permettre mieux ; rien dans le sample inspecté ne le prouve.

Le cooldown de confirmation n’est consommé qu’après un retour dont l’association à la requête est certaine et dont la signification est documentée. Garder une réservation temporaire et un pacing de tentative distincts pour éviter les doubles envois. Un refus explicite libère la réservation ; il peut conduire à réévaluer le candidat actuel avec une politique de retry bornée. Une erreur n’est pas une preuve de son entendu, un succès SDK n’est pas une mesure d’audibilité.

Test contradictoire obligatoire : A → timeout → Stop/reconnexion → tentative B → succès tardif A. Attendu : aucun succès attribué arbitrairement à B, aucune consommation de son cooldown comme si B avait été entendu, aucune nouvelle commande ancienne. **F08/V03 doivent nommer ce test**, pas seulement une erreur audio simple.

## 4. Les contrats de fidélité ne se confondent pas

**Objection ML acceptée.** La NMS agnostique et le plafond de huit boîtes décrivent le profil Core ML/Swift historique. Ils ne doivent pas devenir les contraintes automatiques du candidat end-to-end. Une NMS supplémentaire peut supprimer des objets valides ; un top huit par confiance peut retirer un objet central utile.

Trois comparaisons séparées :

1. **Fidélité d’export** : même `.pt`, même branche, mêmes tenseurs prétraités entre PyTorch et runtime ; vérifier la structure réelle et les sorties avec les tolérances choisies avant l’essai.
2. **Fidélité des politiques conservées** : mêmes détections déjà normalisées et horodatées injectées dans Swift/Kotlin, sans repasser par YOLO/NMS. Comparer vocabulaire, zone, expiration, pacing, reset et logique réellement conservée. Ne pas prétendre comparer tout le moteur LiDAR à une politique RGB qui n’a pas ses entrées.
3. **Qualité du système adapté** : même jeu représentatif HTC pour évaluer effets du prétraitement, de la branche, des seuils et de la décision RGB. C’est une validation d’adaptation, pas une preuve d’équivalence Paris V1.

Toute suppression de NMS/plafond hérité doit être inscrite dans la matrice comme adaptation du **contrat de perception**, avec cas de personnes et véhicules qui se recouvrent et plus de huit candidats. Les tests purs de politique n’obligent pas à conserver ce filtrage en production.

**Objection à la priorité de `.scaleFill` proposée par ML :** elle est justifiée comme profil de comparaison historique, pas comme préférence de qualité HTC. La comparaison PyTorch/ONNX peut porter sur des tenseurs identiques dans chacun des profils. Garder `.scaleFill` et letterbox nommés, ne figer le profil live qu’après vérification géométrique et scène représentative. La réutilisation maximale d’EchoNav ne demande pas de préserver une déformation si une meilleure option est démontrée.

**XNNPACK :** je soutiens une comparaison bornée CPU EP/XNNPACK sur le même export après preuve de correction. Le cahier classant toute « accélération » P1 peut créer une contradiction : une optimisation devient nécessaire au P0 si le CPU de référence échoue aux cibles de démo. Garder la quantification/compétition de formats P1 ; autoriser le comparatif de providers comme test de faisabilité précoce, sans promettre son résultat.

## 5. Revue du cahier des charges

| Point | Verdict / correction à apporter |
|---|---|
| Priorité Kotlin et accès iOS | Cohérent : Android immédiat, iOS conditionnel F12/V09. Garder fixtures/contrats communs et Swift réutilisable. KMP reste différé ; pas de donnée LiDAR/pose du téléphone appliquée implicitement aux lunettes. |
| F04 : décision RGB | Préciser que le mode RGB annonce catégorie/direction sans transformer le proxy en « proche » ni niveau métrique de danger. Les seuils hérités 0,82/0,84/0,86 sont des points de départ, pas des mesures validées. |
| F06 : identités temporaires | Ajouter le verdict conditionnel de la porte ci-dessus. L’alternative classe/zone ne satisfait pas ce P0. L’identité après rotation/occultation peut être inconnue ; ce fait doit apparaître dans le contrat. |
| F07 : arrêt | Formulation actuelle honnête : aucun nouveau dispatch, parole déjà soumise mesurée séparément. Conserver cette distinction dans le prompt et la recette. |
| F08 et N02 : audio | Ajouter corrélation anonyme ambiguë et `AUDIO_UNKNOWN`. Une génération locale ne transforme pas un callback externe en callback numéroté. |
| N03 : âge maximal 500 ms | Bonne cible initiale si la réception de l’image est traçable ; inclure la garde au moment du dispatch, pas seulement à la sortie d’inférence. Si une source ne prouve pas l’âge, rapporter cet échec au lieu de réinitialiser l’horloge. |
| N07 vs repli PixelCopy | Le repli dépendant de l’aperçu est une dégradation qui ne valide pas l’indépendance de rendu demandée. Autoriser une démo partielle bien nommée ; ne pas déclarer N07 réussi par défaut. |
| V05 : politique et corpus | Correct : fixtures déterministes utilisables immédiatement, replay historique explicitement non exécuté sans médias. Ajouter cas >8 candidats / recouvrement interclasses au test de contrat perception, séparé des tests métier. |

## Ce que ce débat change réellement

1. Le choix de tracker devient une expérience de qualification de F06 avec un échec explicite ; aucune bibliothèque ou valeur IoU n’est « approuvée » sans essai.
2. L’expiration vocale est rattachée à la dernière observation, jamais au rafraîchissement d’une mémoire ou d’un candidat maintenu.
3. Un timeout TTS ne permet plus de réattribuer arbitrairement le callback suivant ; l’état audio inconnu devient une partie du contrat.
4. Les NMS/top huit historiques restent un profil de référence, pas un héritage imposé au modèle end-to-end. L’équivalence de politique est testée indépendamment des sorties du modèle.
5. La performance finale ne se résume plus à ONNX CPU : comparaison bornée du provider autorisée sans changer de format ni quantifier prématurément.
6. Deux chemins de repli — classe/zone et PixelCopy dépendant de l’aperçu — conservent leur utilité de démonstration, mais ne cochent pas silencieusement les P0 qu’ils contournent.

Les contraintes produit et les contrats sont assez précis pour lancer ensuite les preuves matérielles. Le suivi 2D, l’extraction exacte des pixels, la confirmation/annulation TTS, le provider rapide et les seuils définitifs restent des choix à valider, pas un consensus technique acquis.
