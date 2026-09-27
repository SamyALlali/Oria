# R05 — Audio priorisé, niveaux et préemption

## Verdict du 27 septembre 2026

L’ordonnanceur audio unique est implémenté et les contrôles JVM/build sont réussis. Le mélange vocal gauche/centre/droite conserve les gains 70/30 déjà confirmés humainement pendant la recette réelle Oria 1.4 ; aucun ajustement de confort non démontré n’a été appliqué.

Les nouveaux bips de danger sont **expérimentaux et désactivés par défaut**. Leur génération, leur intermittence et leur balance stéréo sont testées numériquement, mais leur confort et leur audibilité n’ont pas encore été confirmés dans les lunettes. Cette limite interdit de présenter ces nouveaux motifs comme validés physiquement.

## Contrat livré

- Une seule parole peut être en vol pour danger, navigation ou réponse à une commande.
- Un danger fort ou critique préempte une navigation, une description ou un danger moins urgent.
- Un danger faible ne coupe pas une parole en cours et n’est pas rejoué tardivement.
- Une nouvelle manœuvre remplace l’ancienne en attente ; tout élément périmé est abandonné.
- Reset, mode silencieux et perte de route annulent la parole active et purgent la file.
- Un callback d’une ancienne génération ne peut ni terminer ni faire avancer la nouvelle file.
- Le mode silencieux affiche explicitement « perception active » et ne modifie pas le pipeline visuel.
- Les motifs LOW, MEDIUM, HIGH et CRITICAL utilisent la même route Bluetooth vérifiée que la voix. CRITICAL est continu ; les autres niveaux sont intermittents.
- Les UUID, génération, priorité, périphérique déclaré, décisions, début Android, fin, annulation et échecs sont inscrits dans la trace JSONL. `decisionToAndroidStartMs` mesure la décision jusqu’à la dernière garde précédant l’écriture PCM ; il ne mesure pas le son réellement entendu après les tampons Android/Bluetooth.

## Vérifications automatiques

Commande exécutée avec le SDK Android local :

```text
ANDROID_HOME=/Users/rayan/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Résultat : **BUILD SUCCESSFUL**, 130 tests JVM, zéro échec et zéro erreur ; APK debug construit et lint debug réussi. Les scénarios R05 couvrent notamment préemption, escalade, expiration, remplacement de manœuvre, UUID dupliqué, ancienne génération, mode silencieux, perte/récupération de route et balance des bips.

## Preuve humaine réutilisée et recette restante

La preuve humaine existante est consignée dans [LIVE_20260927_V14.md](LIVE_20260927_V14.md) : trois lectures LEFT, CENTER et RIGHT ont été terminées pendant une session vidéo réelle, puis l’utilisateur a confirmé les trois positions avec le mélange 70/30. Cette preuve porte sur la voix, dont les gains restent inchangés.

À faire sur le matériel avant de valider entièrement R05 : activer volontairement les bips expérimentaux, confirmer chaque direction et chaque niveau à volume confortable, provoquer une préemption navigation → danger, retirer/reconnecter la route Bluetooth et vérifier qu’aucune ancienne parole n’est entendue après reset. Archiver la trace et une confirmation humaine séparée ; ne pas déduire l’audibilité d’un callback Android seul.

Le HTC `CN4B53M00860` était visible par ADB lors de cette livraison, mais la mise à jour conservant les données a été refusée par Android avec `INSTALL_FAILED_UPDATE_INCOMPATIBLE` : l’ancien starter et l’APK debug local n’ont pas la même signature. Aucune désinstallation n’avait été effectuée lors de ce rapport. La consigne organisateur confirmée est de sauvegarder puis remplacer cet ancien starter avec notre version qui conserve le même package autorisé ; aucune clé HTC supplémentaire n’est requise. La recette matérielle R05 reste à exécuter après ce remplacement.
