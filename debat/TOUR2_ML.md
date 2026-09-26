# Tour 2 — Réponse contradictoire ML et décisions proposées

26 septembre 2026 — agent `modele_mobile`. Documents lus : `TOUR1_HTC.md`, `TOUR1_PORTAGE.md`, cahier des charges v1.0 et objections de l’orchestrateur. Aucun export, benchmark ou essai matériel. Les décisions ci-dessous sont des choix de départ pour l’implémentation, pas des performances constatées.

## 1. Runtime : objection acceptée et périmètre limité

Accord avec l’orchestrateur : **CPU EP d’abord pour la correction ; XNNPACK, si disponible avec les opérateurs et la version embarquée, dans le même premier benchmark Android**. Même ONNX FP32, mêmes images, même prétraitement et même appareil. Ce comparatif est un choix de fournisseur d’exécution au sein d’ONNX Runtime, pas deux pipelines ni une compétition de formats.

Le choix final dépend de la chaîne complète : un gain d’inférence qui ralentit le décodage ou l’UI par saturation des threads n’est pas un gain utile. Consigner nombre de threads, provider effectivement utilisé, repli éventuel sur CPU et différence de résultats. LiteRT, quantification, accélérateurs supplémentaires restent conditionnés à un échec mesuré du chemin correct. La documentation ORT motive l’essai XNNPACK sur FP32, mais ne garantit pas un gain sur ce téléphone. [ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/)

## 2. Prétraitement : je précise et révise mon choix provisoire

Mon tour 1 préconisait de commencer par `.scaleFill` pour isoler la comparaison Swift. Cette valeur est correcte **comme profil témoin iOS**, mais ne suffit pas à choisir le runtime SILMO : son checkpoint et sa branche diffèrent de la référence Paris V1. Je propose donc maintenant :

- **Profil SILMO initial : letterbox carré fixe 416 × 416**, proportions conservées, padding centré, sans rectangle automatique variable. Contractualiser les arrondis, l’interpolation et la transformation inverse ; les paramètres de départ du `LetterBox` versionné sont padding 114, interpolation bilinéaire et `scaleup=true`.
- **Profil témoin Swift : stretch correspondant à `.scaleFill`**, avec rotation séparée. Il sert à reproduire le chemin historique et à comparer l’effet de la géométrie ; aucune rotation `.right` n’est copiée automatiquement sur HTC.

Motif de ce choix provisoire : le prédicteur officiel Ultralytics 8.4.27 utilise `LetterBox`, puis RGB, BCHW et division par 255 pour les images ordinaires. Son comportement de rectangle automatique dépend du format ; le désactiver explicitement permet de conserver le contrat ONNX fixe. Ce fait rend letterbox cohérent avec une référence Python contrôlée, **sans prouver sa supériorité visuelle ni reconstituer à lui seul le prétraitement exact d’entraînement**. [Prédicteur versionné](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/engine/predictor.py#L137), [LetterBox versionné](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/data/augment.py#L1348)

Sans corpus annoté, on peut vérifier le contrat de géométrie et la fidélité PyTorch/ONNX ; on **ne peut pas** déclarer letterbox ou stretch meilleur pour les lunettes. Conserver letterbox comme défaut réversible permet d’avancer, avec statut « qualité HTC non validée ».

**Preuve de sélection finale proposée :** constituer un petit lot HTC fixe couvrant les quatre classes alertables, objets centraux/latéraux, tailles et lumières variées, mouvements de tête et scènes négatives ; annoter classes/boîtes avant de comparer. Sur les mêmes images et le même checkpoint end-to-end, comparer les détections appariées (IoU fixé avant essai), les faux positifs, les objets manqués et les erreurs de direction. Publier les effectifs par classe et les variations d’alertes avec une politique inchangée. Les mires testent la géométrie ; elles ne mesurent pas le rappel réel. Ne pas sélectionner avec la seule confiance moyenne ni avec une scène de jury choisie après les résultats. Si aucun gain clair n’apparaît, conserver le défaut documenté ; si réglage nécessaire, vérifier ensuite sur quelques séquences nouvelles distinctes du lot de réglage. Un petit lot justifie un prototype, pas une mAP généralisable.

## 3. Objections précises à l’acquisition HTC

**Choix accepté :** essayer d’abord la sortie Image/YUV du codec, puis un seul repli explicitement nommé si elle bloque. Un `Image` détenu pendant une inférence lente peut bloquer le décodeur ; l’ownership proposé par l’agent HTC répond au risque.

**Complément nécessaire :** copier et convertir systématiquement toutes les images à 30 Hz avant de n’en inférer que quatre gaspillerait un budget que nous ne connaissons pas. Décoder correctement la chaîne H.264, sélectionner les images décodées destinées à l’analyse, puis borner les copies possédées et la conversion. L’optimisation de YUV vers le tenseur peut fusionner redimensionnement/conversion après validation des couleurs et de la géométrie ; elle ne doit pas inventer une voie « zéro copie » ni modifier silencieusement le prétraitement. Mesurer séparément copie YUV, couleur, rotation, redimensionnement et remplissage du tenseur ; regrouper les étapes seulement si l’instrumentation ne peut les séparer proprement.

**Réserve sur PixelCopy :** une requête de copie récente ne prouve pas que la surface contient une image récente. Si le PTS ou une autre preuve de renouvellement de l’image ne peut pas être corrélé au résultat, on ne peut pas satisfaire honnêtement la garde de 500 ms en démarrant le chronomètre à la demande PixelCopy. Le repli reste utilisable comme démonstration partielle avec aperçu et fraîcheur limitée documentée ; il n’obtient pas automatiquement la recette F02/N02/N03/N07. Un overlay compteur de frame peut aider au test, sans remplacer le contrat temporel de production.

L’alerte TTS partage les ressources avec la vidéo et le calcul. Je soutiens HTC-03 très tôt : découpler l’horloge vidéo du retour micro, puis éprouver vidéo + voix ensemble avant de conclure à la viabilité d’une session.

## 4. Objections et accord sur le portage métier

Je soutiens l’assistance descriptive RGB, les directions de caméra, la distance inconnue et les règles métriques inactives. Les seuils Swift restent des points de départ séparés de la preuve de précision du nouveau modèle.

**Objection à une seule liste déjà filtrée pour tout :** l’association des pistes ne doit pas recevoir uniquement les candidats qui ont déjà franchi les seuils d’alerte visuelle de 0,82–0,86. Une confiance qui oscille autour de ce seuil pourrait provoquer disparition/réapparition, nouvel identifiant et répétition de la voix pour le même objet. Prévoir un seuil de conservation pour l’association, distinct de la qualification d’annonce, puis tester son hystérésis et l’expiration. Le seuil de détection Swift 0,70 constitue une référence initiale traçable, pas un seuil de tracking validé.

**Accord avec l’orchestrateur sur le plafond :** conserver les sorties bornées par `max_det` de l’export (attente 300 à vérifier), puis filtre de détection et association ; effectuer la sélection métier ensuite. Pas de coupe arbitraire aux huit meilleures confiances avant le suivi et les critères de zone. Le plafond huit et la NMS agnostique restent dans le profil de référence iOS si l’on reproduit celui-ci ; ils ne sont pas imposés à l’export end-to-end SILMO.

Une liste bornée de 300 ne signifie pas qu’on annonce 300 objets : mémoire et ordonnanceur audio restent bornés séparément. Le scénario « objet central pertinent derrière huit objets périphériques de confiance supérieure » doit révéler tout retour involontaire de ce raccourci.

La proposition classe/zone est un repli honnête si les pistes 2D sont instables, mais elle change F06 : il faut alors enregistrer cette dégradation, parler de présence collective et ne pas déclarer le critère d’identités temporaires entièrement satisfait.

## 5. Parité : tolérances figées et comparaison ensembliste

Les tolérances proposées restent **1 pixel par coordonnée dans le repère 416 × 416 et 0,001 de score** pour FP32. Elles sont enregistrées dans le manifeste de validation avant la première comparaison. En cas d’échec, conserver l’échec et les différences ; ne pas agrandir les tolérances pour rendre le rapport vert. Une modification ultérieure exige une nouvelle version du protocole et un motif indépendant.

Le protocole proposé distingue :

1. **Contrat strict** : même checkpoint, branche, tenseur FP32 d’entrée et configuration ; dimensions/types/classe valides, pas de valeurs non finies. Les conventions NCHW, RGB et plage numérique deviennent des mesures vérifiées, pas des suppositions.
2. **Ensemble de détections** : appariement un-à-un de même classe, invariant à l’ordre TopK ; une paire n’est admissible que si chaque écart de coordonnée et de score respecte les tolérances. Publier tous les éléments non appariés, y compris ceux sous le seuil métier ; ne pas comparer seulement les index des lignes.
3. **Frontières** : identifier à l’avance les bandes de tolérance autour du seuil de confiance, du rang `max_det`, des frontières de zone et des seuils de sélection. Un échange de rang entre sorties proches n’est pas une erreur d’ordre ; une apparition/disparition ou un changement de classe reste une divergence visible. Les cas aux frontières sont analysés, jamais supprimés du rapport.
4. **Effet métier** : appliquer les mêmes politiques aux résultats. Si une petite divergence numérique change une phrase, une direction, une sélection ou un état de session, le scénario fonctionnel reste en échec/non résolu jusqu’à correction ou arbitrage explicitement documenté. La conformité numérique ne suffit pas à revendiquer une sortie métier identique.

Si TopK masque la cause d’un cas limite, instrumenter une comparaison des sorties pré-TopK comme diagnostic ponctuel ; cela ne justifie pas de changer la branche de déploiement. La vérification sur tenseur commun précède le test du prétraitement Android afin de ne pas mélanger les causes d’écart.

## 6. Cadence, démarrage froid et budget réel

Une inférence de 600 ms dont tous les résultats sont rejetés à 500 ms respecte une garde d’âge, mais n’est pas une assistance utilisable. Le cahier doit publier **le nombre de résultats utiles frais par seconde**, les périodes sans observation fraîche et le rapport résultats acceptés/inférences terminées. Les images décodées volontairement ignorées avant inférence et les calculs achevés trop tard sont deux compteurs différents.

Mesurer le chargement et le premier résultat froid à part. L’état « modèle chargé » ne vaut pas « session prête » ; une nouvelle image valide après initialisation doit rendre le pipeline opérationnel. Puis mesurer la phase chaude et le début/fin de l’essai de dix minutes. Un p95 calculé uniquement sur les quelques résultats retenus masquerait les rejets : rapporter latence de toutes les inférences démarrées, nombre d’abandons et latence des décisions réellement livrées.

La cible 4 Hz et le plafond de 500 ms ne sont pas interchangeables. La chaîne réception→pixels→prétraitement→inférence→décision doit être mesurée ; décision→soumission TTS et soumission→son s’ajoutent dans un rapport séparé. Revalider l’âge de l’observation avant soumission audio. Si l’attente audio expire constamment les alertes, le remède porte sur la cadence/longueur des phrases et l’ordonnanceur, pas sur le seul backend ML.

## 7. Modifications demandées au cahier des charges

| Référence | Ajustement proposé |
|---|---|
| F02, N04, V02 | Ajouter le repère d’image orientée, la transformation modèle→image, l’âge de la frame réelle et la durée de possession/copie des buffers ; une date de demande PixelCopy ne remplace pas la fraîcheur du contenu. |
| F03, V04 | Inscrire deux profils nommés : SILMO letterbox fixe provisoire, Swift `.scaleFill` témoin ; contractualiser RGB/NCHW/normalisation après vérification. Ne pas prétendre sélectionner la meilleure géométrie sans lot HTC annoté. |
| F04, F06, V05 | Séparer filtre de détection, association et qualification d’alerte ; ajouter oscillation de confiance et plus de huit objets avec candidat central pertinent. Aucune NMS/top8 héritée implicitement sur la sortie end-to-end. |
| N03, N05, V07 | Ajouter rendement de résultats frais, fenêtres sans observation utile, rejets tardifs, phase froide et chaude ; ne pas valider une session qui rejette tous ses calculs. Définir la cadence cible sur les résultats utiles, pas seulement les lancements. |
| N06, V04 | Fixer tolérances avant essai, appariement ensembliste un-à-un, cas proches des seuils et impact métier ; conserver les échecs et la version du protocole. |
| §3 / optimisation | CPU EP = référence de correction ; CPU/XNNPACK = petit comparatif initial du même runtime. Les autres formats/quantifications restent ultérieurs sur problème mesuré. Cela évite que « accélération P1 » interdise ce comparatif limité. |
| §8 / option iOS | Futur export Core ML SILMO séparé, nommé et haché ; `best.mlpackage` Paris V1 reste intact. Partager contrats et fixtures sans promettre équivalence des poids ni compatibilité d’export. |

**Décision proposée après contradiction :** ONNX FP32 end-to-end, correction CPU puis comparatif limité CPU/XNNPACK ; letterbox fixe comme défaut SILMO réversible, stretch comme témoin Swift ; sélection métier après sortie bornée/association ; aucun verdict de performance sans résultats frais utiles mesurés sur le HTC. Qualité du prétraitement, seuils, format de pixels et TTS restent conditionnés aux essais prévus.
