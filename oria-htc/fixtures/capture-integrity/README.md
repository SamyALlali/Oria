# Fixtures communes d’intégrité des captures

Corpus entièrement synthétique, sans capture privée ni modèle. `cases.json` décrit 11 petites sessions v1 et leurs attentes explicites, indépendantes des lecteurs Android et Mac. Les fichiers binaires ne sont pas committés : `materialize.py` crée des PNG RGB uniformes de 2×2 pixels avec la seule bibliothèque standard Python.

Depuis `oria-htc/` :

```sh
python3 fixtures/capture-integrity/materialize.py
python3 -m unittest discover -s fixtures/capture-integrity -p 'test_materialize.py' -v
```

La première commande imprime le chemin d’un nouveau dossier temporaire. L’option `--output /chemin/nouveau-dossier` exige un dossier qui n’existe pas encore. Le générateur ne remplace et ne supprime aucun fichier existant. Chaque cas produit `capture/` (manifest, frames/events/packets JSONL, PNG), puis **à côté** `expected.json` et `payload-sha256.json`. Copier une capture synthétique dans un espace temporaire avant toute expérience qui écrit ; comparer les SHA avant/après pour les lecteurs.

API Python : `read_cases()` retourne les définitions ; `materialize(destination)` retourne une liste de tuples `(case_dict, capture_path)`. Ce module peut être chargé avec `importlib.util.spec_from_file_location` depuis les tests Mac, sans paquet ni dépendance additionnelle. Un adaptateur Android peut copier les mêmes fichiers générés dans son cache de test ; le corpus n’est pas un asset de production.

## Oracle

`expected.positionCount` est le nombre de lignes non vides dans `frames.jsonl`. `expected.rows` garde cet ordre, avec `sourceLine` (numéro physique, commençant à 1). Une ligne vide ne devient pas une image, mais incrémente les numéros physiques suivants. Le cas 02 place volontairement les images hors ordre temporel ; **ne pas trier l’index pour satisfaire une horloge**.

- `identity` : paire numérique exacte lorsque ses champs sont bien formés, sinon `null`. `identityWellFormed` ne prouve pas l’unicité : le cas 04 conserve la paire originale dupliquée mais interdit une association arbitraire. Une chaîne ou un booléen n’est jamais coercé en identifiant. Un lecteur peut exposer des champs partiels valides avec leur diagnostic, sans construire une identité utilisable.
- `receivedAtMs`, lorsqu’attendu, est l’observation source exacte. Les champs `atMs`/`recordedAtMs` ultérieurs des événements ne la remplacent jamais. L’absence d’une valeur dans l’oracle n’autorise aucun défaut `0` ni timestamp inventé.
- `imagePath` est le texte enregistré, y compris les chemins malveillants synthétiques ; il n’autorise jamais une lecture hors capture. `filePresent`, lorsqu’attendu, décrit seulement la présence d’un fichier.
- `recordedInferenceConsistent` décrit la cohérence de l’inférence enregistrée avec les métadonnées : unicité, identités et horloges, structure des détections. `detectionClassIds` fournit leur ordre exact lorsque cohérentes. C’est distinct de la disponibilité et de la sûreté du PNG. Une PNG manquante n’altère pas à elle seule les données numériques enregistrées.
- `issueKinds` contient les causes sémantiques requises, pas des chaînes d’interface à comparer littéralement. Des défauts supplémentaires justifiés sont autorisés ; une cause peut être dans l’entrée ou les diagnostics de session, mais elle ne doit pas disparaître. Les exemples comprennent `invalid_json`, `missing_png`, `invalid_identity`, `duplicate_frame_identity`, `duplicate_inference`, `inference_clock_mismatch`, `contradictory_observation_clock`, `invalid_utf8`, `duplicate_json_field`, `oversized_jsonl_line`, `invalid_detection` et `unsafe_image_path`.

Aucune attente n’autorise une décision ou un rejeu sur des données ambiguës. La décision de lancer un moteur est un contrat supplémentaire, vérifié par les tests de chaque application.

**Différence d’affichage actuelle explicite :** la galerie Android 1.3 conserve la position d’un PNG absent mais masque aussi son analyse (`readFrame` sans référence exploitable). Le Mac peut inspecter une inférence numérique cohérente malgré ce PNG absent et doit bloquer le recalcul ONNX. Le cas 01, ligne 3, documente l’inférence cohérente `[1]` sans exiger une API ou un affichage identique des deux plateformes. Le cas 11 traite de même les données enregistrées sans autoriser la lecture du chemin hors capture. Cette distinction évite de fabriquer une parité d’interface inexistante.

## Couverture

| Cas | Défaut ou invariant |
| --- | --- |
| 01 | Quatre positions A / JSON invalide / PNG B absente / C |
| 02 | Ordre source non chronologique, ligne vide, temps de traitement ultérieurs |
| 03 | Chaîne, booléen, ID absent/null, alias vidéo/session incohérents |
| 04 | Identité dupliquée, même frameId dans des vidéos distinctes |
| 05 | Inférence dupliquée : pas de « dernière gagnante » |
| 06 | Horloge différente de la PNG, alias d’observation contradictoires |
| 07 | JSON tronqué à EOF sans retour à la ligne |
| 08 | UTF-8 invalide puis clé JSON répétée |
| 09 | Ligne >2 Mio drainée et position suivante conservée |
| 10 | Boîte de détection invalide : analyse explicitement inutilisable |
| 11 | Chemins relatifs sortants et absolus refusés |

Les trois tests du générateur vérifient ses octets, positions et PNG, son déterminisme et son refus d’écraser un répertoire. Ils ne prouvent pas qu’une application satisfait cet oracle. Les résultats Android/Mac doivent être déclarés séparément. Aucun cas ne représente un flux caméra réel ni une preuve acoustique.
