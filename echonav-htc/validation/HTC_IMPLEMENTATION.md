# Implémentation HTC / vidéo — 26 septembre 2026

> Rapport historique de l’implémentation initiale et des essais sur **CN4B53M00860**. Les décisions de navigation/installations anciennes sont conservées comme historique ; la reprise actuelle ouvre EchoNav directement avec Diagnostic HTC secondaire. État courant et preuves du nouveau **CN46V3M00284** : [RECETTE.md](RECETTE.md) et `new-device-CN46V3M00284/`.

Travail dans `echonav-htc/android-project/` uniquement. Le package original Downloads et le projet Swift n'ont pas été modifiés. Le téléphone a été identifié par l'orchestrateur comme HTC U24 pro, Android 14 ; les résultats appareil ci-dessous restent séparés des résultats statiques.

## Code livré

- `ViveGlassKitManager.kt` : chemin EchoNav distinct des onglets historiques, start/stop avec invalidation des sessions, callbacks vidéo propres à chaque démarrage, permissions avec attente bornée, collecte d'événements vocaux anonymes et observation des changements de transport. Les `TODO()` des erreurs/arrêts du player historique sont remplacés par des états et logs.
- `echonav/video/EchoNavVideoDecoder.kt` : MediaCodec H.264 en sortie buffer/Image sans Surface d'aperçu et sans dépendance à l'horloge du microphone. SPS/PPS conservés ; aucune image compressée n'est volontairement supprimée pour rattraper le retard. Lorsque le codec n'a pas d'input disponible, le même paquet reste en tête. Backlog borné ; dépassement → erreur explicite et arrêt du flux par le manager.
- `echonav/video/H264Parameters.kt` : parseur Annex-B/SPS adapté du starter, avec refus des lectures tronquées et dimensions invalides.
- `echonav/video/YuvPixels.kt` : accès aux plans YUV420 selon rowStride/pixelStride/crop, conversion selon range et BT.709 lorsque signalés, sinon hypothèse BT.601 limitée à vérifier. Rotation et miroir configurables explicites. Bitmap de grand côté maximal 832 pixels, proportions conservées ; ce compromis initial doit être évalué sur les objets fins.
- `echonav/video/VideoContracts.kt` : frames possédées avec session, numéro, PTS, réception monotone exactement associée, dimensions source et transformations appliquées. État vidéo et événement de synthèse sans faux identifiant de requête.

Les traitements codec/conversion utilisent un thread dédié. Une image est proposée au plus toutes les 250 ms au départ ; les sorties codec intermédiaires sont libérées. Une image déjà âgée de plus de 500 ms au décodage, puis après conversion et juste avant transfert, n'est pas envoyée à l'inférence. Le contrôleur revalide cet âge après inférence et juste avant soumission vocale.

La file comprimée accepte au plus 96 paquets / 8 Mio et refuse un paquet individuel au-delà de 4 Mio. Un paquet restant plus de 1 000 ms en attente déclenche un échec visible. Ces bornes sont des réglages initiaux de prototype ; elles ne constituent pas un benchmark. Une saturation n'est jamais camouflée en « aucune détection ».

## API d'intégration

```kotlin
suspend fun manager.startEchoNavVideo(
    sessionId: Long,
    rotationDegrees: Int = 0,
    mirrored: Boolean = false,
    onFrame: (EchoNavVideoFrame) -> Boolean,
): Boolean
manager.stopEchoNavVideo()
manager.echoNavVideoStatus // StateFlow<EchoNavVideoStatus>

manager.speakEchoNavText(text) // Boolean : soumission, Locale.FRENCH
manager.synthesisEvents // SharedFlow<EchoNavSynthesisEvent>
manager.synthesisTransportChanges // StateFlow<Long>
manager.synthesisTransportEpoch // Long
```

Le callback image est rapide : `true` transfère la propriété du Bitmap au contrôleur, qui doit le recycler après usage ou remplacement dans son slot récent. `false` laisse le décodeur le recycler. Aucune inférence synchrone n'est permise dans ce callback. `close()` attend seulement la fin d'un éventuel transfert rapide en cours ; aucun nouveau callback image ne commence après son retour. L'arrêt manager invalide d'abord la version de requête.

La frame est tournée selon la configuration demandée, **pas certifiée droite par son existence**. La mire matérielle reste indispensable avant annonces directionnelles. Le PTS sert à retrouver l'heure monotone de réception du paquet contenant l'image ; aucune correspondance capture lunettes ↔ horloge téléphone n'est inventée. Une sortie sans PTS enregistré provoque une erreur explicite.

Le SDK impose dans l'appel vidéo actuellement utilisé un format/callback audio et les permissions caméra + microphone. Le chemin EchoNav ignore les échantillons audio entrants, sans les décoder ni les lire. Cela ne signifie pas que le microphone n'est jamais acquis sur les lunettes.

## Voix et corrélation

`speakEchoNavText` conserve le SDK HTC et choisit explicitement le français. Son booléen confirme uniquement l'appel de soumission sans exception. `false` signifie uniquement que la connexion était absente avant tout appel. Une exception SDK/IPC remonte au contrôleur et doit conduire à l'état incertain : l'absence de livraison ne peut pas être présumée.

L'événement expose `event`, `transportEpoch` observée et `receivedAtMs`. **Cette époque ne prouve pas l'époque de la phrase d'origine**, puisque le callback HTC est anonyme. Après timeout, arrêt pendant une phrase ou rupture de transport, le contrôleur doit garder un état audio incertain et interdire un nouvel envoi tant qu'aucune procédure de remise à zéro/isolation des callbacks n'a été prouvée. Ni changement de compteur, ni délai arbitraire, ni reconnexion seule ne valent cette preuve. Une commande SDK réussie n'est pas une preuve d'audibilité.

## Vérifications réalisées

| Vérification | Statut | Preuve |
|---|---|---|
| Compilation Kotlin ciblée du décodeur et primitives avec API Android 36 | RÉUSSI | `htc-tests/compile.txt` — compilateur Kotlin 2.0.21, JBR 21 |
| Compilation ciblée du manager et vidéo contre SDK HTC 0.6.0 + simulateur + classes starter baseline | RÉUSSI | `htc-tests/manager-compile.txt` |
| Sept tests JUnit de primitives | RÉUSSI | `htc-tests/junit.txt` (six initiaux), puis `htc-tests/gradle-video-unit.xml` (sept, après optimisation), `VideoPrimitivesTest` |
| Build global final et 39 tests JVM, dont un test starter | RÉUSSI | `prototype-build-final.log`, `unit-tests-final/` : zéro échec/erreur, y compris les huit tests ML de géométrie/contrat. |
| Décodage sur le HTC réel avec média embarqué | RÉUSSI APRÈS CORRECTION | `device-run02-instrumentation.log`, `device-run-02/files/video-decoder-report.json` ; premier échec conservé. |
| Replay vidéo + décodeur + XNNPACK + politique RGB pendant 60 secondes | RÉUSSI | `device-combined-final.log`, `device-final/files/combined-pipeline-report.json` ; détails ci-dessous. |
| Contrôle final de parité XNNPACK et benchmark court | RÉUSSI | Second test de `device-combined-final.log`, `device-final/files/ml_validation/onnx_xnnpack.json`. |
| Transport live des lunettes, orientation réelle et écoute FR simultanée | NON EXÉCUTÉ par cet agent | Ne peut pas être déduit des tests JVM ou du média local. |

Les tests JVM vérifient la corrélation PTS malgré réordonnancement et duplication de slices, le refus de débordement de la table sans timestamp inventé, séparation des NAL à préfixes 3/4 octets, dimensions SPS avec crop, rejet SPS tronqué et valeurs YUV noir/blanc/rouge. Ils ne valident pas MediaCodec ni le transport radio.

## Test instrumenté du décodeur

Classe : `com.htc.vive.eagle.hackathon.starter.echonav.video.VideoDecoderInstrumentedTest`.

Méthode : `bundledH264DecodesWithoutPreviewOrMicrophone`.

Le test utilise la ressource `res/raw/video_sample.mp4` fusionnée du simulateur (ou la même ressource copiée dans le harness de validation séparé) (8 153 320 octets, durée lue statiquement dans MP4 ≈ 6,43 s). MediaExtractor fournit SPS/PPS et unités d'accès, les PTS sont conservés, les 100 premières images sont alimentées au rythme du média. Le test exige au moins cinq images échantillonnées, un débit plafonné, une corrélation exacte des PTS, des images fraîches et bornées en dimensions. La première image est comparée à un décodage indépendant MediaMetadataRetriever au même PTS pour détecter une erreur grossière de couleur/crop ; cette tolérance ne remplace pas une validation colorimétrique. Enfin, aucun nouveau callback ne doit survenir après `close()`.

Ce test fonctionne sans Surface d'aperçu, sans écoute du microphone et sans lunettes connectées. Son succès prouve le décodage **sur le téléphone** avec le fichier fourni ; il ne prouve pas l'acquisition HTC live, le TTS, l'orientation de caméra ni le comportement thermique prolongé.

## Premier essai téléphone : échec conservé puis correction

Le premier essai dans le harness sur U24 pro a produit des images, mais échoué au contrôle de fraîcheur : la garde était évaluée avant la conversion YUV, et celle-ci pouvait faire dépasser les 500 ms avant livraison. L'échec est conservé intégralement dans `device-tests-first-failure/` ; il n'est pas masqué par la relance.

Correction : seconde garde juste avant handoff, après conversion, avec recyclage de toute image périmée. L'accès `image.planes` est maintenant fait une seule fois par image (l'ancienne boucle pouvait provoquer un très grand nombre d'accès/clones), les plans sont lus en bloc, les strides sont cachés et les coefficients colorimétriques pré-calculés. Un test compare exactement 16 384 combinaisons YUV au total sur quatre profils à la formule de référence. Les sept tests JVM passent après modification.

Le test appareil écrit maintenant `files/video-decoder-report.json` et les logs `EchoNavVideoTest` : âges au dispatch et à l'entrée du callback, durée de conversion, PTS, nombre d'images et rejets avant/après conversion, codec et erreur RGB face au décodage indépendant. La relance menée par l'orchestrateur est réussie, avec les mesures ci-dessous. Les 500 ms n'ont pas été augmentés.

## Résultat appareil — relance 02

Le test instrumenté a **réussi** sur HTC U24 pro / Android 14, dans `com.echonav.silmo.validation`. Preuves : `device-run02-instrumentation.log` et `device-run-02/files/video-decoder-report.json`.

| Mesure du fichier local | Résultat |
|---|---|
| Codec | `c2.qti.avc.decoder`, H.264 matériel |
| Entrées / sorties | 102 paquets dont configuration, 100 images décodées, 13 Bitmaps livrés |
| PTS des Bitmaps livrés | De 0 à 3,3 s, association exacte aux entrées |
| Âge entrée téléphone du replay → livraison Bitmap | Minimum 75 ms, médiane 101 ms, maximum 176 ms |
| Conversion YUV → Bitmap | Minimum 57,11 ms, médiane 82,37 ms, maximum 127,91 ms |
| Périmés avant / après conversion | 0 / 0 |
| Taille | 467 × 832 pixels, ratio conservé depuis 480 × 856 |
| Écart couleur moyen absolu avec le décodage indépendant | 11,57 niveaux sur 255, sous la tolérance diagnostique de 35 |
| Arrêt | Aucun nouveau callback après retour de `close()`, assertion réussie |

Treize échantillons ne suffisent pas à caractériser une latence soutenue : le percentile 95 par rang le plus proche est ici égal au maximum et ne doit pas être extrapolé. Cette mesure **exclut transport radio, YOLO, politique d'alerte et TTS**. Ce test de quelques secondes ne constitue pas la recette continue de dix minutes. L'endurance ML séparée menée par l'orchestrateur ne doit pas être présentée comme une endurance vidéo+ML+voix complète.

## Respect du starter installé

L'utilisateur a demandé de conserver le starter installé. L'orchestrateur a utilisé un harness séparé `com.echonav.silmo.validation`, contenant la ressource vidéo du simulateur, pour mesurer codec/modèle/cœur sans remplacer cette application. Ce paquet différent ne revendique pas l'autorisation HTC et ne valide aucun appel SDK live. L'APK EchoNav dérivé du starter n'a pas été installé sur le téléphone.

## Limites à vérifier lors de la recette

1. Le contrat SDK réel doit fournir des paquets Annex-B complets et des PTS exploitables. Une fragmentation arbitraire, des PTS réutilisés pour plusieurs images distinctes ou un autre framing échouent explicitement ; ils demanderaient un assembleur documenté.
2. La sortie codec Image/YUV, la couleur et le crop ont été vérifiés avec le média local sur U24 pro ; ils restent à vérifier avec le flux réel des lunettes. Un changement SPS/PPS en cours de session entraîne un redémarrage requis.
3. Conversion à 832 pixels et 4 Hz au maximum, bornes d'âge et de backlog sont initiales. Le p95 combiné du replay est mesuré ci-dessous ; les poteaux fins, la latence terrain et l'endurance de dix minutes de la chaîne complète restent à évaluer.
4. Les corrections de backpressure concernent le chemin EchoNav nouveau ; le player d'aperçu historique garde sa conception de lecteur multimédia et n'est pas utilisé pour l'inférence.
5. Ni capture→réception absolue, ni annulation TTS, ni audio hors ligne, ni préemption/spatialisation ne sont déclarées validées.

Références API utilisées : [MediaCodec/getOutputImage](https://developer.android.com/reference/android/media/MediaCodec#getOutputImage(int)), [Image/Plane](https://developer.android.com/reference/android/media/Image.Plane).

## Revue croisée ML depuis l'intégration vidéo

Revue bornée en lecture seule de `OnnxObjectDetector.kt`, `LetterboxPreprocessor.kt`, `YoloTensorContract.kt`, manifeste des assets, contrat Kotlin et choix du provider dans le contrôleur. Aucun bug critique reproductible supplémentaire trouvé dans le parsing `[1,300,6]`, la table des classes, le checksum, le cycle de vie ORT ou la propriété des pixels. Le contrôleur sélectionne explicitement XNNPACK, dont le statut diffère de celui du CPU EP.

Écart mineur signalé puis corrigé : la transformation inverse letterbox employait le facteur uniforme avant arrondi, alors que le resize réalise des dimensions entières. Exemple vérifiable : 467 × 832 → 234 × 416, facteur nominal 0,5 ; une abscisse caméra 0,3898 projetée par le resize réel pouvait revenir vers 0,390635. L'écart est inférieur à un pixel, mais peut franchir une frontière de zone. L'ancien test géométrique reconstruisait ses coordonnées avec le facteur nominal et n'exerçait pas ce cas. L'agent ML a corrigé l'inverse avec `resizedWidth`/`resizedHeight`, et ajouté des tests aux quatre frontières proches de 0,39/0,61, sur quatre dimensions portrait/paysage et les bords du padding. Cette correction a été relue et approuvée par l'agent HTC ; elle ne change ni le prétraitement ni les sorties brutes du modèle. Les tests finaux passent. Preuve : `LETTERBOX_GEOMETRY_REVIEW.md`. Aucune modification ML n'a été faite directement par cet agent.

Le test combiné ci-dessous mesure désormais leur cadence simultanée sur le média local. Le resize vidéo initial et le prétraitement letterbox ne sont pas une validation de qualité sur les poteaux vus depuis les lunettes. La matrice de sources distingue toujours entrée téléphone du replay, capture réelle et sortie sonore.

## Test combiné — résultat final sur téléphone

`CombinedPipelineInstrumentedTest.sixtySecondReplayPipelineAndStopGuard` relie réellement, sur téléphone, replay H.264 → `EchoNavVideoDecoder` → slot récent borné → `OnnxObjectDetector` XNNPACK → `RgbAlertEngine` → sink vocal factice confirmé. Durée d'alimentation par défaut : 60 secondes ; paramètre de test `pipelineSeconds` borné entre 20 et 60. Aucun seuil du modèle, du moteur ou de fraîcheur n'est modifié.

Le rapport `files/combined-pipeline-report.json` contient cadence utile, toutes les tentatives et leur âge (y compris rejetées), coûts de conversion/prétraitement/inférence, remplacements du slot, périmés et sorties du sink factice. En fin d'essai, un résultat issu d'une véritable inférence est retenu, la session est arrêtée, puis ce résultat est libéré : il doit être invalidé avant toute décision/voix. Un petit cas de détections synthétiques exerce séparément une annonce positive et le rejet du ticket après arrêt, sans compter comme résultat du modèle ou du replay.

Le test a **réussi**, après l'endurance ML séparée, sur le HTC U24 pro dans le harness distinct. Le même lancement a aussi réussi le contrôle XNNPACK court : `device-combined-final.log` conclut `OK (2 tests)`. Mesures relues directement dans `device-final/files/combined-pipeline-report.json` :

| Mesure combinée | Résultat |
|---|---|
| Durée de replay | 60,169 s |
| Paquets / images décodées | 1 806 / 1 804 |
| Bitmaps livrés / décisions acceptées | 225 / 224 |
| Cadence utile | 3,72 décisions/s |
| Âge réception replay → décision | Min. 247 ms ; médiane 297 ms ; p95 313 ms ; max. 486 ms |
| Rejets de fraîcheur avant/après inférence | 0 / 0 |
| Rejets de fraîcheur au décodeur avant/après conversion | 0 / 0 |
| Remplacements dans le slot récent | 0 |
| Inférence XNNPACK | Médiane 153,84 ms ; p95 162,93 ms ; max. 357,33 ms |
| Prétraitement | Médiane 47,49 ms ; p95 54,86 ms ; max. 112,35 ms |
| Résultat réel retenu puis invalidé après arrêt | 1, avec assertion réussie |
| Annonces issues du modèle sur ce replay | 0 |
| Cas positif synthétique, distinct du replay | « Piéton devant », confirmation factice et rejet du ticket après arrêt réussis |

Les 224 tentatives normales sont toutes fraîches ; le percentile « toutes les tentatives » est donc identique au percentile des seules décisions acceptées. Le 225e Bitmap correspond à l'inférence volontairement retenue pour vérifier l'arrêt : son résultat a été invalidé, sans nouvelle annonce. Les percentiles sont calculés par rang le plus proche. Le replay négatif n'a généré aucune annonce ; le cas synthétique positif ne prouve ni une détection réelle ni la restitution sonore.

Cette preuve porte sur la **réception du replay dans le téléphone → décision RGB**, avec les pixels réellement décodés et le modèle réel. Elle exclut capture lunettes, transport SDK/radio et son réel. Une minute de replay et l'endurance ML seule ne remplacent pas la recette de dix minutes lunettes → téléphone → haut-parleurs.

## Revue de concurrence du manager

Une course a été identifiée par inspection : un événement vidéo SDK pouvait vérifier le token sur un thread non spécifié, puis fermer `echoDecoder` après un arrêt/redémarrage intervenu sur Main. Les événements vidéo et statuts du décodeur sont désormais dispatchés sur Main, puis leur token est vérifié **dans** ce bloc, avant toute mutation. Les notifications de connexion, dont `onDisconnected()` ferme également le décodeur, passent par le même exécuteur Main. Le callback vidéo historique vérifie aussi l'absence de session EchoNav dans son bloc Main. Le contrôleur appelle déjà start/stop depuis Main ; ce contrat est documenté sur le manager.

Cette correction supprime l'interleaving entre vérification et mutation sur les chemins relus. Elle ne prouve pas que le SDK identifie ou ordonne parfaitement ses notifications de connexion anonymes entre deux connexions. Aucun test appareil de ces transitions du SDK live n'a été réalisé. Le build global et les 39 tests JVM ont réussi pour cette version ; l'ajout ultérieur de l'onglet partagé demande un nouveau build, décrit ci-dessous.

## Onglet EchoNav et fonctionnalités historiques — revue suivante

Après la demande de conserver les onglets HTC autour d'un nouvel onglet EchoNav, le manager a été adapté. Les événements et buffers audio/vidéo historiques sont maintenant créés par démarrage et capturent la version de navigation ainsi qu'une version propre au flux. Un aller-retour entre Audio et EchoNav ne rend donc pas valide un ancien callback simplement parce que le mode est redevenu historique. Les événements SDK et ceux du player sont vérifiés sur Main avant mutation ; un verrou court ordonne le transfert des buffers et les arrêts locaux.

L'entrée dans EchoNav envoie les arrêts SDK historiques sans se fier à la réception préalable de `STARTED`, invalide les requêtes en attente et nettoie localement player, décodeur audio et indicateurs. Elle n'attend pas un callback `STOPPED` devenu obsolète pour libérer la lecture. Les arrêts publics audio/vidéo n'invalident que leur type de requête et protègent le propriétaire EchoNav. `startEchoNavVideo` exige le mode EchoNav, également vérifié après les autorisations. Les autorisations différées historiques sont recontrôlées avant l'appel SDK.

Les décodeurs historiques sont préparés juste avant l'appel de démarrage SDK : cela conserve les premiers buffers de configuration qui peuvent arriver avant le traitement de `STARTED` sur Main. Ce callback accuse ensuite le démarrage sans redémarrer une seconde fois le décodeur. Une exception de démarrage nettoie les ressources locales et remonte à l'appelant. Chat reste dirigé vers la réservation vocale commune du contrôleur ; aucun chemin TTS parallèle n'a été réintroduit.

Validation de cette passe à sa remise : revue statique, sans ADB ni exécution des callbacks SDK. Le nouveau build est confié à l'orchestrateur. La photo live observée séparément par l'orchestrateur ne valide pas ces changements ni la concurrence vidéo/voix. Les mesures du harness ci-dessus restent des preuves de traitement du replay et ne sont pas renommées en preuves de transport réel.
