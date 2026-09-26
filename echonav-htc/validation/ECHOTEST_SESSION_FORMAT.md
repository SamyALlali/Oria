# EchoTest — contrat de session v1

État : implémenté dans `EchoTestRecorder.kt` et les hooks du manager, compilé et installé. Trois tests instrumentés du recorder passent sur le nouveau téléphone ; trois captures réelles (20 s, 60 s et arrêt Home) sont finalisées, exportées et contrôlées. Le lecteur Mac réinfère la première et reproduit ses 48 décisions métier avec les entrées enregistrées. Périmètre, échecs numériques et preuves : [RECETTE.md](RECETTE.md) et [echotest-validation.json](new-device-CN46V3M00284/echotest-validation.json). Aucune nouvelle preuve acoustique n'est déduite de ces captures.

## Activation et périmètre

L’enregistrement est désactivé par défaut. Une action explicite dans EchoTest arrête la session de perception précédente, arme une nouvelle capture, puis redémarre la vidéo. Ce redémarrage vise à inclure les configurations codec du nouveau flux. Il ne permet pas de reconstruire des octets que le SDK n’aurait pas émis.

Sont enregistrés : les buffers vidéo H.264 reçus via le SDK, leurs informations `BufferInfo`, une copie PNG sans perte des images sélectionnées proposées au contrôleur, les sorties d’inférence disponibles et les événements que le contrôleur/manager exposent. La capture n’ajoute aucun accès au microphone.

Ne sont pas disponibles dans ce contrat : son ambiant/microphone, profondeur, pose, horodatage de capture optique dans les lunettes. Une notification vocale terminée ne prouve pas à elle seule son audibilité. La confirmation humaine reste une preuve distincte. Le fichier H.264 contient une vidéo muette.

Les PNG sont les pixels exacts proposés au contrôleur, après conversion, redimensionnement, rotation et miroir déjà appliqués par le décodeur. Ce ne sont pas les aperçus réduits de l’interface. Une PNG peut être proposée mais refusée, évincée de la file `latest`, périmée avant inférence, ou encore en vol lors de l’arrêt. L’existence d’une PNG ne prouve donc pas une inférence. `frame_delivery.accepted`, puis `inference`, puis `decision` décrivent séparément la progression effective.

## Contenu du dossier / ZIP

Les fichiers sont à la racine du ZIP, sans dossier parent supplémentaire :

```
manifest.json
video.h264
packets.jsonl
frames.jsonl
events.jsonl
frames/0000000001.png
frames/0000000002.png
...
```

Les JSONL utilisent UTF-8, un objet JSON par ligne. Les noms de fichier internes sont générés par l’application ; les métadonnées de l’utilisateur ne sont pas interprétées comme chemins.

### `manifest.json`

| Champ | Sens |
|---|---|
| `schemaVersion` | Entier `1`. |
| `kind` | `echotest-session`. |
| `sessionId` | UUID chaîne du dossier de capture, distinct de la génération vidéo. |
| `status` | `recording`, `complete` ou `incomplete`. |
| `usableForReplay` | Vrai si au moins une PNG a été écrite avec son index ; indépendant de `complete`. |
| `noMedia` | Vrai si aucune PNG n’a été écrite. Une capture vide n’est pas présentée comme scène exploitable. |
| `startedAtEpochMs` | Date de début en millisecondes Unix, pour afficher/trier ; pas pour les latences. |
| `monotonicOriginMs` | `SystemClock.elapsedRealtime()` au début de la capture. |
| `endedAtMonotonicMs` | Même horloge à l’arrêt des admissions, ou `null` tant que non finalisé / après interruption non observable. |
| `durationMs` | Intervalle entre début de capture et arrêt des admissions ; inclut préparation et autorisations. |
| `clock` | Déclaration de l’horloge monotone Android, origine au démarrage du téléphone. |
| `stopReason` | Motif de fin : action, arrêt vidéo, limite, erreur, etc. |
| `incompleteReason` | `null` sans perte détectée, sinon motif explicite. |
| `metadata` | Objet gelé fourni par le contrôleur avant lancement du flux. |
| `unavailable` | `microphone`, `depth`, `pose`, `glasses_capture_timestamp`. |
| `limits` | `durationMs=60000`, `sessionBytes=262144000`, `queuedBytes=20971520`. |
| `counts` | Nombres écrits `packets`, `frames`, `events`, compteur d’octets de données `bytes`, rejets d’identité `ignoredOtherSessionEvents`. |
| `pixelContract`, `videoContract` | Sémantique lisible des PNG et du H.264. |

`complete` signifie que les travaux admis ont été écrits et fermés sans erreur détectée ; il ne signifie ni scène avec objets, ni annonce audible, ni résultat pour toute image proposée. Une capture vide peut être correctement finalisée, avec `usableForReplay=false` et une indication « sans image exploitable » dans l’état UI. Un arrêt volontaire après quelques images constitue une capture partielle dans le temps, mais peut être `complete` au sens de l’intégrité des écritures.

`counts.bytes` comptabilise les octets écrits par les flux bornés, hors manifeste et ZIP. Après une erreur d’écriture/PNG, il peut inclure des octets partiels supprimés : ce n’est alors pas une taille fiable des fichiers restants. La taille des entrées du ZIP fait foi pour le transfert. Les compteurs d’un manifeste initial récupéré après mort du processus peuvent être incomplets ; ne pas les présenter comme bilan final observé.

`metadata` inclut notamment `videoSessionId` numérique obligatoire, `source` (`htc_live` ou `htc_simulator`), `onnxSha256`, manifeste et fournisseur du modèle, identité/version/SHA-256 de l’APK si calculable, modèle du téléphone/API Android, `rotationAppliedDegrees`, `mirrored`, confirmation du repère, backend vocal, `sampleIntervalMs`, seuil de détection, configuration de la politique RGB et contrats des sorties brutes. Une provenance non calculable est signalée, jamais inventée.

### `packets.jsonl` et `video.h264`

Chaque ligne contient `packetId`, `videoSessionId`, `ptsUs`, `receivedAtMs`, `recordedAtMs`, `offset`, `length`, `flags`, `sdkBufferOffset`.

- `offset` est l’offset dans `video.h264`, `length` le nombre exact d’octets du buffer SDK capturé. Pour une session complète, ces segments contigus couvrent exactement le fichier.
- `sdkBufferOffset` conserve l’offset source du `BufferInfo`. L’extraction utilise une vue `ByteBuffer.duplicate()`, respecte ce couple offset/taille et ne modifie ni position ni limite du buffer emprunté.
- `flags` conserve le bitmask `MediaCodec.BufferInfo.flags`, y compris configuration codec. `ptsUs` reste inchangé, y compris une éventuelle valeur spéciale sur une configuration. Il n’est pas remplacé par une horloge d’audio ou une estimation.
- `receivedAtMs` est lu une seule fois à l’entrée du callback manager et transmis aussi au décodeur. Ce n’est pas l’instant de capture dans les lunettes. `recordedAtMs` est l’instant de copie/mise en file, pas une garantie d’écriture physique sur disque.
- Les octets ne sont ni transcodés ni remuxés. Le flux brut ne transporte pas à lui seul l’index temporel externe. Une vidéo remuxée pour lecture Mac reste un contexte avec correspondance temporelle estimée ; la PNG jointe par identifiants est l’image analysée de référence.

### `frames.jsonl`

Chaque ligne contient :

`captureFrameId`, `frameId`, `videoSessionId`, `receivedAtMs`, `ptsUs`, `deliveredAtMs`, `recordedAtMs`, `imagePath`, `width`, `height`, `sourceWidth`, `sourceHeight`, `rotationDegrees`, `mirrored`, `conversionMs`, `copyMs`.

`captureFrameId` numérote les fichiers PNG dans la capture. `frameId` et `videoSessionId` sont les identifiants de la chaîne de perception. `imagePath` est relatif, par exemple `frames/0000000001.png`. `width`/`height` sont les dimensions PNG exactes ; `sourceWidth`/`sourceHeight` sont celles du décodeur avant géométrie finale. `rotationDegrees` et `mirrored` indiquent les transformations déjà appliquées. Ne pas les appliquer une deuxième fois.

Les boîtes sont `xyxy` normalisées dans le repère de cette PNG. Les événements du contrôleur utilisent aussi le nom `rotationAppliedDegrees`. `deliveredAtMs` est l’instant de livraison calculé par le décodeur, avant la copie EchoTest. `copyMs` mesure cette copie synchrone, dont le coût peut augmenter l’âge de la décision ; la garde des 500 ms n’est pas relâchée.

### `events.jsonl`

Chaque événement conserve les champs du producteur et ajoute `recordedAtMs` et `recordingSessionId` (UUID de capture). `sessionId` numérique / `videoSessionId` numérique désignent la génération de perception. La jointure de référence est :

```
frames.(videoSessionId, frameId)
    ↔ events.(sessionId ou videoSessionId, frameId)
```

Événements manager : `sdk_video_event`, `decoder_status`, `frame_delivery`, `video_stop`. Le statut décodeur inclut ses compteurs de réception/décodage/livraison et de péremption avant/après conversion, la conversion récente et le codec. Le contrôleur ajoute les événements de démarrage/arrêt, orientation, inférence, décision et voix qu’il expose.

`inference` contient notamment `atMs`, `receivedAtMs`, `observedAtMs`, `ptsUs`, dimensions, transformations, source, `preprocessMs`, `inferenceMs`, `resultAgeMs`, `accepted`, `detectionConfidenceFloor`, et `detections` sous forme `{classId, confidence, box:{left,top,right,bottom}}`. Lorsque disponibles pour EchoTest, `rawModelOutputIncluded`, `rawModelOutput` (tenseur float32 aplati `[1,300,6]`) et `modelDetections` conservent aussi les 300 lignes normalisées sans filtre de confiance ; les boîtes vides après découpage y restent explicitement marquées `validBox=false`. Le tenseur brut exprime ses coordonnées en pixels d’entrée du modèle, pas en pixels PNG.

`decision` contient le résultat de la politique, le candidat sélectionné, l’éventuelle alerte éligible et les pistes, ainsi que les états empêchant la voix. Les événements vocaux conservent l’identité de requête et le côté lorsqu’ils sont exposés par le contrôleur. L’arrêt écrit son événement pour l’ancienne génération avant la finalisation du manager. Un résultat d’inférence qui arrive après la coupure d’admission n’est pas ajouté rétroactivement : une image sans résultat est donc un état possible du contrat.

## Propriété mémoire, bornes et arrêt

Les callbacks ne font pas d’I/O fichier. Le buffer SDK est copié pendant sa validité. Chaque bitmap est copié synchroniquement avant de transférer l’original au contrôleur ; seul le clone possédé est envoyé au thread d’écriture, puis recyclé par ce thread. Le contrôleur reste responsable de son bitmap original.

Le budget réserve la mémoire avant copie : 20 MiB maximum et 160 travaux en vol, y compris le travail en cours. Une copie de paquet utilise un verrou bref de mémoire pour conserver l’ordre d’admission entre callbacks concurrents ; aucune écriture disque ne détient ce verrou. La file n’attend pas que le disque la vide. Si le budget manque, la capture cesse ses admissions, draine ses travaux admis et signale `incomplete`; elle ne jette pas silencieusement une image pour continuer une capture prétendument complète.

Limites : 60 s depuis l’armement ; 250 MiB par session avec 1 MiB réservé au manifeste ; refus d’une nouvelle capture si le stockage EchoTest déjà présent atteint 1 GiB ; contrôle d’espace libre au démarrage/export. La limite temporelle produit une fin normale `duration_limit` et laisse la perception active. Une limite mémoire/disque, une erreur de copie, de compression, d’écriture ou de fermeture rend la capture incomplète. Aucune ancienne session n’est supprimée automatiquement.

Un arrêt interdit immédiatement de nouvelles admissions. Les réservations déjà prises, même si leur copie se termine après l’arrêt, sont finalisées avant le manifeste final. Il ne peut pas y avoir de nouvelle session active avant la fin de ce drainage. Les événements/frames/paquets d’une autre génération sont exclus ; leurs identifiants ne sont jamais réattribués à la nouvelle capture. Les erreurs tardives sont attachées à leur objet de session et ne peuvent pas arrêter une nouvelle capture.

Stop utilisateur, arrêt/déconnexion vidéo, sortie de premier plan et fermeture manager finalisent la capture. Le SDK peut produire un événement `STOPPED` sans exposer sa cause détaillée : EchoTest conserve l’événement observable, sans inventer une cause. Après interruption du processus, un manifeste encore `recording` est signalé `incomplete` au prochain chargement. Un manifeste endommagé reste visible/exportable comme capture endommagée, sans être annoncé complet.

## Export et intégrité

Le dossier et le ZIP sont privés (`filesDir/echotest/`). `export()` attend une session finalisée, travaille sur un worker I/O et crée d’abord un `.zip.part`, puis renomme le fichier terminé. Les exports sont sérialisés ; l’armement d’une nouvelle capture est refusé pendant l’export. L’interface copie ensuite ce ZIP vers l’URI choisie explicitement via SAF. Rien n’est téléversé.

Les CRC32 et tailles des entrées ZIP permettent de détecter une corruption de transfert. Ils ne sont pas une signature cryptographique. Le lecteur doit vérifier : version et statut du manifeste, présence des fichiers/index, offsets+longueurs contigus et dans le fichier H.264, PNG existantes aux dimensions annoncées, chemins relatifs sûrs, identités de jointure, CRC des entrées. Un enregistrement incomplet peut être inspecté pour diagnostic, mais ne doit pas être présenté comme acquisition exhaustive. Une preuve de SHA-256 du ZIP peut être ajoutée au journal de transfert sur Mac sans modifier le contenu d’origine.

## Tests ajoutés

Classe : `com.htc.vive.eagle.hackathon.starter.echonav.recording.EchoTestRecorderInstrumentedTest`.

1. Buffer avec offset/limite, source modifiée après copie ; bitmap muté puis recyclé ; arrêt ; contenu PNG/octet exact et index ; ZIP avec vérification CRC/tailles.
2. Saturation déterministe du budget mémoire, état incomplet ; nouvelle session et callbacks de l’ancienne génération, sans pollution.
3. Armement conservé par démarrage vidéo correspondant ; refus d’autorisation sans image, `usableForReplay=false`.

Commande à lancer par l’opérateur du téléphone uniquement :

```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.htc.vive.eagle.hackathon.starter.echonav.recording.EchoTestRecorderInstrumentedTest
```

Ces tests synthétiques vérifient le recorder Android ; une capture réelle courte, un export et son import/rejeu Mac sont nécessaires pour vérifier l’intégration transport → pixels → événements → archive.
