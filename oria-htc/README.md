# Oria × HTC VIVE Eagle — prototype SILMO

## Candidat jury R11

Le candidat gelé est **Oria 1.5-jury-r11 / code 6**, package HTC inchangé. Son APK release local porte le SHA-256 `bc85469264337b8985751974eb100c1b52ab7093a9c44f1aaf565981e529a323`. Il passe 174 tests JVM, 68/68 tests Mac, les 28 fixtures Swift/Kotlin et trois recettes instrumentées stables sur HTC. Voir le [rapport de gel R11](validation/R11_JURY_FREEZE_REPORT.md), le [manifeste](artifacts/r11-release-manifest.json) et la [checklist jury](validation/R11_OPERATOR_CHECKLIST.md).

Ce candidat n’est pas encore installé : sa clé debug locale diffère de celle de l’ancien starter présent, donc Android refuse une mise à jour `-r`. Le flux prévu par l’organisateur consiste bien à modifier ce starter autorisé en conservant son package, puis à remplacer son ancienne installation par notre version après sauvegarde. Il ne faut ni créer un second package ni rechercher une clé HTC. L’APK n’est ni suivi dans Git ni publié sur GitHub ; il se trouve uniquement dans le bundle local ignoré `artifacts/releases/oria-1.5-jury-r11/`. Les trois scénarios de cinq minutes réussis sont virtuels ; les trois démonstrations physiques restent en cours de validation.

L’application **Oria** s’ouvre sur l’assistance en direct et propose deux onglets : **Oria** et **Oria Lab**. Oria Lab enregistre explicitement une scène des lunettes et sa télémétrie pour les examiner sur le téléphone ou les rejouer sur Mac. Les commandes restent fixes sous le contenu défilant, même avec un aperçu portrait. **Diagnostic HTC** conserve Glasses, Chat, Audio et Camera ; son retour ramène à l’onglet principal précédent.

Le package et le namespace restent `com.htc.vive.eagle.hackathon.starter`. Le SDK, les dépendances locales et la clé debug sont conservés. Les paramètres numériques du modèle, les règles RGB, les seuils et les fixtures partagées Swift sont inchangés ; seules les métadonnées descriptives du modèle ont été renommées.

## Version de nuit installée — 27 septembre

**Oria 1.4-night**, versionCode 5, est actuellement installé sur **CN46V3M00284** : APK `aaec205c5f0916d2789c082e707eecf0ab810fd51038959b74d9e7beaca8b8dc`, signature habituelle du projet. L’APK et les données privées de la version précédente ont été sauvegardés avant la mise à jour. Un runner d’instrumentation a ensuite désinstallé automatiquement le package ; l’application et ses 521 fichiers privés ont été restaurés et vérifiés, puis l’app a été arrêtée. Voir le [bilan de nuit](validation/NIGHT_WORK_20260927.md) et le [manifeste de compilation](artifacts/night-20260927-v1.4-validation.json).

Les captures et le mode poche n’ont plus de durée maximale. Les quotas fixes de capture sont remplacés par une réserve réelle de **512 Mio libres** ; aucune scène n’est supprimée automatiquement. Le mode poche reste expérimental, désactivé par défaut, avec arrêt depuis la notification et arrêt sur perte de session. Oria Lab finalise toujours au passage en arrière-plan. Noms, corbeille/restauration, export et comparaison A/B sont disponibles ; le suivi `LEGACY_IOU` reste le défaut.

**83 tests JVM passent sur 1.4-night. Douze tests instrumentés de galerie passent sur HTC ; un autre est ignoré.** Une PNG manquante n’efface plus l’inférence cohérente : sa détection enregistrée reste consultable en texte, sans faux visuel ni surimpression. Le rapport d’instrumentation complet conserve deux échecs (assertion d’identifiant obsolète corrigée dans la source mais non relancée sur appareil, et parité CPU ONNX déjà en échec) ainsi que deux tests ignorés. Les seuils du modèle restent inchangés. Les entrées invalides restent visibles, avec leur cause ; les détections sont chargées à la sélection. Recette réelle : 164 images / 240 détections concordent avec une lecture indépendante, horloges et décisions exactes, fichiers inchangés. Index synthétique de deux heures : 21 622 entrées ouvertes en 14,18 s sur HTC ; ce n’est pas une endurance caméra. [Rapport galerie](validation/GALLERY_LONG_SESSIONS.md).

Sur Mac, **67 tests Python et les interactions JavaScript passent**. Les positions invalides restent inspectables dans l’ordre source ; identités, horloges ou analyses ambiguës bloquent le recalcul. Une PNG seule absente conserve les données cohérentes enregistrées et signale la couverture visuelle incomplète. Parité exacte des 164 positions, 240 détections et politiques sur la scène réelle, sources intactes. [Validation Mac](artifacts/night-20260927-mac-integrity-validation.json). La livraison Android 1.4 ajoute la même distinction entre disponibilité de l’image et intégrité des données.

L’export ZIP reste préparé sur disque avec téléchargement natif, indépendant des renommages ultérieurs ; archive réelle de 91 739 169 octets téléchargée lors de la livraison précédente, CRC valides et 169 fichiers identiques à la sauvegarde du téléphone.

Les 10 instruments capture/modèle/replay de **1.2-night** restent des preuves de cette version : six fixtures XNNPACK et replay de 60 s à 3,73 décisions fraîches/s, âge p95 294 ms, voix factice. Une nouvelle [recette réelle sur 1.4-night](validation/LIVE_20260927_V14.md) valide 131 s de vidéo, 362 analyses fraîches et les tests vocaux gauche/centre/droite pendant le flux, avec écoute 70/30 et repère confirmés par l’utilisateur. Le fonctionnement écran verrouillé reste à valider. Les résultats vidéo/voix historiques ci-dessous gardent leurs APK d’origine.

## État et preuves historiques

Le téléphone de cette campagne était **HTC U24 pro / Android 14 / arm64-v8a**, ADB **CN46V3M00284**. Ses rapports actuels sont dans `validation/new-device-CN46V3M00284/`. Les rapports physiques historiques et sauvegardes ont été archivés hors du projet ; voir [PUBLICATION.md](../PUBLICATION.md). L’ancien appareil était **CN4B53M00860** : son APK livré, son manifeste, son README et sa recette sont préservés dans `validation/archive-CN4B53M00860/`. Les dossiers historiques `device-*` concernent l’ancien téléphone.

La voix HTC **avec vidéo arrêtée a été entendue dans les lunettes**, selon la [confirmation explicite de l’utilisateur](validation/new-device-CN46V3M00284/human-confirmations.json). Pendant la vidéo, le SDK HTC 0.6 refuse `speakText` avec `ERROR_RESOURCE_CONFLICT`, y compris avec son entrée vidéo seule. Le prototype utilise donc désormais **Bluetooth VIVE** par défaut : synthèse française locale sur le téléphone, puis lecture PCM sur la sortie audio des lunettes.

**Le build précédent Oria + Oria Lab**, [installé sur ce HTC](validation/new-device-CN46V3M00284/production-update-oria-final.json), a pour SHA-256 `f49def1953699349d1c189402c2a7b0229e6bd17db1ca7c0f29c54c9873eeb43`. Ses [51 tests JVM réussissent](validation/new-device-CN46V3M00284/oria-final-build.json), dont dix tests PCM et deux régressions d’initialisation de la navigation. Les [trois tests instrumentés de l’enregistreur](validation/new-device-CN46V3M00284/oria-lab-instrumented-tests.log) ont passé sur le build Oria Lab précédent `1c3226…`, avant le renommage interne des sources d’enregistrement. Le crash de navigation de ce premier build est corrigé ; il n’est pas présenté comme une livraison utilisable.

**Les annonces automatiques stéréo gauche / droite / centre ont été entendues pendant la vidéo** sur le build historique `412eec7f…`, avec confirmation explicite « Oui, les côtés sont corrects » dans [le registre humain](validation/new-device-CN46V3M00284/human-confirmations.json). La route audio est préparée puis conservée entre les phrases. Après un débit chaud insuffisant à 250 ms, la sélection à 333 ms a tenu plus de dix minutes à **2,631 décisions fraîches/s, âge p95 406 ms**, sur `40175f…`, avec la garde 500 ms inchangée. Ces résultats précèdent la charge supplémentaire d’enregistrement OriaLab ; le verdict détaillé figure dans [la recette](validation/RECETTE.md).

Deux captures réelles du build OriaLab `9dbaec1f…` ont été transférées en ZIP sans effacement du téléphone : [20,219 s, 570 paquets, 49 PNG et 48 inférences](validation/new-device-CN46V3M00284/oria-lab-first-transfer.json), puis [60,067 s, 1 746 paquets et 155 PNG](validation/new-device-CN46V3M00284/oria-lab-60s-transfer.json), arrêtée par `duration_limit`. Toutes deux sont finalisées sans saturation de file ; la seconde compte 154 décisions toutes fraîches, soit 2,564 Hz et un âge p95 de 290,35 ms. Le lecteur Mac a été vérifié sur la première : import, image précédente/suivante, lecture/pause, deux recalculs avec confirmation à deux puis une observation, et vidéo H.264 de contexte 480 × 856 / 18,966 s. Le [rapport de rejeu réel](validation/new-device-CN46V3M00284/oria-lab-real-replay.json) confirme les **48/48 décisions identiques** en rejouant les détections Android avec leur horloge exacte. Il conserve l’échec de parité brute ONNX sur 7 des 48 images comparables : 41 passent strictement, sans détection au seuil 0,70 affectée. L’orientation était **non confirmée** sur le téléphone pendant ces captures, donc sans annonce automatique ; les annonces calculées sur Mac sont des simulations sans son.

Les essais précédents restent distincts :

| Build — préfixe SHA-256 | Résultat physique associé |
|---|---|
| `aef0571a` — route recréée pour chaque phrase | [Phrase manuelle Bluetooth entendue pendant vidéo](validation/new-device-CN46V3M00284/production-update-bluetooth.json) ; les 35 premières annonces automatiques expirent avant lecture. |
| `b682e0ce` — première préparation de route | [Échec d’amorçage](validation/new-device-CN46V3M00284/warm-failure-logcat.txt) : « Route VIVE non confirmée ». |
| `a56bfa58` — préparation corrigée, mono | [Annonces automatiques entendues dans les deux oreilles](validation/new-device-CN46V3M00284/human-confirmations.json), sans séparation gauche/droite. |
| `412eec7f` — stéréo à 250 ms | Annonces automatiques et côtés confirmés pendant vidéo ; continuité dix minutes démontrée, débit chaud insuffisant à cette cadence. |
| `40175fb4` — stéréo à 333 ms | Dix minutes complètes : critères de cadence soutenue réussis, avant ajout OriaLab. |
| `9dbaec1f` — première livraison Oria + OriaLab | Captures réelles, transfert USB et lecteur Mac vérifiés ; 50 tests JVM réussis. |

Résultats du nouveau téléphone : [modèle XNNPACK](validation/new-device-CN46V3M00284/files/ml_validation/onnx_xnnpack.json), [décodage vidéo](validation/new-device-CN46V3M00284/files/video-decoder-report.json), [replay combiné](validation/new-device-CN46V3M00284/files/combined-pipeline-report.json). Ces tests ne prouvent pas une phrase audible pendant la caméra réelle.

**Stéréo actuelle :** demande utilisateur du 26 septembre : amplitude gauche/droite 70/30 pour avant-gauche, 30/70 pour avant-droite ; devant reste identique dans les deux canaux au volume précédent. L’utilisateur a confirmé les trois positions pendant la vidéo sur 1.4-night le 27 septembre ; voir la [recette réelle](validation/LIVE_20260927_V14.md). La confirmation historique 100/0 reste distincte. Modèle, cœur déterministe et règles de fraîcheur restent inchangés.

**Profondeur expérimentale :** MiDaS v2.1 Small peut être activé dans les réglages pour produire une proximité relative et une confiance. Il reste désactivé par défaut, n'annonce jamais de mètres et ne modifie pas encore la politique RGB. Voir [R03](validation/R03_MONOCULAR_DEPTH.md).

**Résolution R04 :** le moteur complet porte les sept provenances Swift, leur propriétaire, l'arbitrage et la stabilisation. Sur Eagle, RGB et MiDaS restent explicitement visuels/relatifs ; les branches métriques LiDAR ne s'activent pas sans preuve métrique. Le moteur tourne en comparaison silencieuse et son snapshot est enregistré dans Oria Lab ; la voix RGB historique reste la référence jusqu'à R05. L'approche relative est séparée et désactivée par défaut, sans TTC inventé. Voir [R04](validation/R04_DANGER_RESOLUTION.md).

[Audit de la dernière scène utilisateur de 60 s](validation/user-scene-0548b68a/AUDIT.md) : 164 décisions reproduites exactement, 164 images examinées, et limites de perception/suivi illustrées. Cette capture précède le nouveau mélange 70/30.

## Compiler

```bash
cd "/Users/sam/Documents/ChatGPT/Hackathon SILMO/oria-htc/android-project"
export JAVA_HOME="/Users/sam/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain
```

JDK 21, SDK Android 36 et Gradle 8.13 local. `local.properties` est propre à ce poste. APK compilé : `android-project/app/build/outputs/apk/debug/app-debug.apk` ; copie historique livrée : `artifacts/oria-silmo-debug.apk`. Le script `validation/build_offline.py` produit séparément `artifacts/oria-silmo-candidate.apk`.

## Utiliser Oria

1. Connecter les lunettes dans VIVE Connect, puis ouvrir **Oria**. L’accueil est disponible même hors connexion.
2. Laisser le simulateur désactivé pour le réel et appuyer sur **Connecter**. Attendre le modèle chargé et la préparation de la voix française locale. La sortie par défaut est **Bluetooth VIVE** ; le système Android doit aussi exposer les lunettes comme sortie audio Bluetooth. Sans voix française locale installée ou sans sortie VIVE reconnue, le statut explique l’indisponibilité et aucun repli sonore sur le téléphone n’est demandé.
3. **Démarrer**, autoriser la caméra Android/HTC, puis attendre le statut **route prête**. La première préparation audio a lieu avant les annonces et le même lecteur stéréo reste ouvert pendant la session. Le flux Oria emploie l’entrée vidéo seule du SDK ; les permissions microphone concernent les outils du diagnostic. Si une activité d’autorisation met l’application en arrière-plan, revenir puis démarrer à nouveau : une ancienne demande ne redémarre pas silencieusement la vidéo.
4. Pendant que les images progressent, utiliser successivement les boutons **Gauche**, **Centre**, **Droite**. Attendre la fin de chaque phrase : gauche doit être entendue du côté gauche, droite du côté droit, et centre des deux côtés. « Lecture Bluetooth terminée » indique une fin de lecture côté Android ; ce message ne remplace pas le contrôle dans les lunettes. Refaire ce contrôle si la route audio change.
5. Vérifier aussi haut/bas et gauche/droite **dans l’image**, avec une scène asymétrique. Ajuster rotation/miroir dans **Réglages Oria**, puis redémarrer si nécessaire. Cocher **« J’ai vérifié gauche / droite dans l’aperçu et le test vocal. Autoriser les annonces d’objets. »** seulement après les deux contrôles. Dans la configuration essayée, le repère a été confirmé avec rotation 0° et miroir désactivé ; ne pas recopier ce choix sans vérifier un autre montage.
6. **Arrêter** invalide les calculs et nouvelles annonces, puis annule la lecture locale et ferme son lecteur. Passer au diagnostic arrête aussi la session. L’arrière-plan l’arrête par défaut ; le mode poche propose une exception explicite sans durée maximale, dont le maintien du flux lunettes écran verrouillé reste à tester. Le délai d’arrêt effectivement entendu après les tampons Bluetooth reste à mesurer.

La carte voix et la confirmation d’orientation précèdent l’aperçu. Lorsqu’une image dépasse 500 ms, elle et ses boîtes disparaissent ; l’emplacement reste stable avec « En attente d’image fraîche ». La commande Arrêter demeure fixe.

Le diagnostic HTC conserve les commandes originales de connexion, caméra, audio et Chat. Ses demandes périmées sont invalidées au changement de mode ; ses médias s’arrêtent en arrière-plan. La voix de Chat et les alertes partagent une seule réservation et la sortie sélectionnée. Retourner à Oria ne lance pas automatiquement une session.

Pour vérifier la voix propriétaire HTC, **arrêter la vidéo**, ouvrir **Réglages Oria**, sélectionner **HTC · diagnostic**, puis **Tester la voix**. Cette sortie sert uniquement au diagnostic sans vidéo. Revenir à **Bluetooth VIVE** à l’arrêt avant une démonstration simultanée. Un échec ou une interruption locale suspend les annonces automatiques ; une lecture de test **Gauche**, **Centre** ou **Droite** terminée permet leur reprise. Si la route est interrompue, arrêter puis redémarrer pour la préparer à nouveau. Une annonce simplement expirée attend une observation fraîche.

Une livraison HTC ambiguë bloque les nouvelles demandes, même après changement d’écran ou redémarrage de l’Activity. Changer de sortie vocale ne résout pas cette ambiguïté. La procédure de rétablissement matériel reste à prouver. La lecture locale utilise des identifiants propres pour ne pas confondre une annulation et la confirmation d’une nouvelle phrase.

## Enregistrer et rejouer avec Oria Lab

1. Connecter les lunettes et attendre le modèle prêt. Ouvrir **Oria Lab**, puis **Enregistrer une scène**. Le flux redémarre pour inclure ses en-têtes H.264 ; aucune capture ne commence au lancement de l’application ou au retour sur l’onglet. La source réelle ou simulateur est affichée et enregistrée.
2. Observer durée, nombre d’images/paquets et octets, puis **Arrêter et finaliser**. Attendre « Capture sauvegardée ». Aucune limite de durée ou de taille de session n’est imposée. Si l’espace libre approche la réserve de 512 Mio, la capture est finalisée avec un motif explicite ; la perception peut continuer. Utiliser alors **Arrêter la vidéo**. Le badge **Oria Lab ●** reste visible si l’on passe à Oria ; diagnostic ou arrière-plan arrêtent la session et finalisent la capture.
3. Brancher le HTC au Mac avec le débogage USB autorisé, puis double-cliquer [Récupérer depuis HTC.command](<oria-lab-transfer/Récupérer depuis HTC.command>). La dernière capture finalisée est copiée dans `~/Documents/Oria Lab Captures/` et ouverte automatiquement dans le lecteur Mac. Garder le terminal ouvert pendant le rejeu. Les données du téléphone restent en place ; avec plusieurs appareils, sélectionner le numéro explicitement selon [le guide USB](oria-lab-transfer/README.md).
4. Dans le lecteur, parcourir les PNG exactes et les détections du téléphone, puis lancer le recalcul ONNX + moteur Kotlin avec les paramètres souhaités. La vidéo complète fournit le contexte ; ses pas nominaux de 1/30 s ne remplacent pas les horloges enregistrées des PNG. Voir [le guide Mac](oria-lab-desktop/README.md).

Alternative indépendante : après finalisation et arrêt vidéo, **Exporter ZIP** ouvre le sélecteur Android pour choisir le fichier de destination. Le ZIP peut ensuite être importé dans [le lecteur Mac](<oria-lab-desktop/Lancer Oria Lab.command>). **Revoir les images** fournit une galerie précédente/suivante sur le téléphone. L’application ne transmet rien à un serveur externe ; le lecteur Mac tourne uniquement sur `127.0.0.1`.

L’archive contient vidéo H.264, PNG sans perte, horloges, détections applicatives, 300 sorties brutes par inférence enregistrée, décisions, événements vocaux et provenance APK/modèle. Aucune piste microphone, profondeur ou pose n’est capturée. Une PNG peut être enregistrée sans avoir été analysée avant l’arrêt ; le lecteur le signale. **Recette acquise sur le build historique `9dbaec1f…` :** [export SAF vers Downloads](validation/new-device-CN46V3M00284/oria-lab-saf-export.json), 160 fichiers avec CRC et empreintes identiques au transfert USB ; galerie téléphone passée de l’image 1 à l’image 6 avec détections correspondantes ; [passage en arrière-plan pendant une capture](validation/new-device-CN46V3M00284/oria-lab-background-manifest.json) finalisé à 17,177 s / 37 PNG, puis retour sans relance automatique. Ces essais avaient l’orientation non confirmée et ne constituent pas une nouvelle preuve audio. Le stockage privé suit l’espace réellement disponible, avec réserve de 512 Mio et sans suppression automatique des scènes.

## Installation et sauvegarde

Le paquet vocal **français (France), environ 23 Mo**, a été installé sur ce téléphone ([état après installation](validation/new-device-CN46V3M00284/tts-fr-after.xml)). Sur un autre appareil, installer cette langue dans les paramètres du moteur de synthèse vocale Android, puis relancer Oria pour qu’il initialise la voix locale. Ce téléchargement initial est distinct de la synthèse locale utilisée ensuite par le prototype.

Le README HTC impose le package inchangé et prévoit la désinstallation du **seul starter** en cas de signature différente. Sur le nouveau téléphone, son APK d’origine et ses fichiers privés accessibles ont été sauvegardés avant remplacement : `validation/new-device-CN46V3M00284/installed-before.apk`, `installed-before-private-data.tar` et `backup-manifest.json`. L’archive a été relue et les empreintes vérifiées ; une restauration complète n’a pas été testée. Les caches, permissions système et clés Android ne sont pas inclus. VIVE Connect et son appairage restent indépendants.

Une fois notre version installée, les mises à jour utilisant la même clé se font avec `adb -s CN46V3M00284 install -r <apk>`. Ne pas exécuter le script HTC destiné à tous les appareils branchés. Voir [la procédure de démonstration](DEMONSTRATION.md).

## Tests, architecture et provenance

Le pipeline est local : H.264 HTC sans micro → MediaCodec/YUV → dernier Bitmap récent → ONNX FP32 → politique RGB → PCM français local → sortie Bluetooth VIVE. Sélection après décodage ; une inférence active et une image en attente. L’âge maximal reste **500 ms**, vérifié aussi avant le premier PCM d’une annonce ; il ne coupe pas une phrase en cours après 500 ms. Cet âge commence à la réception téléphone et n’inclut pas une mesure de capture lunettes ou d’audibilité après tampon Bluetooth. Pas de profondeur, pose, mètres ou annonce « voie libre » inventés.

- `oria/core/` : règles Kotlin pures, pistes temporaires, mémoire et livraison vocale transactionnelle.
- `oria/video/` : décodage, corrélation PTS/réception, orientation et propriété des images.
- `oria/ml/` : letterbox provisoire 416 et inverse `raster_inverse_v2`, six classes, sortie `[1,300,6]`, sans NMS ni top huit ajoutés.
- `oria/audio/` : français local, synthèse silencieuse, cache PCM16 stéréo borné (13 phrases préparées, 32 au total), IDs de lecture et route Bluetooth préparée avant les annonces. Gauche applique les gains d’amplitude 0,7/0,3 ; droite 0,3/0,7 ; devant conserve les deux canaux à 1/1, sans amplification ni modification du cache. Le même AudioTrack est conservé pendant la session avec au plus 40 ms de silence en attente après amorçage. Les textes manuels sont limités à 240 caractères et 8 secondes de PCM. Aucun `TextToSpeech.speak`, SCO ou sortie téléphone implicite n’est utilisé. La sélection de route Android reste une préférence : une transition système peut précéder son callback, donc une absence absolue de fuite sonore n’est pas revendiquée.
- `oria/recording/` : copie des buffers/images avant leur libération, écriture asynchrone bornée, finalisation et export ZIP OriaLab.
- `oria-lab-transfer/`, `oria-lab-desktop/` et `oria-lab-policy/` : transfert USB, lecteur local et rejeu du même moteur Kotlin sur Mac.
- `OriaController.kt` : sessions, fraîcheur, inférence sérialisée et voix.
- `ml/` et `fixtures/` : export reproductible, manifestes et comparaison des fonctions Swift/Kotlin. `ml/.venv/bin/python ml/verify_integrity.py` contrôle les artefacts sans réexporter.

Checkpoint utilisateur : `oria_silmo_fp32.pt`, SHA-256 `a591f2db91a90e98297d8a5f035b037b9745cc88aecace98f434e162c2a63f55`. Les sources Downloads et Swift/Core ML restent préservées. L’option iOS conserve les contrats/fixtures ; aucun accès HTC iOS n’est présumé disponible.

`validation-project/` compile directement les mêmes sources core/ML/vidéo dans le paquet distinct `com.oria.silmo.validation`, sans SDK HTC ni permission caméra/micro. Son replay utilise le média HTC fourni. Les commandes instrumentées et la collecte des rapports figurent dans `DEMONSTRATION.md` ; lancer toute la classe ML inclut un test CPU strict en échec.

Les traces privées `oria-trace-*.jsonl` contiennent des événements et métriques, sans enregistrement vidéo par défaut. OriaLab n’enregistre vidéo et images qu’après une action explicite. Distinguer réception téléphone → décision, capture lunettes → réception et commande → son entendu : seuls les intervalles effectivement mesurés sont rapportés.

## Cadence soutenue sur ce téléphone

Après la preuve stéréo `412eec…`, une session réelle de plus de dix minutes a révélé un débit chaud insuffisant : 1,297 décision fraîche/s sur la fenêtre instrumentée, malgré une continuité sans interruption. Le build `40175fb4…` a ensuite validé la sélection **après décodage** à 333 ms : 620,346 s de session réelle, dont 600,053 s de collecte instrumentée, 1 632 décisions fraîches à 2,631 Hz et p95 406 ms. Le build Oria actuel conserve cette cadence. Les paquets H.264 sont tous consommés ; modèle et garde 500 ms sont inchangés. Les gains stéréo actuels sont 70/30, avec écoute gauche/centre/droite confirmée sur 1.4-night le 27 septembre. Les replays historiques du harness à 250 ms et l’enregistrement OriaLab restent des charges distinctes. La cible initiale de 4 Hz n’est pas prétendue atteinte.


## Noms Oria / Oria Lab

Le 26 septembre 2026, **Oria** désigne l’assistance et **Oria Lab** la capture/relecture sur téléphone et Mac. Le renommage couvre les fichiers, classes Kotlin, assets, clés de préférences, traces et marqueurs du format de capture. La mise à jour est compatible et conserve le package HTC autorisé ainsi que la signature.

Les cinq captures du HTC, sept sessions en cache Mac et cinq exports ZIP ont été migrés après sauvegarde externe. Leur UUID, vidéo, images et événements restent conservés ; les manifestes portent maintenant `kind: oria-lab-session` et une empreinte de leur original. Les fichiers descriptifs du checkpoint et de l’ONNX ont été harmonisés ; les 708 tenseurs et le graphe ONNX ont été vérifiés identiques. Politique, cadence et gains PCM 70/30 restent inchangés.

Le lanceur Mac est [Lancer Oria Lab.command](<oria-lab-desktop/Lancer Oria Lab.command>). Le stockage Mac est `~/Library/Application Support/Oria Lab/`, les exports `~/Documents/Oria Lab Captures/` et le dossier privé Android `files/oria-lab/`. Les captures migrées ont été rouvertes et la scène de 164 images recalculée sur Mac. Cette livraison n’ajoute aucune preuve d’écoute physique. Voir [la validation du renommage](validation/ORIA_RENAMING.md).
