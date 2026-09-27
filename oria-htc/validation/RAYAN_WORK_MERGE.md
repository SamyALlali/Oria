# Branche `rayan_work` — fusion finale

## Objectif

Cette branche part du `main` de Samy au commit `f3454f6` et conserve la même arborescence. Elle superpose les fonctions validées dans le dépôt de Rayan sans retirer les travaux récents de Samy sur l'accessibilité et l'analyse expérimentale des murs et obstacles dans Oria Lab.

## Fonctions ajoutées au runtime Android

- cycle de session sûr et états dégradés explicites ;
- moteur complet de résolution/priorisation EchoNav et fixtures Swift/Kotlin ;
- profondeur monoculaire optionnelle, désactivée par défaut ;
- ordonnanceur audio unique entre dangers, navigation et interactions ;
- navigation guidée réelle et simulation déterministe ;
- commandes vocales et bouton IA des lunettes ;
- dashboard produit/développeur, rejeu complet et mesures de robustesse ;
- protections de confidentialité des captures Oria Lab ;
- campagne de tests, rapports de validation et manifeste de livraison R11.

## Front final

L'accueil conserve le thème accessible clair/sombre de Samy, avec une présentation produit distincte :

- bandeau EchoNav / Oria et état de connexion immédiatement visibles ;
- bouton principal unique pour démarrer ou arrêter Oria ;
- résumé lisible de la perception, de la destination et de la dernière information ;
- commandes vocales simplifiées ;
- navigation repliable afin de réduire la charge visuelle ;
- réglages, aperçu caméra et télémétrie réservés au « Mode expert » ;
- cibles tactiles larges et informations essentielles lisibles sans dépendre de la couleur.

Version Android : `1.6-final-rayan` (`versionCode 7`). Le package HTC autorisé reste `com.htc.vive.eagle.hackathon.starter`.

## Détection de murs et obstacles

Les expériences de Samy restent intégrées à Oria Lab sur Mac avec leurs modèles, licences, politiques, contrôles qualité et tests. Elles restent explicitement expérimentales : aucune distance métrique, aucun passage sûr et aucune alerte live ne sont affirmés sans qualification supplémentaire.

## Vérifications

- `testDebugUnitTest`, `assembleDebug`, `lintDebug` : réussis ;
- 175 tests Python Oria Lab : réussis ;
- tests Node de l'interface Oria Lab : réussis ;
- installation et démarrage sur HTC U24 pro : réussis ;
- inspection visuelle de l'accueil produit sur l'appareil : effectuée.
