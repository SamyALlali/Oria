# Téléphone → Mac par USB

Dans l’onglet Oria Lab, enregistrer puis arrêter et attendre la finalisation. Brancher le téléphone autorisé au Mac, puis double-cliquer **Récupérer depuis HTC.command**. La capture finalisée la plus récente est copiée dans `~/Documents/Oria Lab Captures/`, importée et ouverte dans le lecteur Mac. Garder le terminal ouvert pendant le rejeu ; Ctrl+C arrête le serveur local. Les données du téléphone restent en place.

Ce chemin utilise le mode debug du starter et ADB ; il évite de passer par le sélecteur Android pour chaque essai. Le bouton **Exporter ZIP** dans l’application reste utilisable indépendamment. Aucun compte ou transfert externe n’est nécessaire.

En ligne de commande :

```sh
python3 oria-lab-transfer/pull_capture.py --serial CN46V3M00284
# Une autre session peut être sélectionnée avec --session <UUID>.
```

Sans `--serial`, le script exige un seul appareil ADB autorisé. Il refuse de sélectionner arbitrairement un téléphone parmi plusieurs. Les archives et les données existantes ne sont pas effacées. Un fichier adjacent `.transfer.json` identifie appareil, session, empreinte et taille de l’archive.

Captures longues : le transfert ne fixe aucune durée. Les bornes techniques sont **128 Gio par archive et 100 000 fichiers**, identiques au lecteur Mac. Le flux TAR est converti en ZIP64 par blocs de 1 Mio ; la vidéo et les images ne sont pas chargées ensemble en mémoire. Une réserve de **512 Mio libres sur le Mac** est vérifiée durant l’écriture, y compris pour les métadonnées du ZIP. Prévoir aussi l’espace d’une copie supplémentaire pour l’import dans Oria Lab.

Un dépassement, un manque d’espace ou une interruption provoque une erreur explicite, jamais une archive présentée comme complète mais tronquée. Le script ne supprime que le `.part` qu’il a lui-même créé pendant une tentative échouée. Un `.part` préexistant, une archive existante et les données du téléphone sont conservés. La publication finale utilise un lien atomique dans le même dossier pour refuser tout écrasement concurrent ; un système de fichiers ne prenant pas en charge les liens matériels renvoie une erreur et laisse la capture sur le téléphone.

Les contrôles portent sur les chemins, doublons, types de fichiers, tailles reçues, CRC ZIP et identité complète du manifeste. Le reçu `.transfer.json` contient le SHA-256 de l’archive, les bornes appliquées et `archive_reused`. Une archive déjà valide est réutilisée ; pour récupérer une nouvelle étiquette de scène après renommage, choisir un autre `--output`.

Validation sans téléphone :

```sh
cd oria-lab-transfer
python3 -m unittest -v test_pull_capture.py
```

Ces tests utilisent uniquement des fixtures et un transport simulé. Ils ne constituent pas une mesure de débit USB ni une validation physique de captures de plusieurs heures.
