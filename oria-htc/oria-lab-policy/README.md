# Politique Oria sur Mac

`run_policy.py` compile directement les fichiers Kotlin de `android-project/.../oria/core/` et un adaptateur JSONL. Le moteur n'est pas réécrit en Python. Le JDK et les dépendances Kotlin/Gson proviennent de l'outillage Android local ; aucune résolution réseau n'a lieu au lancement. Le cache change lorsque le code ou les dépendances changent. Les préparations simultanées sont verrouillées et le JAR est publié atomiquement.

```bash
python3 oria-lab-policy/run_policy.py --build-only
python3 oria-lab-policy/run_core_tests.py --report /tmp/oria-core-tests.json
cd oria-lab-policy
python3 -m unittest -v test_policy.py
```

Protocole version 1, une requête et une réponse par ligne. L'horloge est celle de la scène enregistrée, jamais celle du Mac. Le processus conserve les pistes et la mémoire vocale entre les images.

- `start` : `sessionId`, `atMs`, `config` partielle facultative (champs de `RgbAlertConfig`). Un changement de configuration exige un nouveau processus de rejeu.
- `frame` : `requestId`, `sessionId`, `frameId`, `observedAtMs`, `nowMs`, `detections` (`classId`, `confidence`, `box` normalisée). Retour : `evaluation`, `eligibleAlert` et `alertText` si une annonce est éligible.
- `submitted` : `alertId`, `nowMs`. Retour : `accepted`, `ticketId` si accepté.
- `confirmed`, `failed`, `ambiguous` : `ticketId`, `nowMs`. Retour : `accepted`.
- `current` : `nowMs`. Retour : état de perception sans nouvelle observation.
- `stop` : invalide session et perception ; une demande en vol devient ambiguë, comme sur Android.

Un lecteur peut simuler une durée vocale pour comparer des réglages, à condition de l'indiquer. Une confirmation simulée ne prouve pas l'écoute réelle. Pour naviguer en arrière, recalculer la scène depuis son début ou consulter des résultats déjà calculés depuis le début : ne pas réinjecter une vieille image dans un moteur avancé.

Le paramètre `config.trackingMode` accepte `LEGACY_IOU` (défaut préservé) et `STABLE_RGB_V2` (expérimental). Une valeur inconnue est rejetée. Le mode V2 utilise une continuité géométrique prudente et réinitialise les confirmations ambiguës ; il ne reconnaît pas l'identité physique d'une personne. Le champ supplémentaire `tracks[].associationStatus` explique la dernière association : `NEW`, `LEGACY_IOU`, `UNAMBIGUOUS_IOU`, `MOTION_RECOVERY` ou `AMBIGUOUS_NEW`. Il est purement descriptif et ne modifie pas les coordonnées. Pour une piste non visible, il décrit sa dernière observation, pas une prédiction courante.

Les six tests exécutables couvrent confirmation et cooldown, direction, reproductibilité, rejet de résultats périmés et de callbacks d'une ancienne session, validation des paramètres et des modes de suivi, ainsi que deux compilations simultanées avec cache vide. Les tests complets du moteur restent ceux d'Android.

`run_core_tests.py` exécute aussi les tests purs du cœur avec Kotlin/JUnit et les dépendances déjà présentes, sans Gradle ni téléphone. Il partage le verrou de compilation et produit facultativement un rapport avec les empreintes des sources. `--long-session-only` limite l'exécution aux invariants de sessions longues. Les horloges de 24 heures sont simulées ; ce test ne mesure pas une journée de fonctionnement matériel. Le test qui écrit les résultats des fixtures Swift reste réservé à la vérification Gradle complète.

La [comparaison V2](../validation/TRACKING_V2.md) conserve les mêmes détections, horloges et règle de confirmation audio simulée pour les deux modes. Les événements audio réels ne sont jamais attribués au candidat.
