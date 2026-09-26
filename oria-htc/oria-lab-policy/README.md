# Politique Oria sur Mac

`run_policy.py` compile directement les trois fichiers Kotlin de `android-project/.../oria/core/` et un adaptateur JSONL. Le moteur n'est pas réécrit en Python. Le JDK et les dépendances Kotlin/Gson proviennent de l'outillage Android local ; aucune résolution réseau n'a lieu au lancement. Le cache change lorsque le code ou les dépendances changent.

```bash
python3 oria-lab-policy/run_policy.py --build-only
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

Les quatre tests exécutables couvrent confirmation et cooldown, direction, reproductibilité, rejet de résultats périmés et de callbacks d'une ancienne session, ainsi que validation des paramètres. Les tests complets du moteur restent ceux d'Android.
