# Fixtures communes et revue d’intégrité — troisième passage

27 septembre 2026. Référence : décision D27. Périmètre de cet agent : nouveau corpus synthétique partagé, oracle explicite, relecture de l’UI Mac et mesure ciblée d’annulation JVM. Aucun ADB, Gradle, changement Android de production/test existant, ni mutation d’une capture privée.

## Corpus livré

Racine retenue avec l’orchestrateur : `oria-htc/fixtures/capture-integrity/`, dans le dossier de fixtures déjà commun au projet. Aucun dossier `shared/` concurrent n’est ajouté.

`cases.json` définit 11 cas en données lisibles. Les identités, numéros physiques de ligne et horloges attendus sont écrits explicitement ; aucun résultat d’un lecteur de production n’est utilisé pour les fabriquer. `materialize.py` produit des sessions v1 dans un nouveau dossier uniquement, avec PNG synthétiques 2×2 générées par la bibliothèque standard. Aucun binaire, modèle ni fichier utilisateur n’est inclus. Les attentes et empreintes sont placées hors des dossiers `capture/`.

Le README du corpus décrit les champs de l’oracle, la portée des causes et les règles de consommation. Les cas couvrent les positions perdues, absence de PNG, ordre source non chronologique, horloges de traitement distinctes des observations, IDs string/bool/null/absents, alias incohérents, doublons de frames et d’inférences, EOF tronqué, UTF-8 et JSON invalides, ligne >2 Mio, boîte invalide et chemins sortants.

**La cohérence des données numériques est distincte de la disponibilité visuelle.** Une PNG absente n’invalide pas à elle seule une inférence enregistrée aux bonnes identité/horloge. Le lecteur Android actuel masque son analyse, tandis que le Mac peut la laisser inspectable et interdire ONNX. Cette différence est documentée : l’oracle ne prétend pas à une parité d’interface inexistante. Une identité ambiguë ou un événement incohérent ne reçoit en revanche jamais une association arbitraire.

Vérifications acquises sur le générateur : **3 tests Python passent en 0,054 s** — positions/octets/oracles et PNG (CRC et pixels), déterminisme intégral, refus d’écraser un répertoire existant. Ces tests valident le corpus et sa matérialisation, pas les lecteurs de production. La consommation des 11 cas par les tests Mac relève du propriétaire backend ; l’intégration instrumentée du corpus Android n’est pas exécutée dans ce passage.

Revue indépendante du corpus par l’agent tracking : aucun bloqueur. Les trois tests du générateur ont été relancés dans un dossier temporaire et passent. Le reviewer confirme la distinction entre identité bien formée, unicité/cohérence et présence des pixels, ainsi que l’absence de permission implicite de rejouer des horloges hors ordre. Cela ne constitue pas une nouvelle exécution Android.

## Relecture ciblée de l’UI Mac

Relus en lecture seule : endpoint `Session.image_path(index)` du serveur, `static/app.js`, `index.html`, styles et cas `test_ui.js`. Les clés de sélection et versions des requêtes conservent la position source ; les résultats précédents sont nettoyés, les erreurs de PNG restent visibles et les horloges absentes/non chronologiques empêchent l’autoplay. Les boutons distinguent l’inspection des données enregistrées et le besoin de PNG pour ONNX.

Un défaut a été **reproduit** avec un petit harnais Node isolé, à partir du faux DOM des tests existants : une réponse calculée déjà prête pouvait afficher ses RAW alors que `recorded.integrity` déclarait l’entrée invalide. Les décisions et l’audio étaient déjà protégés ; le dernier rendu `rawDetails` ne l’était pas. L’orchestrateur a ajouté le garde `unsafe ? {} : …` et un cas de régression. Après correction, la reproduction indépendante retourne `rawDetails={}`, `decision={}`, `audio=[]`. Aucun autre bloqueur dans ce raccord UI ciblé ; cela ne remplace pas la revue complète du backend encore en intégration.

## Annulation du chargeur Android — mesure JVM ciblée

La durée de 14,178 s observée précédemment sur la fixture HTC de 21 622 images pouvait faire craindre que quitter la galerie attende la fin du chargement. Le chargeur existant vérifie cependant l’annulation pendant les lectures et le `skipValue` du JSON.

Une mesure indépendante hors Android réutilise le bytecode du chargeur de production et le vrai `JsonReader` du SDK sur JVM Mac, avec la fixture synthétique de 21 622 entrées. Une autre tâche demande l’annulation après environ 100 ms ; le tas JVM est limité à 128 Mio. **5 essais sur 5 propagent `CancellationException`, aucun ne retourne un index.** Latences demande → sortie : 0,149 ; 0,173 ; 0,059 ; 0,036 ; 0,042 ms (maximum 0,173 ms). Les sources et la capture synthétique restent inchangées.

Aucun échec d’annulation n’a été reproduit. Cette mesure ne valide pas Compose, le décodage natif d’une PNG, une annulation réellement demandée après 14 s ni la latence sur HTC ; aucune modification Android supplémentaire n’en est déduite. Harnais et résultat temporaires : `/tmp/oria-gallery-cancel-measure/CancelMeasure.java`, `result.json`. Le rapport de galerie du passage précédent conserve les limites précises de la vérification coopérative autour du décodage natif.
