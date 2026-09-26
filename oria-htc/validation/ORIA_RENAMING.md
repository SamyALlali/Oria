# Vérification du nommage Oria / Oria Lab

La version courante utilise exclusivement **Oria** pour l’assistance et **Oria Lab** pour le laboratoire. Les noms sont harmonisés dans les chemins, sources, classes et packages Kotlin internes, documents, fixtures, scripts, assets et métadonnées des modèles. Le package Android autorisé reste `com.htc.vive.eagle.hackathon.starter`, avec la même signature.

## Version installée

APK SHA-256 : `f49def1953699349d1c189402c2a7b0229e6bd17db1ca7c0f29c54c9873eeb43`.

Compilation réussie, puis mise à jour compatible sur **CN46V3M00284**, HTC U24 pro / Android 14, le 26 septembre 2026 à 16:21 UTC. L’accueil affiche Oria, le modèle est chargé et Oria Lab retrouve la capture de 60 secondes / 164 images. Aucun nouvel essai de caméra ou d’écoute dans les lunettes n’est revendiqué. Les gains PCM restent 70/30 et 30/70, centre 1/1 ; leur écoute reste à confirmer.

## Conservation des données

Avant migration, copie externe des sources et modèles, archive Git initiale et sauvegarde privée du téléphone dans `/Users/sam/Documents/Oria Archives/2026-09-26-naming/`. Les originaux historiques ne sont pas altérés ; leurs noms ne sont pas réintroduits dans le projet actif.

- **5 captures Android** dans `files/oria-lab/`, avec préférences audio et noms de traces harmonisés.
- **7 sessions Mac** dans `~/Library/Application Support/Oria Lab/`.
- **5 exports ZIP** dans `~/Documents/Oria Lab Captures/`, préfixés `OriaLab-`, avec contrôle CRC.

Les manifestes portent `kind: oria-lab-session` et `namingMigration.originalManifestSha256`. UUID, images, événements et flux vidéo sont conservés. Les ZIP migrés ont de nouvelles empreintes car leur manifeste a changé ; les originaux sont archivés. Le lecteur attend le nouveau marqueur et ne prétend pas ouvrir directement un export original non migré.

La scène `0548b68a-b9e3-445f-a072-0182e9ce98d6` a été rouverte dans le lecteur Mac : **164 PNG / 1 772 paquets**, recalcul complet terminé en **11,3 s**. Il s’agit d’un contrôle fonctionnel de la migration ; les résultats de l’audit métier antérieur gardent leur portée et leurs limites.

## Modèles

Seules quatre chaînes descriptives du checkpoint et un champ de métadonnées ONNX ont changé. Comparaison par chargement PyTorch : **708 tenseurs de state_dict strictement égaux**. Le graphe ONNX sérialisé est strictement identique, SHA-256 `13fa2713cade24269bbeb91e4573b00640e72b7d421c17fa7eae75881f4868bc`.

| Artefact courant | SHA-256 |
|---|---|
| `oria_silmo_fp32.pt` | `a591f2db91a90e98297d8a5f035b037b9745cc88aecace98f434e162c2a63f55` |
| `oria_silmo_fp32.onnx` | `abab2174c8000e219d4d40550beb4506fd1156054b6282e8e8ee34154acd4742` |

Le lecteur accepte les deux empreintes vérifiées du graphe et signale `GRAPH_IDENTICAL_METADATA_RENAMED` pour les captures faites avant cette harmonisation. Une empreinte inconnue reste refusée. Les résultats historiques CPU/XNNPACK concernent les fichiers antérieurs ; aucune nouvelle mesure de performance physique n’est déduite de cette équivalence.

## Contrôles exécutés

- **51 tests JVM** réussis sur le build final, sans échec ni test ignoré.
- **12 tests Mac** réussis, dont le contrôle d’admission des empreintes vérifiées du modèle.
- **4 tests de rejeu Kotlin** réussis.
- Recherche insensible à la casse dans tous les chemins et octets des fichiers versionnés : aucune occurrence des deux anciens noms.
- Même recherche dans chaque entrée décompressée de l’APK final : aucune occurrence.

Les journaux et relevés UI actuels restent locaux dans `validation/new-device-CN46V3M00284/` (`oria-final-*`). Les détails de conservation et d’équivalence sont dans `validation/oria-storage-migration.json` et `validation/oria-model-naming.json`. Le résumé publié accompagne la release. L’historique Git et la première release ne sont pas réécrits. Les sources originales externes sont préservées.
