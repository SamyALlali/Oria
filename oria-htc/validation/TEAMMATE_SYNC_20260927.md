# Synchronisation du travail de l'équipe — 27 septembre 2026

## Source examinée

Le dépôt de Samy présent dans `git_dir/` a été comparé à la branche principale locale jusqu'au commit `f3454f6` (`Add category-independent depth obstacle experiment to Oria Lab`). Les changements ont été repris sélectivement afin de conserver les fonctions plus récentes déjà présentes dans Oria : moteur EchoNav complet, navigation guidée, ordonnanceur audio, commandes vocales, dashboard et rejeu intégral.

## Éléments repris

- Le thème Compose accessible clair/sombre, avec typographie agrandie et contrastes documentés.
- La navigation Oria / Oria Lab avec cibles tactiles plus grandes et état d'enregistrement annoncé sans dépendre uniquement de la couleur.
- La simplification accessible d'Oria Lab : titres sémantiques, états dynamiques annoncés, boutons plus grands et libellés explicites.
- L'application du thème accessible à l'écran Oria enrichi existant, sans retirer le dashboard, la navigation ni les réglages de profondeur.
- Le laboratoire Mac d'analyse d'obstacles génériques : segmentation de surfaces, relief monoculaire relatif, analyse indépendante des catégories, garde-fous de qualité d'image, politique temporelle et export des preuves.
- Les scripts d'export, manifestes, licences, fixtures, rapports de validation et tests associés.

## Portée exacte de la détection de murs et d'obstacles

La nouvelle analyse est volontairement **expérimentale et hors ligne dans Oria Lab sur Mac**. Elle peut mettre en évidence une grande surface ou une occupation relative devant/gauche/droite même lorsque YOLO ne reconnaît aucune classe. Elle ne fournit ni distance en mètres, ni trajectoire sûre, ni probabilité de collision et n'émet aucune alerte live sur les lunettes.

Cette limitation est conservée : activer automatiquement une alerte live à partir d'un relief monoculaire non qualifié créerait un risque de faux sentiment de sécurité. Le runtime Android garde son adaptateur de profondeur optionnel et désactivé par défaut ; le corpus Oria Lab sert maintenant à mesurer les faux positifs et faux négatifs avant une promotion éventuelle.

## Éléments non repris

- Les modifications non validées du décodeur H.264 dans l'ancien starter `eagle-hackathon-starter-usb` : Oria possède déjà un pipeline borné, versionné par session, testé en rejeu et associé à ses métriques de fraîcheur. Le remplacement proposé fermerait notamment son thread consommateur de manière non redémarrable et introduirait une horloge de frame ambiguë.
- Le remplacement complet de `OriaScreen.kt` de Samy : il aurait supprimé le dashboard produit/développeur, la navigation guidée et les contrôles de profondeur ajoutés ensuite. Seules ses améliorations compatibles d'accessibilité et de thème ont été reportées.
- Toute présentation de l'expérience « murs » comme une fonctionnalité de sécurité déjà validée sur lunettes.

## Vérifications après fusion

- Android : `testDebugUnitTest`, `assembleDebug` et `lintDebug` réussis avec Android SDK 36.
- Oria Lab : 175 tests Python réussis avec l'environnement `ml/.venv-r11`.
- Obstacles/profondeur : 107 tests ciblés réussis.
- Interface web Oria Lab : contrôles Node réussis.
