# R09 — Parité finale d’Oria Lab

Date de validation : 27 septembre 2026
Commit cible : `feat(replay): extend Oria Lab to full-system parity`

## Verdict

La porte logicielle R09 est validée sur les données disponibles : le replay complet emploie les moteurs de production RGB, résolution de danger, arbitrage audio et navigation avec une horloge contrôlée. Deux exécutions d’une même session produisent les mêmes décisions champ par champ et la même empreinte SHA-256.

La validation physique finale reste ouverte : l’archive `0548b68a` n’est pas présente sur cette machine et l’APK autorisé installé sur le HTC porte une signature différente de l’APK recompilé.

## Changements vérifiés

- Schéma Oria Lab v2 limité aux nouveaux diagnostics : génération, profondeur relative estimée, pistes, candidats, stabilisation, audio arbitré et navigation.
- Lecture des captures v1 et v2 sans migration ni réécriture ; les tests comparent les SHA-256 des fichiers source avant et après import.
- Paquets H.264, offsets, PTS, index d’images, PNG, positions invalides, lecture paresseuse, contrôles d’intégrité et ZIP en flux D26–D30 inchangés.
- Capture toujours explicite ; aucun média n’est enregistré lorsque l’utilisateur n’arme pas Oria Lab.
- Noms, requêtes, adresses, coordonnées, instructions et textes libres de destination sont omis à la frontière de persistance. Les champs de diagnostic structurés restent disponibles.
- Un saut de replay réinitialise pisteur, mémoire visuelle, résolution, arbitrage audio et navigation avant toute donnée suivante.
- L’empreinte comportementale couvre notamment les pistes, candidats, profondeur relative, sélection stabilisée, raison de décision, résultat audio et versions de navigation. Elle ne contient pas de destination.

## Résultats exécutés

| Contrôle | Résultat |
|---|---:|
| Tests JVM Android, dont replay complet et fixtures Kotlin | 165/165 |
| Référence Swift RGB contre Kotlin | 18/18, identique |
| Référence Swift résolution de danger contre Kotlin | 10/10, identique |
| Cœur Oria Lab Policy JVM | 63/63 |
| Tests Python Oria Lab Policy | 6/6 |
| Corpus d’intégrité matérialisé | 3/3 |
| Transfert de capture | 11/11 |
| Qualification R02 historique/documentaire | 4/4 |
| Oria Lab Desktop | 67/68 ; un test ONNX non exécuté faute de module `onnxruntime` |
| Build debug + APK de tests instrumentés + lint | succès |
| Installation de mise à jour sur HTC | bloquée par `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |

APK debug produit : SHA-256 `7318e758ca43b4fd246a4d6da1ba5bd0269e6a2fd57b894a5aecca8064a90e0d`.

## Scène `0548b68a`

Les scripts d’audit et les rapports historiques sont présents, mais l’archive attendue `OriaLab-0548b68a-b9e3-445f-a072-0182e9ce98d6.zip` est absente du dépôt, de `/Users/rayan/Desktop/Oria` et du dossier de captures utilisateur. La scène n’a donc pas été prétendue rejouée pendant R09. Sa récupération permettra de relancer `validation/user-scene-0548b68a/policy_audit.py` en lecture seule.

## Écarts, causes et risques

1. **Pas de capture réelle v2 complète** — les comparaisons doubles du nouveau replay utilisent une session déterministe synthétique couvrant perception, profondeur relative, audio et navigation. Risque restant : données réelles malformées ou séquences matérielles non représentées.
2. **Scène 0548b68a indisponible** — cause : archive source absente. Les rapports antérieurs restent des preuves historiques, pas une nouvelle exécution R09.
3. **Test Mac ONNX indisponible** — Python disponible en 3.9, alors que l’environnement n’a pas `onnxruntime==1.22.0`. Les 67 autres tests Desktop passent ; aucune tolérance ni sortie attendue n’a été modifiée pour masquer cet écart.
4. **Tests Android instrumentés non lancés sur le HTC** — l’installation `-r` est refusée car la signature autorisée diffère. L’application existante n’a pas été désinstallée et ses captures n’ont pas été supprimées.
5. **Pas de preuve acoustique ou GPS physique** — la parité porte sur les décisions et commandes produites, pas sur ce qu’un utilisateur entend réellement ni sur un trajet extérieur.

## Conclusion

R09 ferme la parité logicielle et la compatibilité de format. Les limites restantes sont des preuves matérielles ou des données absentes ; elles doivent rester visibles jusqu’aux campagnes R10/R11.
