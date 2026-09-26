# Oria Lab sur Mac

Double-cliquer **Lancer Oria Lab.command**, puis importer le ZIP de capture v1 ou saisir le chemin du dossier qui contient `manifest.json`. Le lanceur utilise l’environnement existant `ml/.venv`, choisit un port disponible et ouvre le navigateur. Garder son terminal ouvert ; Ctrl+C ferme le serveur. Rien n’est téléchargé à l’ouverture.

En ligne de commande, depuis `oria-htc/` :

```sh
ml/.venv/bin/python oria-lab-desktop/server.py --port 8765
```

Pour ouvrir directement un ZIP ou dossier déjà récupéré : `ml/.venv/bin/python oria-lab-desktop/launch.py --import /chemin/session.zip`. Le lanceur importe puis ouvre la session ; l’URL ne contient qu’un identifiant local opaque.

Ouvrir `http://127.0.0.1:8765`. Serveur lié exclusivement à `127.0.0.1`, sans CDN. Les captures sont copiées dans `~/Library/Application Support/Oria Lab/sessions/`, hors du dépôt. Ne pas publier ce dossier : il peut contenir des images de la scène. L’interface affiche le chemin de stockage en bas de page.

## Bibliothèque, noms et corbeille

La bibliothèque liste les captures déjà importées. Ouvrir une capture ne la copie pas une seconde fois. **Renommer** écrit uniquement `session-label.json` avec `{"schemaVersion":1,"displayName":"…"}` ; le manifeste, son UUID, les images et les événements ne sont pas réécrits. Le nom utilise le même contrat qu’Android : NFC, espaces Unicode condensés, contrôles et caractères invisibles Cc/Cf/Cs retirés, entre 1 et 80 points Unicode, sans troncature. Le sidecar est inclus dans **Exporter ZIP** et relu à l’import.

**Mettre en corbeille** déplace atomiquement le dossier vers `.trash/`, dans le même stockage. Cocher **Corbeille**, puis **Restaurer** conserve le même identifiant et les mêmes octets. Aucune fonction de suppression définitive n’existe. Renommer ou archiver une capture occupée par un traitement/export est refusé ; annuler un traitement puis attendre sa fin libère la capture. Les tests de ces opérations utilisent exclusivement des dossiers temporaires.

## Comparer A / B

**Comparer la session complète** exécute deux instances du moteur Kotlin. A et B reçoivent exactement les mêmes détections, `observedAtMs` et horloge d’évaluation, avec le même délai de confirmation simulée. Deux sources sont possibles : détections enregistrées (aucune inférence ONNX), ou un seul recalcul ONNX partagé entre A et B pour chaque PNG. Une image sans inférence enregistrée n’invente pas une observation vide ; elle est explicitement ignorée dans le mode enregistré.

Les réglages visibles permettent de comparer le suivi `LEGACY_IOU` à `STABLE_RGB_V2` expérimental, les observations nécessaires à la confirmation et le délai de répétition. Les autres paramètres sont hérités de la capture. Ce choix ne change pas le réglage de l’application HTC. Le début de chaque variante a une mémoire vierge ; ce n’est pas une restitution de l’état interne du téléphone avant le début de la capture.

Le résumé donne les décisions différentes, les annonces différentes et séparément les changements d’identifiants. Le verdict sémantique compare les géométries, confirmations, sélection, suppression et intention d’annonce : les numéros arbitraires de piste/ticket et les noms diagnostics d’association ne créent pas à eux seuls un écart. Les traces complètes conservent ces identifiants. **Écart suivant** et les boutons d’image permettent l’inspection ; **Exporter le rapport JSON** inclut chaque entrée commune, son empreinte globale, les paramètres, décisions A/B, résumés et événements vocaux simulés. Le rapport est aussi stocké localement à côté de la capture comme analyse dérivée.

**Annuler le traitement** l’interrompt entre deux images. Passer à une autre capture invalide les réponses UI en cours et annule l’ancien traitement. Au plus quatre traitements sont actifs/en attente, seize restent en mémoire, deux imports sont admis simultanément. Les calculs de session sont sérialisés pour éviter une multiplication des moteurs ONNX.

## Écoute stéréo sur Mac

L’aperçu vocal ne démarre que par **Écouter sur ce Mac**. Il utilise la voix locale macOS Thomas, convertie en PCM16, avec les gains Android : gauche `(0.7,0.3)`, droite `(0.3,0.7)`, centre `(1,1)`, arrondi symétrique. Les gains expriment l’amplitude, pas une égalisation de puissance ou de volume. Le son passe par la sortie audio du Mac ; utiliser une sortie stéréo pour comparer les canaux. **Inspecter** une annonce A/B prépare sa phrase et son côté sans la jouer.

Cet aperçu ne modifie pas les tickets/confirmations de la simulation et ne valide ni le routage Bluetooth ni l’écoute HTC. Il requiert `/usr/bin/say` et FFmpeg local ; aucune voix n’est téléchargée. **Arrêter l’écoute** et tout changement de session invalident les synthèses précédentes. Le serveur autorise les URL `blob:` uniquement pour les médias ; les protections script/connexion/Host/Origin/jeton restent locales.

## Lire et recalculer

- **Observations du téléphone** : PNG exactes du bitmap remis au détecteur, boîtes normalisées, décisions et événements vocaux enregistrés. Timeline, lecture/pause, vitesse, touches ←/→ et espace.
- **Recalcul sur ce Mac** : le même ONNX FP32 416, RGB/letterbox et projection `raster_inverse_v2`. L’inférence utilise ONNX Runtime CPU Mac ; ses temps ne sont pas ceux du HTC XNNPACK. Les 300 lignes brutes sont comparées lorsqu’elles ont été enregistrées, sans relever les tolérances 1 px / 0,001. Un échec sous seuil reste un échec de parité brute.
- Le **moteur Kotlin réel** est rejoué chronologiquement via `oria-lab-policy/run_policy.py`. Il reprend les champs de `metadata.policyConfig` reconnus par `RgbAlertConfig`, puis les réglages visibles : nombre d’observations pour confirmer et intervalle de répétition. Les catégories et frontières de zones documentaires ne sont pas transmises comme paramètres inconnus.
- La **confirmation vocale simulée** intervient après le délai configurable (2 000 ms par défaut), avant la prochaine image lorsque son échéance est atteinte. Elle ne joue aucun son. Cette simulation suppose une orientation confirmée et une voix prête/non suspendue ; les portes du contrôleur et le transport audio ne sont pas reproduits. Les résultats du batch complet sont mémorisés : revenir à une image ne réinitialise pas le moteur. Le replay démarre sans mémoire antérieure à la capture ; ce n’est pas une restauration des pistes internes du téléphone.
- L’horloge métier vient de `decision.evaluatedAtMs` ou des timings de résultat enregistrés. Si une PNG n’a pas d’inférence horodatée, elle peut être réinférée mais sa politique est explicitement ignorée, sans inventer sa fraîcheur.

La vidéo H264 complète est une vue de contexte distincte. FFmpeg la remuxe localement en MP4 à horloge reconstruite 30 fps. Le pas vidéo de 1/30 s est nominal ; les horodatages irréguliers `packets.jsonl`, les PTS source et le repère orienté des PNG ne peuvent pas être déduits du MP4 reconstruit. Les boîtes/régressions reposent toujours sur les PNG et `frames.jsonl`.

Les captures du poste ont été migrées vers le marqueur `oria-lab-session` et le stockage Oria Lab, après sauvegarde externe de leurs originaux. Le lecteur attend ce nouveau marqueur. Le seul lanceur du dépôt est `Lancer Oria Lab.command`.

## Format v1

`manifest.json` (`schemaVersion:1`, `kind:"oria-lab-session"`), `frames.jsonl` et `frames/*.png`, `events.jsonl`, éventuellement `packets.jsonl` et `video.h264`. Jointure `(frames.videoSessionId, frameId)` ↔ `(events.sessionId, frameId)` ; aliases `videoSessionId` et `frame` acceptés côté événements. Le `sessionId` UUID du manifeste identifie la capture, pas la génération vidéo. Capture incomplète et dernière ligne JSONL partielle sont signalées. Les PNG manquantes sont signalées, jamais remplacées par une image proche de la vidéo.

L’import refuse traversées `..`, chemins absolus, liens symboliques, doublons ZIP et archives chiffrées. Limites : ZIP envoyé ≤1 Gio, contenu décompressé/dossier ≤2 Gio, 10 000 fichiers, index ≤32 Mio. Mutations HTTP protégées par origine/Host local et jeton de session ; aucun sous-processus avec `shell=True`.

## Dépendances et tests

Le graphe ONNX et les paramètres du modèle sont conservés ; seules des métadonnées descriptives ont changé. Le recalcul accepte les deux empreintes vérifiées de ce même graphe et refuse une empreinte inconnue. Le lecteur utilise numpy, Pillow, scipy et onnxruntime déjà présents dans `ml/.venv`. Le runner Kotlin utilise le JDK et les jars Gradle existants, sans lancement Gradle par ce lecteur.

FFmpeg est cherché via `ORIA_LAB_FFMPEG`, puis le binaire isolé `vendor/imageio_ffmpeg/binaries/ffmpeg-*`, puis le PATH. Installation isolée facultative, sans modifier le verrouillage ML :

```sh
ml/.venv/bin/python -m pip install --no-deps --target oria-lab-desktop/vendor imageio-ffmpeg==0.6.0
```

Sans FFmpeg, toutes les PNG, détections et fonctions de recalcul restent disponibles. `vendor/` est ignoré par Git.

```sh
ml/.venv/bin/python -m unittest discover -s oria-lab-desktop -p 'test_*.py' -v
```

Vérification locale actuelle : 25 tests passent. Les 13 nouveaux tests couvrent bibliothèque et noms Unicode partagés, export ZIP/labels, corbeille/restauration et conservation des fichiers, blocage des mutations pendant un export/job, annulation et limites d’import, protections HTTP et CSP média, A/B sans double inférence, horloges et confirmations identiques, exclusion des simples renumérotations, gains PCM16 exacts et nettoyage d’un remux interrompu avant publication atomique. Les 12 tests préexistants couvrent import ZIP/dossier, traversée de chemins/liens/collisions de casse refusées, dimensions PNG/index H264 incohérents, navigation et HTTP Range, six tenseurs identiques aux fixtures Android, vraie inférence ONNX positive, seuil exact `.70f`, rejeu complet avec confirmation simulée via le moteur Kotlin, et admission des seules empreintes vérifiées du même graphe. Une synthèse Thomas locale a produit un WAV stéréo 22 050 Hz vérifié ; aucune écoute humaine HTC n’en est déduite. Les mesures de captures qui suivent sont historiques. Le remux puis décodage FFmpeg du média SDK a également été vérifié. Deux captures réelles ont ensuite été importées et décodées : 20,219 s (49 PNG, 48 inférences) et 60,067 s (155 PNG, 154 inférences). Le recalcul des 49 PNG et le rejeu Kotlin des 48 décisions sont vérifiés. La parité brute Android XNNPACK / Mac CPU passe sur 41/48 images : sept lignes de très faible confiance diffèrent dans le top-k (maximum 0,000066668), sans écart sur les 57 lignes ≥ 0,70 ni bascule de seuil. Le verdict de parité brute demeure **FAIL**. Aucun enregistrement sonore ni annonce automatique n’est validé par ces captures, dont l’orientation était non confirmée. Voir `../validation/new-device-CN46V3M00284/oria-lab-real-replay.json` et son journal pour les mesures et limites.

Régressions d’interaction sans navigateur : `node oria-lab-desktop/test_ui.js` (Node, sans dépendance tierce), depuis `oria-htc/`. Vérifie les trois zones du moteur vers l’aperçu, une lecture asynchrone devenue périmée, la confirmation de corbeille et l’arrêt de la vidéo lors de l’archivage. La recette visuelle du navigateur demeure distincte.
