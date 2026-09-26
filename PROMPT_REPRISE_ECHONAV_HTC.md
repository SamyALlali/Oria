# Reprise EchoNav × HTC VIVE Eagle — application dédiée, nouveau téléphone

Tu reprends l’implémentation existante du prototype EchoNav pour le hackathon SILMO. Travaille dans le projet local, effectue les modifications, compile, installe sur le téléphone ciblé lorsque possible et poursuis jusqu’à une démonstration vérifiable. Ne te limite pas à un plan ou à un nouveau prompt.

## Décision utilisateur actuelle

Nous reprenons uniquement mon projet EchoNav. Il ne faut plus de séparation Samy/Rayan, d’onglet « EchoNav · Samy » ou de structure destinée à accueillir le projet de mon binôme.

EchoNav doit être l’interface principale, affichée dès le lancement. Conserve les fonctions utiles du starter derrière une entrée secondaire « Diagnostic HTC », pour tester connexion, caméra et audio. Elles ne doivent plus occuper la navigation principale de la démonstration.

Il s’agit toujours de modifier le starter HTC existant. Conserve son SDK, ses dépendances locales, sa configuration debug et impérativement :

`applicationId = com.htc.vive.eagle.hackathon.starter`

Garde aussi le namespace et les packages existants. Changer l’interface ou retirer le nom Samy ne nécessite pas de changer cette identité.

Un **nouveau téléphone HTC prêté** est maintenant disponible. Identifie l’appareil réellement connecté ; ne reprends pas automatiquement le numéro ADB ou les performances de l’ancien.

Ces décisions remplacent les anciennes exigences de navigation par cinq onglets et de séparation entre les membres du binôme. Les autres contraintes du projet restent applicables.

## Projet et ressources

Répertoire principal :
`/Users/sam/Documents/ChatGPT/Hackathon SILMO/echonav-htc/`

Projet Android à modifier :
`/Users/sam/Documents/ChatGPT/Hackathon SILMO/echonav-htc/android-project/`

Références originales à préserver :

- Starter : `/Users/sam/Downloads/eagle-hackathon-starter-usb/`
- EchoNav Swift : `/Users/sam/Documents/ChatGPT/echonav/EchoNav_main`
- Checkpoint : `/Users/sam/Downloads/echonav_current_best.pt`

Réutilise la copie de travail et les composants existants. Inspecte les modifications présentes et préserve le travail ajouté depuis la précédente session. Ne recopie pas le starter par-dessus le projet et ne reconstruis pas l’application depuis zéro.

## Lecture initiale

Dans `echonav-htc/`, lire d’abord :

1. `README.md`
2. `validation/RECETTE.md`
3. `artifacts/delivery_manifest.json`
4. `validation/HTC_IMPLEMENTATION.md`
5. `validation/ML_IMPLEMENTATION.md`
6. `validation/PORTAGE_IMPLEMENTATION.md`
7. `DEMONSTRATION.md`

Dans le dossier parent, consulter ensuite le cahier des charges, le registre d’architecture, la matrice de portage et les métadonnées du modèle :

- `CAHIER_DES_CHARGES_ECHONAV_SILMO.md`
- `DECISIONS_ARCHITECTURE_ECHONAV_SILMO.md`
- `PORTAGE_ECHONAV_SWIFT_ANDROID.md`
- `ECHONAV_MODEL_METADATA.json`

Les documents sont des références et des preuves datées. Distingue faits vérifiés, anciennes décisions, choix provisoires et essais manquants. Les sections historiques peuvent encore évoquer « Samy », cinq onglets ou un blocage d’installation depuis résolu : la présente décision utilisateur prévaut pour l’interface à réaliser. Ne réécris pas les résultats historiques comme s’ils provenaient du nouveau téléphone.

## État réel à reprendre

La chaîne Kotlin est déjà intégrée : réception H.264 HTC → décodage sans Surface → pixels → ONNX local → politique RGB EchoNav → demandes vocales françaises via HTC.

Composants importants :

- `MainActivity.kt` et `ui/tab/AppDestination.kt` : navigation actuelle.
- `echonav/ui/EchoNavScreen.kt` : écran EchoNav existant à réutiliser.
- `echonav/EchoNavController.kt` : sessions, calcul, fraîcheur, voix et traces.
- `ViveGlassKitManager.kt` et `echonav/video/` : SDK et décodage.
- `echonav/ml/` : prétraitement, runtime et projection des boîtes.
- `echonav/core/` : moteur Kotlin indépendant d’Android.
- `ml/`, `fixtures/`, `validation-project/` : export reproductible, parité Swift et harness matériel séparé.

Dernières preuves enregistrées, à contrôler dans les artefacts :

- Build réussi et **39 tests JVM réussis**.
- **18 fixtures Swift/Kotlin concordantes**, sur un périmètre de fonctions pures, pas toute l’application iOS.
- ONNX FP32 réel, entrée RGB NCHW `[1,3,416,416]`, sortie end-to-end `[1,300,6]` : `xyxy, score, classe`.
- Six classes : `person`, `vehicle`, `bike_scooter`, `pole`, `traffic_light`, `traffic_sign`.
- XNNPACK avec CPU fallback : parité réussie sur six fixtures. Le test CPU seul conserve un échec strict sur une boîte de très faible score ; ne pas masquer cet échec ni élargir les tolérances.
- Ancien HTC U24 pro Android 14 : endurance **du modèle et de son prétraitement uniquement**, 2 400 résultats frais en dix minutes, environ 4 Hz et p95 195 ms. Cet essai précédait la correction finale de projection des boîtes ; le modèle et les tenseurs sont inchangés.
- Replay combiné local de 60 secondes avec projection corrigée : **224 décisions fraîches, 3,72 Hz, p95 313 ms**, sortie vocale factice. Aucune preuve d’audibilité dans cet essai.
- Le dernier APK a été réellement installé sur l’ancien téléphone après accord utilisateur. Les cinq onglets, puis « Lunettes connectées » et « Perception active · caméra seule », ont été observés. L’USB a disparu avant extraction des mesures live et confirmation audio.

**La démonstration physique complète n’est donc pas encore validée.** Aucun résultat de l’ancien téléphone ne vaut automatiquement pour le nouveau.

## Installation : règle HTC correctement établie

Le README original exige le package inchangé pour la whitelist, ligne 14. Ses lignes 64–68 prévoient explicitement de désinstaller uniquement le starter puis de réinstaller le build si les signatures diffèrent. Le dépannage et le script d’installation fourni le confirment. N’invente pas une obligation d’obtenir une signature spéciale HTC pour cette procédure.

Sur le nouveau téléphone : identifier modèle, Android, ABI, numéro ADB, installation présente et compatibilité de signature. Cibler cet appareil explicitement ; ne pas exécuter un script d’installation sur tous les appareils connectés.

Privilégier une installation ou mise à jour compatible avec la même clé. Si une désinstallation devient nécessaire, préparer l’APK vérifié et la sauvegarde de l’APK présent, puis demander seulement l’autorisation réellement manquante pour cet appareil et la perte de ses données locales. L’accord déjà donné pour remplacer le starter de l’ancien téléphone n’établit pas à lui seul l’état ou le contenu du nouveau. Ne toucher ni à VIVE Connect ni à son appairage.

## Organisation agentique

Utilise trois sous-agents pour des travaux indépendants, avec propriétés de fichiers explicites :

1. **Interface Android** : `MainActivity.kt`, navigation et `echonav/ui/`. Faire d’EchoNav l’accueil, enlever la séparation personnelle, conserver les diagnostics HTC accessibles et les commandes essentielles faciles à atteindre.
2. **HTC / sessions / voix** : manager et vidéo. Relire les transitions entre EchoNav et diagnostics, les permissions, les callbacks tardifs et la concurrence des ressources. Corriger seulement les problèmes identifiés.
3. **ML / preuves** : runtime, manifestes, fixtures et rapports. Vérifier la reproductibilité et préparer l’analyse des mesures sur le nouveau téléphone. Garder le modèle existant sauf incompatibilité ou échec mesuré.

Tu es l’orchestrateur : intégration, cœur métier, arbitrages, manipulation du téléphone, compilation finale et recette. Un seul responsable pilote ADB ; ne pas lancer plusieurs benchmarks ou manipulations UI concurrents. Fais relire les modifications critiques par un autre agent. Les débats restent courts et aboutissent à une décision ou à une expérience précise. Les agents servent au développement ; le pipeline embarqué reste local et déterministe.

## Travail prioritaire

1. Inspecter sources, artefacts et outillage ; identifier le nouveau téléphone sans confondre les appareils.
2. Modifier l’accueil en réutilisant EchoNavScreen. Nom visible « EchoNav », sans Samy/Rayan. Afficher connexion, caméra, modèle, voix, aperçu, démarrer/arrêter et dernière annonce. Garder Arrêter accessible même avec un aperçu portrait. Mettre les outils HTC dans le diagnostic secondaire.
3. Préserver les règles de propriété des ressources. Le démarrage initial sur EchoNav doit activer correctement `setEchoNavMode(true)` ; quitter ce mode doit arrêter sa session. Ne pas supprimer les protections en retirant l’ancien shell. Maintenir `setManualSpeechHandler` et la réservation commune des paroles manuelles et automatiques.
4. Compiler, exécuter les tests pertinents, identifier APK/package/certificat/modèle, puis installer sur le téléphone ciblé dans le périmètre autorisé. Ne pas s’arrêter après la compilation si l’installation et les essais sont possibles.
5. Prouver au plus tôt la vidéo réelle et une phrase française **entendue dans les lunettes pendant le streaming**. Demander une confirmation d’écoute courte à l’utilisateur ; un retour SDK ne suffit pas.
6. Vérifier orientation et gauche/droite sur une scène asymétrique avant annonces directionnelles, puis détecter des objets réels et vérifier les règles d’annonce.
7. Tester arrêt, retour depuis le diagnostic, arrière-plan, reconnexion, résultat périmé et callback vocal tardif. Effectuer ensuite dix minutes de la chaîne physique réalisable et publier les limites restantes.

## Invariants à conserver

- Vidéo indépendante de l’horloge du microphone ; pas de purge arbitraire des paquets H.264. Sélection de l’image récente après décodage, une inférence active et au plus une image récente en attente.
- Horloge monotone, génération et fraîcheur contrôlées aux frontières asynchrones ; garde également juste avant l’envoi vocal. Âge maximal initial 500 ms, sans le relever pour faire passer un test.
- Letterbox provisoire 416, RGB et arrondis déterministes ; inverse `raster_inverse_v2` fondé sur les dimensions réellement redimensionnées. Préserver les tests des frontières 0,39/0,61. Pas de NMS ou top huit ajoutés à la sortie end-to-end.
- Absence de profondeur et de pose explicite. Pas de distance en mètres inventée, de « voie libre » ou d’autorisation de traverser.
- Observation fraîche, piste, mémoire vocale, intention et confirmation restent distinctes. Une seule requête vocale en vol ; timeout/ambiguïté bloque les nouvelles demandes. Ne pas attribuer arbitrairement un callback anonyme à une nouvelle phrase ni effacer l’incertitude par un simple changement d’écran.
- Pas de vidéo enregistrée ou envoyée par défaut. Traces techniques minimales ; conserver les échecs et distinguer reçu/décodé/analysé/frais/rejeté.
- Préserver les contrats et fixtures Swift pour une éventuelle suite iOS, sans retarder Android.

## Livrables et manière de travailler

Avance sur les opérations réversibles déjà cadrées. Demande uniquement les informations ou autorisations réellement manquantes, en expliquant le blocage concret et en poursuivant les travaux indépendants.

Donne des mises à jour courtes : preuve obtenue, obstacle et prochaine vérification. Corrige d’abord les échecs observés ; ne lance pas de refonte ou de nouvel export sans justification.

Livre le code intégré, l’APK installé ou son blocage exact, les empreintes actualisées, les résultats sur le nouveau téléphone, les rapports de tests et une procédure de démonstration courte. Mets à jour README, recette, manifeste de livraison, cahier des charges et registre pour refléter la nouvelle interface et les preuves obtenues.

Distingue systématiquement **implémenté**, **compilé**, **testé sur fixtures/replay**, **observé sur le nouveau téléphone**, **confirmé à l’écoute**, **échoué** et **non exécuté**. Une absence de matériel n’arrête pas les travaux logiciels, mais interdit de déclarer une validation physique fictive.

Commence maintenant par l’état réel du workspace et du nouveau téléphone, la répartition des trois agents et la simplification de l’interface. Poursuis jusqu’à la recette du périmètre réalisable.
