# Contenu du dépôt Oria

Ce dépôt privé conserve le code Android et Mac, le SDK/simulateur fournis avec le starter HTC, le checkpoint utilisateur, l’ONNX exporté, les fixtures de référence, les tests, les scripts et les documents du hackathon.

Les captures personnelles, vidéos, images réelles, traces brutes, sauvegardes des téléphones et anciens APK restent locaux. Les originaux antérieurs au renommage sont sauvegardés hors du projet dans `/Users/sam/Documents/Oria Archives/2026-09-26-naming/`, sans modification de leurs octets. Les captures actives du Mac sont dans `~/Library/Application Support/Oria Lab/` et leurs exports dans `~/Documents/Oria Lab Captures/`. Les fixtures publiques, SDK et synthétiques nécessaires aux tests sont incluses dans Git.

L’APK courant est distribué dans la release `v0.1.1-silmo`, accompagné de son SHA-256 et d’un résumé de validation. La distribution Gradle se récupère avec `python3 oria-htc/scripts/prepare_gradle.py` ; le wrapper HTC et les dépendances locales restent inchangés.

Les références aux preuves historiques dans les documents désignent les rapports originaux archivés hors du projet ; leur ancienne arborescence relative est conservée dans l’archive. Les noms ont été harmonisés dans les synthèses maintenues, sans réattribuer les mesures à la nouvelle version. Les rapports actuels sous `validation/new-device-CN46V3M00284/` restent locaux, sauf les conclusions textuelles versionnées et le résumé de release.

`ml/verify_integrity.py` vérifie le checkpoint inclus, l’ONNX, les assets et le manifeste de livraison local. Les contrôles de rapports physiques archivés sont explicitement non exécutés quand ces fichiers sont absents. Le contrôle ou la régénération du Swift source exige `ORIA_SWIFT_SOURCE` pointant vers le fichier de référence externe. Les fixtures Swift calculées sont déjà incluses. Les tests Android/Mac du README fonctionnent avec les fichiers du dépôt et les dépendances indiquées.

Le modèle a été fourni par l’utilisateur et les politiques ont été portées depuis une base Swift. Les bibliothèques HTC proviennent du package du hackathon ; les fixtures publiques sont identifiées dans leur manifeste. Ce dépôt n’attribue pas de nouvelle licence aux composants tiers.

Les fichiers, classes, assets, métadonnées et stockages actifs utilisent Oria / Oria Lab. Cinq captures sur le HTC, sept sessions en cache Mac et cinq exports ZIP ont été migrés ; leurs images, événements et UUID sont conservés. Seuls les marqueurs de nommage des manifestes et les chemins changent. Le package HTC autorisé et la signature restent identiques. L’historique Git et la release initiale restent des archives ; aucune réécriture de leur historique n’a été faite. Voir [le rapport de vérification](oria-htc/validation/ORIA_RENAMING.md).
