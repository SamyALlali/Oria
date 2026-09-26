# Oria Lab sur Mac

Double-cliquer **Lancer Oria Lab.command**, puis importer le ZIP de capture v1 ou saisir le chemin du dossier qui contient `manifest.json`. Le lanceur utilise l’environnement existant `ml/.venv`, choisit un port disponible et ouvre le navigateur. Garder son terminal ouvert ; Ctrl+C ferme le serveur. Rien n’est téléchargé à l’ouverture.

En ligne de commande, depuis `echonav-htc/` :

```sh
ml/.venv/bin/python echotest-desktop/server.py --port 8765
```

Pour ouvrir directement un ZIP ou dossier déjà récupéré : `ml/.venv/bin/python echotest-desktop/launch.py --import /chemin/session.zip`. Le lanceur importe puis ouvre la session ; l’URL ne contient qu’un identifiant local opaque.

Ouvrir `http://127.0.0.1:8765`. Serveur lié exclusivement à `127.0.0.1`, sans CDN. Les captures sont copiées dans `~/Library/Application Support/EchoTest/sessions/`, hors du dépôt. Ne pas publier ce dossier : il peut contenir des images de la scène. L’interface affiche le chemin de stockage en bas de page.

## Lire et recalculer

- **Observations du téléphone** : PNG exactes du bitmap remis au détecteur, boîtes normalisées, décisions et événements vocaux enregistrés. Timeline, lecture/pause, vitesse, touches ←/→ et espace.
- **Recalcul sur ce Mac** : le même ONNX FP32 416, RGB/letterbox et projection `raster_inverse_v2`. L’inférence utilise ONNX Runtime CPU Mac ; ses temps ne sont pas ceux du HTC XNNPACK. Les 300 lignes brutes sont comparées lorsqu’elles ont été enregistrées, sans relever les tolérances 1 px / 0,001. Un échec sous seuil reste un échec de parité brute.
- Le **moteur Kotlin réel** est rejoué chronologiquement via `echotest-policy/run_policy.py`. Il reprend les champs de `metadata.policyConfig` reconnus par `RgbAlertConfig`, puis les réglages visibles : nombre d’observations pour confirmer et intervalle de répétition. Les catégories et frontières de zones documentaires ne sont pas transmises comme paramètres inconnus.
- La **confirmation vocale simulée** intervient après le délai configurable (2 000 ms par défaut), avant la prochaine image lorsque son échéance est atteinte. Elle ne joue aucun son. Cette simulation suppose une orientation confirmée et une voix prête/non suspendue ; les portes du contrôleur et le transport audio ne sont pas reproduits. Les résultats du batch complet sont mémorisés : revenir à une image ne réinitialise pas le moteur. Le replay démarre sans mémoire antérieure à la capture ; ce n’est pas une restauration des pistes internes du téléphone.
- L’horloge métier vient de `decision.evaluatedAtMs` ou des timings de résultat enregistrés. Si une PNG n’a pas d’inférence horodatée, elle peut être réinférée mais sa politique est explicitement ignorée, sans inventer sa fraîcheur.

La vidéo H264 complète est une vue de contexte distincte. FFmpeg la remuxe localement en MP4 à horloge reconstruite 30 fps. Le pas vidéo de 1/30 s est nominal ; les horodatages irréguliers `packets.jsonl`, les PTS source et le repère orienté des PNG ne peuvent pas être déduits du MP4 reconstruit. Les boîtes/régressions reposent toujours sur les PNG et `frames.jsonl`.

Les captures EchoTest existantes restent compatibles avec Oria Lab ; le format et le répertoire de stockage conservent leurs identifiants techniques. L’ancien lanceur `Lancer EchoTest.command` ouvre aussi Oria Lab.

## Format v1

`manifest.json` (`schemaVersion:1`, `kind:"echotest-session"`), `frames.jsonl` et `frames/*.png`, `events.jsonl`, éventuellement `packets.jsonl` et `video.h264`. Jointure `(frames.videoSessionId, frameId)` ↔ `(events.sessionId, frameId)` ; aliases `videoSessionId` et `frame` acceptés côté événements. Le `sessionId` UUID du manifeste identifie la capture, pas la génération vidéo. Capture incomplète et dernière ligne JSONL partielle sont signalées. Les PNG manquantes sont signalées, jamais remplacées par une image proche de la vidéo.

L’import refuse traversées `..`, chemins absolus, liens symboliques, doublons ZIP et archives chiffrées. Limites : ZIP envoyé ≤1 Gio, contenu décompressé/dossier ≤2 Gio, 10 000 fichiers, index ≤32 Mio. Mutations HTTP protégées par origine/Host local et jeton de session ; aucun sous-processus avec `shell=True`.

## Dépendances et tests

Le modèle original et les assets Android ne sont ni convertis ni remplacés. Le lecteur utilise numpy, Pillow, scipy et onnxruntime déjà présents dans `ml/.venv`. Le runner Kotlin utilise le JDK et les jars Gradle existants, sans lancement Gradle par ce lecteur.

FFmpeg est cherché via `ECHOTEST_FFMPEG`, puis le binaire isolé `vendor/imageio_ffmpeg/binaries/ffmpeg-*`, puis le PATH. Installation isolée facultative, sans modifier le verrouillage ML :

```sh
ml/.venv/bin/python -m pip install --no-deps --target echotest-desktop/vendor imageio-ffmpeg==0.6.0
```

Sans FFmpeg, toutes les PNG, détections et fonctions de recalcul restent disponibles. `vendor/` est ignoré par Git.

```sh
ml/.venv/bin/python -m unittest discover -s echotest-desktop -p 'test_*.py' -v
```

Vérification locale : 11 tests passent, comprenant import ZIP/dossier, traversée de chemins/liens/collisions de casse refusées, dimensions PNG/index H264 incohérents, navigation et HTTP Range, six tenseurs identiques aux fixtures Android, vraie inférence ONNX positive, seuil exact `.70f`, et rejeu complet avec confirmation simulée via le moteur Kotlin. Le remux puis décodage FFmpeg du média SDK a également été vérifié. Deux captures réelles ont ensuite été importées et décodées : 20,219 s (49 PNG, 48 inférences) et 60,067 s (155 PNG, 154 inférences). Le recalcul des 49 PNG et le rejeu Kotlin des 48 décisions sont vérifiés. La parité brute Android XNNPACK / Mac CPU passe sur 41/48 images : sept lignes de très faible confiance diffèrent dans le top-k (maximum 0,000066668), sans écart sur les 57 lignes ≥ 0,70 ni bascule de seuil. Le verdict de parité brute demeure **FAIL**. Aucun enregistrement sonore ni annonce automatique n’est validé par ces captures, dont l’orientation était non confirmée. Voir `../validation/new-device-CN46V3M00284/echotest-real-replay.json` et son journal pour les mesures et limites.
