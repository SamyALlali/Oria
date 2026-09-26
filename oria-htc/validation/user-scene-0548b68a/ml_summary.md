# Audit ML — capture 0548b68a

Capture de 60.022 s : **164 PNG, 164 inférences, 164 décisions**, 1772 paquets. ZIP CRC valide ; archive originale inchangée : `36db9e3ad3a0a0e7c3a70e32ed8919e25fa67aeec997839008cbd20a20e60528`.

Même ONNX FP32 416 (`c3b7b89fadf62385a9244edac2832944bf49266ae763cc8744a39f2d4e95756d`), letterbox et inverse raster existants. Réinférence des 164 PNG exactes sur **CPU Mac**, sans rotation supplémentaire, export ou seuil modifié. Ces temps ne mesurent pas le HTC.

**Parité brute FAIL : 147/164 images**, 18 lignes non appariées sur 49200. Tolérances conservées : même classe, 1 pixel d'entrée et 0,001 de confiance. Confiance maximale des lignes non appariées : 3.7044286727905273e-05. Toutes sous 0,70 : True.

À partir de 0,70 : 240 lignes comparées, **0 non appariée(s)**, 0 bascule(s) de seuil. Les listes enregistrées correspondent exactement (float32) au décodage de leurs sorties brutes : True. Une détection à 0,70 ne signifie pas une annonce : les seuils par classe puis confirmation, sélection et disponibilité audio s'appliquent ensuite.

Téléphone : 164/164 inférences acceptées ; états décision {'ACCEPTED': 164}. Âge décision médian 280.0 ms, p95 302.55 ms, max 330 ms. Débit sur la capture entière : 2.732 Hz. Copie bitmap p95 0.817 ms. Le surcoût isolé de l'enregistrement exige un essai OFF/ON comparable.

H264 décodé entièrement : code 0, 1771 images. L'horloge nominale du décodeur n'est pas l'horloge de réception. Les PNG et leurs timestamps sont la référence pour la comparaison.

## Écarts bruts localisés

| Image | frameId | Temps depuis début | Lignes non appariées |
|---:|---:|---:|---:|
| 14 | 137 | 5.638 s | 1 |
| 84 | 898 | 30.885 s | 1 |
| 86 | 918 | 31.565 s | 1 |
| 89 | 950 | 32.627 s | 1 |
| 91 | 968 | 33.339 s | 2 |
| 92 | 981 | 33.702 s | 1 |
| 93 | 993 | 34.047 s | 1 |
| 103 | 1100 | 37.634 s | 1 |
| 113 | 1203 | 41.108 s | 1 |
| 114 | 1215 | 41.471 s | 1 |
| 125 | 1335 | 45.476 s | 1 |
| 126 | 1346 | 45.864 s | 1 |
| 133 | 1425 | 48.445 s | 1 |
| 136 | 1454 | 49.497 s | 1 |
| 138 | 1478 | 50.231 s | 1 |
| 141 | 1509 | 51.279 s | 1 |
| 153 | 1642 | 55.705 s | 1 |

## Inspection visuelle et limites

14 planches `ml_contact_XX.jpg` couvrent les 164 images à environ 250 px de largeur chacune. Les boîtes sont les prédictions téléphone ≥0,70, et ne constituent pas des annotations humaines. Classes détectées : {'person': 238, 'bike_scooter': 2}.

Les compteurs de voix et l’orientation sont conservés dans le manifeste ; l’audit des transactions et du moteur temporel est séparé. Aucun microphone enregistré : cet audit ne prouve pas l’écoute physique. Aucun rappel, taux de faux positifs ou sécurité de navigation ne peut être établi sans annotation de la scène.

Reproduction depuis `oria-htc` :

```sh
ml/.venv/bin/python validation/user-scene-0548b68a/ml_audit.py
```

Artefacts : `ml_summary.json`, `ml_frames.jsonl` (164 résultats et sorties brutes Mac), `ml_audit.log`, `ml_contact_01.jpg` à `ml_contact_14.jpg`. Les originaux et les sources du serveur ne sont pas modifiés.
