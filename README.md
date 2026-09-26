# Oria · Oria Lab

Prototype du hackathon SILMO pour HTC VIVE Eagle : la caméra des lunettes transmet la vidéo au téléphone Android, qui exécute YOLO localement et annonce les objets en français dans les lunettes. **Oria Lab** enregistre une session puis permet son inspection image par image et son recalcul sur Mac.

Le prototype reprend notre modèle YOLO et des politiques portées depuis une base Swift. Le package autorisé reste `com.htc.vive.eagle.hackathon.starter`.

## Version de nuit — 27 septembre

**Oria 1.2-night est installé sur le HTC CN46V3M00284**, par mise à jour conservant les données. Les captures n’ont plus de limite de durée et le mode poche n’expire plus après 15 minutes. Le stockage garde 512 Mio libres, sans purge automatique. Le suivi habituel reste le défaut et le mode poche reste une option expérimentale. [Livraison, mesures et suite des travaux](oria-htc/validation/NIGHT_WORK_20260927.md).

**83 tests JVM Android, 10 tests instrumentés sur HTC, 36 tests Python Mac et 6 tests de rejeu Kotlin passent**, ainsi que les régressions JavaScript. Replay de 60 s sur HTC : 3,73 décisions fraîches/s, âge p95 294 ms. Les lunettes sont déconnectées pendant cette campagne : aucune nouvelle preuve de voix réelle ou d’écran verrouillé avec flux lunettes.

Oria Lab Mac lit désormais les données détaillées à la demande et écrit ses rapports progressivement : l’index de la scène de 164 images passe de 11,44 Mo à 34,6 Ko, avec les mêmes résultats de politique. Captures, corbeille/restauration, export et comparaison A/B sont disponibles. Le suivi V2 reste facultatif.

Pour produire l’APK et son manifeste : `python3 oria-htc/validation/build_offline.py`. La release ci-dessous reste historique ; la version de nuit compilée et installée est identifiée dans [le manifeste](oria-htc/artifacts/offline_candidate_manifest.json).

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
- Gains PCM actuels : gauche **70/30**, droite **30/70**, centre **1/1**. L’écoute de ce nouveau mélange reste à confirmer ; la preuve humaine précédente concernait 100/0.
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
