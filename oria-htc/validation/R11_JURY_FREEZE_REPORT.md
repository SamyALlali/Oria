# R11 — Gel jury et plans B

Date : **27 septembre 2026**

## Verdict

Le candidat logiciel est gelé sous l’identité **Oria 1.5-jury-r11 / versionCode 6**, package `com.htc.vive.eagle.hackathon.starter`. L’APK release local possède une empreinte et un certificat consignés dans le manifeste.

La porte physique n’est pas encore fermée : l’ancien starter installé sur `CN4B53M00860` est signé par `60372c…beb7`, tandis que le candidat local est signé par la clé debug de ce poste `15876f…c411`. Android refuse donc `install -r` avec `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Le flux prévu par l’organisateur est de modifier le starter en conservant son package, puis de remplacer son ancienne installation par notre version après sauvegarde ; aucune clé HTC n’est requise et aucun second package ne doit être créé. Ce remplacement n’avait pas encore été exécuté lors du gel. Les trois scénarios ci-dessous sont virtuels, pas trois démonstrations physiques.

## Identité du candidat

| Champ | Valeur |
|---|---|
| Release | `oria-1.5-jury-r11` |
| Package | `com.htc.vive.eagle.hackathon.starter` |
| Version | `1.5-jury-r11` / code 6 |
| APK | `artifacts/releases/oria-1.5-jury-r11/oria-1.5-jury-r11.apk`, bundle local ignoré par Git |
| Taille | 211 380 791 octets |
| SHA-256 | `bc85469264337b8985751974eb100c1b52ab7093a9c44f1aaf565981e529a323` |
| Signature | v2, certificat SHA-256 `15876fd1dec1cbb7a4da7e4d1d5a721a560766ed390be73bef32b3cb288ac411` |
| Modèle ONNX | `abab2174c8000e219d4d40550beb4506fd1156054b6282e8e8ee34154acd4742` |

L’APK n’est ni suivi par Git ni publié dans une GitHub Release : aucun remote n’est configuré. Il existe uniquement dans le bundle local et le dossier Gradle. Le JSON suivi dans `artifacts/r11-release-manifest.json` décrit l’artefact sans prétendre le contenir.

## Suites exécutées

| Suite | Résultat |
|---|---:|
| JVM Android, navigation et scénario jury inclus | **174/174** |
| Build debug, release, APK instrumenté et lint | succès |
| Fixtures Swift RGB ↔ Kotlin | **18/18**, identiques |
| Fixtures Swift résolution ↔ Kotlin | **10/10**, identiques |
| Oria Lab Policy Python | **6/6** |
| Transfert USB | **11/11** |
| Corpus d’intégrité | **3/3** |
| Qualification R02 | **4/4** |
| Oria Lab Desktop Python 3.11 + ONNX Runtime 1.22 | **68/68** |
| Interaction UI Node | succès |
| Instrument HTC : XNNPACK | **1/1**, six fixtures, p95 152,20 ms |
| Instrument HTC : H.264 | **1/1**, 100 images décodées |
| Instrument HTC : replay combiné 60 s | **1/1**, 224/224 décisions fraîches, 3,723 Hz, âge p95 306 ms |

Le replay instrumenté emploie la vidéo HTC fournie, pas le flux Eagle live ; son sink vocal est factice. Le test CPU strict historiquement en échec et l’endurance opt-in ne sont pas assimilés à cette recette stable. Les instruments du package produit complet n’ont pas été installés, car le remplacement contrôlé de l’ancien starter n’avait pas encore été effectué.

## Trois scénarios de cinq minutes

`OriaJuryScenarioTest` exécute trois instances neuves de cinq minutes virtuelles. Chaque passage valide multi-objets, priorité, escalade audio, description, destination, deux manœuvres, interruption par danger, reprise sur donnée fraîche, recalcul, arrivée et refus d’une sortie de l’ancienne génération. Résultat : **3/3**.

Cette preuve contrôle les moteurs déterministes. Elle ne couvre ni caméra/lunettes, GPS réel, réseau OSRM, son entendu, microphone, chauffe ni manipulation humaine. Les trois répétitions physiques restent obligatoires.

## Préparation matérielle relevée

- HTC U24 pro `CN4B53M00860`, Android 14, build `1.18.2401.4`, correctif sécurité 5 janvier 2025.
- Batterie : 100 %, 36,0 °C, alimentation secteur ; stockage `/data` : 197 Gio libres.
- Wi-Fi validé et connecté au moment du contrôle.
- Route audio Android détecte `VIVE Eagle_FFF3`; l’audibilité reste une vérification humaine.
- APK autorisé sauvegardé dans le bundle local : version 1.0, SHA-256 `f49def…eb43`, certificat `60372c…beb7`.
- Vidéo de secours : échantillon HTC SDK, SHA-256 `e56dcb…cda6`, jamais présenté comme capture live.

Firmware exact des lunettes, permissions du candidat, projection et écoute ne peuvent pas être validés sur le candidat non installé. Ils restent dans la checklist opérateur.

## Règle de gel et porte R11

Après création du tag R11, aucun changement risqué n’est autorisé. Toute modification impose version/code supérieurs, nouvel APK et recette complète. La [checklist opérateur](R11_OPERATOR_CHECKLIST.md) décrit les resets et les plans B.

- APK identifié : **OUI**.
- Plans B explicites : **OUI**.
- Trois scénarios logiciels consécutifs : **OUI, 3/3**.
- Trois démonstrations physiques consécutives : **NON EXÉCUTÉES**, remplacement du starter et recette en attente.

Le gel logiciel est prêt. La suite consiste à sauvegarder l’ancienne installation, la remplacer par cet APK qui conserve le même package autorisé, puis à exécuter la recette physique complète. Aucune clé HTC supplémentaire n’est attendue.
