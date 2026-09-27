# R07 — Commandes vocales et bouton des lunettes

## Verdict du 27 septembre 2026

L’interaction mains libres est **implémentée, compilée et validée sur JVM**. L’APK courant ne peut toutefois pas être installé sur le HTC sans remplacer l’application autorisée : Android refuse la mise à jour car la signature locale diffère. La porte physique « commandes critiques sans écran » reste donc ouverte et n’est pas présentée comme acquise.

## Contrat livré

- Transcription SDK, parsing pur et exécution sont trois responsabilités séparées.
- Un appui sur le bouton IA attend 550 ms, puis répète l’instruction de navigation active ou la dernière alerte ; sans information active, il produit une description courte des objets reconnus devant.
- Deux appuis dans cette fenêtre lancent la transcription sur les lunettes. Les appuis supplémentaires pendant l’écoute sont ignorés.
- Intentions : démarrer/arrêter la perception, répéter, décrire devant, guider vers une destination, pause/reprise/arrêt navigation, couper/remettre les alertes, confirmer/annuler une destination et choisir le premier/deuxième résultat.
- Les accents, apostrophes, traits d’union et espaces sont normalisés avant le parsing.
- « arrête », « stop », « pause », « reprends » ou « continue » sans cible n’exécutent rien ; Oria demande de préciser perception ou navigation.
- Une destination dictée lance seulement la recherche. Un résultat unique doit encore être confirmé vocalement ; plusieurs résultats exigent un choix puis une confirmation.
- Chaque transcription porte un UUID local. Un terminal ancien, dupliqué ou appartenant à une autre demande est ignoré.
- La caméra est arrêtée avant la prise du microphone SDK. La perception n’est restaurée qu’après le terminal de la demande encore active et seulement si elle fonctionnait avant l’écoute.
- Une erreur, un conflit de ressource, un silence de douze secondes, une annulation ou une déconnexion reviennent à un état borné. Aucun texte partiel n’est exécuté.
- Les réponses passent par l’ordonnanceur R05. La commande de silence attend la fin de sa confirmation avant de couper la sortie.

Le bouton conserve donc une action immédiate accessible sans écran, tandis que le double-appui ouvre explicitement le mode commande. Ce choix évite qu’un simple appui déclenche accidentellement une action de navigation ou d’arrêt.

## Vérifications logicielles

```text
ANDROID_HOME=/Users/rayan/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Résultat : **BUILD SUCCESSFUL**, **154 tests JVM dans 21 suites**, zéro échec, zéro erreur et zéro test ignoré. L’APK debug est construit et le lint est réussi.

Les nouveaux tests couvrent : intentions critiques, variantes françaises, accents, destination manquante, commande ambiguë, silence, texte inconnu, simple appui différé, double/appuis répétés, demande concurrente, callback tardif et callback dupliqué. Les tests R06 conservés couvrent les erreurs de géocodage et de fournisseur d’itinéraire.

APK produit : SHA-256 `6f0f6076e76ae47296671cdface898ce7f86b496f5fefd1facacbe7dc20c407d`.

## Validation matérielle restante

Le HTC `CN4B53M00860` est détecté par ADB. L’installation du nouvel APK retourne :

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.htc.vive.eagle.hackathon.starter signatures do not match newer version
```

L’ancien starter n’avait pas été remplacé lors de ce rapport. La consigne organisateur confirmée est de le sauvegarder puis d’installer notre version avec le même package autorisé, sans rechercher de clé HTC. Il restera alors à valider physiquement : simple appui, double-appui, transcription lunettes, conflit caméra/microphone, réponse audio, restauration caméra, huit intentions critiques, destination ambiguë et trois répétitions rapides.
