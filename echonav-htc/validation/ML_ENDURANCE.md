# Endurance du modèle et du prétraitement — 10 minutes

26 septembre 2026, HTC U24 pro / Android 14. XNNPACK avec repli CPU, deux threads, même ONNX FP32 que les essais de parité. Test instrumenté dans le harness distinct : **terminé, 1 test réussi en 601,334 s côté runner ; fenêtre mesurée 600,026 s**.

Ce test alterne quatre images fixes positives/négatives à quatre offres synthétiques par seconde : bus Ultralytics, frame SDK négative, zidane Ultralytics, seconde frame SDK négative. Il exécute le vrai prétraitement Bitmap et le vrai détecteur. **Il n’utilise ni acquisition des lunettes, ni décodeur H.264, ni SDK de connexion, ni voix.** L’âge d’une offre de fixture n’est pas une mesure de fraîcheur d’une frame de caméra.

## Résultats soutenus

| Mesure | Résultat |
|---|---:|
| Résultats terminés | 2 400 |
| Résultats à moins de 500 ms de leur offre synthétique | 2 400 |
| Résultats périmés / offres sautées | 0 / 0 |
| Débit de résultats frais | 3,99983 Hz |
| Offre synthétique → résultat, p50 | 181,76 ms |
| Offre synthétique → résultat, p95 | 194,99 ms |
| Offre synthétique → résultat, maximum | 355,30 ms |
| Inférence seule, p95 | 159,01 ms |
| Prétraitement seul, p95 | 38,07 ms |
| Plus long intervalle sans nouveau résultat frais | 412,32 ms |

Les nombres de détections sont restés respectivement 3, 0, 2 et 0 pour les quatre fixtures sur tout le test. C’est un constat de stabilité sur ces entrées, pas une mesure du rappel, une nouvelle parité exhaustive à chaque itération ou un résultat sur images de lunettes réelles.

## Début et fin de session

Percentiles au rang supérieur (`ceil(n×q)`, comme dans le test), fenêtres de 240 résultats chacune.

| Mesure | Première minute | Dernière minute |
|---|---:|---:|
| Offre → résultat, p50 | 181,66 ms | 181,00 ms |
| Offre → résultat, p95 | 192,70 ms | 192,95 ms |
| Inférence, p95 | 155,26 ms | 153,69 ms |
| Prétraitement, p95 | 37,16 ms | 38,09 ms |
| Offre → résultat, maximum | 302,25 ms | 196,06 ms |
| Résultats frais | 240 / 240 | 240 / 240 |

La dernière minute ne montre pas de dégradation progressive du calcul par rapport à la première dans ce scénario. Les pics restent visibles dans le rapport complet ; les rejets n’ont pas été retirés du calcul pour améliorer les chiffres.

## Mémoire et état thermique

- Statut thermique Android : **0, aucun niveau de limitation signalé**, sur tous les échantillons.
- Température batterie rapportée : **30 → 34 °C**. Ce n’est pas une température de SoC.
- Niveau batterie rapporté : 100 % pendant l’essai ; **aucune autonomie ou puissance consommée n’en est déduite**.
- PSS : 331 764 KiB juste après initialisation, 252 005 KiB à environ 10 s, 255 396 KiB juste avant fermeture du détecteur. Après 10 s, les mesures restent entre 252 005 et 284 392 KiB ; aucune croissance continue n’apparaît dans ces dix minutes.
- Allocations natives : 303 600 896 octets au départ, 303 576 432 juste avant fermeture, soit −24 464 octets. Cette mesure ne représente pas la mémoire physique résidente.
- Heap Java : 10 310 208 octets à 10 s et 13 730 368 avant fermeture, avec cycles de collecte visibles. Le test retient volontairement 2 400 objets JSON de mesure : cette comptabilité ajoute quelques mégaoctets que le pipeline de production ne doit pas conserver sans borne.

PSS, allocations natives et heap Java sont des comptabilités différentes et ne s’additionnent pas. Les valeurs initiales incluent les allocations de mise en route ; le premier garbage collection explique une part importante de la baisse. Ce test borné ne démontre pas l’absence de toute fuite à long terme.

## Version testée et limite géométrique

Le test installé avait encore l’inverse géométrique à gain uniforme (`uniform_gain_v1`). La correction `raster_inverse_v2`, fondée sur les dimensions redimensionnées après arrondi, a été appliquée pendant l’exécution du test sur le poste de développement, **pas sur le téléphone en cours d’essai**. Le modèle ONNX, les images, les tenseurs et le prétraitement sont inchangés. L’endurance apporte donc une preuve pour ce calcul soutenu, mais ne valide pas rétroactivement la projection finale corrigée. Ses huit tests JVM ML et son smoke test Android XNNPACK sur six fixtures ont ensuite réussi séparément ; voir `ML_IMPLEMENTATION.md` pour leurs rapports.

Le CPU seul reste en échec de parité complète sur une fixture du run 02. Ce résultat d’endurance XNNPACK ne le réhabilite pas. Le test combiné décodeur + YOLO, les connexions et la voix des lunettes sont également des validations séparées.

## Preuves

- Rapport brut : `validation/device-endurance/files/ml_validation/onnx_xnnpack_endurance.json`.
- SHA-256 du rapport brut : `57d5d52f7af691e211255e70c16e1ef567af1abc9858916f5ec0b90b45df6b36`.
- Analyse des fenêtres, mémoire et effectifs : `validation/device-endurance/summary.json`.
- Aucun modèle, asset ou manifeste n’a été modifié pour inscrire ce résultat ; il s’agit d’un rapport expérimental daté sur la version effectivement testée.
