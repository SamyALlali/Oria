# Préparation ML — nouveau téléphone HTC

26 septembre 2026. Agent ML, reprise en lecture seule des artefacts et du runtime. Aucun ADB, Gradle, chargement de checkpoint, export, inférence ou benchmark exécuté par cet agent pendant cette reprise. Les commandes ci-dessous sont destinées à l’orchestrateur, seul responsable du téléphone.

## État établi et distinction des appareils

Le nouveau téléphone **`CN46V3M00284`**, initialement `unauthorized`, a ensuite été autorisé et identifié par l’orchestrateur : **HTC U24 pro, Android 14 / API 34, arm64-v8a, plateforme crow**. Le relevé daté du 26 septembre 2026 à 12:45:29 UTC est conservé dans `validation/new-device-CN46V3M00284/device-identification.json`. Ces caractéristiques proviennent du nouvel appareil ; elles ne sont pas déduites de l’ancien.

L’ancien appareil **`CN4B53M00860`** était un HTC U24 pro Android 14. Les preuves existantes de parité XNNPACK, d’échec CPU strict, d’endurance modèle et de replay combiné restent associées à cet ancien appareil. Le champ `android_parity_executed=true` du manifeste indique ces essais historiques, **pas une validation du nouveau téléphone**. Les rapports ML embarquent modèle/Android/ABI, mais pas le numéro ADB : joindre un contexte de run avec le serial est indispensable pour distinguer deux appareils du même modèle.

La simplification actuelle de l’interface ne nécessite aucun changement du modèle, des seuils, des classes ou du runtime. Les mentions Samy/Rayan, cinq onglets et ancien serial présentes dans les documents historiques sont remplacées par la décision de reprise ; leurs résultats de tests ne sont pas réécrits.

## Audit daté du 26 septembre — avant backend Bluetooth

Le script **`ml/verify_integrity.py`** effectue exclusivement des lectures et imprime un JSON. L’audit antérieur a conclu **44 contrôles vrais, `all_passed=true`, code de sortie 0** sur l’APK `artifacts/oria-silmo-debug.apk`, SHA-256 **`c93320f055eb2cd4165516a372fa1ea65706a8423dcfd8fc8bfe986c894f7f6c`**. Ce résultat date de l’état **avant ajout du backend Bluetooth** ; il ne certifie pas l’APK qui l’a remplacé. Le tableau ci-dessous conserve les résultats ML de cet audit daté.

La vérification de traçabilité suivante constate l’APK courant **`aef0571aeb6ea6662fbb478413ab3924640ca4dde5eafca8d68ba8a01ffe2a18`**, **159 596 536 octets**. Ses assets embarqués sont vérifiés par lecture ZIP : l’ONNX reste `c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d` et son manifeste reste `23b6e20c6c71c89a5da02249d72752f9c24c85709c71db54691c812364a0e67e`. Aucun modèle, tenseur de fixture, seuil, projection ou runtime ML n’a changé.

À cette vérification, le manifeste partagé décrit encore `c933…`. La relance du script retourne donc **43/44, code 1**, avec le seul contrôle `delivery_apk_sha=false` ; toutes les vérifications ML et les empreintes d’assets embarqués passent. C’est un décalage documentaire identifié, pas une erreur du modèle. L’orchestrateur possède et actualise ce manifeste. Le relevé `production-update-bluetooth.json` associe l’installation réussie de `aef…` au nouveau téléphone à 13:17:03 UTC ; cet agent l’a lu sans exécuter ADB. Cette preuve d’installation ne confirme pas l’audibilité.

| Élément | Résultat |
|---|---|
| Checkpoint original et copie de travail | SHA identique : `7dd79d15d1fe61200b19916c7d0b73136637fd768c9da53a94d0499f010a40c1` |
| ONNX exporté, asset Android et modèle dans l’APK livré | SHA identique : `c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d`, 38 030 922 octets |
| Manifestes export/asset/APK livré | Identiques : SHA `23b6e20c6c71c89a5da02249d72752f9c24c85709c71db54691c812364a0e67e` |
| Contrat numérique | FP32 `images[1,3,416,416]` → `output0[1,300,6]`, six classes, `xyxy,score,classe` |
| Projection | `raster_inverse_v2` présente ; dimensions redimensionnées après arrondi ; aucune rotation ajoutée au détecteur |
| Protocole de parité | Coordonnées ≤1 px et scores ≤0,001, appariement un-à-un de même classe ; inchangé |
| Fixtures ML | Les 18 empreintes image/tenseur/sortie des six fixtures correspondent au manifeste |
| Fixtures métier | Source Swift et JSONL inchangés ; les 18 résultats enregistrés Swift/Kotlin/attendus concordent |
| Environnement | Python 3.11.14 ; versions enregistrées et verrouillage complet concordent |
| Sources ML/fixtures suivies dans la dernière livraison | Aucun changement préexistant détecté |
| Preuves historiques | SHA des rapports CPU et XNNPACK conformes ; CPU `FAILED` et XNNPACK `PASSED` conservés |

Versions centrales inchangées : Ultralytics 8.4.27, torch 2.8.0, torchvision 0.23.0, ONNX 1.17.0, ONNX Runtime 1.22.0, numpy 2.2.6, OpenCV 5.0.0.93. Le contrôleur choisit explicitement XNNPACK ; le défaut CPU du constructeur reste un outil de comparaison, pas une validation universelle.

Pour reproduire uniquement cet audit, depuis `oria-htc/` :

```sh
ml/.venv/bin/python ml/verify_integrity.py
```

Le script compare aussi l’APK et ses sources au manifeste de livraison. Pendant un nouveau build, un écart sur la livraison peut être un état transitoire à signaler à l’orchestrateur ; ce n’est pas une raison de remplacer le modèle.

**Aucun réexport n’est justifié.** `ml/export_and_validate.py` reste syntaxiquement valide et les dépendances sont disponibles, mais ce script régénère les fixtures, manifestes et copies d’assets même lorsqu’il réutilise l’ONNX. Ne pas le lancer comme simple audit sur cette reprise. `fixtures/run_swift_reference.py` régénère également son harness et ses résultats : les comparaisons enregistrées ont été vérifiées sans réexécution ni écrasement.

## Différences préexistantes et limites à conserver

1. Le manifeste contient encore la mention historique `final projection tests pending`. Les preuves finales de l’ancien appareil figurent déjà dans `validation/unit-tests-final/` et `validation/device-final/`. Cette mention datée n’est ni un changement de projection ni une preuve sur le nouveau téléphone. Ne pas changer un asset simplement pour effacer cet historique avant recette.
2. CPU seul a échoué sur une substitution de très faible score en sortie TopK de `sdk_sample_1` : 299/300 lignes appariées, aucune au seuil applicatif 0,70 affectée. C’est toujours un **échec de parité complète**. Un nouveau résultat CPU devra être consigné séparément, sans assouplir les tolérances ni effacer l’ancien échec.
3. Le test XNNPACK est optionnel si le provider est indisponible : un test ignoré ou un JSON `UNAVAILABLE` n’est pas une réussite. Exiger `status=PASSED`, six fixtures et 300 lignes appariées par fixture.
4. L’instrumentation écrit les mêmes noms de rapports à chaque run. Collecter et archiver avant relance. Un `adb install -r` conserve les fichiers privés : ne pas attribuer un rapport ancien à un nouveau test qui aurait échoué avant sa rédaction. Vérifier également le verdict JUnit, le contexte du run et la présence des résultats attendus.
5. Aucun défaut de parsing ou d’intégrité nouveau n’a été identifié. Aucun fichier de runtime, poids, asset ou fixture existante n’a été modifié pendant cette préparation. Seul le script d’audit en lecture seule et le présent rapport ont été ajoutés.

## Commandes reproductibles réservées à l’orchestrateur

Les smoke tests et le replay décrits plus bas ont déjà été exécutés par l’orchestrateur. Les commandes suivantes restent une procédure de reproduction ; elles n’appellent pas une relance automatique. Ces variables ciblent exclusivement le nouveau téléphone. Les versions du harness sont distinctes du package whitelisted du prototype.

```sh
ORIA_WORKSPACE='/Users/sam/Documents/ChatGPT/Hackathon SILMO/oria-htc'
ORIA_ADB='/Users/sam/Library/Android/sdk/platform-tools/adb'
ORIA_SERIAL='CN46V3M00284'
ORIA_RUN="$ORIA_WORKSPACE/validation/new-device-$ORIA_SERIAL-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$ORIA_RUN"
"$ORIA_ADB" -s "$ORIA_SERIAL" get-state
"$ORIA_ADB" -s "$ORIA_SERIAL" shell getprop ro.product.model > "$ORIA_RUN/model.txt"
"$ORIA_ADB" -s "$ORIA_SERIAL" shell getprop ro.build.version.release > "$ORIA_RUN/android.txt"
"$ORIA_ADB" -s "$ORIA_SERIAL" shell getprop ro.product.cpu.abilist > "$ORIA_RUN/abi.txt"
printf '%s\n' "$ORIA_SERIAL" > "$ORIA_RUN/serial.txt"
```

Ne continuer que si le serial est bien celui identifié et son état est `device`. Construire puis installer **les deux APK du harness seulement**, si cette validation est retenue ; aucun benchmark concurrent ni session Oria active pendant la mesure :

```sh
export JAVA_HOME='/Users/sam/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home'
cd "$ORIA_WORKSPACE/validation-project"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > "$ORIA_RUN/harness-apks.sha256"
shasum -a 256 "$ORIA_WORKSPACE/ml/exports/oria_silmo_fp32.onnx" "$ORIA_WORKSPACE/android-project/app/src/main/assets/oria/model_manifest.json" > "$ORIA_RUN/model-assets.sha256"
"$ORIA_ADB" -s "$ORIA_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ORIA_ADB" -s "$ORIA_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Le harness est `com.oria.silmo.validation` ; son runner est `com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner`. Il compile directement les mêmes sources core/ML/vidéo et assets que le prototype, sans SDK HTC ni capture ou voix des lunettes. L’installation d’un harness n’est pas une validation du package whitelisted.

### 1. Parité XNNPACK et courte mesure — premier test

```sh
mkdir -p "$ORIA_RUN/xnnpack"
"$ORIA_ADB" -s "$ORIA_SERIAL" shell am instrument -w -r \
  -e class 'com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxObjectDetectorInstrumentedTest#xnnpackParityAndShortBenchmarkIfAvailable' \
  com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner > "$ORIA_RUN/xnnpack/instrumentation.log" 2>&1
"$ORIA_ADB" -s "$ORIA_SERIAL" exec-out run-as com.oria.silmo.validation \
  tar -cf - files/ml_validation > "$ORIA_RUN/xnnpack/reports.tar"
tar -xf "$ORIA_RUN/xnnpack/reports.tar" -C "$ORIA_RUN/xnnpack"
```

Exiger une exécution JUnit réussie et le JSON `files/ml_validation/onnx_xnnpack.json` portant `PASSED`. Vérifier six lignes de fixture, `preprocessing_passed=true`, `parity.passed=true`, `matched_rows=300`, zéro franchissement de seuil et aucun résultat applicatif non apparié. Les métriques incluent vingt inférences après trois échauffements ; c’est une mesure courte du modèle.

### 2. CPU — diagnostic séparé, si nécessaire

Même runner, méthode **`cpuParityAndShortBenchmark`**. Garder un dossier distinct `cpu/` et collecter immédiatement les rapports comme ci-dessus. `onnx_cpu.json`, les `.parity.json` et les `.output.f32` donnent le diagnostic avant assertion finale. Le succès du nouveau CPU ne réécrit pas l’échec ancien ; un échec n’autorise pas à relever les seuils. Ne pas lancer toute la classe ML simplement pour obtenir un ensemble vert.

### 3. Replay combiné et endurance — campagnes séparées

Pour le replay avec décodeur, modèle et politique, mais sink vocal factice :

```sh
"$ORIA_ADB" -s "$ORIA_SERIAL" shell am instrument -w -r \
  -e class 'com.htc.vive.eagle.hackathon.starter.oria.video.CombinedPipelineInstrumentedTest#sixtySecondReplayPipelineAndStopGuard' \
  -e pipelineSeconds 60 \
  com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Rapport : `files/combined-pipeline-report.json`. Pour l’endurance du calcul seul, après smoke réussi :

```sh
"$ORIA_ADB" -s "$ORIA_SERIAL" shell am instrument -w -r \
  -e class 'com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxObjectDetectorInstrumentedTest#sustainedXnnpackModelBenchmark' \
  -e mlEnduranceSeconds 600 \
  com.oria.silmo.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Rapports : `files/ml_validation/onnx_xnnpack_endurance_progress.json`, puis `onnx_xnnpack_endurance.json`. Créer un dossier distinct pour chaque campagne, conserver sa sortie d’instrumentation et collecter seulement les chemins réellement produits ; demander à `tar` un rapport vidéo inexistant peut faire échouer inutilement la collecte.

Sur la version actuelle, cette nouvelle endurance inclurait `raster_inverse_v2`, contrairement à l’endurance historique de l’ancien appareil. Elle resterait un test sur fixtures, sans SDK, transport radio, caméra réelle ni son.

## Résultats réellement obtenus sur CN46V3M00284

Les rapports proviennent de `validation/new-device-CN46V3M00284/`. Le log `harness-xnnpack-video-combined.log` se termine par **`OK (3 tests)`** ; le test CPU distinct, dans `harness-cpu.log`, se termine par **`Tests run: 1, Failures: 1`**. Le code de fin d’instrumentation `-1` apparaît dans les deux logs et ne suffit donc pas à décider d’une réussite.

Le contexte `harness-install.json` associe explicitement ce serial aux deux APK installés avec succès :

| APK du harness | SHA-256 |
|---|---|
| Application | `a4a70642817c6b9e0f28ed331825fbfeaba7bc9540fbe02a9dc4117169fb1252` |
| Instrumentation | `438c0173d03314e97bd85dda6f258b152086450778ac7f19828dddd344fe13b7` |

### Parité du modèle et mesures courtes

| Provider demandé | Verdict strict | Fixtures conformes | Inférence p50 | Inférence p95 | Maximum |
|---|---|---:|---:|---:|---:|
| XNNPACK avec repli CPU | **PASSED** | 6/6 | 152,343 ms | 152,765 ms | 152,775 ms |
| CPU seul | **FAILED** | 5/6 | 175,000 ms | 175,348 ms | 175,359 ms |

Les deux sessions utilisent deux threads, trois inférences d’échauffement et vingt mesures. Ce sont des latences d’inférence sur fixtures après échauffement, pas une mesure thermique ni une latence caméra→parole. Le libellé XNNPACK avec repli CPU désigne la configuration testée ; il ne prouve pas que chaque opérateur s’exécute sur XNNPACK.

Pour les six fixtures, le prétraitement Android produit exactement les tenseurs de référence (`max_input_difference=0`) dans les deux configurations. XNNPACK apparie les 300 lignes de chaque fixture dans les tolérances gelées de 1 px et 0,001. Les nombres de détections au seuil 0,70 sont respectivement **0, 0, 0, 3, 2, 0** pour `sdk_sample_0`, `sdk_sample_1`, `sdk_sample_2`, `ultralytics_bus`, `ultralytics_zidane`, `geometry_rgb_odd`. Aucun franchissement de seuil ni objet applicatif non apparié n’est observé. Ces deux images positives publiques et quatre cas négatifs/géométriques constituent un échantillon technique de parité ; elles ne mesurent ni précision générale, ni rappel, ni sécurité de navigation.

**CPU reproduit exactement le diagnostic de l’ancien appareil.** Sur `sdk_sample_1`, 299 lignes sur 300 sont appariées :

| Rôle | Ligne | Classe | Score | Boîte `xyxy` en pixels modèle |
|---|---:|---|---:|---|
| Référence absente | 291 | 1, véhicule | 0,000004202127456665039 | `[90.3802338, 5.3180084, 248.5981140, 401.2824707]` |
| Sortie CPU ajoutée | 299 | 2, vélo/trottinette | 0,000004112720489501953 | `[59.7374382, 409.1825562, 75.0832748, 415.8771362]` |

Les tableaux complets `unmatched_reference` et `unmatched_actual` du nouveau JSON sont identiques à ceux de `validation/device-run-02/files/ml_validation/onnx_cpu.json`. La collecte CPU est maintenant complète : **six diagnostics `cpu_*.parity.json` et six sorties `cpu_*.output.f32`** sont archivés dans le dossier du nouveau téléphone. Les douze fichiers sont identiques octet pour octet aux fichiers correspondants de l’ancien run 02 ; cette comparaison confirme la reproduction intégrale de ces sorties sur ce jeu de fixtures. Les six fixtures CPU présentent zéro franchissement de seuil et aucun objet non apparié au seuil 0,70. Cette distinction sous seuil explique la portée applicative limitée de l’écart sans transformer le verdict strict en réussite. Elle reste compatible avec une substitution près de la coupure TopK due aux différences numériques ; les tenseurs intermédiaires avant TopK sur téléphone n’ont pas été extraits, donc la cause interne n’est pas prouvée davantage par ce second appareil. Aucun modèle, seuil, tolérance ni classement de sortie n’a été modifié pour faire disparaître l’échec.

**Décision conservée : XNNPACK reste la configuration prototype validée sur cet échantillon du nouveau téléphone ; CPU seul reste expérimental et non validé en parité complète.**

### Décodage matériel et replay de 60 secondes

Le smoke codec décode le MP4 public du SDK par `c2.qti.avc.decoder` : 102 paquets reçus, 100 images décodées et 12 bitmaps livrés ; aucune erreur ni image périmée avant/après conversion. Son erreur absolue moyenne couleur vaut 11,572 sur son témoin de comparaison. Ces compteurs décrivent un test local à cadence limitée ; ils ne sont pas une mesure du flux caméra réel.

Le replay combiné, également alimenté par le MP4 local, utilise le même détecteur XNNPACK et la projection `raster_inverse_v2` :

| Mesure | Résultat nouveau téléphone |
|---|---:|
| Durée mesurée | 60,135 s |
| Paquets / images décodées / bitmaps livrés | 1 805 / 1 788 / 225 |
| Décisions tentées et acceptées | 224 / 224 |
| Débit accepté | 3,725 Hz |
| Âge de décision p50 / p95 / maximum | 291 / 302 / 357 ms |
| Inférence p50 / p95 / maximum | 152,830 / 155,372 / 159,921 ms |
| Prétraitement p50 / p95 / maximum | 41,024 / 46,884 / 52,442 ms |
| Rejets avant/après inférence, images périmées, remplacements de slot | 0 |
| Travail invalidé après arrêt | 1 |
| Son après arrêt | Aucun dans le sink factice |

Le test d’arrêt est atteint (`stopProbeReached=true`, `noVoiceAfterStop=true`). La sonde métier synthétique confirme « Piéton devant » via un sink factice et rejette le ticket tardif ; elle n’utilise ni le modèle ni une sortie audio réelle. Les images du replay n’ont déclenché **aucun événement vocal**. Les rapports précisent explicitement `sdkTransport=false` et `actualAudio=false` : cette réussite ne valide pas le SDK connecté, l’orientation physique, le transport radio, la TTS ou une alerte entendue dans les lunettes.

Aucune endurance de 600 secondes du calcul seul ni de la chaîne complète n’a été exécutée sur **ce nouveau téléphone** dans cette campagne. Les dix minutes historiques restent attribuées à l’ancien serial, avec l’ancienne projection et sans flux réel. Le replay de 60 secondes ne permet pas de conclure à l’absence d’échauffement à long terme ni de calculer une autonomie.

### Empreintes des preuves nouvelles

| Preuve dans `validation/new-device-CN46V3M00284/` | SHA-256 |
|---|---|
| `device-identification.json` | `d0a3d349d4177781d6b4c7a4a218f2df140d17856639d865cde3afb48727c289` |
| `harness-install.json` | `a6203d961235c1eb265665624f522ad0d97d6ab7f5a161c8b9c66f8090f40512` |
| `harness-xnnpack-video-combined.log` | `7d37fb8a7c49a986eed065b22b43043d3f984e9851fecd43257a13cf5d7667d9` |
| `harness-cpu.log` | `d4a9e00811322e94989694224e192b00f322538b5c6755e11dd4294df9471561` |
| `files/ml_validation/onnx_xnnpack.json` | `fc18f3334b7022934472e45dba04cafa17fb910240323435a9b0b57a68280ec2` |
| `files/ml_validation/onnx_cpu.json` | `33682beed4731adc514f45f9130376768ce950fd290a91ead69f072f03a13c69` |
| `files/video-decoder-report.json` | `66c4bf0e75e2f9bbe899610effff664991d4a9af35bc9695413341544545de5e` |
| `files/combined-pipeline-report.json` | `a6b8d93e2f24b274d0720ec0e6955c47ca95aea464e9647a6c6de9dfc342a2b0` |

## Suite des vérifications

- L’audit **44/44** est conservé comme résultat daté de `c933…`, avant backend Bluetooth. L’APK courant `aef…` contient les mêmes assets ML ; l’audit présent donne **43/44** car le SHA de livraison est encore ancien. Après mise à jour du manifeste partagé par l’orchestrateur, relancer `ml/verify_integrity.py`. Aucun réexport n’est nécessaire.
- Collecte CPU complète et vérifiée : conserver les douze fichiers par fixture avec le JSON global et le log de cette campagne.
- Si une endurance est lancée, rapporter résultats frais/périmés, offres sautées, débit utile, plus longue période sans résultat frais et évolution première/dernière minute. Distinguer mémoire après initialisation, après dix secondes, avant/après fermeture ; PSS, heap Java et allocations natives ne s’additionnent pas. Température batterie et température SoC sont des mesures distinctes.
- Réserver la confirmation de la caméra réelle, de son orientation et du son dans les lunettes à la recette physique correspondante.

**Verdict : assets ML inchangés dans le nouvel APK, collecte CPU complète ; manifeste de livraison à actualiser avant audit final global. Le 44/44 antérieur reste daté avant backend Bluetooth. Aucun réexport nécessaire ; XNNPACK, prétraitement, codec et replay court validés dans le harness du nouveau téléphone. L’échec CPU strict est reproduit sans modification des tolérances. La validation physique caméra→alerte entendue reste distincte et non établie par ces tests.**
