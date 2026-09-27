# R08 — Dashboard produit et développeur

## Verdict du 27 septembre 2026

Le dashboard est **implémenté, compilé et validé logiciellement**. Oria reste l’accueil ; le Diagnostic HTC reste un écran secondaire. La validation TalkBack physique et la comparaison FPS visible/caché restent à exécuter après le remplacement sauvegardé de l’ancien starter par notre version portant le même package autorisé.

## Vue produit

Le premier écran présente, en texte et dans un résumé TalkBack unique :

- connexion des lunettes et état de la perception ;
- message opérationnel courant et commandes démarrer/arrêter existantes ;
- destination, prochaine instruction et dernière alerte ;
- navigation réelle ou simulée, confirmation, pause, reprise, arrêt et arrivée.

Le résumé essentiel n’utilise pas la couleur comme seule information. Les changements importants portent une région sémantique `Polite` pour ne pas interrompre brutalement la lecture d’écran.

## Vue développeur

Le snapshot immuable contient :

- flux reçu/analysé/abandonné/périmé, cadence utile, âge d’observation et latences p50/p95 ;
- boîtes normalisées, identifiants de pistes, classes, confiances, zones et confirmation ;
- candidat retenu, niveau, source, raison de politique, stabilisation et suppression ;
- estimation de proximité relative et estimation de confiance de profondeur, jamais présentées en mètres ;
- navigation réelle/simulée, GPS, recalcul, version de route, préemptions par danger et reprises fraîches ;
- modèle, fournisseur, suivi, audio, abandons audio, batterie, température du téléphone et dernière erreur ;
- état d’enregistrement/relecture Oria Lab.

Toutes les valeurs numériques liées à la profondeur sont explicitement qualifiées d’**estimations**. La température affichée est celle du téléphone Android, pas celle des lunettes.

## Isolation et coût

- Le contrôleur publie au maximum un snapshot toutes les 250 ms, soit 4 Hz.
- Les listes d’objets et les boîtes sont copiées avant publication ; Compose ne reçoit aucune structure mutable du pipeline.
- En vue produit, la construction du dashboard développeur est arrêtée. À l’ouverture, un snapshot forcé est produit puis la limite de 4 Hz s’applique.
- La lecture de batterie/température est mise en cache cinq secondes.
- La vue affiche son nombre de publications et son coût moyen de construction.

Mesure JVM dédiée puis reconfirmée dans les suites complètes : **20 000 projections**, moyenne observée de **0,781 à 2,404 µs par snapshot** sur ce Mac selon le passage. Cette mesure prouve que la projection pure est très faible ; elle ne remplace pas une comparaison physique des FPS, CPU et chauffe sur le HTC.

## Previews, tests et accessibilité

Six previews Compose et des tests purs couvrent : actif, limité, déconnecté, navigation/recalcul, danger prioritaire et replay. Les tests vérifient aussi la copie immuable, la limite de 4 Hz, le rejet d’une horloge inversée, les percentiles et le résumé textuel indépendant des couleurs.

Commande finale :

```text
ANDROID_HOME=/Users/rayan/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Résultat : **BUILD SUCCESSFUL**, **161 tests JVM dans 22 suites**, zéro échec, zéro erreur, zéro test ignoré, APK construit et lint réussi.

APK : SHA-256 `e3483200ae65bcfc17d8253a5c4757962c4a5ec1f5e78d522fc18127402e82ee`.

## Porte physique restante

Le HTC `CN4B53M00860` est détecté par ADB, mais l’installation retourne encore :

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.htc.vive.eagle.hackathon.starter signatures do not match newer version
```

L’ancien starter n’avait pas été remplacé lors de ce rapport. Une fois notre version installée avec le même package autorisé, il restera à mesurer le dashboard développeur visible/caché sur le même scénario, vérifier TalkBack de bout en bout et faire confirmer par une personne extérieure que perception, décision et raison sont comprises en moins de dix secondes.
