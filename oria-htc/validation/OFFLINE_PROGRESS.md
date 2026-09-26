# Développement hors téléphone — 26 septembre 2026

Cette livraison est un **candidat compilé sur Mac, non installé et non validé sur HTC**. Les preuves vidéo/voix des APK précédents ne sont pas réattribuées à ce code. Le modèle, les seuils de confiance, la garde de fraîcheur de 500 ms et les gains PCM 70/30 restent inchangés.

## Périmètre implémenté

- Captures Android : nom personnalisé dans un fichier `session-label.json`, corbeille réversible, restauration, espace total/corbeille, export reconstruit avec le nom actuel. Images, manifestes, horloges, UUID et événements originaux restent inchangés. Le verrou d’export couvre la copie SAF complète. La corbeille ne libère pas d’espace ; aucune purge automatique.
- Suivi : mode `STABLE_RGB_V2` facultatif, association par continuité géométrique et mouvement borné, confirmation remise à zéro en cas d’ambiguïté. `LEGACY_IOU` reste le défaut sur téléphone. Aucun test sur une scène unique ne suffit à établir une meilleure précision générale. Rapport : [TRACKING_V2.md](TRACKING_V2.md).
- Mode poche Android : option expérimentale, désactivée par défaut, démarrage explicite depuis Oria visible, notification persistante avec Arrêter et maintien CPU borné à 15 minutes. Ni démarrage automatique ni reprise après mort du processus. La destruction de l’Activity ferme aussi la session ; ce prototype ne revendique pas une survie à la recréation de l’Activity. Oria Lab s’arrête et finalise toujours sa capture au passage en arrière-plan.
- Oria Lab Mac : bibliothèque de sessions, noms communs avec Android, corbeille/restauration, export ZIP, comparaison A/B et export de rapport, aperçu vocal stéréo local déclenché explicitement.

## Mode poche : raisons techniques et limites

Le service déclare `connectedDevice|mediaPlayback`, car la vidéo vient d’un périphérique HTC et le son est livré au Bluetooth. Il ne démarre pas la caméra ou le microphone du téléphone. La notification doit être autorisée et son canal visible ; un refus empêche le mode poche. Le service commence lorsque l’Activity est visible, s’arrête si le flux disparaît, si le Bluetooth est coupé, depuis la notification ou au terme de 15 minutes. Les notifications d’une session précédente portent un token distinct et ne peuvent pas arrêter une nouvelle session.

Références : [types de services Android](https://developer.android.com/develop/background-work/services/fgs/service-types), [démarrage d’un service de premier plan](https://developer.android.com/develop/background-work/services/fgs/launch), [verrou CPU borné](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set). Ces API Android ne prouvent pas que le SDK HTC maintient son flux lorsque l’écran est verrouillé ; ce point reste explicitement à tester.

## Vérifications sur Mac

Le script `validation/build_offline.py` compile l’APK, exécute les tests JVM, compile les tests instrumentés, contrôle package/signature et produit `artifacts/offline_candidate_manifest.json`. Il ne contient aucune installation ADB. L’APK de cette préparation est `artifacts/oria-silmo-candidate.apk`, distinct de l’APK historique déjà installé.

Résultats du candidat `1.1-preview` / versionCode 2 :

- **71 tests JVM Android réussis**, zéro échec, erreur ou test ignoré ; APK de tests instrumentés compilé, non exécuté.
- **Lint Android terminé sans erreur** ; 100 avertissements et 2 indications conservés dans le rapport, notamment ressources/icônes du starter, versions de dépendances et conseils de stockage. Aucun verdict matériel déduit de lint.
- **25 tests Python Mac**, **6 tests de rejeu Kotlin** et les régressions JavaScript réussis : trois zones stéréo, lecture asynchrone périmée, annulation/confirmation de corbeille et protection contre une ancienne confirmation.
- Intégrité du modèle, des assets, du checkpoint et des fixtures vérifiée sans réexport ni nouvelle inférence matérielle.
- Revue croisée Android → backend Mac, Mac → suivi V2 et Android → service poche. Les corrections concernent notamment les leases d’export, les réponses périmées, les médias blob et la publication atomique du MP4 après conversion.

APK : `artifacts/oria-silmo-candidate.apk`, SHA-256 `f4f5c8bdf18642aefe3891dce67d1be444b3be3071765faa65c4f53ba030132f`. Package HTC et clé de signature inchangés. Le manifeste candidat liste aussi les empreintes des sources Android. L’APK `oria-silmo-debug.apk` et `delivery_manifest.json` restent ceux de la livraison précédente.

La comparaison sur la capture réelle de 60 s utilise **164 images**, les mêmes détections et horloges, deux moteurs indépendants, et un délai de confirmation simulé de 2 s : **26 images avec écart métier** (y compris mémoire de pistes), **5 avec annonce différente**, **12 annonces par variante**. Les **125 images avec identifiants techniques différents** sont diagnostiquées séparément ; elles ne sont pas assimilées automatiquement à une alerte incorrecte. Le suivi V2 produit 90 identités contre 81 pour la référence : le bénéfice est local, pas général. La référence conserve les **164/164 évaluations et 12/12 annonces simulées** de la version figée avant les changements, hors ajout du diagnostic d’association.

Dans le navigateur intégré : chargement de la scène, comparaison complète, saut au premier écart, inspection d’une annonce à droite et lecture WAV locale sans erreur vérifiés. La phrase affichée est « Piéton avant-droite », canal `RIGHT`, durée du WAV 1,238 s. Cela ne constitue pas une confirmation humaine de spatialisation. Import et renommage d’une capture synthétique vérifiés dans l’UI. Export ZIP, CRC, conservation des six fichiers source, corbeille et restauration vérifiés via l’API locale. Une boîte native `confirm()` a bloqué l’automatisation du navigateur ; elle a été remplacée par une confirmation HTML intégrée, couverte par les tests JavaScript. La fin de recette visuelle de ce nouveau contrôle reste distincte des tests HTTP réussis.

Journaux et rapports locaux : `validation/offline-lab-20260926/`, `validation/offline-build-20260926T214704837054Z/`. Les rapports contenant des données de scène restent ignorés par Git.

Les tests de stockage utilisent des dossiers temporaires ; aucune capture personnelle n’est renommée, retirée ou supprimée pendant la recette. Les comparaisons sur la scène réelle lisent ses données et écrivent seulement des résultats dérivés. Les tests Mac vérifient l’import, la comparaison, les accès locaux et les transformations PCM ; ils ne valident pas le Bluetooth HTC.

Deux premiers essais de compilation ont été conservés : erreur de nullabilité dans le service, puis trois attentes incorrectes dans les nouvelles fixtures de suivi (pacing global encore actif et déplacement initial trop grand pour construire un historique). Ces causes ont été corrigées sans changer les seuils du moteur. Le troisième build intégré passe ; le manifeste final identifie le candidat livré.

## Recette à faire quand le HTC sera branché

1. Identifier le numéro ADB et sauvegarder les captures présentes. Installer **le candidat** en mise à jour avec la même signature ; aucune désinstallation automatique.
2. Vérifier les deux onglets, le modèle et la voix locale. Lunettes connectées : image droite/gauche correcte, test vocal gauche/centre/droite et annonces 70/30 pendant vidéo.
3. Sur une capture d’essai : renommer, exporter et importer sur Mac, retirer puis restaurer. Vérifier les images/événements et le nom ; ne pas utiliser la seule copie d’une scène importante.
4. Mode habituel : Home doit arrêter la perception ; Oria Lab doit finaliser et ne pas redémarrer seul au retour.
5. Mode poche : activer à l’arrêt, autoriser les notifications, démarrer et attendre l’état actif. Verrouiller l’écran, vérifier continuité et écoute, arrêter depuis la notification. Vérifier Bluetooth coupé, retour manuel, destruction de l’Activity et expiration de 15 minutes. Un refus de notification ou un démarrage devenu obsolète ne doit pas lancer le flux.
6. Comparer les suivis sur les mêmes scènes, notamment croisement/occlusion/rotation et plusieurs objets distincts. Vérifier que les répétitions diminuent sans masquer un nouvel objet.
7. Mesurer latence, chauffe et consommation sur le téléphone, avec et sans capture. Faire séparément un essai sans accès Internet et une mesure d’arrêt acoustique.

L’ergonomie complète de première connexion et l’entraînement sur un corpus HTC annoté restent des travaux ultérieurs ; aucun gain de rappel YOLO ni équivalence acoustique Mac/HTC n’est annoncé.
