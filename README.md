# Oria · Oria Lab

Prototype du hackathon SILMO pour HTC VIVE Eagle : la caméra des lunettes transmet la vidéo au téléphone Android, qui exécute YOLO localement et annonce les objets en français dans les lunettes. **Oria Lab** enregistre une session puis permet son inspection image par image et son recalcul sur Mac.

Le prototype reprend notre modèle YOLO et des politiques portées depuis une base Swift. Le package autorisé reste `com.htc.vive.eagle.hackathon.starter`.

**Travaux antérieurs dans Oria Lab : Obstacles · expérimental.** Segmentation et relief relatif recherchent des régions non couvertes par YOLO, inspectables image par image. Ce rapport concerne le volet Mac silencieux, sans distance métrique. La profondeur Android expérimentale ajoutée ensuite est conservée dans1.8. **164 images analysées et 128 tests Python réussis** ; [résultats et limites](oria-htc/validation/OBSTACLES_LAB_20260927.md), [préparation des modèles](oria-htc/surface-ml/README.md).

## Candidat sélectif sur la branche fusion — 27 septembre

**1.8-fusion-preview** ajoute une navigation réelle ou simulée, une voie audio prioritaire pour les dangers, la dictée du téléphone et des protections de confidentialité. La base est **main b650dad (1.7-unified)** : modèles, deux analyses parallèles, façade HTC, accueil accessible et fonctionnement en veille conservés. Ce candidat ne modifie pas main.

**200 tests JVM et 176 tests Python passent.** [Comparaison, décisions, recette HTC et limites](oria-htc/validation/FUSION_REVIEW.md). Le moteur Rayan est intégré en diagnostic ; il ne remplace pas nos décisions vocales. La navigation GPS est limitée au premier plan, la transcription mains libres des lunettes n’est pas revendiquée, et les obstacles restent expérimentaux.

## Historique de l’interface 1.5 — 27 septembre

**À la livraison 1.5, le candidat était compilé et non encore installé.** Accueil simplifié, grandes commandes, thème clair/sombre contrasté et contrôles nommés pour TalkBack ; Oria Lab reprend le même style. Trois agents ont assuré réalisation et revue ; 83 tests JVM passent. Le rendu agrandi et le parcours TalkBack restent à vérifier sur HTC. [Livraison 1.5 et limites](oria-htc/validation/ACCESSIBILITY_DELIVERY_20260927.md).

**Validation historique : 1.4-night sur CN46V3M00284.** Vidéo et trois positions vocales 70/30 confirmées avec l’utilisateur. Le mode poche, pour lequel une coupure possible a été signalée, reste à retester. [Recette physique 1.4](oria-htc/validation/LIVE_20260927_V14.md).

## Travaux de nuit antérieurs

**Oria 1.3-night a été installé sur le HTC CN46V3M00284**, puis remplacé par 1.4. Les captures n’ont plus de limite de durée et le mode poche n’expire plus après 15 minutes. Le stockage garde 512 Mio libres, sans purge automatique. Le suivi habituel reste le défaut et le mode poche reste une option expérimentale. [Livraison, mesures et suite des travaux](oria-htc/validation/NIGHT_WORK_20260927.md).

**Livraison 1.3 : 83 tests JVM Android et 10 tests de galerie sur HTC passent.** Les 164 images et 240 détections de la capture réelle concordent avec une lecture indépendante ; galerie synthétique de 21 622 images ouverte en 14,2 s sur HTC. Le ZIP Mac se télécharge nativement, sans blob intégral ; 169 fichiers réels vérifiés identiques par SHA-256.

**Dernière étape Mac : 62 tests Python et les régressions JavaScript passent**, avec 11 fixtures synthétiques partagées et une revue indépendante. Les positions abîmées restent inspectables ; les identités ou horloges ambiguës bloquent le recalcul. Une PNG manquante seule conserve les données enregistrées exploitables, avec couverture visuelle incomplète signalée. La scène réelle garde exactement ses 164 positions, 240 détections et résultats de politique. [Validation Mac](oria-htc/artifacts/night-20260927-mac-integrity-validation.json).

**Preuves antérieures conservées sur 1.2-night :** 10 instruments de capture/modèle/replay et 6 tests de rejeu Kotlin ; replay de 60 s sur HTC à 3,73 décisions fraîches/s, âge p95 294 ms, voix factice. Les lunettes restent déconnectées : aucune nouvelle preuve de voix réelle ou d’écran verrouillé avec flux lunettes.

Oria Lab Mac lit les données détaillées à la demande et écrit ses rapports progressivement : l’index de la scène de 164 images pèse désormais 56,2 Ko, diagnostics d’intégrité compris, contre 11,44 Mo avant les travaux de nuit. Captures, corbeille/restauration, export et comparaison A/B sont disponibles. Le suivi V2 reste facultatif.

Pour produire l’APK et son manifeste : `python3 oria-htc/validation/build_offline.py`. La release ci-dessous reste historique ; [le manifeste du candidat](oria-htc/artifacts/offline_candidate_manifest.json) identifie la dernière compilation, sans prouver son installation.

## Démarrer après un clone

Prérequis Android : JDK 17 ou 21, SDK Android 36, Build Tools 36.0.0, accès aux dépôts Maven pour la première compilation. Les AAR HTC du starter sont inclus. Le checkpoint PyTorch et le modèle ONNX FP32 sont versionnés ; aucun réexport n’est nécessaire pour lancer l’application.

```sh
git clone git@github.com:SamyALlali/Oria.git
cd Oria
python3 oria-htc/scripts/prepare_gradle.py
cd oria-htc/android-project
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Définir `JAVA_HOME` vers le JDK et `ANDROID_HOME` vers le SDK, ou ouvrir `oria-htc/android-project` dans Android Studio qui crée le fichier local `local.properties`. Le script prépare Gradle 8.13 à l’emplacement attendu par le wrapper HTC et contrôle son SHA-256. L’APK compilé se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

La [release SILMO](https://github.com/SamyALlali/Oria/releases/tag/v0.1.1-silmo) contient l’APK Oria déjà installé et testé sur le HTC du hackathon, ainsi que son empreinte. La clé de signature reste locale : un build sur un autre ordinateur peut avoir une signature différente et Android refusera alors sa mise à jour directe. La procédure et les sauvegardes sont expliquées dans le [guide de démonstration](oria-htc/DEMONSTRATION.md).

## Oria Lab sur Mac

Compiler Android une première fois pour préparer les dépendances Kotlin locales, puis depuis la racine du dépôt :

```sh
cd oria-htc
python3.11 -m venv ml/.venv
ml/.venv/bin/python -m pip install -r ml/requirements.lock.txt
ml/.venv/bin/python -m unittest discover -s oria-lab-desktop -p 'test_*.py' -v
ml/.venv/bin/python oria-lab-desktop/launch.py
```

Le lanceur **[Lancer Oria Lab.command](<oria-htc/oria-lab-desktop/Lancer Oria Lab.command>)** ouvre le laboratoire dans le navigateur. Importer un ZIP exporté depuis Oria Lab sur le téléphone, ou utiliser **[Récupérer depuis HTC.command](<oria-htc/oria-lab-transfer/Récupérer depuis HTC.command>)** avec un téléphone autorisé en USB. Aucune capture personnelle n’est incluse dans le dépôt. FFmpeg est facultatif pour la vidéo intégrale ; les PNG et le moteur de recalcul fonctionnent sans lui.

## État vérifié de la version précédente

- Build Oria : **51 tests JVM, 12 tests Mac et 4 tests de rejeu Kotlin réussis**, installé sur HTC U24 pro / Android 14.
- Chaîne lunettes → inférence → annonces vocales démontrée sur CN46V3M00284 avant le renommage. Installation historique du 26 septembre sur CN4B53M00860 : lancement et modèle chargé vérifiés, sans nouvelle preuve vidéo/voix.
- Gains PCM actuels : gauche **70/30**, droite **30/70**, centre **1/1**. Écoute des trois positions confirmée sur 1.4 le 27 septembre ; la preuve humaine plus ancienne concernait 100/0.
- Audit d’une capture de 60 secondes : **164/164 décisions reproduites**, **240/240 détections ≥0,70 concordantes** entre téléphone et Mac. La parité brute échoue sur certaines lignes de très faible confiance ; des limites de suivi et des faux positifs restent documentés.

Le rejeu Mac simule les transactions vocales et ne reproduit pas les délais matériels HTC/Bluetooth. La caméra RGB n’apporte ni profondeur, ni distance métrique, ni garantie de voie libre.

## Organisation

| Dossier | Contenu |
|---|---|
| `oria-htc/android-project` | Application Kotlin, SDK HTC, modèle embarqué, tests |
| `oria-htc/oria-lab-desktop` | Oria Lab sur Mac, import, visualisation et réinférence |
| `oria-htc/oria-lab-policy` | Rejeu du moteur Kotlin réel sur JVM |
| `oria-htc/oria-lab-transfer` | Export USB des captures |
| `oria-htc/ml` | Checkpoint, export ONNX, contrat des tenseurs, environnement verrouillé |
| `oria-htc/fixtures` | Références communes Swift/Kotlin |
| `oria-htc/validation` | Outils et conclusions ; données physiques brutes conservées localement |

Le [cahier des charges](CAHIER_DES_CHARGES_ORIA_SILMO.md), les [décisions d’architecture](DECISIONS_ARCHITECTURE_ORIA_SILMO.md), la [matrice de portage](PORTAGE_ORIA_SWIFT_ANDROID.md) et l’[audit de scène](oria-htc/validation/user-scene-0548b68a/AUDIT.md) détaillent les choix et leurs limites. Les fichiers et dossiers utilisent les noms `oria` / `oria-lab`. Les captures ont été migrées vers ces noms en conservant leurs UUID, images et événements. Les métadonnées descriptives du modèle ont été renommées, avec vérification des 708 tenseurs du checkpoint et du graphe ONNX inchangés. Voir le [rapport du renommage](oria-htc/validation/ORIA_RENAMING.md).

Voir [PUBLICATION.md](PUBLICATION.md) pour distinguer les fichiers Git, les artefacts de release et les preuves disponibles uniquement sur le Mac d’origine.
