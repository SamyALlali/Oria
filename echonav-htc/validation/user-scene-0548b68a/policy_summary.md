# Audit déterministe — capture 0548b68a

Rejeu **164/164 décisions identiques**, **25/25 mutations vocales identiques**. Aucune confirmation vocale simulée.

Capture réelle de 60.022 s : 164 PNG, 164 inférences et 164 décisions. Détections Android et `evaluatedAtMs` inchangés ; 500 ms conservées. L’orientation était confirmée. Âge de décision min/médiane/p95/max : 258/280/303/330 ms (p95 rang supérieur).

## Méthode et portée

Les trois sources core Kotlin sont compilées sans modification, avec un adaptateur d’audit séparé. Aucun Gradle/ADB. SHA ZIP `36db9e3ad3a0a0e7c3a70e32ed8919e25fa67aeec997839008cbd20a20e60528` ; même empreinte après lecture. Les fichiers `policy_inputs.jsonl` et `policy_outputs.jsonl` rendent l’ordre de toutes les mutations vérifiable.

`start()` efface perception, mémoire vocale et temporisations, mais conserve les compteurs de durée de vie. Le compteur de piste 23 et le compteur d’alerte 5 sont déduits du premier lot de pistes et de la première éligibilité, puis amorcés dans le moteur de rejeu seulement. Cela ne reconstitue pas une mémoire d’objets antérieure. La génération interne absente des sorties Android n’est pas comparée ; le sessionId vidéo 7 est conservé. Tous les autres champs enregistrés sont comparés, y compris ordre des pistes, boîtes, confirmation, sélection, priorité, zone, texte, éligibilité, état audio et suppression. Les flottants sont comparés à leur valeur Float32 exacte.

Les classements de candidats sont lus dans le moteur rejoué, sans mutation ; ils ne sont pas un champ directement enregistré sur téléphone. `policy_frames.json` détaille chaque image, le classement par priorité, l’ordre vocal avec sélection prioritaire, la mémoire et l’état avant/après. Ce rapport ne juge pas les objets réels dans les pixels : la revue visuelle et la réinférence YOLO sont séparées.

## Alertes réellement demandées

| N° | t (s) | Image / frameId | Piste | Phrase / canal | Âge à la garde (ms) | Résultat |
|---:|---:|---|---:|---|---:|---|
| 1 | 3.103 | 6 / 51 | 23 | Piéton avant-droite / RIGHT | 335 | COMPLETED |
| 2 | 10.869 | 28 / 288 | 32 | Piéton devant / CENTER | 281 | COMPLETED |
| 3 | 13.065 | 34 / 354 | 36 | Piéton avant-droite / RIGHT | 274 | COMPLETED |
| 4 | 15.954 | 42 / 441 | 48 | Piéton avant-droite / RIGHT | 281 | COMPLETED |
| 5 | 19.363 | 51 / 537 | 38 | Piéton avant-droite / RIGHT | 319 | COMPLETED |
| 6 | 22.296 | 59 / 620 | 56 | Piéton devant / CENTER | 297 | COMPLETED |
| 7 | 24.777 | 66 / 703 | 63 | Piéton avant-gauche / LEFT | 303 | COMPLETED |
| 8 | 31.848 | 86 / 918 | 79 | Piéton avant-gauche / LEFT | 284 | COMPLETED |
| 9 | 39.999 | 109 / 1162 | 79 | Piéton avant-gauche / LEFT | 300 | COMPLETED |
| 10 | 44.968 | 123 / 1311 | 86 | Piéton avant-gauche / LEFT | 306 | COMPLETED |
| 11 | 51.919 | 142 / 1521 | 93 | Piéton avant-gauche / LEFT | 268 | COMPLETED |
| 12 | 54.537 | 149 / 1599 | 94 | Piéton avant-droite / RIGHT | 273 | COMPLETED |
| 13 | 59.560 | 163 / 1749 | 103 | Piéton avant-droite / RIGHT | 301 | CANCELLED hors ZIP |

Les 13 phrases et canaux correspondent à leur candidat éligible. Les gardes enregistrées sont entre 268 et 335 ms. Violations de phrase, zone, fraîcheur, réserve audio, portes contrôleur et cooldown : **0**. Une garde journalisée n’est pas une mesure du premier son entendu après tampon Bluetooth ; le contrôleur fait encore son contrôle final après la trace.

Douze résultats Android `COMPLETED` sont enregistrés dans le ZIP. La dernière phrase commence avant la limite de capture : sa fin doit être qualifiée avec la trace de production, sans inventer un callback dans l’archive. La table précise le résultat terminal observé hors fenêtre. `COMPLETED` décrit la lecture Android, pas une écoute humaine enregistrée.

## Tri, stabilité et répétitions

81 pistes temporaires différentes (23–103) et 12 pistes annoncées. Une piste est une association 2D par classe/IoU, pas l’identité certaine d’une personne.

Images où la sélection reste différente de la priorité maximale : [143, 144, 145, 147, 164]. Images où l’alerte choisie diffère de la sélection (autre candidat disponible) : [34]. Le détail avant/après et les mémoires dans le JSON permettent de distinguer maintien 1 100 ms, marge de remplacement 0,28, pacing et cooldown.

8 intervalles entre annonces voisines sont inférieurs à 6 500 ms. Cela n’est pas automatiquement une violation : ce cooldown porte sur une même piste après confirmation ; le rythme global est de 1 000 ms, et une nouvelle piste n’a pas cette mémoire. Les contrôles ci-dessus rejouent ces conditions exactes. Une fragmentation visuelle peut donc provoquer plusieurs annonces d’une même personne réelle sous plusieurs IDs ; seul l’examen des images permet de l’attribuer.

| Motif de suppression | Images |
|---|---:|
| NO_FRESH_CANDIDATE | 51 |
| NEEDS_CONFIRMATION | 23 |
| NONE | 13 |
| AUDIO_IN_FLIGHT | 38 |
| GLOBAL_PACING | 22 |
| SAME_ENTITY_COOLDOWN | 17 |

## Points terrain à corriger après arbitrage

La conformité 164/164 prouve le déterminisme du code actuel, pas une identité réelle fiable. Les observations visuelles ci-dessous viennent de la revue des images par l’orchestrateur et l’agent HTC ; les IoU, compteurs et instants proviennent du rejeu.

**Permutation de personne : images 141–142, frameId 1509→1521.** La piste 93 appartient d’abord à la personne proche à cheveux courts/lunettes, puis est attribuée à une autre personne bouclée entrant à gauche. Le recouvrement avec cette dernière est 0,4899385 contre 0,26461035 avec la personne initiale. Le choix glouton prend le premier et crée 94 pour l’autre. La piste 93 passe de 1 à 2 confirmations et annonce « Piéton avant-gauche » ; la personne initiale, pourtant plus grande dans l’image, vient de devenir 94 et n’a qu’une confirmation. C’est une limite démontrée du critère d’identité, malgré un résultat conforme au tri.

**Répétition d’une même personne : images 51→59, frameId 537→620.** La personne bouclée annoncée avec piste 38 est ensuite annoncée avec piste 56. À frame 598, le recouvrement ancienne 38/nouvelle boîte est 0,21171786, sous 0,25 : création 56. Son score 0,7398 ne confirme pas encore ; frames 606 puis 620 dépassent le seuil et valident deux observations. L’écart entre observations est 2,955 s et entre demandes vocales 2,933 s, seulement 1,547 s après confirmation de la phrase précédente. La nouvelle piste 56 n’hérite pas du cooldown de 38. Il ne s’agit pas d’une violation du compteur existant, mais d’une répétition perceptible que la mémoire par piste ne prévient pas.

**Autre candidat annoncé malgré la sélection : image 34, frame 354.** La piste 32 sélectionnée a priorité 2,8589973, contre 1,8209677 pour 36. La première est sous cooldown (confirmation seulement 1,164 s avant cette décision) ; 36 est donc annoncé. Le moteur ne choisit pas systématiquement le plus gros objet à chaque phrase.

La seule réannonce conservant le même ID est 79 : la nouvelle soumission intervient 6,806 s après sa confirmation précédente, conforme au délai 6,5 s. Les huit intervalles ci-dessous inférieurs à 6,5 s utilisent tous des IDs différents. Ils ne représentent pas tous une répétition de personne : seule la paire 5→6 est attribuée à la même personne par la revue visuelle citée.

| Annonces | Pistes | Écart demandes (s) | Texte identique | Interprétation visuelle |
|---|---|---:|---|---|
| 2→3 | 32→36 | 2.196 | non | Identité réelle non conclue par le seul audit numérique |
| 3→4 | 36→48 | 2.889 | oui | Identité réelle non conclue par le seul audit numérique |
| 4→5 | 48→38 | 3.409 | oui | Identité réelle non conclue par le seul audit numérique |
| 5→6 | 38→56 | 2.933 | non | Même personne selon revue images 537/620 |
| 6→7 | 56→63 | 2.481 | non | Identité réelle non conclue par le seul audit numérique |
| 9→10 | 79→86 | 4.969 | oui | Identité réelle non conclue par le seul audit numérique |
| 11→12 | 93→94 | 2.618 | non | Identité réelle non conclue par le seul audit numérique |
| 12→13 | 94→103 | 5.023 | oui | Identité réelle non conclue par le seul audit numérique |

## Détections présentes mais non éligibles

Les comptes suivants sont des observations de boîtes dans des images, pas des personnes uniques. Les détections sous 0,70 absentes de l’entrée applicative et les objets non détectés sont hors de ce comptage ; ils relèvent de la revue visuelle/ML.

| Qualification des boîtes courantes | Occurrences |
|---|---:|
| Confirmées | 102 |
| Qualifiantes mais seconde confirmation attendue | 39 |
| Confiance sous seuil initial, géométrie admissible | 47 |
| Géométrie sous seuil, confiance admissible | 13 |
| Confiance et géométrie sous seuil | 39 |

Exemples vérifiables dans les PNG : image 2/frame 6, grande boîte piste 23 à 0,9299, une seule confirmation ; image 3/frame 10, même piste à 0,8257 < 0,84, compteur remis à 0 avant d’avoir été confirmé ; image 18/frame 181, piste 25 à 0,9081 mais aire 0,0094 sous le seuil ; image 55/frame 581, piste 50 à 0,8649 mais aire 0,0165 hors zone centrale ; images 76–79/frame 810→841, personne détectée avec confiance suffisante mais petite boîte hors centre, donc refus géométrique. La règle vient d’une approximation RGB de pertinence, pas d’une distance réelle ni d’une garantie qu’un objet visible sera annoncé.

Les sorties `AUDIO_IN_FLIGHT` (38 images), `GLOBAL_PACING` (22) et `SAME_ENTITY_COOLDOWN` (17) expliquent aussi des silences lorsque des candidats sont présents. Ne pas confondre ces temporisations avec un objet perdu par YOLO.

## Dernière phrase et limite de capture

La phrase 13 est soumise à 10902347 ms avec garde 301 ms. La capture s’arrête à 10902809 ms. La trace production prouve ensuite `policy_voice failed` accepté à 10902865 ms (+56 ms), puis `speech_local_cancelled` à 10902870 ms (+61 ms), lors de l’arrêt explicite. Elle a donc été annulée après 523 ms, hors ZIP ; aucune fin `COMPLETED` ni panne spontanée n’est inférée. Les 12 premières lectures terminées côté Android restent distinctes d’une mesure acoustique.

Aucun réglage métier, appariement ou seuil n’a été changé. Les deux défauts d’identité doivent être arbitrés séparément d’un éventuel changement de seuil YOLO ou de priorité sonore.


## Reproduction

```bash
python3 "/Users/sam/Documents/ChatGPT/Hackathon SILMO/echonav-htc/validation/user-scene-0548b68a/policy_audit.py" --zip "/Users/sam/Documents/EchoTest Captures/EchoTest-0548b68a-b9e3-445f-a072-0182e9ce98d6.zip" --production-trace "/Users/sam/Documents/ChatGPT/Hackathon SILMO/echonav-htc/validation/user-scene-0548b68a/production-trace.jsonl"
```

Fichiers : `policy_report.json` (provenance, voix, anomalies), `policy_frames.json` (toutes les images), `policy_inputs.jsonl` / `policy_outputs.jsonl` (mutations exactes), `policy_adapter.kt` (inspection seule du core), `policy_compile.log` (compilation autonome). Aucune règle du prototype n’est modifiée par cet audit.
