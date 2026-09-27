# R03 — Profondeur monoculaire optionnelle

Date : 27 septembre 2026

Verdict : **preuve logicielle intégrée, désactivée par défaut ; porte HTC ouverte**. Le moteur RGB validé reste inchangé et demeure l'unique source des alertes jusqu'au travail de fusion R04.

## Modèle et licence

- Modèle : **MiDaS v2.1 Small**, publication officielle Intel ISL destinée aux appareils contraints.
- Asset officiel : `model-small.onnx`, 66 764 249 octets, SHA-256 `2d8c6cb8f415229daf1eb041024208e2608c9f98e17c81cc7c6ecb449c56fd58`.
- Source : `https://github.com/isl-org/MiDaS`, release `v2_1`.
- Licence : MIT, texte livré avec l'asset.
- Runtime Android : ONNX Runtime 1.22.0, CPU ou XNNPACK avec fallback CPU.
- Contrat : entrée RGB NCHW float32 `[1,3,256,256]`, sortie float32 `[1,256,256]`.

Le résultat est une **profondeur inverse relative** : une valeur supérieure suggère une zone plus proche dans l'image. Il n'existe aucune échelle métrique, aucun étalonnage Eagle et aucun nombre de mètres dans le produit.

## Alignement des pixels

La frame fournie au modèle est déjà redressée et non miroir par le décodeur Oria. Le prétraitement redimensionne toute cette frame directement vers 256 × 256 avec un bilinéaire déterministe, sans crop ni letterbox. Les coordonnées normalisées des boîtes YOLO se projettent donc directement sur la carte de profondeur ; le test couvre paysage, portrait et limites de zones.

Cette implémentation utilise un bilinéaire déterministe tandis que le script ONNX historique de MiDaS utilise le bicubique OpenCV. Cette différence reste visible et doit être incluse dans la validation de qualité Eagle ; elle n'est pas masquée par un ajustement de seuil.

## `OriaDistanceEvidence`

L'adaptateur produit, pour chaque piste visible :

- médiane de profondeur inverse relative ;
- proximité normalisée dans la frame, entre 0 et 1 ;
- confiance fondée sur couverture valide, dispersion locale et dynamique de la frame ;
- âge de la carte ;
- tendance heuristique `APPROACHING`, `STABLE`, `RECEDING` ou `UNKNOWN` ;
- motif explicite d'indisponibilité.

L'agrégation utilise la partie basse et centrale de la boîte, pas son pixel central. Une région frontale fixe produit également une preuve d'obstruction générique indépendante des classes reconnues. Une carte absente, périmée, plate, partiellement invalide ou trop dispersée revient au mode RGB avec `MODEL_MISSING`, `MODEL_ERROR`, `STALE`, `INVALID_MAP` ou `LOW_CONFIDENCE`.

La tendance compare des proximités relatives normalisées entre deux observations fraîches d'une même piste. Elle n'est ni une vitesse, ni un TTC, ni une preuve d'identité physique.

## Intégration

- Option « MiDaS relatif · expérimental » dans les réglages Oria, désactivée par défaut.
- Chargement hors thread UI, asset vérifié par taille et SHA-256 puis publié atomiquement dans le cache de code.
- Inférence sur le worker existant ; aucune exécution dans un callback caméra.
- Échec de profondeur isolé du détecteur et de la politique : la perception RGB continue.
- Temps d'inférence, disponibilité, confiance, âge, tendance et motifs enregistrés dans les traces et les captures Oria Lab, sans sérialiser la carte 256 × 256.
- La profondeur n'influence encore ni sélection, ni priorité, ni voix. Cette fusion appartient à R04.

## Vérifications

- Contrat ONNX lu avec ONNX Runtime Mac : noms `0` → `797`, formes exactes, sortie 100 % finie.
- Dix inférences CPU Mac sur tenseur synthétique : 65,86 à 71,98 ms, médiane 67,47 ms. Cette mesure n'est pas transposable au HTC.
- Tests du cœur : proche/loin monotone, pixel de fond aberrant, tendance, faible dynamique, carte périmée, NaN/occultation, corridor générique, paysage/portrait et modèle absent.
- Empreinte, taille, licence et géométrie de l'asset testées automatiquement.
- **103/103 tests JVM**, **50/50 tests du cœur Kotlin isolé**, 6/6 tests Python de politique et 4/4 tests Python R02 réussis.
- Build debug et lint Android réussis ; APK debug local : 221 938 509 octets.

Reproduction :

```bash
python3 validation/validate_depth_model.py --samples 10 --output /tmp/r03-depth-model.json
cd android-project
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Le script fonctionne sans ONNX Runtime en ne vérifiant que l'asset ; pour le benchmark, installer `onnxruntime` et `numpy` dans un environnement Python isolé.

## Porte restante

L'APK debug atteint environ 211 Mo avec les deux modèles. L'application installée sur le HTC porte une signature différente de l'APK local : elle n'a pas été remplacée et aucune donnée utilisateur n'a été supprimée. Avant activation par défaut, il faut mesurer sur le HTC la latence, le pic mémoire, la chauffe, le débit combiné YOLO + MiDaS, puis vérifier sur des images Eagle annotées l'ordre proche/loin et l'alignement des boîtes. Toute régression au-delà de la garde de fraîcheur doit maintenir le fallback RGB.
