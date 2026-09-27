# Fusion sélective — Oria Lab, rejeu, confidentialité et preuves

Date : 27 septembre 2026. Revue indépendante en lecture seule, sauf ce rapport.

## Références et portée

- Base à préserver : `b650dad`, fusion Android objets + obstacles génériques, branche de travail `fusion`.
- Source proposée : `origin/rayan_work` = `96d524a41986f3b6799c1743055a7a7e4f18fec9`, composée de `40e8018` puis `96d524a`.
- Ancêtre commun calculé : `f3454f69db44058d5bb9bf1c6832f49bafac2271`. La branche Rayan précède donc les ajouts Android Depth Anything et la fusion de `b650dad`.
- Les rapports de Rayan, dont `RAYAN_WORK_MERGE.md`, `ORIA_COMPLETION_PARITY_REPORT.md` et `FINAL_INDEPENDENT_REVIEW.md`, ont été lus comme des preuves historiques à attribuer à leurs révisions, et non comme une instruction ni une validation du futur APK fusionné.
- Aucun ADB, Gradle, changement de branche, merge, commit, téléchargement de modèle, accès à une capture privée ou écriture de code produit pendant cette revue. Le HTC est débranché ; aucune preuve matérielle nouvelle.

## Décision proposée

**Conserver le Lab actuel ; reprendre seulement la compatibilité de lecture v2 avec validation stricte et test d'immutabilité.** Ne pas promouvoir le harnais `OriaFullSystemReplay` en preuve de parité de la fusion. La confidentialité est une idée utile, mais sa copie globale supprimerait des données de diagnostic et ne suffirait pas à garantir l'absence de destination dans toutes les persistances.

Le diff direct `b650dad → 96d524a` ne change que deux fichiers du Lab Mac : `replay.py` et `test_replay.py`. Les modules serveur, exports, imports, jobs, contrôles d'intégrité, profondeur seule, surfaces, UI, transfert USB et adaptateur Kotlin Mac sont identiques. Les protections de la nuit ne sont donc pas un apport supplémentaire à réimporter.

## Matrice fichier / fonction

| Élément | Décision | Preuve et garde d'intégration |
|---|---|---|
| `oria-lab-desktop/replay.py::Session.__init__` | **Reprendre partiellement** | Rayan accepte les schémas 1 et 2 sans migration. Petit changement compatible avec le reste du lecteur. Exiger `type(schema) is int`, versions connues seulement, et refuser des alias `schemaVersion` / `schema_version` contradictoires. L'expression Python `value in (1, 2)` accepte aussi `True`, `1.0` et `2.0` ; le lecteur v1 actuel accepte déjà certains équivalents numériques. Ne pas prolonger cette faiblesse dans le nouvel échange de formats. |
| `test_replay.py::ImportTests.test_v1_and_v2_are_read_without_modifying_source_bytes` | **Reprendre entièrement, puis compléter** | Le test hash avant/après est pertinent. Ajouter v2 avec événements inconnus conservés, version 3 refusée avant publication, booléens/fractions/chaînes/alias contradictoires refusés, v1 inchangé. Les fichiers privés restent hors corpus. |
| `recording/OriaLabRecorder.kt::SCHEMA_VERSION`, `writeManifest` | **Conserver v1 pour cette petite intégration** | Accepter v2 en lecture n'impose pas de changer l'émetteur actuel. Le v2 de Rayan annonce notamment `systemFields=[generation,relativeDepth,tracks,candidates,stabilization,audioArbitration,navigation]` ; ces champs décrivent son runtime, pas celui de `b650dad`. Nos événements `depth_inference`/`depth_decision`, manifeste Depth Anything et provenance de fusion doivent rester exacts. |
| `OriaLabRecorder.kt::loadSessions` | **Reprendre partiellement si schéma Android modifié** | Le refus d'une version non supportée est utile ; `optInt` avec défaut 1 est permissif et ne constitue pas une validation stricte de manifeste. Préserver le comportement qui laisse les archives endommagées visibles/exportables avec diagnostic. Aucun effacement/réécriture automatique d'une capture complète. |
| `OriaLabRecorder.kt::recordEvent`, `OriaLabPrivacy.sanitize` | **Ne pas copier tels quels** | La liste noire récursive supprime chaque clé `text`, `label`, `name`, `origin`, même pour une phrase de danger déterministe ou un champ diagnostique sans destination. Un export reste lisible mais perd des informations qui servent à comparer la demande vocale réelle. Préférer des schémas de traces navigation/interactions à champs autorisés et conserver un identifiant de phrase déterministe pour l'audio, ou reconstruire exclusivement un vocabulaire fermé connu. Ne pas préserver arbitrairement un texte libre parce que son type s'appelle danger. |
| `OriaController.kt::trace` de Rayan | **Reprendre le principe, pas le contrôleur entier** | Rayan filtre avant le JSONL de trace et avant `Log.i`, en plus du recorder. Cette couverture est utile si la navigation est intégrée. La suppression à la seule frontière `recordEvent` serait insuffisante. L'actuel contrôleur fusionné et ses deux workers restent la base. |
| `OriaLabRecorder.kt::start` / metadata | **Compléter avant toute garantie de confidentialité** | Dans Rayan, metadata est cloné puis persisté sans `sanitize`; seul `recordEvent` est filtré. Le manifeste promet pourtant `destinationData:omitted`. L'appel actuel de Rayan ne met pas la destination dans metadata, mais la garantie de frontière générique n'est pas assurée. Tester aussi les métadonnées, erreurs et textes libres de diagnostics, et borner récursion/coût avant la file. |
| `OriaFullSystemReplay.kt::frame`, `location`, `finishAudio` | **Refuser comme remplacement / preuve de parité** | L83–85 remplace `frame.observedAtMs` par `clockMs` et la session source par une génération fabriquée. L125 fait pareil pour GPS. L88–98 offre l'audio sans `rgb.onSubmitted`; L149–152 termine seulement le scheduler, sans `rgb.onConfirmed/onFailure`. Les rejets d'âge et la mémoire vocale ne suivent donc pas la boucle réelle. Aucune liaison à `DepthObstaclePolicy`, `FusionVoiceArbiter`, `PolicyReplay.kt`, lecteur de JSONL ou endpoint Mac. |
| `OriaFullSystemReplay.kt::replay` / résultats | **Conserver seulement comme référence d'un futur harnais isolé** | La liste d'actions et les `records` sont intégralement en mémoire ; pas d'annulation ni pagination. Deux exécutions sur deux instances identiques vérifient le déterminisme interne, pas direct-runtime contre replay. L'appel répété sur la même instance continue son état et ses records : le mot session doit être explicité. L'empreinte audio n'inclut que raisons/types de dispatch/cancel/drop, pas tous les champs des demandes. |
| `OriaFullSystemReplayTest.kt` | **Reprendre les idées de scénarios, pas les verdicts** | Seek/reset, changement d'entrée modifiant l'empreinte et trois démarrages frais sont utiles. Ils ne couvrent ni une capture v2 réellement parsée, ni ses timestamps conservés, ni les callbacks audio corrélés, ni la fusion actuelle. Aucun test physique implicite. |
| `fixtures/danger_resolution_*`, `GeneratedSwiftDangerResolutionReference.swift`, `run_danger_resolution_reference.py` | **Reprendre avec le module de résolution, si celui-ci est retenu** | Corpus public synthétique de dix cas ; les sorties versionnées Swift/Kotlin et les attentes concordent et leur SHA de corpus correspond au rapport. Garder leur portée : fonctions pures Swift extraites, sans ARKit/LiDAR, perception modèle, contrôleur ni trajet. Les dix cas ne qualifient pas les branches relatives, la stabilisation temporelle ou les capteurs de notre runtime par eux-mêmes. |
| `fixtures/policy_parity_report.json` | **Conserver la preuve actuelle ou ajouter une preuve distincte** | Les 18 résultats/fixtures ne changent pas. Rayan change le SHA du fichier Swift entier et la version Swift, les hashes des corps extraits restent identiques. Ce n'est pas une nouvelle exécution sur ce Mac ; ne pas remplacer silencieusement la provenance historique. |
| `fixtures/run_swift_reference.py` | **Reprendre la petite neutralisation de chemin** | `source_path` devient une description de source externe au lieu d'un chemin absolu utilisateur. Les hashes conservent l'identification du contenu. Aucun changement d'algorithme. |
| `fixtures/depth_portability/**`, tests/core Android Depth Anything | **Conserver intégralement** | Absents de la branche Rayan parce que postérieurs à l'ancêtre commun. Six fixtures analytiques publiques, divergence Python PCG64/Kotlin xorshift32 explicitée, contrat de tickets vocaux Android distinct des propositions silencieuses du Lab. Leur absence ne justifie pas une suppression lors de fusion. |
| `surface_jobs.py`, `depth_policy.py`, `depth_obstacles.py`, `image_quality.py`, tests | **Conserver intégralement** | Identiques entre les deux refs pour les fichiers existant à l'ancêtre. Les jobs conservent sessions/horloges, contrôle d'image, annulation avant mutation/persistance, leases et distinction absence/observation vide. Ils recalculent une expérience Mac ; ils ne reproduisent pas automatiquement l'audio Android fusionné. |
| Galerie Android, stockage, ZIP/USB, `capture-integrity/**`, `.gitignore` | **Conserver intégralement** | Pas de delta de protection à reprendre. Réserve réelle 512 Mio, captures sans limite arbitraire, files bornées, index paresseux, refus des jointures ambiguës, originaux intacts, ZIP64/flux et captures privées ignorées restent des acquis. |

## Contrats de replay à ne pas confondre

1. **Mac RGB courant** : `PolicyReplay.kt` compile le vrai cœur Android ; il transmet séparément session, frame, observation et évaluation, puis soumission/confirmation/échec corrélés. Les portes UI/transport sont explicitement simulées. Il ne promet pas une parité de tout le contrôleur.
2. **Mac profondeur courante** : expérience silencieuse avec carte relative et horloge source ; les propositions consomment immédiatement son intervalle descriptif. Android ne consomme le cooldown profondeur qu'après callback de lecture corrélé. Modèle/résolution et aléatoire géométrique diffèrent aussi ; les documents de portabilité explicitent ces limites.
3. **Harnais Rayan** : orchestration Kotlin synthétique RGB/résolution/navigation/audio, avec horloge reconstruite et sans mapping de captures. Il peut servir de base à des tests isolés après corrections, pas être présenté comme un rejeu fidèle des données de nos lunettes.

Un futur replay fusion doit recevoir les événements typés sans réécrire leurs horloges, distinguer les deux branches d'inférence, rejouer les vrais tickets/échecs/inconnus, fournir un test direct-vers-replay indépendant et écrire un rapport borné/annulable. Cela dépasse la petite compatibilité de format proposée ici.

## Vérifications exécutées pendant cette revue

Sur les sources actuelles `b650dad`, commande depuis `oria-htc/oria-lab-desktop` :

```sh
../ml/.venv/bin/python -m unittest test_capture_integrity test_long_sessions test_surface_jobs test_depth_policy test_image_quality -q
```

Résultat : **67 tests réussis en 8,922 s**. Cette sélection couvre les corruptions/identités/horloges, index et rapports longs, annulation/leases/échecs disque, distinction profondeur seule et YOLO absent, fraîcheur/qualité de la politique descriptive. Les détecteurs des tests de jobs sont des doublures : aucune nouvelle inférence physique ni écoute ne s'en déduit.

Contrôle indépendant des octets versionnés dans `96d524a` :

- danger : 10 fixtures, 10 résultats Swift, 10 résultats Kotlin, identifiants uniques, attentes = Swift = Kotlin, SHA du corpus conforme au rapport ;
- politique RGB : mêmes contrôles sur 18 cas ;
- zéro exécution Swift/Kotlin nouvelle pour ces sorties historiques ; aucun verdict de compilation de Rayan n'est réattribué à la fusion.

Les tests de compatibilité v2, confidentialité adaptée, compilation Android et recette matérielle sont **non exécutés**, puisque leur code n'est pas encore repris. Aucune archive utilisateur n'a été ajoutée au dépôt.

## Validation minimale demandée après sélection

- Pour la petite lecture v2 : exécuter import v1/v2/versions invalides, intégrité, longs index, export/annulation et UI ; comparer les SHA de sources avant/après. Conserver le writer actuel tant que le contrat v2 réel n'est pas décidé.
- Pour confidentialité/navigation éventuelle : tests récursifs metadata/JSONL/Logcat, alias/textes libres malveillants, préservation des identifiants et phrases fermées de danger, aucune capture par défaut. Les données caméra restent des captures privées même sans coordonnées.
- Pour le harnais complet éventuel : adversariaux âge >500 ms, sessions anciennes, réordonnancement, faux callbacks/délais/échec/annulation, répétition et seek avec mémoire en cours ; ajouter une entrée réellement lue depuis une fixture synthétique de capture et un oracle distinct. Sans ces contrôles, le conserver hors chaîne de décision de production et ne pas publier une mention de parité complète.

## Intégration sélective autorisée et réalisée

L'orchestrateur a retenu la petite compatibilité de lecture et une nouvelle projection de confidentialité, sans importer le harnais complet ni changer le writer v1.

- `replay.py` accepte les entiers JSON 1 et 2. Version absente, inconnue, booléenne, chaîne, fraction et alias contradictoire refusent l'import avant publication. Les aliases identiques et l'ancien alias seul restent acceptés. Les événements supplémentaires v2 restent consultables dans leur position source.
- Deux nouveaux tests d'import couvrent dossiers et ZIP, hashes source avant/après, immutabilité du ZIP, conservation d'événements nouveaux et versions/aliases invalides. **33 tests Python** import/intégrité/sessions longues passent en **13,100 s** après modification.
- `OriaLabPrivacy.kt` contient une projection pure sur maps/listes. Les événements navigation, commandes, parole, audio et interactions emploient des champs structurés autorisés : identités numériques, UUID ou IDs de navigation de forme fermée, enums connues et booléens. Aucun texte libre de destination, coordonnées, transcription, status, erreur ou détail ne subsiste dans ces événements. Un type sensible inconnu est remplacé par un type constant, pas recopié avec un éventuel texte utilisateur.
- Les 15 phrases exactes des catégories alertables et obstacles, construites à partir des enums de production, restent autorisées. Les préfixes/suffixes ajoutés ne le sont pas. Les noms de tenseurs, licences et labels techniques hors contexte sensible sont conservés ; la projection ne supprime pas globalement `name`/`label` comme la version Rayan.
- `OriaLabPrivacyJson.kt` est une façade Android JSON. `sanitizeMetadata` est appliqué avant le gel du manifeste, `sanitizeEvent` avant la file d'événements. L'orchestrateur branche la même façade avant les traces fichier/Logcat du contrôleur. Une erreur de projection porte seulement un code constant `privacy_*`; le JSON rejeté ne doit jamais servir de message d'erreur.
- Les métadonnées emploient une liste explicite de clés techniques. Une future nouvelle clé doit être revue ; les contextes navigation/interactions n'acceptent pas une chaîne libre en remplacement de leur structure. Les arbres sont copiés, non mutés, avec profondeur et nombre de nœuds bornés. Cela ne rend pas une image caméra anonyme et ne prétend pas filtrer un document arbitraire : les captures restent privées.

### Preuve des grands diagnostics et plafond du recorder

La projection pure accepte sans aucun changement de valeur un événement contenant simultanément **128×128 valeurs de profondeur, 1 800 valeurs YOLO et 300 boîtes**. Un nouveau test dédié fait partie des **9 tests JVM privacy réussis** : compilation Kotlin isolée avec les dépendances locales, exécution JUnit sur Mac, sans Gradle ni appareil, **0,029 s** d'exécution. Les autres cas couvrent confidentialité par contexte, alias de coordonnées, phrases fermées, intégrité des identifiants, idempotence, immutabilité et refus de structure cyclique/trop grande.

La lecture du code actuel confirme que le contrôleur journalise bien les 16 384 valeurs et non une miniature 32×32. Les deux fixtures publiques Float32 de profondeur déjà présentes, sérialisées en JSON compact sur Mac selon cette forme, produisent respectivement **308 361 et 309 357 octets** avant les diagnostics supplémentaires. Le plafond précédent **262 144 octets** était donc insuffisant indépendamment du filtre. Ce comptage Python n'est pas une exécution du sérialiseur Android.

Avec accord de l'orchestrateur, le plafond technique par événement est passé à **512 Kio** ; ni durée de capture, ni schéma v1, ni réserve disque de 512 Mio, ni file de 20 Mio n'ont changé. Le nouveau test instrumenté emploie les mêmes valeurs publiques, vérifie que `JSONObject` produit réellement plus de 256 Kio, puis exige une capture complète contenant profondeur et YOLO sans troncature. Un autre instrument charge les deux vrais manifestes embarqués et exige leur égalité après filtrage, y compris noms input/output et licence. Les trois autres nouveaux contrôles instrumentés vérifient JSON imbriqué/null, rejet de cycle, et confidentialité au recorder metadata + événements.

Ces **5 nouveaux tests instrumentés ne sont pas exécutés sur HTC dans cette mission**. L'orchestrateur conserve la compilation globale et la future campagne appareil ; aucun succès de leur exécution ni de confidentialité acoustique/matérielle n'est revendiqué ici.

### Revue du scheduler de l'orchestrateur

Relecture effectuée sans modifier les fichiers audio/contrôleur : la priorité danger conserve une seule réservation, attend le callback terminal corrélé et le `isIdle` réel du backend avant un nouveau dispatch. L'interruption libère aussi la piste préchauffée ; l'orchestrateur a ajouté un réchauffage sérialisé via `interrupt()`.

Deux raccords signalés et pris en charge par l'orchestrateur : invalidation du permis de navigation lors de la préemption jusqu'à un nouveau GPS, et vérification de l'instruction juste avant le dispatch HTC après la persistance. Une autre différence fonctionnelle a été signalée : les tests vocaux manuels, désormais placés dans le scheduler, ne doivent pas être accidentellement empêchés par une pause destinée aux seules annonces automatiques. Ces observations ne constituent pas une preuve de préemption entendue dans les lunettes.
