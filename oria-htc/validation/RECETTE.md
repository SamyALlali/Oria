# Recette Oria × VIVE Eagle — nouveau HTC, 26 septembre 2026

## Verdict courant

L’application **Oria + Oria Lab** est compilée et installée sur **CN46V3M00284**, HTC U24 pro, Android 14, arm64-v8a. APK courant : `f49def1953699349d1c189402c2a7b0229e6bd17db1ca7c0f29c54c9873eeb43`. Le package autorisé reste `com.htc.vive.eagle.hackathon.starter`. Oria est l’accueil ; Oria Lab ajoute capture et relecture ; les quatre écrans d’origine restent accessibles par **Diagnostic HTC**. Les commandes Démarrer/Arrêter restent fixes même avec un aperçu portrait.

**La chaîne réelle est démontrée : images des lunettes, YOLO sur le téléphone, politique RGB et annonces françaises stéréo entendues dans les lunettes pendant la vidéo.** L’utilisateur a confirmé l’image gauche/droite, l’audibilité automatique, puis les trois positions stéréo. Le premier backend ouvrait une piste par phrase et faisait expirer les annonces ; cet échec est archivé. La route est désormais préparée et maintenue entre phrases, avec la même limite de fraîcheur de 500 ms. L’endurance continue et la campagne de transitions restent des validations séparées.

Toutes les preuves de ce téléphone se trouvent dans [new-device-CN46V3M00284](new-device-CN46V3M00284/). Les résultats de l’ancien **CN4B53M00860** restent dans [la recette historique](archive-CN4B53M00860/RECETTE.md). Les dix minutes du modèle sur l’ancien téléphone ne deviennent pas une endurance physique du nouveau.

## Livraison et installation

- Projet : `../android-project/`. APK courant et empreintes : [manifeste de livraison](../artifacts/delivery_manifest.json), [APK](../artifacts/oria-silmo-debug.apk).
- Checkpoint fourni : SHA-256 `7dd79d15d1fe61200b19916c7d0b73136637fd768c9da53a94d0499f010a40c1`.
- ONNX FP32 : 38 030 922 octets, SHA-256 `c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d` ; [manifeste modèle](../ml/exports/model_manifest.json).
- RGB NCHW `[1,3,416,416]` vers `[1,300,6]`, `xyxy, score, classe`, six classes. Letterbox provisoire, inverse `raster_inverse_v2`, ni NMS ni top huit ajoutés.
- Starter initial et fichiers privés accessibles sauvegardés et archives vérifiées : [backup-manifest.json](new-device-CN46V3M00284/backup-manifest.json). Une restauration intégrale des réglages n’a pas été testée ; Keystore, permissions système et VIVE Connect ne font pas partie de cette archive.
- Remplacement du seul starter autorisé explicitement sur ce téléphone ; VIVE Connect conservé. Les mises à jour suivantes utilisent la même clé debug, sans effacement de données.

## Build, fixtures et harness du nouveau téléphone

**51 tests JVM réussis** : 23 métier (dont le test regroupant 18 fixtures Swift/Kotlin), 8 ML/géométrie, 7 vidéo, 10 PCM/stéréo, 2 initialisation de navigation et 1 original. XML du build actuel : `new-device-CN46V3M00284/unit-tests-oria-final/`. Les 18 fixtures communes correspondent aux sorties Swift sauvegardées et exécutées dans la phase initiale ; aucune nouvelle exécution iOS n’est prétendue. Les tests JVM ne valident pas les API AudioTrack sur matériel.

Le harness `com.oria.silmo.validation` mesure les mêmes composants ML/vidéo/cœur, séparément du SDK HTC et sans son réel.

| Essai sur CN46V3M00284 | Verdict | Mesure / périmètre |
|---|---|---|
| XNNPACK avec CPU fallback | Réussi, six fixtures | Tenseurs pixels identiques, 300/300 boîtes appariées par fixture ; inférence p50 152,343 ms, p95 152,765 ms, maximum 152,775 ms ; 20 essais après 3 échauffements. |
| CPU seul | **Échoué** en parité stricte | `sdk_sample_1` : 299/300, substitution à environ 0,00000420 / 0,00000411 ; aucun écart observé ≥0,70. p95 inférence 175,348 ms. Tolérances inchangées, cause numérique non prouvée. |
| Décodage replay | Réussi | 102 paquets, 100 images décodées, 12 Bitmaps livrés, aucune livraison périmée ou après fermeture ; `c2.qti.avc.decoder`, erreur RGB moyenne 11,572/255. |
| Replay combiné 60,135 s | Réussi sur son périmètre | 1 805 paquets, 1 788 images décodées, 225 Bitmaps, 224 décisions fraîches, 3,725 Hz ; âge p50 291, p95 302, maximum 357 ms. Une véritable inférence retenue puis invalidée à l’arrêt. Voix factice. |
| Endurance modèle seul 600 s | Non exécutée ici | Le résultat de l’ancien téléphone est archivé, pas transféré. |

Preuves : [journal XNNPACK/vidéo/replay](new-device-CN46V3M00284/harness-xnnpack-video-combined.log), [rapports extraits](new-device-CN46V3M00284/files/), [analyse ML](ML_NEW_DEVICE_PREPARATION.md). Le lot fixe mesure la fidélité numérique, pas le rappel sur les six classes dans le salon.

## Essais physiques et progression des APK

Les traces sont attribuées au build qui les a produites ; une compilation ultérieure ne réécrit pas une preuve antérieure.

1. **Accueil dédié, APK c93320…** : image réelle et boîtes YOLO observées. Première session : 186 décisions fraîches, âge p95 268 ms ; écoute non confirmée pour cet essai. Voir `live-before-ui-fix-summary.json` et `live-first/`.
2. **Correction de l’aperçu, APK 67f0dd…** : carte vocale avant aperçu et hauteur conservée quand une observation expire. Le SDK HTC refuse la voix pendant vidéo (`ERROR_RESOURCE_CONFLICT`). Vidéo arrêtée, phrase HTC entendue dans les lunettes. [Analyse SDK](SDK_VIDEO_VOICE_CONFLICT.md).
3. **Bluetooth local, APK aef057…** : voix française Android téléchargée (~23 Mo), sélection d’une voix installée hors ligne ; synthèse silencieuse en PCM puis route VIVE explicite. Manuel pendant vidéo : résultat logiciel COMPLETED après 3 695 ms, 13 décisions pendant cette fenêtre, écoute et orientation confirmées par l’utilisateur. **Échec conservé des 35 premières annonces d’objet : EXPIRED avant lecture.** Délai soumission→résultat 309–821 ms, incluant plusieurs étapes ; il ne mesure pas à lui seul l’ouverture AudioTrack.
4. **Route préparée, APK b682e0… puis a56bfa…** : premier amorçage échoué ; remplissage du tampon réel corrige le démarrage (448 ms mesurés). Les annonces automatiques passent sous la garde de 500 ms et sont entendues. Signal encore mono, ce que l’utilisateur relève. Traces `live-warm-route/` ; collecte interrompue pour la demande stéréo et comportant déjà des transitions d’Activity, donc aucune endurance continue de dix minutes revendiquée.
5. **Stéréo demandée, APK 412eec…** : normalisation PCM16 vers deux canaux, zone explicite du candidat réellement annoncé, copie orientée sans modifier le cache. 48 tests réussis, installation compatible réussie ; l’utilisateur confirme « Oui, les côtés sont corrects » pour les annonces automatiques pendant la vidéo. Essai prolongé terminé : continuité réussie, débit insuffisant à 250 ms ; voir ci-dessous.

Preuves d’écoute : [human-confirmations.json](new-device-CN46V3M00284/human-confirmations.json). Traces de la troisième étape : [live-bluetooth](new-device-CN46V3M00284/live-bluetooth/). La voix HTC anonyme reste un diagnostic à l’arrêt, avec son verrou d’incertitude. Les callbacks Bluetooth sont corrélés par UUID ; fin de consommation Android et écoute humaine sont deux preuves différentes.

## Mesure continue stéréo à 250 ms : stabilité réussie, débit insuffisant

APK `412eec…` : une session réelle de **711,903 s**, dont **600,061 s** avec 21 relevés téléphone, sans changement de PID ni interruption de session avant l’arrêt opérateur. Toutes les inférences sont prises en compte : sur la fenêtre instrumentée, 1 919 inférences donnent **778 décisions fraîches (1,297 Hz)**, âge p50 527 / p95 665 / maximum 720 ms. La dernière minute donne 1,133 décision fraîche/s. **Le critère de continuité passe, le débit et la cible de latence toutes inférences ne passent pas.**

Sur la session entière, 93 demandes vocales se terminent (91 issues d’objets et deux manuelles), une expire avant lecture. Les gardes autorisées associées à un objet sont ≤498 ms ; la garde refusée est à 502 ms. Ces contrôles ne mesurent pas l’arrivée du son à l’oreille. Batterie 38→39 °C, PSS 377 313→365 813 Kio (maximum 386 183), statut thermique Android 0 et PID 4725 sur les 21 relevés. Téléphone branché : aucune conclusion d’autonomie.

Preuves figées : [traces analysées à 250 ms](new-device-CN46V3M00284/live-stereo-summary.json) et [endurance à 250 ms](new-device-CN46V3M00284/endurance-stereo-summary.json). Le ralentissement mesuré justifie D14 : un nouvel APK `40175f…` teste une sélection après décodage à 333 ms, avec modèle, voix stéréo et garde 500 ms identiques. Cet essai reste séparé ; ses performances finales figurent ci-dessous. Le décodeur du harness historique reste à son défaut de 250 ms.

## Mesure continue stéréo à 333 ms : critères D14 réussis

APK `40175f…`, avant ajout OriaLab : **620,346 s de session réelle**, dont **600,053 s de collecte instrumentée**, 21 relevés, PID 9854 stable. 1 633 inférences / **1 632 décisions fraîches, 2,631 Hz** ; âge p50 391 / p95 **406** / maximum 534 ms, ce dernier rejeté. Les dix minutes pleines passent les critères fixés avant mesure : ≥2,5 Hz et ≥90 % acceptées. 100 fins de lecture Android, gardes vocales autorisées ≤427 ms. Batterie 38→39 °C, statut thermique 0 ; téléphone branché, aucune mesure d’autonomie. La cible initiale 4 Hz reste non atteinte.

Preuves : [trace finale333](new-device-CN46V3M00284/live-paced-summary.json), [relevés600s](new-device-CN46V3M00284/endurance-paced-summary.json). L’audio est inchangé depuis la confirmation humaine sur `412eec…`. L’enregistrement OriaLab constitue une nouvelle charge et exige sa propre recette.

## Matrice de recette

| Critère | Statut et limite |
|---|---|
| V01 — Base et installation | Réussi sur le nouveau téléphone ; package, copie du starter, sauvegarde et autorisation conservés. |
| V02 — Pixels et repère | Décodage replay et pixels physiques obtenus ; gauche/droite confirmés par l’utilisateur pour rotation 0, sans miroir. Couleur vérifiée sur replay ; latence capture lunettes→réception non mesurée. |
| V03 — Voix simultanée | **Annonces automatiques Bluetooth stéréo entendues pendant vidéo, côtés confirmés** ; voie SDK HTC simultanée échouée et conservée comme diagnostic à l’arrêt. |
| V04 — Modèle | Six fixtures XNNPACK réussies ; échec strict CPU préservé. Pas de jeu HTC annoté ni mesure de rappel terrain. |
| V05 — Politique RGB | Cœur/fixtures testés, décisions réelles observées ; pas encore de campagne de croisements/occlusions en mouvement. |
| V06 — Arrêt et reprises | Générations testées dans le harness ; arrêt manuel du build40175f sans décision tardive ; Home pendant capture du build9dbaec finalise puis retour sans reprise. Coupure Bluetooth et arrêt acoustique restent non exécutés. |
| V07 — Endurance | Continuité physique de dix minutes démontrée à 250 ms, mais débit chaud insuffisant ; réglage 333 ms validé séparément : 2,631 Hz et p95 406 ms ; cible initiale 4 Hz non atteinte. |
| V08 — Démonstration | **Chaîne réelle avec personnes et annonces stéréo démontrée** ; endurance, latence acoustique et campagne de pannes restent distinctes. |
| V09 — iOS | Non exécuté, accès non confirmé ; contrats et fixtures partagés conservés. |
| V10 — OriaLab | Capture réelle 60 s, export USB/SAF, navigation image par image téléphone/Mac, recalcul YOLO et politique validés ; limites et échecs numériques ci-dessous. |

## OriaLab : capture réelle et rejeu Mac

Preuve consolidée : [oria-lab-validation.json](new-device-CN46V3M00284/oria-lab-validation.json). Les trois captures suivantes proviennent du nouvel APK `9dbaec…` sur le nouveau téléphone, avec orientation non confirmée et annonces automatiques désactivées. Elles ne constituent donc pas une nouvelle preuve d'écoute stéréo.

| Capture | Résultat |
|---|---|
| `370bbc92…` | 20,219 s, 570 paquets H264, 49 PNG, 48 inférences et décisions ; finalisée `video_stopped`. |
| `317ae6fc…` | 60,067 s, 1 746 paquets, 155 PNG, 154 décisions toutes fraîches, 2,564 Hz sur la capture entière ; âge décision p95 290,35 ms, maximum 301 ms. Finalisation automatique `duration_limit`. |
| `cf6eb456…` | 17,177 s, 204 paquets et 37 PNG ; Home pendant capture produit le stop « Application en arrière-plan · session arrêtée », finalise le dossier de capture et ne relance pas au retour (ZIP créé ensuite à l’export). Aucune décision/soumission tardive dans la trace. |

Les ZIP récupérés par USB sont conservés dans `~/Documents/Oria Lab Captures/`, les originaux restent sur le téléphone. L'export Android SAF de la capture 60 s vers Downloads réussit : 160 fichiers, CRC valides et contenus identiques à l'archive USB. La galerie téléphone passe de l'image 1 à 6 (indices 1/155 → 2/155), avec leurs résultats associés. Preuves : [export SAF](new-device-CN46V3M00284/oria-lab-saf-export.json), [cycle de vie](new-device-CN46V3M00284/lifecycle-results.json), [vue finale arrêtée](new-device-CN46V3M00284/oria-lab-ui/final-idle.json). Le réglage temporaire d'écran allumé sur alimentation est restauré à sa valeur initiale 0.

Sur Mac, l'import de la vraie capture, lecture/pause, image précédente/suivante, boîtes et bascule enregistré/recalculé ont été utilisés dans l'interface. Les 49 PNG se recalculent en environ 3,3–3,7 s sur ce Mac. Modifier le nombre d'observations de confirmation change bien le résultat simulé sans modifier l'original. La vidéo H264 intégrale des deux premières captures se décode sans erreur (569 et 1 745 images, plus un paquet de configuration chacune). Sa lecture à 30 fps reconstruits reste une vue de contexte, sans alignement exact garanti sur les PNG YOLO. [Preuve UI](new-device-CN46V3M00284/oria-lab-mac-ui.json).

La politique Kotlin réelle, alimentée par les détections Android et `evaluatedAtMs`, reproduit **48/48 décisions exactement**. Le recalcul ONNX Mac ne passe pas toute la comparaison brute : **41/48 images passent**, sept ont une substitution parmi les lignes de très faible score. Les 57 détections applicatives ≥0,70 concordent sans franchissement de seuil ; les tolérances restent 1 px et 0,001. [Rapport détaillé](new-device-CN46V3M00284/oria-lab-real-replay.json). Aucune équivalence numérique intégrale ni équivalence Core ML n'est déclarée.

Tests supplémentaires : **3 tests instrumentés d'enregistrement** réussis sur ce téléphone avec `1c3226…`, sources recorder inchangées dans le build courant ; **11 tests desktop** et **4 tests du vrai cœur Kotlin en JVM** réussis. Un crash initial d'initialisation de navigation a été observé sur `1c3226…`, corrigé par initialisation différée et couvert par deux tests JVM. Une première version de ces tests ne compilait pas avec les stubs Android ; son journal d'échec est conservé, puis le build final passe. Ces échecs ne sont pas effacés des archives.

Limites explicites : capture 60 s / 250 Mio, refus au-delà de 1 Gio de captures existantes, aucune suppression automatique ni gestion de suppression dans l'interface actuelle. Le H264 reçu est conservé intégralement ; les PNG sont les images sélectionnées après décodage pour l'analyse. Microphone, pose, profondeur et timestamp de capture lunettes sont absents de ce mode. Les événements vocaux sont journalisés quand ils surviennent, mais aucune piste acoustique n'est enregistrée. Le rejeu simule disponibilité/confirmation audio ; il ne valide ni latence HTC, ni SDK, ni Bluetooth. La mesure de 60 s avec capture ne remplace pas l'endurance de dix minutes du build40175f sans capture ; son surcoût n'a pas été isolé par essai OFF/ON apparié.

## Scène utilisateur de 60 s et nouvelle stéréo70/30

La capture `0548b68a-b9e3-445f-a072-0182e9ce98d6`, enregistrée à17h16 le26septembre avec le build9dbaec, contient164PNG,164inférences,164décisions et13annonces soumises. Elle est copiée sur le Mac et chargée dans OriaLab. Audit par les trois agents et l'orchestrateur : **164/164 décisions et25/25 mutations vocales identiques** avec horloge et événements réellement enregistrés ; les164images sélectionnées ont été inspectées visuellement. [Rapport complet, cas précis et résultats image par image](user-scene-0548b68a/AUDIT.md).

Les directions des13cibles annoncées sont cohérentes avec leurs boîtes. Douze lectures Android se terminent ; la13e est annulée lors de l'arrêt,56–61ms après la limite de la capture selon les événements de la trace complémentaire. Gardes de début268–335ms. Aucune écoute humaine supplémentaire n'est déduite de ces traces.

La fidélité au code ne signifie pas perception parfaite : personnes assises manquées, mobilier pris pour deux-roues et affiche reconnue comme personne (sans annonce pour ces faux positifs dans cette séquence), fragmentation et permutation apparente de piste pendant rotation. Au couple1509/1521, la piste93 passe apparemment entre deux personnes puis cumule deux confirmations. Une même personne visible peut aussi recevoir deux IDs et être réannoncée avant6,5s (frames537/620). Aucun seuil ni algorithme de tri n'est modifié par l'audit. Parité brute Mac147/164,18lignes de très faible score différentes ; les240détections≥0,70 concordent.

À la demande utilisateur, le nouveau build5109439f applique les gains d'amplitude gauche/droite70/30 pour LEFT,30/70 pour RIGHT et conserve CENTER1/1. **51testsJVM passent**, dont10PCM avec balayage des65536valeurs signées. Installation compatible réussie, capture antérieure préservée. La connexion a été tentée mais l'écran restait« Lunettes déconnectées » ; le confort et les côtés de ce mélange restent donc à confirmer à l'écoute. La preuve humaine historique concernait100/0.

## Limites conservées

Pas de profondeur ni de pose : aucune distance en mètres, voie libre ou autorisation de traverser. Identités temporaires par IoU, sans garantie de réidentification en cas d’occlusion/rotation. Modèle, seuils et tolérances inchangés.

Le premier collecteur froid a duré 600,016 s, mais la plus longue session de cette collecte ne dure que 507,038 s avant un arrêt SDK ; ce résultat **ne satisfait pas** dix minutes continues. La cause de cet arrêt n’est pas établie.

La route préférée et la route effective sont contrôlées avant et pendant les PCM. Cela ne prouve pas l’absence de toute fuite transitoire lors d’un changement de route Android. Arrêt acoustique dans les lunettes, latence Bluetooth jusqu’à l’oreille, coupure réelle de route et essai sans accès Internet restent des preuves distinctes. La synthèse locale est sélectionnée dans le code ; un essai réseau coupé complet n’a pas encore été exécuté.

Procédure : [DEMONSTRATION.md](../DEMONSTRATION.md). Les archives préservent les échecs et les anciens builds ; un simulateur ou une voix factice ne valide jamais les haut-parleurs physiques.


## Renommage Oria — 26 septembre 2026

Build `2fc0b36f173e8f1b3352f765377450a0d60fa293760056d43ff9983b253b64a8` : 51 tests JVM, 11 tests Mac et installation compatible réussis. Onglets Oria / Oria Lab vérifiés sur CN46V3M00284 ; capture utilisateur 0548b68a… de 164 PNG rouverte dans Oria Lab sur Mac. Les cinq manifestes de captures du téléphone sont inchangés. Aucun essai de caméra ou de son supplémentaire sur ce build ; la preuve 70/30 reste en attente d’écoute. Voir `new-device-CN46V3M00284/oria-branding.json`.


## Harmonisation complète Oria / Oria Lab

Build courant `f49def1953699349d1c189402c2a7b0229e6bd17db1ca7c0f29c54c9873eeb43` : 51 tests JVM, 12 tests Mac et 4 tests de rejeu Kotlin réussis. Installation compatible et modèle prêt sur CN46V3M00284 ; galerie retrouvée avec la scène de 60 secondes / 164 images. Migration de 5 captures Android, 7 caches Mac et 5 ZIP, après sauvegarde externe. Recalcul complet des 164 PNG sur Mac terminé en 11,3 s. Tenseurs du checkpoint et graphe ONNX inchangés après harmonisation des métadonnées. Aucune nouvelle preuve acoustique. Voir [ORIA_RENAMING.md](ORIA_RENAMING.md).
