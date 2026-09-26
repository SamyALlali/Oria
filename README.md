# Oria · Oria Lab

Prototype du hackathon SILMO pour HTC VIVE Eagle : la caméra des lunettes transmet la vidéo au téléphone Android, qui exécute YOLO localement et annonce les objets en français dans les lunettes. **Oria Lab** enregistre une session puis permet son inspection image par image et son recalcul sur Mac.

Le prototype reprend le modèle et des politiques du projet EIP **EchoNav**, développé en Swift. Le package autorisé reste `com.htc.vive.eagle.hackathon.starter`.

## Démarrer après un clone

Prérequis Android : JDK 17 ou 21, SDK Android 36, Build Tools 36.0.0, accès aux dépôts Maven pour la première compilation. Les AAR HTC du starter sont inclus. Le checkpoint PyTorch et le modèle ONNX FP32 sont versionnés ; aucun réexport n’est nécessaire pour lancer l’application.

```sh
git clone git@github.com:SamyALlali/Oria.git
cd Oria
python3 echonav-htc/scripts/prepare_gradle.py
cd echonav-htc/android-project
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Définir `JAVA_HOME` vers le JDK et `ANDROID_HOME` vers le SDK, ou ouvrir `echonav-htc/android-project` dans Android Studio qui crée le fichier local `local.properties`. Le script prépare Gradle 8.13 à l’emplacement attendu par le wrapper HTC et contrôle son SHA-256. L’APK compilé se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

La [release SILMO](https://github.com/SamyALlali/Oria/releases/tag/v0.1.0-silmo) contient l’APK Oria déjà installé et testé sur le HTC du hackathon, ainsi que son empreinte. La clé de signature reste locale : un build sur un autre ordinateur peut avoir une signature différente et Android refusera alors sa mise à jour directe. La procédure et les sauvegardes sont expliquées dans le [guide de démonstration](echonav-htc/DEMONSTRATION.md).

## Oria Lab sur Mac

Compiler Android une première fois pour préparer les dépendances Kotlin locales, puis depuis la racine du dépôt :

```sh
cd echonav-htc
python3.11 -m venv ml/.venv
ml/.venv/bin/python -m pip install -r ml/requirements.lock.txt
ml/.venv/bin/python -m unittest discover -s echotest-desktop -p 'test_*.py' -v
ml/.venv/bin/python echotest-desktop/launch.py
```

Le lanceur **[Lancer Oria Lab.command](<echonav-htc/echotest-desktop/Lancer Oria Lab.command>)** ouvre le laboratoire dans le navigateur. Importer un ZIP exporté depuis Oria Lab sur le téléphone, ou utiliser **[Récupérer depuis HTC.command](<echonav-htc/echotest-transfer/Récupérer depuis HTC.command>)** avec un téléphone autorisé en USB. Aucune capture personnelle n’est incluse dans le dépôt. FFmpeg est facultatif pour la vidéo intégrale ; les PNG et le moteur de recalcul fonctionnent sans lui.

## État vérifié

- Build Oria : **51 tests JVM et 11 tests Mac réussis**, installé sur HTC U24 pro / Android 14.
- Chaîne lunettes → inférence → annonces vocales démontrée sur ce téléphone avant le renommage.
- Gains PCM actuels : gauche **70/30**, droite **30/70**, centre **1/1**. L’écoute de ce nouveau mélange reste à confirmer ; la preuve humaine précédente concernait 100/0.
- Audit d’une capture de 60 secondes : **164/164 décisions reproduites**, **240/240 détections ≥0,70 concordantes** entre téléphone et Mac. La parité brute échoue sur certaines lignes de très faible confiance ; des limites de suivi et des faux positifs restent documentés.

Le rejeu Mac simule les transactions vocales et ne reproduit pas les délais matériels HTC/Bluetooth. La caméra RGB n’apporte ni profondeur, ni distance métrique, ni garantie de voie libre.

## Organisation

| Dossier | Contenu |
|---|---|
| `echonav-htc/android-project` | Application Kotlin, SDK HTC, modèle embarqué, tests |
| `echonav-htc/echotest-desktop` | Oria Lab sur Mac, import, visualisation et réinférence |
| `echonav-htc/echotest-policy` | Rejeu du moteur Kotlin réel sur JVM |
| `echonav-htc/echotest-transfer` | Export USB des captures |
| `echonav-htc/ml` | Checkpoint, export ONNX, contrat des tenseurs, environnement verrouillé |
| `echonav-htc/fixtures` | Références communes Swift/Kotlin |
| `echonav-htc/validation` | Outils et conclusions ; données physiques brutes conservées localement |

Le [cahier des charges](CAHIER_DES_CHARGES_ECHONAV_SILMO.md), les [décisions d’architecture](DECISIONS_ARCHITECTURE_ECHONAV_SILMO.md), la [matrice de portage](PORTAGE_ECHONAV_SWIFT_ANDROID.md) et l’[audit de scène](echonav-htc/validation/user-scene-0548b68a/AUDIT.md) détaillent les choix et leurs limites. Les anciens noms techniques `echonav` / `echotest` sont conservés pour la compatibilité des données et de l’historique.

Voir [PUBLICATION.md](PUBLICATION.md) pour distinguer les fichiers Git, les artefacts de release et les preuves disponibles uniquement sur le Mac d’origine.
