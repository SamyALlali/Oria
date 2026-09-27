# Fixtures synthétiques de politique Surfaces

`policy_sequences.json` contient deux séquences minimales, l’une descriptive de mur, l’autre de candidat obstacle générique. Toutes les valeurs sont fabriquées. Aucun pixel ni événement d’une capture privée ne figure dans ce corpus. Le champ `synthetic: true` et le texte `meaning` font partie du document.

Les observations complètes utilisent les trois zones `LEFT`, `CENTER`, `RIGHT`, aux frontières normalisées 0,39 et 0,61. Une liste vide, `null`, une zone absente ou une mesure absente représentent des données manquantes. Une liste complète avec fractions à zéro est une observation valide sans candidat ; elle ne constitue pas une preuve de passage libre.

Chaque observation donne un `expectedStatus` et, uniquement lors d’une proposition, un `expectedText`. Les séquences vérifient trois observations cohérentes sur 600 ms, puis une interruption par absence de données et une observation complète vide. Les tests adversariaux plus larges sont dans `../../oria-lab-desktop/test_surface_policy.py` : horloges inversées, doublons, gaps, changement de session, côtés alternés, répétitions, types JSON ambigus, NaN/Infinity et fractions incohérentes.

Depuis la racine du dépôt :

```sh
python3 -m unittest discover -s oria-htc/oria-lab-desktop -p test_surface_policy.py -v
```

Ces fixtures éprouvent la mécanique déterministe. Elles ne mesurent ni la segmentation, ni le relief monoculaire, ni une distance, ni la pertinence des propositions pour un usager.
