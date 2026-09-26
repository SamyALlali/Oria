# Audit de ta scène EchoTest — 26 septembre 2026

**La scène de 17 h 16 est copiée et ouverte sur le Mac. Le tri reproduit exactement les règles implémentées, mais la perception et le suivi ont des limites visibles.** La nouvelle stéréo 70/30 est installée séparément sur le HTC.

## Périmètre réellement vérifié

Capture `0548b68a-b9e3-445f-a072-0182e9ce98d6` : **60,022 secondes**, 164 images sélectionnées et 164 décisions, 1 772 paquets H264. APK enregistré `9dbaec1f…` ; cette capture précède le changement de stéréo. Original préservé sur le téléphone et ZIP intact dans `~/Documents/EchoTest Captures/`. Empreinte du ZIP : `36db9e3ad3a0a0e7c3a70e32ed8919e25fa67aeec997839008cbd20a20e60528`.

Les trois agents et l'orchestrateur ont réparti l'audit : modèle et données ; moteur et événements vocaux ; inspection visuelle et revue croisée ; intégration/stéréo. **Les 164 images sélectionnées ont toutes été inspectées visuellement**, sur 14 planches, avec agrandissement des 13 cibles annoncées et des cas de suivi. Le décodage des 1 771 images H264 passe, mais il ne s'agit pas d'une revue visuelle individuelle de toutes les images de la vidéo à 30 fps.

| Vérification | Résultat |
|---|---|
| Décisions du vrai moteur Kotlin, mêmes détections et horloges | **164/164 identiques** : pistes, ordre, priorité, sélection, éligibilité, texte, direction et suppression. |
| Mutations vocales réelles dans le ZIP | **25/25 identiques**, sans confirmation simulée dans cet audit. |
| Fraîcheur | 164/164 acceptées ; âge décision p95 interpolé **302,55 ms**, maximum **330 ms**. |
| Début des 13 annonces | Gardes toutes autorisées, âges **268–335 ms** ; aucune violation de zone, phrase ou cooldown du code. |
| Fin audio | **12 COMPLETED côté Android** ; la 13e est annulée lors de l'arrêt, juste après la fin de capture. La trace complémentaire le prouve. Pas de preuve acoustique ajoutée. |
| Recalcul YOLO Mac | 164 PNG réinférées ; **240/240 détections ≥0,70 concordantes**, aucune bascule de seuil. |
| Parité de toutes les sorties brutes | **147/164 images passent**, 18 lignes de très faible confiance différentes ; verdict brut FAIL conservé. |
| Inspection des annonces | Les 13 boîtes annoncées visent des personnes visibles ; directions cohérentes avec ces boîtes. |

Le moteur possède des compteurs conservés entre sessions. Le rejeu exact documente leur amorçage déduit des premières sorties (piste 23, alerte 5) ; il ne restaure pas une mémoire d'objets cachée. L'interface « Recalcul sur ce Mac » utilise, elle, des confirmations audio simulées : ce n'est pas ce mode qui prouve l'égalité des 25 événements réels.

## Ce qui mérite une correction ultérieure

1. **Permutation d'identité à 51,28 → 51,65 s, frames 1509 → 1521.** La piste 93 suit d'abord une personne proche, puis semble passer à une autre personne entrant à gauche pendant la rotation. Les confirmations passent de 1 à 2 et déclenchent « avant-gauche ». La direction est bonne pour la boîte courante, mais les deux observations ne semblent pas concerner la même personne. Le suivi choisit la boîte de gauche avec IoU 0,490, contre 0,265 pour la continuité visuelle de la première personne. [Avant](visual_tracking_1509.jpg) · [Après](visual_tracking_1521.jpg).
2. **Répétition d'une personne sous deux IDs.** Les annonces des frames 537 et 620 concernent visuellement la même personne, avec pistes 38 puis 56. Elles sont espacées de 2,933 s à la soumission, malgré le délai de répétition de 6,5 s : ce dernier est attaché à l'ID. Entre les frames 588 et 598, l'IoU tombe à 0,212 sous le seuil 0,25. Ce n'est pas une violation du code, c'est une limite du suivi sous rotation.
3. **Faux deux-roues sur le mobilier**, frames 1268/1278, 43,22–43,58 s : scores 0,775 puis 0,942 sur un ensemble table/chaise/personnes. La confirmation temporelle empêche l'annonce dans cette scène. [Planche 10](ml_contact_10.jpg).
4. **Personne imprimée sur une affiche détectée**, notamment frames 1716/1726. Aucune annonce attribuée à l'affiche dans ce lot, mais ce silence ne garantit pas son rejet si le regard reste fixe. [Planche 14](ml_contact_14.jpg).
5. **Personnes assises manquées ou intermittentes**, notamment frames 150/161/171, 1025, 1289 et 1681. Le moteur ne peut pas trier un objet que YOLO ne lui transmet pas. Les tables, chaises et câbles ne sont pas des classes couvertes. Le mot « Personne » décrirait mieux les cibles assises que « Piéton » ; le vocabulaire reste inchangé ici.

Les critères de confirmation ont donc filtré des erreurs, sans résoudre tous les problèmes de perception. Aucune mesure de rappel/précision, aucune distance ni niveau de danger n'est déduit de cette seule revue qualitative. **Aucun seuil ni règle métier n'a été modifié pour faire passer la scène.** Les cas ci-dessus sont conservés pour comparer une future correction de suivi sur des séquences annotées ; déplacer globalement le seuil IoU peut améliorer un cas et dégrader l'autre.

## Stéréo livrée

APK installé `5109439ff9f3ea481ebdf59e7ad284af02cad374d66330ee394c53852b6d42ed` :

| Zone | Amplitude gauche | Amplitude droite |
|---|---:|---:|
| Avant-gauche | 70 % | 30 % |
| Avant-droite | 30 % | 70 % |
| Devant | 100 % | 100 % — volume central précédent conservé |

Ces valeurs sont des gains PCM, pas des pourcentages d'énergie acoustique. **51 tests JVM passent**, dont dix tests PCM couvrant tous les 65 536 échantillons signés, la symétrie, les limites, la durée et l'absence de modification du cache. Mise à jour compatible, sans effacement des captures. La tentative de connexion pour écoute est restée « Lunettes déconnectées » ; le confort de ce mélange reste à confirmer dans les lunettes. La précédente validation humaine concernait le mélange 100/0.

Un défaut d'affichage Mac repéré pendant cet audit a également été corrigé : en attente du recalcul, les événements audio enregistrés ne sont plus présentés comme simulés. Le parcours a été revérifié sur la frame 51, puis les 164 images recalculées en 10,9 s sur ce Mac.

## Refaire l'inspection

Dans le [lecteur Mac](http://127.0.0.1:62044/), choisir **Observations du téléphone** pour les décisions/annonces originales, puis la timeline et les flèches. Les images 141/142 montrent le cas de suivi ; les images 119/120 le faux deux-roues. « Recalcul sur ce Mac » permet une expérience avec d'autres paramètres et des transactions audio simulées, tout en conservant l'original.

- [Tableau CSV des 164 images](frames_auditees.csv), avec décision, motif de silence et remarques visuelles.
- [Rapport déterministe et table des 13 annonces](policy_summary.md) ; [résultats des 164 images](policy_frames.json) ; [script reproductible](policy_audit.py).
- [Rapport ML](ml_summary.md) ; [comparaisons par image](ml_frames.jsonl) ; [script reproductible](ml_audit.py).
- [Revue visuelle images 1–84](visual_first_half.md) ; [revue images 85–164](visual_second_half.md).
- Cibles réellement annoncées, encadrées en rose : [1–4](visual_alert_sheet_01.jpg), [5–8](visual_alert_sheet_02.jpg), [9–12](visual_alert_sheet_03.jpg), [13](visual_alert_sheet_04.jpg).
