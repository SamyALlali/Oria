# R01 — machine d’état et invalidation de session

Validation du 27 septembre 2026 sur la copie `SILMO_Hackathon_2026/Oria`.

## Implémentation

- Phases explicites : `READY`, `STARTING`, `ACTIVE`, `LIMITED`, `INTERRUPTED`, `DISCONNECTED`, `FAILED`.
- Dépendances séparées : caméra, décodeur, détecteur, profondeur, audio et navigation.
- `OriaController` reste l’unique propriétaire de la génération. `OriaRuntimeState` ne crée jamais d’identifiant et refuse les événements d’une ancienne génération ou à horloge inversée.
- Le flux passe à `ACTIVE` seulement après une décision issue d’une image fraîche acceptée.
- Une absence d’image fraîche vide l’interface, force l’expiration dans le moteur et passe à `LIMITED`. Un flux figé, une erreur vidéo ou une déconnexion arrête la session et invalide sa génération.
- La profondeur est explicitement `UNAVAILABLE` et optionnelle ; le mode RGB peut rester `ACTIVE` sans fabriquer de distance.
- Les frames et observations exposent leur génération. Les résultats d’inférence internes, pistes, candidats, décisions, alertes et transactions vocales portent génération et horodatage monotone.
- `OriaRuntimeEvidence<T>` fournit le contrat commun aux futurs adaptateurs de profondeur et de navigation.

## Tests logiciels exécutés

Commande principale :

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Résultat : **92 tests JVM réussis sur 92**, aucun échec et aucun test ignoré. Build debug, APK instrumenté et lint réussis ; lint contient 100 avertissements historiques et zéro erreur.

Les huit scénarios dédiés couvrent :

- arrêt puis redémarrage ;
- déconnexion puis reconnexion ;
- verrouillage sans autorisation du mode poche ;
- perte Bluetooth ;
- preuve périmée, future ou désordonnée ;
- résultat tardif d’une ancienne génération ;
- ancien callback vocal ;
- retour arrière de l’horloge ;
- fonctionnement RGB actif avec profondeur optionnelle absente.

Le test du moteur vérifie aussi que décision, piste et candidat transportent session, génération, frame et observation. La politique compilée séparément passe ses **6 tests Python** et ses **41 tests JUnit Kotlin**.

APK debug de cette passe : `cdcef2df53927e03326c2d864bca0aef7ab86627496be39c8f56083ee0898a0a`.

## Validation matérielle ouverte

Le HTC `CN4B53M00860` est visible en ADB, mais l’application installée est la version `1.0` code 1 signée par le certificat SHA-256 `60372c0d29a3e6a0ea7af9af3bf16cbaff79fd95f75912c1692a9e12a9dfbeb7`. L’APK local est signé par un autre certificat debug, SHA-256 `15876fd1dec1cbb7a4da7e4d1d5a721a560766ed390be73bef32b3cb288ac411`.

Une mise à jour directe est donc impossible. Aucune désinstallation, suppression de données ou substitution de l’ancien starter n’avait été tentée lors de ce rapport. La consigne organisateur désormais confirmée est de conserver le même package autorisé et de remplacer l’ancienne installation par notre starter modifié après sauvegarde ; aucune clé HTC supplémentaire n’est requise. Il reste ensuite à valider :

1. flux HTC réel écran verrouillé en mode poche ;
2. présence et action de la notification Arrêter ;
3. arrêt effectif de la caméra, du décodeur et de l’audio ;
4. absence de reprise d’une ancienne génération après déverrouillage/reconnexion ;
5. perte Bluetooth physique et reprise manuelle sûre.

R01 est donc **validé logiciellement** et **ouvert pour la porte matérielle**, sans prétendre que celle-ci a été exécutée.
