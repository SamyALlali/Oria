# Contenu du dépôt Oria

Ce dépôt privé conserve le code Android et Mac, le SDK/simulateur fournis avec le starter HTC, le checkpoint utilisateur, l’ONNX exporté, les fixtures de référence, les tests, les scripts et les documents du hackathon.

Les éléments suivants restent sur le Mac d’origine : captures des lunettes, vidéos et images de scènes réelles, traces brutes, sauvegardes privées des téléphones, anciennes copies du starter, APK historiques, environnements Python, caches et résultats de compilation. Ils ne sont ni supprimés ni déplacés. Les images de fixtures publiques/SDK/synthétiques nécessaires aux tests sont incluses.

L’APK courant est distribué dans la release `v0.1.0-silmo`, accompagné de son SHA-256 et d’un résumé de validation. La distribution Gradle se récupère avec `python3 echonav-htc/scripts/prepare_gradle.py` ; le wrapper HTC et les dépendances locales restent inchangés.

Les documents historiques et `echonav-htc/artifacts/delivery_manifest.json` référencent aussi des preuves locales non versionnées. Un lien vers ces preuves n’implique pas qu’elles soient disponibles après un clone. Le script `ml/verify_integrity.py` et certains outils d’audit sont liés au poste d’origine, au checkpoint de Downloads et au projet Swift ; les tests Android et Mac du README sont les commandes de démarrage pour un nouveau poste. La réexportation du modèle et la régénération des références Swift exigent d’adapter leurs chemins source. L’ONNX, le checkpoint copié et les références calculées sont déjà inclus.

Le modèle et les politiques proviennent d’EchoNav ; les bibliothèques HTC proviennent du package du hackathon, et les fixtures publiques sont identifiées dans leur manifeste. Ce dépôt n’attribue pas de nouvelle licence aux composants tiers.
