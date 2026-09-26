# Conflit vidéo / voix du SDK HTC 0.6.0

Analyse du 26 septembre 2026, à partir du SDK livré par HTC et de la nouvelle preuve sur `CN46V3M00284`. Ce rapport ne transpose aucune mesure de l'ancien téléphone.

## Verdict actuel : annonces stéréo automatiques entendues pendant la vidéo

L'APK final stéréo SHA-256 `412eec7f9106b9faadda77c62dd3f41102994d66003fa7d29e8dfdfc64781199` est installé sur `CN46V3M00284`, sans effacement des données (`new-device-CN46V3M00284/production-update-stereo.json`). Le 26 septembre à 13:49:34 UTC, l'utilisateur a confirmé « Oui, les côtés sont corrects » après les annonces automatiques gauche/centre/droite pendant la vidéo. Cette confirmation est consignée dans `human-confirmations.json`, avec le hash de cet APK et `automatic_alert_audible=true`, `stereo_directions_confirmed=true`.

La simultanéité caméra réelle → détection locale → annonce automatique Bluetooth, avec latéralisation entendue dans les lunettes, est donc confirmée pour cette configuration et ces essais. Les six rapports `unit-tests-stereo/TEST-*.xml` totalisent 48 tests JVM réussis, zéro échec, erreur ou test ignoré, dont neuf tests de transformation PCM stéréo. Ces résultats ne valident pas encore l'endurance en cours ni les scénarios matériels d'arrêt/reprise, arrière-plan et diagnostic HTC. Aucun résultat d'endurance n'est anticipé dans ce verdict.

Le TTS du SDK HTC reste incompatible avec son état vidéo ; le succès utilise la synthèse locale Android et la sortie Bluetooth, en conservant la vidéo seule HTC. Les étapes et échecs ci-dessous sont conservés pour rendre cette distinction et la progression vérifiables.

## Échec réellement observé

`new-device-CN46V3M00284/voice-after-submit.xml` contient simultanément « Perception active · caméra seule », la phrase de test EchoNav et « Voix refusée : ERROR_RESOURCE_CONFLICT ». Le flux H.264 réel restait utilisé pendant la demande vocale. Cet essai invalide la simultanéité vidéo + `ViveGlass.speakText` avec la configuration audio/vidéo alors installée ; il ne constitue pas une preuve d'écoute.

Après arrêt du flux, l'utilisateur a confirmé à l'orchestrateur avoir entendu la voix HTC dans les lunettes. La sortie vocale seule est donc confirmée à l'écoute ; cette confirmation ne s'étend pas à la demande refusée pendant la vidéo.

## Source et méthode

Source : `android-project/repository/com/htc/viveglass/sdk/viveglass_client/0.6.0/viveglass_client-0.6.0.aar`, identique à l'original Downloads.

SHA-256 de l'AAR : `51d261ff5b06384c74ef518cf0d9683702d7795d2c2d48116a09884a54d6b882`.

`validation/htc-tests/sdk.jar` est identique au `classes.jar` de cet AAR, vérification binaire effectuée. Inspection de signatures et bytecode avec le `javap` du JBR 21 installé, sans modification du SDK. Exemple reproductible depuis `validation/htc-tests/` :

```bash
"/Users/sam/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home/bin/javap" \
  -classpath sdk.jar -p -c -l com.htc.viveglass.sdk.AppInteractionManager
```

Les numéros de lignes ci-dessous viennent de la `LineNumberTable` embarquée ; ils désignent les sources compilées du fournisseur, pas un fichier Kotlin local disponible.

## Faits établis par inspection

1. Une surcharge publique **vidéo seule** existe bien dans `ViveGlass`, `ViveGlassClient` et `ViveGlassKit` :

   ```kotlin
   glass.startVideoStreaming(videoFormat, videoBufferCallback, streamingEventCallback)
   ```

   `ViveGlassKit` journalise explicitement `startVideoStreaming(video only)`, contrôle seulement `hasCameraPermission()` et appelle la surcharge correspondante d'`EAEClientSdkImpl`. Sa coroutine `startVideoStreaming$2` fournit au gestionnaire interne `audioBitrate = 0`, `audioSampleRate = 44100`, les deux booléens audio à `false` et `audioCallback = null`. Elle ne construit pas d'`AudioStreamingFormat` de repli. C'est l'API prévue par ce SDK pour demander la vidéo seule ; l'absence physique d'acquisition micro n'est pas mesurée par cette inspection.

2. Cette surcharge rejoint **le même état vidéo exclusif**. Dans `AppInteractionManager.startVideoStreaming`, l'état devient `VIDEO_STREAMING` sans condition sur le format audio (ligne source embarquée 361). Dans `speakText`, toute valeur autre que `IDLE` produit `SynthesisEvent.ERROR_RESOURCE_CONFLICT` et un retour d'erreur (lignes 415–417). La garde est exécutée avant l'appel de synthèse du fournisseur. Le refus est donc explicite dans le SDK, même pour sa voie vidéo seule. Réduire l'acquisition micro ne suffit pas à supprimer ce verrou.

3. `Microphone` contient uniquement `MIC_DIRECTION_TOWARD_USER` et `MIC_DIRECTION_SURROUNDING` ; aucune valeur `NONE`. La surcharge audio/vidéo à cinq arguments vérifie que le format et le callback audio sont non nuls. Fournir un bitrate audio nul via `AudioStreamingFormat` ne désactive pas l'audio : sa validation remplace cette valeur invalide par le débit par défaut, 32 000 bit/s.

4. Le simulateur possède aussi la surcharge vidéo seule et lui transmet un callback audio nul. Cependant, son `speakText` refuse toute situation où `isVideoStreaming()` est vrai. Un essai du simulateur ne pourra donc pas établir la coexistence avec le TTS HTC.

5. L'interface publique `ViveGlassClient` inspectée ne fournit pas de commande de lecture PCM/fichier audio dans les lunettes, ni de commutateur autorisant la coexistence TTS/vidéo. Il n'est pas proposé de modifier l'état interne du SDK ou de contourner son gestionnaire.

## Décision et modification bornée

L'orchestrateur a décidé d'utiliser la surcharge vidéo seule **pour le chemin EchoNav uniquement**, indépendamment du choix futur de sortie vocale. Le manager demande maintenant seulement `Permission.CAMERA` pour ce chemin et transmet le format vidéo, le callback de buffers et celui d'événements. L'ancien callback audio ignoré est supprimé. Les gardes de session, de fraîcheur et de fin de permission sont conservées.

Les diagnostics HTC historiques conservent leur API audio/vidéo et leurs permissions micro. L'orchestrateur retire séparément la demande Android `RECORD_AUDIO` du démarrage EchoNav dans l'Activity. Aucun seuil d'âge, modèle ou traitement des images ne change.

Statut initial à la remise de cette modification : implémenté et relu statiquement. Depuis, `new-device-CN46V3M00284/production-build-bluetooth-final.log` atteste une compilation réussie et `production-update-bluetooth.json` atteste l'installation réussie sur `CN46V3M00284` de l'APK SHA-256 `aef0571aeb6ea6662fbb478413ab3924640ca4dde5eafca8d68ba8a01ffe2a18`, le 26 septembre à 13:17:03 UTC. Cette mise à jour associe vidéo seule et backend vocal Android Bluetooth, sans effacement des données. Le changement ne doit pas être annoncé comme un correctif du conflit de `speakText` : ce refus reste prédit par le bytecode.

## Expérience suivante pour la voix simultanée

Le relevé `new-device-CN46V3M00284/audio-policy-before-fallback.txt`, lignes 62–64, expose une sortie Bluetooth A2DP nommée `VIVE Eagle_32B4`. Cela justifie d'évaluer un backend Android TTS avec lecture routée vers cette sortie, indépendamment de `ViveGlass.speakText`. Le port A2DP disponible n'est pas une preuve qu'une phrase y est effectivement jouée, ni qu'il reste utilisable pendant le flux réel.

**Nouvelle preuve après installation de l'APK `aef057…` : l'utilisateur a confirmé entendre la phrase manuelle Bluetooth pendant la vidéo réelle.** Cette preuve est distincte de la voix HTC seule confirmée plus tôt. La trace `new-device-CN46V3M00284/live-bluetooth/echonav-trace-1790429006557.jsonl` contient sa soumission à `4123542` ms, le résultat Android `COMPLETED` à `4127237` ms et 13 décisions de perception entre ces événements. La confirmation acoustique vient de l'utilisateur, pas du seul événement Android.

La même trace révèle cependant **35 annonces automatiques expirées sur 35 tentatives**, après confirmation de l'orientation : la simultanéité manuelle est établie, mais les annonces automatiques ne sont pas opérationnelles sur cette version. Le délai journalisé entre soumission et résultat `EXPIRED` va de 309 à 821 ms ; ce champ inclut le travail du backend et ne mesure pas isolément l'amorçage d'`AudioTrack`. L'âge de la décision immédiatement antérieure à chaque soumission varie de 234 à 370 ms, médiane 286 ms. Ce dernier rapprochement est contextuel : `speech_submitted` ne contient pas l'identifiant de frame. La lecture du code montre que chaque tentative ouvre à nouveau son `AudioTrack`, puis attend la route effective avant d'appliquer la garde stricte de 500 ms : la préparation de la sortie consomme donc le temps restant pour annoncer cette observation.

Correction maintenant implémentée et relue, pas encore validée physiquement par ce rapport : conserver un `AudioTrack` silencieux pendant la session, via `setSessionActive(Boolean)`, et réutiliser exactement cette sortie préparée pour le PCM vocal. La date d'observation et la limite de 500 ms restent inchangées ; un résultat ancien ne devient jamais frais parce que la route audio est enfin disponible. L'arrêt de session, la perte de route et les callbacks tardifs sont inclus dans la revue indépendante ci-dessous.

Recette proposée : démarrer le flux vidéo seul, confirmer des décisions fraîches avant/pendant/après une phrase française, vérifier la route audio effective et obtenir la confirmation d'écoute dans les lunettes. Éprouver ensuite arrêt et perte de la route, sans repli sonore silencieux vers le haut-parleur du téléphone. Les événements de ce backend devront utiliser leurs propres identifiants et conserver la réservation vocale commune ; ils ne peuvent pas être assimilés aux callbacks anonymes du SDK.

L'autre voie est une version ou une API officiellement supportée par HTC permettant cette coexistence. Le cas à leur transmettre est précis : état `VIDEO_STREAMING` ligne 361 contre garde `IDLE` lignes 415–417 de `AppInteractionManager`. Une alternance arrêt vidéo → phrase → redémarrage resterait une dégradation distincte, jamais une chaîne simultanée validée.

## Revue statique de l'intégration Android Bluetooth installée

Relecture bornée de `EchoNavController.kt`, `BluetoothSpeechBackend.kt`, `BluetoothPcmPlayer.kt` et `LocalFrenchSynthesizer.kt` pour la version `aef057…` ; aucune modification des sources, aucun ADB ni Gradle exécuté pendant cette revue. Ces constats ne valident pas encore la correction ultérieure maintenant la route ouverte. Aucun nouveau défaut bloquant identifié dans les chemins d'expiration, d'annulation et de callbacks tardifs examinés :

- Le contrôleur distingue HTC et Bluetooth, réserve une demande unique avec UUID, ignore les résultats Bluetooth d'une autre demande et retire la demande avant l'arrêt local. Les résultats Android sont remis sur Main.
- `canStart` recontrôle les 500 ms avant le premier PCM vocal ; un résultat `EXPIRED` libère la réservation et attend une nouvelle observation, sans confirmer une annonce ni désactiver durablement l'automatique. `canContinue` invalide la session pendant l'émission et sa vidange.
- Le lecteur vérifie `routedDevice` après un amorçage silencieux, puis avant l'écriture vocale. Les anciens callbacks de focus et de route n'arrêtent que leur propre `AudioTrack`. Une annulation entre la fin worker et le callback Main ne devient pas `COMPLETED`.
- Les callbacks de synthèse portent un identifiant distinct ; la synthèse fichier n'est pas elle-même annoncée comme une lecture. `COMPLETED` repose sur la progression de lecture Android, pas sur une preuve d'écoute humaine.

Limites inchangées : la garde de fraîcheur borne l'âge avant écriture PCM, pas le temps jusqu'au son entendu après les tampons Android/Bluetooth. La préférence de périphérique et les gardes de changement de route ne démontrent pas l'absence absolue de transition sonore vers une autre sortie ; arrêt et perte Bluetooth demandent encore une recette physique.

## Revue indépendante de la correction maintenant la route ouverte

Sources audio remises et gelées par leur propriétaire après revue croisée ; compilation et essai de la nouvelle version restent pilotés par l'orchestrateur. Aucun nouveau défaut bloquant identifié dans ce périmètre de lecture :

- Le PCM vocal utilise le même `Lease` et le même `AudioTrack` que l'amorçage. La sortie ne devient prête qu'après routage VIVE effectif, progression de `playbackHeadPosition` et réduction du silence restant à 40 ms au maximum. L'amorçage respecte `startThresholdInFrames` effectif ; Android précise que ce seuil peut différer de la demande et changer avec la sortie. [Documentation AudioTrack](https://developer.android.com/reference/android/media/AudioTrack#setStartThresholdInFrames(int)).
- L'ordonnanceur n'écrit que du silence ; il laisse la main aux blocs vocaux. La position de fin de phrase est figée après le dernier bloc vocal : les silences suivants ne repoussent pas indéfiniment la confirmation. L'expiration avant PCM conserve la sortie préparée sans confirmer la phrase.
- `canStart` garde les 500 ms sur l'observation originale avant la première écriture vocale ; aucun horodatage de détection n'est réinitialisé par la préparation audio. `canContinue` et la génération invalident la demande pendant l'émission.
- La perte de focus avant même l'affectation du `Lease`, repérée en revue, est maintenant mémorisée et bloque son installation. Les callbacks de focus et de route ciblent uniquement leur `Lease`. La perte de route hors préparation donne un état explicite invitant à arrêter et redémarrer.
- Le callback de changement d'état du lecteur poste sur Main. Les prédicats appelés sous son verrou lisent seulement des atomiques ou l'état thread-safe du contrôleur : aucune inversion de verrou backend/lecteur trouvée. Si arrêt et redémarrage surviennent pendant un ancien amorçage, sa version devient invalide ; sa fin programme la préparation de la nouvelle version active.
- Le contrôleur active cette préparation uniquement pour une session Bluetooth réelle et la désactive dans `stop`, également traversé par `close`. Le lecteur libère piste et focus ; sa fermeture termine l'ordonnanceur de silence.

Cette revue traite la cause observée des 35 expirations mais n'atteste pas encore leur disparition sur appareil. La prochaine preuve doit montrer des annonces automatiques issues d'observations réelles, des décisions vidéo simultanées et une confirmation d'écoute, puis vérifier arrêt et perte de route.

## Premier essai de route maintenue ouverte : préparation refusée

`new-device-CN46V3M00284/production-update-warm-route.json` atteste l'installation de l'APK `b682e0ceff4fa887fcfeac466ee594dcb12a5f5abd120aa0889ba46714a9fac7` sur le même téléphone. `ui-warm-orientation.xml` montre ensuite « Route audio indisponible : Route VIVE non confirmée · arrêter puis redémarrer » alors que la perception vidéo reste active. Cette version n'a donc pas validé le fonctionnement des alertes automatiques ; sa préparation audio, bornée à 1,5 seconde, a échoué.

Le correctif ciblé suivant est implémenté et relu statiquement, sans nouvelle preuve matérielle dans ce rapport : tant que la tête de lecture reste à zéro, l'amorçage peut remplir le `bufferSizeInFrames` réel, au lieu de s'arrêter au seuil de démarrage déclaré. Les écritures restent non bloquantes et bornées par ce tampon. Après le début de consommation, le silence doit toujours redescendre à 40 ms au maximum, avec la bonne route VIVE, avant que le backend soit prêt. Le délai de préparation passe à 3 secondes ; la limite de 500 ms avant PCM vocal est inchangée.

Les nouveaux événements `EchoNavAudio` (`warmup`, `warmup_ready`, `warmup_failed`) exposent route effective et cible, tête de lecture, frames écrites, frames restantes, seuil effectif, taille du tampon, état de lecture et fréquence audio. Le diagnostic périodique est limité à une émission par 250 ms pendant la préparation. Ils permettront de distinguer absence de routage, absence de consommation et drainage incomplet. L'inspection ne suffit pas à affirmer que le remplissage du tampon résout l'échec constaté : l'essai physique suivant doit le démontrer.

## Résultat technique après correction de l'amorçage

L'orchestrateur a installé l'APK `a56bfa583b3df3f6b15e45255de7d1b2c9767dd9309e1437491ad4ce90dacfbf` sur `CN46V3M00284` (`production-update-warm-buffer.json`). Il rapporte une route prête après 448 ms et deux annonces automatiques « Piéton avant-droite » terminées avec `COMPLETED`, après des gardes d'âge de 294 et 288 ms. Il s'agit d'un progrès technique sur appareil par rapport aux 35 expirations et à l'échec d'amorçage précédents. L'utilisateur a ensuite confirmé entendre ces annonces pendant la vidéo, mais dans les deux oreilles : `human-confirmations.json` consigne cette écoute le 26 septembre à 13:42:03 UTC, avec `spatialized=false`. Cette limite a motivé la modification stéréo ultérieure ; elle ne remet pas en cause l'écoute automatique déjà établie sur cette version.

## Arrêt vidéo après 507 secondes : cause non établie

La trace froide `live-bluetooth/echonav-trace-1790429006557.jsonl` contient un démarrage à `4107325` ms, puis l'arrêt « Vidéo indisponible : SDK stopped the video stream » à `4614363` ms, soit 507,038 secondes après la demande de démarrage. Cet intervalle part de la demande de session, pas d'un timestamp de capture ni du premier buffer reçu. Il ne démontre pas une durée maximale du SDK.

Inspection bornée du SDK 0.6.0 déjà identifié par son hash :

- Ni `VideoStreamingFormat`, ni les surcharges publiques `startVideoStreaming`, ni la structure interne `StreamingManagerBridge.StreamingFormat` ne contiennent de durée maximale configurable. Les champs inspectés décrivent format, dimensions, débit, cadence et audio. Le constructeur `VideoStreamingFormat` reçoit aussi un booléen mais son bytecode ne l'utilise pas ; il ne s'agit pas d'un réglage de durée établi.
- Le délai de 15 000 ms de `BaseDeviceManager.COMMAND_TIMEOUT_MSEC` borne l'attente de réponse aux commandes, dont `DeviceMediaManager.startStreaming`. Ce n'est pas une minuterie de durée de session vidéo.
- `StreamingError.REACH_MAX_TIME` existe bien, mais sa provenance trouvée est `ExceptionEvent.MEETING_MINUTES_STREAMING_STOPPED_REACH_MAX_TIME`, chaîne `mm_streaming_stopped_reach_max_time`, traitée par `DeviceStatusManager`. Les événements `LIVESTREAMING_STOPPED_*` inspectés concernent batterie faible, surchauffe, retrait des lunettes, capteur couvert et appel entrant ; aucun équivalent vidéo `REACH_MAX_TIME` n'a été trouvé. On ne peut donc pas transposer la limite « meeting minutes » au flux caméra EchoNav, ni lui attribuer une durée de 507 secondes.
- `StreamingManagerBridge.onStopStreaming(error, …)` transmet le motif aux listeners internes, puis émet systématiquement le callback public `StreamingEvent.STOPPED`. Le texte reçu par l'application perd ainsi la précision de l'erreur interne : il est compatible avec plusieurs causes et ne permet pas de trancher.

Pour un prochain arrêt, capturer les tags `DeviceStatusManager:I`, `DeviceMediaManager:I` et `AppInteractionManager:I` en complément des traces EchoNav. `DeviceStatusManager` journalise notamment `NOTIFY_EVENT:` et `dispatchAppInteractionModeEvent`. Dans cet AAR, `com.htc.viveglass.sdk.Log.i` transmet directement à `android.util.Log.i`, même si le mode debug du SDK est désactivé. Rechercher l'événement brut précédant `STOPPED` permettrait d'établir un motif ; aucun événement « durée maximale » n'est attesté pour l'arrêt actuellement observé. Les documents locaux README/setup/troubleshooting consultés ne fournissent pas de limite de durée vidéo.

## Revue du panoramique stéréo demandé ensuite

Lecture seule des changements de contrat et du convertisseur PCM, sans compilation ni nouvelle écoute par ce réviseur. `VoiceAlert.zone` reçoit la zone du candidat réel ; le contrôleur la transforme explicitement en `SpeechPan.LEFT`, `CENTER` ou `RIGHT`, conservé avec l'identifiant de la demande. La direction n'est pas déduite du texte. Le backend HTC reste signalé `HTC_UNCONTROLLED` et ne reçoit pas de promesse de panoramique.

`LocalFrenchSynthesizer` normalise les données avant leur mise en cache : le mono est dupliqué ; le stéréo est ramené au centre par moyenne signée sans dépassement, puis dupliqué. La route maintenue ouverte est donc stéréo dès sa préparation. `SpeechPcmTransforms.panned` produit une copie indépendante en PCM16 little-endian : gauche `[sample, 0]`, droite `[0, sample]`, centre `[sample, sample]`. Le cache et les demandes ultérieures ne sont pas modifiés. La transformation s'exécute sur le worker avant la garde finale de 500 ms, avec la date d'observation originale.

Les neuf tests ajoutés ont été relus, notamment bornes signées, ordre des canaux, copie indépendante et durée limite ; ils n'ont pas été exécutés par ce réviseur. Aucun défaut concret identifié dans cette revue contractuelle. Au moment de sa remise, la preuve précédente `a56bfa…` ne validait pas la nouvelle sortie stéréo. L'orchestrateur a ensuite exécuté les tests et installé l'APK `412eec…` ; la confirmation d'écoute gauche/centre/droite est désormais consignée dans le verdict actuel en tête de ce rapport.

## Recette matérielle proposée après l'endurance, pas encore exécutée ici

Lecture seule du contrôleur, de la navigation et du manager. Aucun arrêt forcé de processus ni effacement de préférences n'est requis :

1. **Arrêt simple.** Pendant la perception, appuyer sur Arrêter et attendre trois secondes. Aperçu et boîtes doivent disparaître ; compteurs et nouvelles décisions doivent rester figés ; aucune nouvelle phrase ne doit commencer.
2. **Annulation de préparation puis reprise.** Démarrer, arrêter immédiatement pendant la préparation audio, puis démarrer à nouveau. Vérifier une seule nouvelle session, compteurs remis à zéro, route prête et absence d'arrêt provoqué par un callback de l'ancienne session.
3. **Arrêt pendant une phrase.** Lancer le test vocal Bluetooth, puis Arrêter pendant sa lecture. Vérifier la libération d'`audioBusy` et l'absence d'ancienne confirmation appliquée à une demande ultérieure. La pause des annonces automatiques après interruption est prévue par le contrôleur ; après redémarrage, un test vocal terminé les réarme. Évaluer séparément la courte éventuelle fin sonore déjà engagée dans les tampons Bluetooth, sans l'assimiler à une nouvelle soumission.
4. **Arrière-plan.** Session active, aller à l'accueil du téléphone trois secondes puis revenir. La session doit rester arrêtée ; la reprise demande Démarrer. `MainActivity.onStop` arrête le contrôleur puis les médias du manager.
5. **Diagnostic HTC puis retour.** Depuis une session active, ouvrir Diagnostic HTC : EchoNav doit s'arrêter. Ouvrir Caméra et essayer son aperçu, l'arrêter, puis revenir à EchoNav. Les ressources legacy doivent être libérées ; EchoNav reste arrêté jusqu'à Démarrer. Vérifier ensuite perception et route prête sans conflit de ressource.

Conserver les traces `start`, `stop`, `sessionId`, `speech_*` et l'observation de l'opérateur. Les résultats de ces manipulations appartiendront à une recette matérielle distincte ; le présent protocole ne les déclare pas réussies.
