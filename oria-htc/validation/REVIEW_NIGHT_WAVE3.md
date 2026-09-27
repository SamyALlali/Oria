# Relecture indépendante — intégrité du replay Mac

27 septembre 2026, référence `c86cea1`. Cette revue concerne l'indexation des captures abîmées, les associations identité/horloge et l'admission des recalculs. Aucun changement du cœur, du modèle ou des seuils ; aucun ADB, Gradle ou redémarrage du serveur de l'utilisateur.

## Défauts de référence reproduits

Les sources de `replay.py` à `c86cea1` ont été extraites sous `/tmp/oria-review-wave3-c86cea1/`. Les dix captures sont synthétiques, avec une PNG de 1 × 1 créée pour le test. Leurs fichiers source conservent leurs SHA-256 pendant l'indexation.

| Cas | Comportement de référence mesuré |
|---|---|
| JSON malformé intérieur | Refus de toute la capture, sans galerie des positions récupérables. |
| Dernière ligne malformée sans retour à la ligne | Ligne retirée avec avertissement ; deux positions deviennent une. |
| PNG manquante | Position conservée, avertissement, analyses enregistrées toujours disponibles. |
| Identité frame dupliquée | Refus de toute la capture. |
| Inférence dupliquée | Dernière inférence choisie silencieusement : confiance 0,72 remplace 0,91. |
| Session numérique 7 contre chaîne `"7"` | Association acceptée sans diagnostic. |
| Inférence à 99 ms, image à 1 000 ms | Association acceptée sans diagnostic ; horloge métier calculée à 1 100 ms. |
| Ordre source 2 000 ms puis 1 000 ms | Positions retriées silencieusement : identifiants `[1, 2]` deviennent `[2, 1]`. |
| Détection à confiance malformée | Détection éliminée silencieusement, observation convertie en liste vide. |
| Horloge source négative | Index accepté sans diagnostic. |

Trois comparaisons A/B ont également été exécutées avec les vrais processus Kotlin, sur ces fixtures uniquement. Les cas horloge source contradictoire et détection malformée terminent chacun en `complete`, avec une position comparée, zéro différence et `frameStatus=ACCEPTED`. Dans le second cas, le moteur reçoit effectivement une liste vide inventée par filtrage de la donnée malformée. Le cas PNG manquante termine avec trois positions comparées ; ce calcul sur détections enregistrées est possible, mais ne valide pas la couverture visuelle.

Trois autres défauts ont été mesurés sur cette même référence :

- Une trace `speech_confirmed` de la session 7 à 1 400 ms est renvoyée dans `audioEvents` de la frame de session 8 à 1 333 ms, car seule la fenêtre temporelle est utilisée. Cela prouve une erreur d'association de traces ; aucun son réel ni confirmation acoustique n'est déduit.
- Un `start.policyAtMs` à 5 000 ms suivi d'une observation à 1 000 ms et d'un résultat à 1 100 ms donne une comparaison `complete`, une position comptée comparée, alors que la réponse Kotlin porte `CLOCK_REVERSED`.
- Une fin de capture explicite à 900 ms, antérieure à la frame à 1 000 ms et à son résultat à 1 100 ms, est également acceptée par le traitement de référence.

Preuves temporaires : `baseline-results.json`, `baseline-job-results.json`, `baseline.log` et `baseline-jobs.log` dans le répertoire ci-dessus. Les résultats A/B créent seulement des dérivés dans les fixtures temporaires.

Les cas complémentaires sont conservés dans `baseline-extra-results.json` et `baseline-clock-edges.json`.

## Décision de comportement convenue

Conserver chaque ligne source non vide à sa position, y compris les lignes malformées, avec `sourceLine` et `sourceOffset`. Les identités et horloges invalides sont nulles, sans conversion en zéro ou chaîne. Les états `available`, `not_recorded`, `invalid` et `ambiguous` distinguent absence normale d'analyse et corruption.

Le recalcul est refusé globalement avant création du job si des métadonnées ou des événements rendent les identités, horloges ou analyses ambiguës/invalides. La revue ne recommande donc pas de segmenter silencieusement la capture ou de réinitialiser le moteur autour d'un trou. Cette décision évite une comparaison partielle présentée comme équivalente à une session complète.

Une PNG indisponible seule peut autoriser la comparaison sur détections enregistrées, si les identités, horloges et analyses restent fiables : `visualCoverageComplete=false` doit rester visible et le recalcul ONNX est interdit. Une image sans inférence enregistrée reste `not_recorded`, puis est ignorée par le moteur sans produire une observation vide. Les positions réellement comparées doivent être distinguées du total, notamment lorsque zéro position est comparable.

Les décisions et événements affichés ne doivent pas s'attacher arbitrairement à une entrée dont l'analyse a été invalidée. Les fichiers originaux restent immuables ; toute information diagnostique est dérivée.

## Verdict final

**Aucun bloqueur restant sur le gel relu.** Les défauts d'association, de conservation des positions et de chronologie mesurés sur la référence sont corrigés. Le recalcul est refusé avant la création d'un job lorsque ses entrées sont invalides ou ambiguës. Les décisions et traces audio ne sont plus attribuées à une entrée dont l'analyse est invalidée, ni à une autre session vidéo.

### Référence contre résultat final mesuré

| Cas | Référence `c86cea1` | Gel corrigé |
|---|---|---|
| JSON malformé intérieur | Capture entière refusée | Trois positions consultables ; celle du milieu est `invalid`, jobs refusés. |
| EOF tronqué | Dernière position retirée | Deux positions conservées ; EOF `invalid`, jobs refusés. |
| PNG manquante seule | Analyse et A/B possibles, avertissement de PNG | Analyse numérique conservée ; trois positions effectivement comparées en mode enregistré, `visualCoverageComplete=false`, ONNX refusé. |
| Identité frame dupliquée | Capture entière refusée | Les deux positions sont `ambiguous`, jobs refusés. |
| Inférence dupliquée | Dernière inférence choisie | Aucun choix arbitraire, analyse `ambiguous`, jobs refusés. |
| Session chaîne contre entier | Jointure acceptée | Aucune inférence jointe ; événement invalide, jobs refusés. |
| Horloge source de l'inférence différente | Jointure et moteur acceptés | Analyse `invalid`, jobs refusés avant moteur. |
| Ordre source non chronologique | Index retrié | Ordre `[1, 2]` conservé ; données inspectables, jobs refusés pour chronologie. |
| Détection malformée | Observation vide fabriquée puis acceptée | Analyse `invalid`, aucune inférence attribuée, jobs refusés. |
| Horloge négative | Index accepté | Horloge publique nulle avec diagnostic, jobs refusés. |
| Trace audio de l'ancienne session | Attachée par fenêtre temporelle à la suivante | Exclue de `audioEvents` de la nouvelle session. |
| Décision après inférence ambiguë | Décision toujours jointe | `recordedInference=null`, événements attribués vides. |
| Début après observation / fin avant dernier résultat | Comparaison terminée malgré incohérence | Deux modes refusés avant création de job. |

Deux cas supplémentaires soulevés à la relecture finale sont aussi refusés : événement `start` présent sans horloge, et alias `resultAgeMs`/`ageMs` contradictoires. Un événement `start` totalement absent conserve l'initialisation simulée explicite au début de la capture ; il n'est pas confondu avec un événement présent mais incomplet.

### Vérifications indépendantes exécutées

- **67 assertions** sur les dix fixtures initiales et leurs jointures : positions, ordre, états, refus synchrones des deux modes sans job résiduel, conservation des empreintes source, séparation audio des sessions et retrait des décisions ambiguës.
- **20 assertions supplémentaires** : refus des quatre cas de chronologie début/fin/âge ; vrai A/B Kotlin autorisé sur la capture dont seule une PNG manque ; vrai A/B Kotlin d'une position saine sans inférence. Ce dernier produit `comparedFrames=0`, `skippedFrames=[0]`, deux politiques explicitement `skipped` et aucun événement audio simulé. Il ne produit pas d'observation vide dans le moteur.
- **15 tests `test_capture_integrity` du propriétaire relancés par le reviewer**, tous verts : ils consomment notamment les onze cas du corpus partagé, vérifient l'UTF-8 strict, les nombres non finis, les alias, la limite exacte de 2 Mio hors LF, les chemins trop longs et la modification des fichiers après indexation.
- **3 tests du générateur partagé relancés**, tous verts : positions et octets, PNG synthétiques avec CRC, déterminisme, refus d'écraser un répertoire. Le corpus et son oracle ont également été relus. Ils distinguent correctement identité, cohérence numérique et disponibilité visuelle, sans revendiquer une parité d'affichage Android/Mac inexistante pour les PNG manquantes.
- **Tests JavaScript d'interaction relancés**, verts. Les positions invalides restent navigables ; les résultats et RAW précédents ne réapparaissent pas sur une entrée ambiguë. Une politique `skipped` affiche « Position non comparée » ; une synthèse à zéro position comparée annonce qu'aucune comparaison n'est possible. La couverture visuelle incomplète reste affichée dans le résultat A/B.

Le refus des fichiers modifiés repose sur les signatures taille/mtime de l'index et des PNG vérifiés, en plus des contrôles de chemin. Il ne constitue pas une preuve cryptographique contre un processus hostile qui modifierait les octets en restaurant volontairement ces métadonnées.

### Portée et limites

Les 87 assertions des deux harnais indépendants correspondent à des vérifications de propriétés regroupées sur des fixtures synthétiques ; ce ne sont pas 87 scènes terrain. Les vrais moteurs Kotlin utilisés ici reçoivent des détections synthétiques enregistrées, sans passage ONNX. Les comparaisons n'apportent aucune preuve d'orientation réelle, de qualité du modèle, de son entendu ni d'endurance matérielle. Aucun fichier de capture utilisateur n'a été modifié ou utilisé pour ces expériences.

Le corpus a été consommé côté Mac et son générateur vérifié ; ce passage ne revendique pas son exécution instrumentée sur HTC. La recette des captures réelles et la suite globale de l'orchestrateur restent des preuves distinctes.

Résultats supplémentaires conservés sous `/tmp/oria-review-wave3-c86cea1/` : `candidate-results.json`, `candidate-extra-results.json` et leurs scripts `candidate_review.py`, `candidate_extra.py`. Les dérivés de calcul restent dans ces captures temporaires ; les fichiers source gardent leurs SHA-256.

## Addendum final — fermeture de la capture après archivage

Dernier diff relu uniquement dans `static/app.js` et `test_ui.js`. Après confirmation d'archivage réussie, la garde UUID/version est conservée ; les requêtes et callbacks de l'ancienne sélection sont invalidés avant le nettoyage. `clearFrameView()` masque le canvas, vide les décisions, traces, détails RAW et diagnostics par image. Le complément efface les diagnostics de session et le sous-titre, remet la position et la timeline à `0 / 0`, remplace les horloges par des tirets et affiche qu'aucune capture n'est ouverte. Les contrôles restent désactivés jusqu'à une nouvelle sélection.

Le test de régression relu précharge les champs avec des marqueurs d'ancienne capture, puis vérifie leur disparition, la position zéro et le canvas masqué après le bon appel d'archivage. Les vérifications existantes de confirmation périmée restent présentes. **Aucune réserve sur ce dernier raccord UI.** Cette relecture n'a relancé aucune suite et n'a modifié aucun code.

L'orchestrateur confirme séparément la réussite de cette régression Node et de la recette navigateur, ainsi que les 62 tests Python du gel backend et la parité saine de 164 positions / 240 détections. Ces résultats d'intégration lui sont attribués ; ils ne sont pas présentés comme de nouvelles exécutions du reviewer. Les empreintes ci-dessous sont actualisées après ce dernier diff.

## Empreintes du gel relu

| Fichier | SHA-256 |
|---|---|
| `oria-lab-desktop/replay.py` | `472a20247f704232902b23ff07e6815817c29f336bd621166e9b95259377eaa7` |
| `oria-lab-desktop/server.py` | `8a72718f2282350c6d87836a855268f838f4b076d994c01b79a14d17f414edc3` |
| `oria-lab-desktop/static/app.js` | `6f73cd01e37e97992d47f579cacc472f733257fb1f523261bd91693c0bb3d24d` |
| `oria-lab-desktop/test_capture_integrity.py` | `ac96ffae7133c48f6629bd1e0aa528182a9ff5c7418b20488f32ece9d8bf041b` |
| `oria-lab-desktop/test_ui.js` | `659e2153789628c73607834cebde458f83d81e7af0066479e2e24031e36d0eb2` |
| `fixtures/capture-integrity/cases.json` | `6ccd87c41d3f2ddc98397a6468797af5712e21e861d6368ee0bd2b2ddb311a97` |
| `fixtures/capture-integrity/materialize.py` | `82ee45eaa8f796bd8e596958ea57d4df80c2dcf5d167b2df02ce4b8e236fc621` |
| `fixtures/capture-integrity/test_materialize.py` | `25bfe78c210df32f37a152c31e5e625d65cc82f793a14a6b58c3d947097aa31b` |
