# Oria 1.5 — interface et accessibilité

## Livraison

La refonte Android demandée le 27 septembre est implémentée et compilée en **1.5-accessibility / code 6**. Trois agents ont travaillé sur l’accueil Oria, Oria Lab et la revue indépendante ; l’intégrateur a réalisé thème, navigation, compilation et sauvegarde. La [revue du code](ACCESSIBILITY_REVIEW_20260927.md) ne relève pas de blocage.

- Palette vert/crème claire et sombre suivant Android, texte courant de 16 à 18 sp, agrandissement système conservé.
- Commande principale pleine largeur d’au moins 64 dp ; autres actions importantes d’au moins 56 dp, avec texte non tronqué par une limite de lignes.
- Accueil organisé autour de la connexion, de la préparation des annonces, de la dernière annonce demandée et du mode poche. Aperçu et réglages avancés repliables.
- Interrupteurs et confirmation des directions regroupés en contrôles uniques nommés, avec rôle et valeur ; titres repérables par les services d’accessibilité. Le point de capture des onglets est décoratif et son état décrit pour TalkBack.
- Oria Lab reprend le même style ; actions répétées de galerie nommées avec la capture concernée.

La vérification du repère reste explicite, avec un accompagnant si nécessaire pour l’image. Aucun rafraîchissement de détection ne déclenche une annonce automatique TalkBack. Modèle, politique d’alerte, mélange 70/30, format des captures et durée illimitée ne sont pas modifiés. La coupure possible du mode poche signalée par l’utilisateur reste à reproduire.

## Preuves et limites

| Vérification | Résultat |
| --- | --- |
| Compilation debug et APK d’instrumentation | Réussie ; instrumentation compilée, non exécutée |
| Tests JVM existants | 83 réussis, aucun échec/erreur/ignoré |
| Lint Android | 0 fatal, 0 erreur, 100 avertissements, 2 indications |
| Correspondance sources / APK | SHA-256 des 109 fichiers du manifeste et de l’APK revérifiés |
| Contrastes des paires actives de palette | 24 paires calculées ; minimum 6,33:1 clair et 6,75:1 sombre |
| Identité | Package HTC, certificat et modèle inchangés |
| Installation 1.5 | **Non exécutée : téléphone débranché avant installation** |
| Rendu Android clair/sombre, 200 %, paysage | **Non exécuté** |
| TalkBack et essai utilisateur déficient visuel | **Non exécutés** |

Ces contrôles ne constituent ni une certification d’accessibilité ni une preuve de rendu. Aucun émulateur n’était configuré sur le Mac ; environ 322 Mio libres ne permettaient pas d’en installer un. Aucun nouveau flux caméra, enregistrement ou test vocal n’a été lancé pour cette refonte.

APK local : `artifacts/oria-silmo-candidate.apk`, **159 376 221 octets**. SHA-256 : `b370c4e0eda2baae010bf9c2cd8f15b3620e0522ac38b3641894e4a37535d86b`. Voir le [manifeste](../artifacts/offline_candidate_manifest.json) et les preuves locales `validation/offline-build-20260927T090056975463Z/`.

## Téléphone et données

Le HTC **CN46V3M00284** a été identifié en USB, puis l’application inactive arrêtée pour sauvegarde. Son APK **1.4-night / code 5** et ses **525 fichiers privés** ont été archivés, lus et contrôlés. La restauration de cette archive n’a pas été testée. Le téléphone a ensuite été débranché ; aucune installation ni modification de réglage Android n’a été exécutée. Il conserve 1.4.

Sauvegarde locale privée, exclue de Git : `validation/accessibility-20260927T085959Z-CN46V3M00284/backup/`. SHA-256 de l’APK : `aaec205c5f0916d2789c082e707eecf0ab810fd51038959b74d9e7beaca8b8dc`. SHA-256 de l’archive privée : `387469278720d9841c29a63f5400a875b4cf85bdac5335ccd57396d5bef8f119`.

Les confirmations humaines de vidéo et de stéréo 70/30 du matin appartiennent à [1.4-night](LIVE_20260927_V14.md), pas à ce candidat.

## Recette restante au branchement du HTC

1. Identifier le téléphone et vérifier l’absence de capture/session active. Sauvegarder les éventuelles données créées depuis la sauvegarde précédente.
2. Contrôler package et signature, installer avec `adb install -r`, puis vérifier les fichiers privés préexistants. Aucune désinstallation, aucun effacement, aucun `connectedDebugAndroidTest`/UTP.
3. Contrôler accueil, onglets et dialogues en clair/sombre, à 100 % et 200 %, puis en paysage ; restaurer les réglages initiaux.
4. Vérifier réellement avec TalkBack noms, valeurs, ordre de focus, accès à l’arrêt, interrupteurs sans doublon et absence d’annonces continues des compteurs.
5. Avec l’utilisateur, faire un essai borné vidéo + voix puis vérifier l’arrêt. Compatibilité TalkBack/voix Oria et mode poche prolongé nécessitent des mesures séparées.
