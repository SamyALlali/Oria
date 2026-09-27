# Oria 1.6 — obstacles caméra et latence HTC

## Livraison

Version **1.6-obstacles-experimental / code 7**, package HTC conservé. Compilation, **117 tests JVM**, lint sans erreur, quatre tests instrumentés de parité/contrat CPU et de sélection d’interface réussis sur HTC U24 pro CN46V3M00284. Mise à jour `adb install -r`, même signature, sans désinstallation ni effacement. L’APK précédent et 697 fichiers privés ont été sauvegardés et leurs archives vérifiées avant intervention. Le manifeste final est `artifacts/depth-android-build.json`.

Sur l’accueil, choisir **Obstacles caméra · expérimental**, attendre le modèle, connecter les lunettes, puis démarrer et vérifier/autoriser les directions. Annonces **« Obstacle possible devant / à gauche / à droite »**, sortie Bluetooth et mélange 70/30 existants. Mode exclusif des objets YOLO, dont le modèle et les seuils restent inchangés. Aucun choix de catégorie ou de couleur de mur n’intervient dans le tri d’occupation.

Le flux réel est **vidéo H.264 continue des lunettes vers le HTC**, demandé à 30 images/s via le SDK ; la sélection des images à analyser intervient après décodage. Le retour microphone ne sert pas d’horloge vidéo. Les dépendances H.264 sont toutes conservées et la file d’images décodées garde au plus la plus récente en attente.

## Mesures et choix de vitesse

Modèle mobile FP32 252, CPU4. Sur une image synthétique 467×832, deux ordres de mesure (1/2/4 puis 4/2/1 threads), deux fixtures numériques par configuration, une chauffe et cinq calculs complets : médianes **415,7/416,1 ms avec 2 threads**, **259,5/261,5 ms avec 4**. Après cache borné des coefficients de resize et histogramme de luminance exact : **260,7 ms**. Le gain significatif vient des threads ; le gain des petites optimisations n’est pas établi séparément. Prétraitement identique aux fixtures Pillow, carte normalisée écart maximal < 9e-7 contre référence sur ces deux fixtures.

Huit replays exécutés **sur le HTC**, chacun borné à 8 s/240 paquets vidéo : MP4 SDK 480×856 à 30 fps, vrai décodeur matériel `c2.qti.avc.decoder`, prétraitement, modèle, géométrie et politique réels. Horloge d’origine du MP4 respectée, pas de radio ni voix. Toutes les ressources sont fermées explicitement ; résultats après arrêt invalidés, aucun résultat périmé accepté.

| Configuration | Âge du résultat p50, ms | p95, ms | Images traitées / 8 s | Attente en file p50, ms |
|---|---:|---:|---:|---:|
| CPU2, 333 ms, premier / répétition | 720 / 717 | 1429 / 1060 | 15 / 17 | 199 / 172 |
| CPU4, 333 ms, premier / ordre inversé | 388 / 389 | 529 / 413 | 22 / 23 | 2 / 1 |
| CPU4, à la demande, premier / ordre inversé | 397 / 394 | 409 / 890 | 20 / 19 | 1 / 1 |
| CPU4, 250 ms | 568 | 710 | 26 | 204 |
| CPU4, 167 ms | 454 | 548 | 26 | 87 |

**Décision : CPU4 et cadence333 ms**, meilleurs âges médians observés. Le mode à la demande testé reste seulement un point de comparaison du test décodeur ; son crédit de production a été retiré car il sérialisait conversion/calcul sans gain. 250/167 ms donnent davantage de résultats mais plus vieux. Ni la cadence radio ni les seuils métier ne sont changés.

Les percentiles incluent les premiers appels froids, les petits échantillons et les résultats invalidés à l’arrêt ; ils ne prouvent pas une endurance thermique. L’âge est mesuré depuis l’alimentation du décodeur sur le téléphone : **pas la latence caméra→radio→oreille**. Le test omet aperçu UI, capture PNG/JSON et vraie soumission audio ; il ne valide pas une scène en marche. Aucun nouveau test d’écoute humaine n’est prétendu. Les gardes de confirmation de trois observations/500 ms s’ajoutent au délai du résultat ; 390 ms n’est pas un temps garanti jusqu’à l’alerte.

## Contrats et limites

Entrée neuve ≤500 ms ; résultat et début de lecture ≤1500 ms pour ce mode expérimental seulement. Confirmation trois observations/500 ms, rupture après1500 ms, répétition huit secondes après confirmation de lecture par zone. Observation, intention, réservation et résultat audio distincts. Arrêt/reconnexion invalident résultats/tickets ; une réservation invalidée ne revit pas si un candidat réapparaît. Les captures incluent `depth_inference`, `depth_decision` et cartes relatives/provenance, sans fausse inférence YOLO vide. Le Lab Mac518 demeure un calcul différent ; la restitution fidèle des événements profondeur capturés dans toutes les vues Lab reste à compléter.

CPU518 a passé la parité sur HTC mais demande environ2016 ms par inférence ; XNNPACK a échoué au chargement avec optimisations ALL puis NO_OPT. Le checkpoint original est conservé. La variante252 FP32 est un export spatial distinct, pas une parité518 : sur les 256 PNG des deux captures, propositions descriptives7→8 et13→12, avec des candidats perdus sur certaines images de mobilier/personnes. La géométrie Kotlin utilise un échantillonneur RANSAC portable distinct ; 11/256 cartes518 diffèrent sur plan/comptage mais donnent les mêmes propositions avec une politique de laboratoire identique. Les tests de calcul ne garantissent pas tous les obstacles, une distance réelle ou un passage libre.

Rapports privés : `validation/depth-speed-20260927/` (benchmarks, chronologies, logs), `validation/depth-android-20260927/` (sauvegarde, export et comparaison252/518). Les captures privées et poids ne sont pas publiés.

## Reproduction bornée

Compiler avec JBR21 : `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`. Préparer le modèle local avec `surface-ml/prepare_depth_android.py` si nécessaire ; empreinte imposée dans son manifeste. Installer app et APK de test **avec `adb -s CN46V3M00284 install -r` seulement**. Ne pas utiliser connectedDebugAndroidTest/UTP, dont la désinstallation automatique effacerait les données.

Exécuter `am instrument -w` avec runner `com.htc.vive.eagle.hackathon.starter.test/androidx.test.runner.AndroidJUnitRunner`, classe `com.htc.vive.eagle.hackathon.starter.oria.video.DepthPipelineLatencyInstrumentedTest`, arguments `depthThreads=4`, `depthSampling=fixed`, `depthFixedIntervalMs=333`. Les rapports sont dans `files/depth_validation/`, à extraire par `run-as`. Le test ne demande jamais caméra, streaming SDK ou lecture audio. `depthSampling=on_demand,fixed` compare les deux modes ; durées plafonnées uniquement dans cet essai automatisé, jamais dans l’application.
