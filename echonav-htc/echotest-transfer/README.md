# Téléphone → Mac par USB

Dans l’onglet Oria Lab, enregistrer puis arrêter et attendre la finalisation. Brancher le téléphone autorisé au Mac, puis double-cliquer **Récupérer depuis HTC.command**. La capture finalisée la plus récente est copiée dans `~/Documents/EchoTest Captures/`, importée et ouverte dans le lecteur Mac. Garder le terminal ouvert pendant le rejeu ; Ctrl+C arrête le serveur local. Les données du téléphone restent en place.

Ce chemin utilise le mode debug du starter et ADB ; il évite de passer par le sélecteur Android pour chaque essai. Le bouton **Exporter ZIP** dans l’application reste utilisable indépendamment. Aucun compte ou transfert externe n’est nécessaire.

En ligne de commande :

```sh
python3 echotest-transfer/pull_capture.py --serial CN46V3M00284
# Une autre session peut être sélectionnée avec --session <UUID>.
```

Sans `--serial`, le script exige un seul appareil ADB autorisé. Il refuse de sélectionner arbitrairement un téléphone parmi plusieurs. Les archives et les données existantes ne sont pas effacées. Un fichier adjacent `.transfer.json` identifie appareil, session, empreinte et taille de l’archive.
