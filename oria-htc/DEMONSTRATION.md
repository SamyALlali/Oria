# Démonstration Oria — nouveau HTC CN46V3M00284

L’application livrée s’appelle **Oria**, s’ouvre directement sur Oria et conserve le package HTC autorisé. Deux onglets donnent accès à **Oria** pour l’assistance en direct et **Oria Lab** pour enregistrer une scène explicitement. **Diagnostic HTC** donne accès aux outils du starter. Les commandes de démarrage/arrêt restent visibles en bas, même lorsque l’aperçu portrait remplit le contenu.

L’installation sur ce nouveau HTC U24 pro Android 14 a été autorisée après sauvegarde de l’APK d’origine et des fichiers privés accessibles, puis exécutée. VIVE Connect est resté installé au même emplacement. Les preuves et le verdict physique consolidé sont dans [la recette](validation/RECETTE.md). Les anciens résultats du téléphone CN4B53M00860 restent distincts.

**Build actuel installé :** `f49def1953699349d1c189402c2a7b0229e6bd17db1ca7c0f29c54c9873eeb43`, Oria + Oria Lab, [51 tests JVM réussis](validation/new-device-CN46V3M00284/oria-final-build.json) et [installation enregistrée](validation/new-device-CN46V3M00284/production-update-oria-final.json). Les trois tests instrumentés de l’enregistreur ont réussi sur le build précédent `1c3226…`, avant le renommage interne du recorder ; son défaut d’initialisation de navigation a été corrigé avant cette livraison.

**Preuves audio et endurance antérieures :** les annonces automatiques **Bluetooth VIVE en stéréo gauche / droite / centre pendant la vidéo** ont été entendues sur `412eec7f…`, avec réponse utilisateur « Oui, les côtés sont corrects » ([registre humain](validation/new-device-CN46V3M00284/human-confirmations.json)). Le build `40175fb4…` à 333 ms a ensuite passé dix minutes instrumentées : **2,631 décisions fraîches/s, âge p95 406 ms**, garde 500 ms inchangée. L’enregistrement OriaLab ajoute une charge distincte. Le build froid `aef0571a` avait une voix manuelle audible mais des annonces automatiques expirées ; `b682e0ce` échouait à préparer la route ; `a56bfa58` annonçait en mono. Le [README](README.md) distingue ces preuves. La voix HTC propriétaire a aussi été entendue **vidéo arrêtée** ; pendant le streaming, le SDK la refuse avec `ERROR_RESOURCE_CONFLICT`.

Le mélange 70/30 demandé remplace maintenant la séparation 100/0. Les tests PCM passent ; une nouvelle écoute humaine doit confirmer le confort et les directions de ce mélange.

## Parcours jury à vérifier

1. Lunettes allumées et connectées dans VIVE Connect ; ouvrir **Oria**, simulateur désactivé, **Connecter**.
2. Attendre le modèle chargé et la voix française locale prête. Conserver **Bluetooth VIVE** comme sortie dans **Réglages Oria**. Le paquet **français (France), environ 23 Mo**, est [installé sur ce HTC](validation/new-device-CN46V3M00284/tts-fr-after.xml). Sur un autre appareil, l’installer dans les paramètres du moteur vocal Android, puis relancer Oria. Si la voix locale ou la sortie audio VIVE manque, résoudre le statut indiqué avant le test ; aucune lecture sur le téléphone ne doit être prise pour une réussite.
3. **Démarrer**, accepter la permission caméra puis attendre **route prête**. Oria prépare un lecteur stéréo et le conserve pendant la session, avec silence borné entre les phrases. Cette préparation anticipée ne change pas les 500 ms autorisées pour commencer une annonce. Le SDK fournit la vidéo seule, sans micro ; les commandes audio historiques du diagnostic ont leurs propres permissions. Si une activité externe suspend Oria, revenir et redémarrer explicitement.
4. Vérifier aperçu, haut/bas et objet à gauche puis droite. Régler rotation/miroir dans **Réglages Oria** si nécessaire. La configuration essayée a été confirmée à rotation 0°, miroir désactivé ; revérifier le repère pour chaque autre montage.
5. Pendant que les images progressent, appuyer sur **Gauche**, puis **Centre**, puis **Droite**, en attendant la fin de chaque phrase. Faire confirmer respectivement le côté gauche, les deux côtés, puis le côté droit **dans les lunettes**. Cocher ensuite **« J’ai vérifié gauche / droite dans l’aperçu et le test vocal. Autoriser les annonces d’objets. »**. « Lecture Bluetooth terminée » confirme la progression côté Android ; consigner séparément écoute humaine et continuité vidéo.
6. Présenter une puis deux personnes et observer les boîtes et annonces descriptives : objet à gauche → amplitude 70 % gauche / 30 % droite, objet à droite → 30 % gauche / 70 % droite, objet devant → deux canaux identiques au volume précédent. Les feux/panneaux restent contextuels ; aucune distance métrique ou indication de passage libre. Une expiration de l’observation attend un nouveau candidat. Après un autre échec local, refaire un test **Gauche / Centre / Droite** pour réactiver les annonces automatiques. Si la route est perdue, arrêter puis redémarrer pour la préparer à nouveau.
7. **Arrêter** : vérifier purge de l’aperçu, absence de nouvelle annonce et délai d’arrêt du son réellement entendu. La lecture locale est annulée ; un tampon Bluetooth peut ajouter du délai. Tester ensuite Diagnostic HTC → retour Oria, arrière-plan et reconnexion avec une session fraîche.

La carte voix et le contrôle d’orientation se trouvent avant l’aperçu. Une image périmée est remplacée par « En attente d’image fraîche », en gardant la hauteur de l’aperçu stable ; Arrêter reste fixe. Garder le téléphone immobile pendant la mesure de cadence. La rotation de l’Activity arrête la session ; une reconnexion peut être nécessaire. Une vidéo de replay, un sink vocal factice ou un retour logiciel ne remplacent pas la confirmation d’écoute.

Pour le test propriétaire HTC déjà entendu : arrêter la vidéo, **Réglages Oria → HTC · diagnostic → Tester la voix**. Revenir ensuite à **Bluetooth VIVE** à l’arrêt. La voix HTC n’est pas une solution de repli pendant la caméra. Les fonctions Glasses, Chat, Audio et Camera restent accessibles par **Diagnostic HTC** ; Chat partage le même gate vocal et la sortie sélectionnée.

Les essais de perte Bluetooth et de réapparition doivent vérifier le son entendu et les transitions de l’UI. Le lecteur refuse de commencer sans route VIVE observée et s’arrête sur changement détecté ; le routage système n’offre cependant pas une garantie absolue de zéro fuite sonore vers une autre sortie.

## Oria Lab : téléphone → Mac → image par image

1. Sur le téléphone connecté aux lunettes, ouvrir **Oria Lab**. Attendre « Modèle prêt » et choisir la source réelle ; le simulateur est étiqueté séparément. Appuyer explicitement sur **Enregistrer une scène**. Cette action arrête une perception précédente, arme la capture puis redémarre la vidéo pour conserver ses en-têtes codec.
2. Filmer la scène souhaitée. Durée, paquets, images et octets sont affichés ; **Arrêter et finaliser** reste fixe. Passer sur Oria conserve cette capture et affiche **Oria Lab ●**. Un retour sur Oria Lab ne crée pas une autre capture.
3. Appuyer sur **Arrêter et finaliser**, puis attendre la sauvegarde. À 60 s ou 250 Mio, l’enregistrement se termine automatiquement ; la perception peut continuer et doit être arrêtée avec **Arrêter la vidéo**. Les captures incomplètes ou sans image exploitable sont signalées. Le stockage total est borné à 1 Gio, sans suppression automatique.
4. Brancher le téléphone par USB au Mac et autoriser son débogage USB. Double-cliquer [Récupérer depuis HTC.command](<oria-lab-transfer/Récupérer depuis HTC.command>). Le lanceur récupère la capture finalisée la plus récente dans `~/Documents/Oria Lab Captures/`, puis l’importe et ouvre automatiquement le lecteur Mac. Les captures du téléphone restent intactes. Garder le terminal ouvert ; Ctrl+C ferme le serveur local.
5. Dans **Observations du téléphone**, utiliser précédente/suivante, la timeline, lecture/pause et la vitesse. Les touches ←/→ parcourent les PNG ; espace commande la lecture. Les boîtes sont celles des images exactes fournies au modèle, avec les décisions et horloges enregistrées.
6. Dans **Recalcul sur ce Mac**, relancer ONNX et la politique Kotlin. Comparer par exemple une confirmation après deux observations puis après une seule. Chaque recalcul parcourt chronologiquement la scène ; revenir dans la timeline consulte les résultats calculés, sans réinitialiser artificiellement la mémoire à chaque image. La voix est **simulée**, avec délai configurable : aucun son n’est joué et le contrôleur/transport Bluetooth réel n’est pas reproduit.
7. La vidéo H.264 complète fournit une vue de contexte séparée. Lecture/pause et pas nominal de 1/30 s ont été vérifiés. Sa conversion MP4 reconstruit une horloge à 30 fps ; elle ne remplace ni les PTS source ni les horloges de réception des PNG pour comparer les détections.

Si plusieurs téléphones sont branchés, choisir explicitement le HTC. Cette commande peut être lancée depuis n’importe quel dossier :

```bash
python3 "/Users/sam/Documents/ChatGPT/Hackathon SILMO/oria-htc/oria-lab-transfer/pull_capture.py" --serial CN46V3M00284 --open
```

Pour choisir une capture particulière, ajouter `--session <UUID>`. Voir [le guide USB](oria-lab-transfer/README.md) et [le guide du lecteur](oria-lab-desktop/README.md).

**Alternative sans récupération ADB :** vidéo arrêtée et capture finalisée, appuyer sur **Exporter ZIP** sur le téléphone, choisir la destination dans le sélecteur Android, puis ouvrir ce ZIP dans [Lancer Oria Lab.command](<oria-lab-desktop/Lancer Oria Lab.command>) sur Mac. Le bouton **Revoir les images** permet aussi une galerie précédente/suivante sur téléphone. L’export n’envoie rien à un serveur externe ; le lecteur Mac est lié à `127.0.0.1`. **Recette acquise :** [export SAF vers Downloads](validation/new-device-CN46V3M00284/oria-lab-saf-export.json) avec 160 fichiers, CRC et empreintes identiques au ZIP USB ; galerie téléphone vérifiée sur ses deux premières images (frameId 1 puis 6, zéro puis une détection) ; [Home pendant une nouvelle capture](validation/new-device-CN46V3M00284/oria-lab-background-manifest.json) provoque arrêt/finalisation à 17,177 s, 37 PNG et 204 paquets, puis [retour sans relance](validation/new-device-CN46V3M00284/oria-lab-ui/background-return.json). Le motif de trace est « Application en arrière-plan · session arrêtée » ; celui du manifeste est `video_stopped`.

Les preuves réelles déjà acquises sur `9dbaec1f…` sont :

| Capture | Résultat |
|---|---|
| [`370bbc92-b096-499e-a1fe-5e5f501bb63c`](validation/new-device-CN46V3M00284/oria-lab-first-transfer.json) | 20,219 s ; 570 paquets ; 49 PNG ; 48 inférences ; finalisée et transférée sans perte de file. |
| [`317ae6fc-8114-4cd2-90f3-823414be3255`](validation/new-device-CN46V3M00284/oria-lab-60s-transfer.json) | 60,067 s ; 1 746 paquets ; 155 PNG ; 154 décisions fraîches, 2,564 Hz, âge p95 290,35 ms ; arrêt `duration_limit`, finalisée sans saturation. |

Le lecteur Mac a importé et réinféré les 49 images de la première capture, puis été vérifié visuellement en pas à pas, lecture/pause et recalculs avec deux configurations. La vidéo de contexte est 480 × 856 et 18,966 s ; sa durée nominale diffère de la durée complète de capture. Le [rapport de comparaison](validation/new-device-CN46V3M00284/oria-lab-real-replay.json) confirme **48/48 décisions Kotlin identiques** avec les détections Android et leur horloge exacte, et **41/48 images conformes à la parité brute ONNX stricte, 7 échecs conservés**, sans détection au seuil 0,70 affectée. Les tolérances ne sont pas relevées pour masquer ces substitutions à faible score. L’orientation était non confirmée sur le téléphone pendant les captures et essais de cycle de vie : ils ne prouvent pas une nouvelle annonce sonore ; les sorties vocales du recalcul sont explicitement simulées.

## Mettre à jour notre installation

Après cette première installation autorisée, notre même clé permet une mise à jour conservant les données :

```bash
cd "/Users/sam/Documents/ChatGPT/Hackathon SILMO/oria-htc"
/Users/sam/Library/Android/sdk/platform-tools/adb -s CN46V3M00284 install -r artifacts/oria-silmo-debug.apk
/Users/sam/Library/Android/sdk/platform-tools/adb -s CN46V3M00284 shell am start -n com.htc.vive.eagle.hackathon.starter/.MainActivity
```

Vérifier d’abord le numéro avec `adb devices -l`. Ne pas remplacer le package ni désinstaller VIVE Connect. Le retour à l’APK original de signature différente imposerait à nouveau de retirer notre seul package et perdrait ses données courantes ; ne pas le faire comme une étape automatique de démonstration.

Sauvegarde de l’original : `validation/new-device-CN46V3M00284/installed-before.apk`, fichiers privés `installed-before-private-data.tar`, manifeste/empreintes `backup-manifest.json`. L’archive a été relue, mais sa restauration complète n’a pas été essayée ; caches, clés Android et autorisations système ne sont pas inclus. L’APK et les fichiers de l’ancien téléphone sont conservés séparément.

## Démonstration vérifiable disponible

Les tests de composants du nouveau téléphone sont consultables sans relance : [XNNPACK](validation/new-device-CN46V3M00284/files/ml_validation/onnx_xnnpack.json), [vidéo](validation/new-device-CN46V3M00284/files/video-decoder-report.json), [replay combiné](validation/new-device-CN46V3M00284/files/combined-pipeline-report.json). Ils n’exécutent pas la voix Bluetooth ni le transport SDK live.

Pour les relancer, préparer les chemins une fois dans le même terminal :

```bash
export JAVA_HOME="/Users/sam/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home"
export ORIA_ROOT="/Users/sam/Documents/ChatGPT/Hackathon SILMO/oria-htc"
export ORIA_SERIAL=CN46V3M00284
export ORIA_ADB=/Users/sam/Library/Android/sdk/platform-tools/adb
cd "$ORIA_ROOT/validation-project"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
"$ORIA_ADB" -s "$ORIA_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ORIA_ADB" -s "$ORIA_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Ces deux APK ciblent uniquement le harness et ses tests. Pour une mise à jour du prototype désormais installé, utiliser son APK final avec la même clé et `adb install -r`, sans nouvelle désinstallation.

1. **Modèle réel sur HTC** : exécuter le test XNNPACK, qui compare les six tenseurs/sorties et mesure vingt inférences après échauffement.

```bash
"$ORIA_ADB" -s "$ORIA_SERIAL" shell am instrument -w -r \
  -e class com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxObjectDetectorInstrumentedTest#xnnpackParityAndShortBenchmarkIfAvailable \
  com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner
```

2. **Pixels H.264** : décodage matériel de la vidéo HTC fournie, sans Surface ni retour microphone.

```bash
"$ORIA_ADB" -s "$ORIA_SERIAL" shell am instrument -w -r \
  -e class com.htc.vive.eagle.hackathon.starter.oria.video.VideoDecoderInstrumentedTest \
  com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner
```

3. **Chaîne combinée sur replay** : vidéo décodée → modèle réel → politique. Le sink vocal est factice ; une fixture positive synthétique séparée teste l’annonce et son ticket, sans prétendre provenir de YOLO.

```bash
"$ORIA_ADB" -s "$ORIA_SERIAL" shell am instrument -w -r \
  -e class com.htc.vive.eagle.hackathon.starter.oria.video.CombinedPipelineInstrumentedTest \
  -e pipelineSeconds 60 \
  com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Le rapport est `files/combined-pipeline-report.json`. Il inclut les rejets et la cadence réellement obtenue ; le seuil d’acceptation du test ne garantit pas à lui seul la cible de 4 Hz.

4. **Politiques Oria, PCM stéréo et navigation** : lancer les commandes suivantes. Les sous-shells conservent le dossier courant pour la suite. Le build actuel a passé 51 tests JVM, dont dix tests PCM (échantillons signés, entrelacement, isolation des canaux, durée et cache intact), deux tests d’ordre d’initialisation des destinations et le test regroupant les 18 fixtures communes Swift/Kotlin. Les tests ne remplacent pas l’écoute dans les lunettes.

```bash
(cd "$ORIA_ROOT/android-project" && ./gradlew :app:testDebugUnitTest)
# Facultatif : définir ORIA_SWIFT_SOURCE vers le fichier Swift de référence externe.
(cd "$ORIA_ROOT/fixtures" && python3 run_swift_reference.py)
```

5. **Endurance du modèle** : lancement volontaire de dix minutes, sans autre benchmark ni interaction concurrente sur le téléphone.

```bash
"$ORIA_ADB" -s "$ORIA_SERIAL" shell am instrument -w -r \
  -e class com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxObjectDetectorInstrumentedTest#sustainedXnnpackModelBenchmark \
  -e mlEnduranceSeconds 600 \
  com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Après chaque essai, récupérer ses rapports **avant de relancer le même test**, car le harness remplace ses fichiers internes. Le dossier ci-dessous est unique à chaque extraction et ne dépend pas du dossier courant :

```bash
ORIA_CAPTURE="$(mktemp -d "$ORIA_ROOT/validation/new-device-CN46V3M00284/manual-run-XXXXXX")"
"$ORIA_ADB" -s "$ORIA_SERIAL" exec-out run-as com.oria.silmo.validation \
  tar -cf - files/ml_validation files/video-decoder-report.json files/combined-pipeline-report.json > "$ORIA_CAPTURE/reports.tar"
tar -xf "$ORIA_CAPTURE/reports.tar" -C "$ORIA_CAPTURE"
```

Le code retour shell d’`am instrument` ne suffit pas : vérifier le verdict JUnit `OK` ou `FAILURES` dans la sortie. Le test `cpuParityAndShortBenchmark` reste disponible pour reproduire l’échec CPU sans modifier ses tolérances. Il écrit son diagnostic avant l’assertion finale.


## Collecter les traces techniques d’une session réelle

Pour rejouer la scène, préférer Oria Lab et son transfert USB ci-dessus. Le bloc suivant extrait tous les fichiers privés à des fins de diagnostic, y compris d’éventuelles captures : utiliser un dossier distinct et prévoir l’espace correspondant. Ne pas écraser la première preuve ou un échec. Ce bloc est autonome et peut être exécuté depuis n’importe quel dossier.

```bash
ORIA_ROOT="/Users/sam/Documents/ChatGPT/Hackathon SILMO/oria-htc"
ORIA_CAPTURE="$(mktemp -d "$ORIA_ROOT/validation/new-device-CN46V3M00284/live-manual-XXXXXX")"
/Users/sam/Library/Android/sdk/platform-tools/adb -s CN46V3M00284 exec-out run-as com.htc.vive.eagle.hackathon.starter tar -cf - files > "$ORIA_CAPTURE/traces.tar"
tar -xf "$ORIA_CAPTURE/traces.tar" -C "$ORIA_CAPTURE"
```

Les traces `oria-trace-*.jsonl` indiquent `source=htc_live` ou `htc_simulator`, session, inférences, âge et décisions. Pour la voix stéréo, relever `speech_submitted` avec `backend=BLUETOOTH`, `requestId` et `pan`, puis `speech_pcm_start_guard` et `speech_local_result` (`COMPLETED`, `EXPIRED`, `NOT_PLAYED` ou `INTERRUPTED`). Les archives OriaLab ajoutent les détections complètes, `decision.evaluatedAtMs` et `policy_voice.policyAtMs` pour conserver l’horloge réellement donnée au moteur. Le contrôle de fraîcheur avant PCM ne mesure pas le son acoustique après les tampons A2DP. Une fin de synthèse fichier n’est jamais une preuve d’écoute. Conserver le contexte appareil, l’empreinte APK et la confirmation humaine séparée. Une nouvelle endurance avec enregistrement doit rester distincte des dix minutes à 333 ms déjà réussies sans cette charge.

Ne pas effacer l’état audio incertain pour obtenir une démo artificiellement verte : établir avec HTC une procédure réelle d’annulation/isolation des callbacks avant de réautoriser une autre demande.


## Noms Oria / Oria Lab

Le 26 septembre 2026, **Oria** désigne l’assistance et **Oria Lab** la capture/relecture sur téléphone et Mac. Le renommage couvre les fichiers, classes Kotlin, assets, clés de préférences, traces et marqueurs du format de capture. La mise à jour est compatible et conserve le package HTC autorisé ainsi que la signature.

Les cinq captures du HTC, sept sessions en cache Mac et cinq exports ZIP ont été migrés après sauvegarde externe. Leur UUID, vidéo, images et événements restent conservés ; les manifestes portent maintenant `kind: oria-lab-session` et une empreinte de leur original. Les fichiers descriptifs du checkpoint et de l’ONNX ont été harmonisés ; les 708 tenseurs et le graphe ONNX ont été vérifiés identiques. Politique, cadence et gains PCM 70/30 restent inchangés.

Le lanceur Mac est [Lancer Oria Lab.command](<oria-lab-desktop/Lancer Oria Lab.command>). Le stockage Mac est `~/Library/Application Support/Oria Lab/`, les exports `~/Documents/Oria Lab Captures/` et le dossier privé Android `files/oria-lab/`. Les captures migrées ont été rouvertes et la scène de 164 images recalculée sur Mac. Cette livraison n’ajoute aucune preuve d’écoute physique. Voir [la validation du renommage](validation/ORIA_RENAMING.md).
