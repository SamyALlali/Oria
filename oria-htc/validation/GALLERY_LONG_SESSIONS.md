# Galerie Android — intégrité des positions et captures longues

État : 27 septembre 2026, second passage. Les preuves instrumentées HTC sont exécutées par l’orchestrateur ; la relecture indépendante du chargeur s’exécute aussi avec les sources réelles de `android.util.JsonReader` du SDK sur la JVM du Mac. Cette seconde preuve ne remplace pas le runtime Android.

## Défaut reproduit

Le chargeur précédent utilisait `mapNotNull` pour les lignes de `frames.jsonl`, `runCatching(...).getOrNull()` pour les JSON et un dictionnaire contenant toutes les détections. Une ligne invalide ou une PNG absente disparaissait silencieusement. Le reviewer a extrait le chargeur du HEAD et exécuté une fixture de quatre lignes : A valide, JSON tronqué, B sans PNG, C valide. Il obtenait les identifiants `[101, 103]`, sans erreur ; deux positions étaient perdues.

## Comportement livré

`OriaLabGallery.load` conserve une entrée par ligne non vide de l’index, dans l’ordre d’origine. Chaque entrée expose son numéro physique de ligne, `videoSessionId`, `frameId`, `receivedAtMs`, chemin PNG et causes de défaut. Une valeur absente reste absente ; aucun ID ou horodatage zéro n’est fabriqué. Les lignes vides restent ignorées, mais elles comptent dans le numéro physique des lignes suivantes.

Les compteurs distinguent le nombre attendu dans le manifeste, les entrées réellement indexées, les références vers des fichiers PNG existants, les entrées d’index invalides et les en-têtes d’événement invalides. Une référence PNG existante n’est pas une preuve que son contenu se décode. L’image sélectionnée est décodée à la demande ; une PNG corrompue affiche un défaut explicite. Plusieurs références vers le même fichier comptent chacune, conformément aux positions de l’index.

Les inférences sont associées par le couple `(videoSessionId, frameId)`. Une identité dupliquée, des événements concurrents pour cette identité ou des horloges incompatibles donnent un diagnostic explicite et empêchent d’afficher une analyse arbitraire. À la sélection, le chargeur revalide le type, l’identité et l’horloge de l’inférence. Une détection invalide invalide l’analyse sélectionnée, sans élimination silencieuse de boîtes. Ces défauts de contenu paresseux sont affichés à la sélection ; les compteurs d’ouverture concernent les métadonnées d’index et les en-têtes d’événement.

Les JSON restent inchangés. Aucune capture n’est renommée, supprimée ou réécrite par la galerie. Les chemins qui sortent du répertoire de la capture et les liens symboliques sont refusés. Le test réel compare les SHA-256 de tous les fichiers avant/après.

L’écran protège chaque réponse asynchrone par l’UUID de capture et le numéro de ligne, et non par le chemin PNG. Le changement d’image, de capture ou la fermeture de la relecture annule le chargement précédent. L’annulation est vérifiée pendant la lecture du fichier et les recharges du lecteur JSON, y compris pendant `skipValue` des tenseurs. Le décodage natif d’une PNG déjà lancé finit sa tâche bornée, puis la coroutine vérifie l’annulation avant de publier ; son résultat ne peut pas être affiché sur une nouvelle sélection.

## Mémoire et limites techniques explicites

Les tenseurs bruts sont parcourus puis ignorés avec `JsonReader.skipValue`, sans construction d’un arbre JSON. L’index conserve seulement l’identité, les horloges, les chemins, les causes et l’offset/longueur de l’inférence. Les détections ne sont matérialisées que pour la sélection courante. La mémoire retenue reste proportionnelle au nombre d’entrées (`O(n)`), et n’est pas constante. Les dictionnaires de jointure des événements sont temporaires pendant l’ouverture.

Un buffer de ligne est limité à 2 Mio ; une ligne plus volumineuse est entièrement drainée puis représentée par une entrée invalide explicite. Le scanner continue aux lignes suivantes. Le tampon interne, sa copie et le décodage UTF-8 représentent plusieurs buffers transitoires bornés par cette taille, pas seulement 2 Mio au total. Les 100 premières causes globales sont conservées, mais le total et les défauts par entrée restent disponibles. Noms de champ : 128 caractères ; chemin PNG : 4 096 ; motif de décision : 512 ; détections d’une inférence : 300 (sortie du modèle courant). Tout dépassement est signalé ; ce ne sont pas des limites de durée.

L’aperçu ne décode que le PNG sélectionné, échantillonné pour limiter chaque côté à environ 1 280 pixels. Les PNG originales ne sont jamais recompressées. Aucun plafond d’images, de secondes, de 30 minutes ou de 2 heures n’est ajouté à la galerie. Des captures beaucoup plus longues augmentent toutefois linéairement le coût de l’index ; la pagination de celui-ci reste une amélioration possible après mesure.

## Vérifications préparées / acquises

Classe : `com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabGalleryInstrumentedTest`.

- Positions A / JSON invalide / PNG B absente / C : quatre positions conservées, aucune détection de B sur C, horloges et fichiers inchangés.
- Même identifiant d’image dans deux sessions vidéo, avec PNG partagé : analyses distinctes.
- ID absent, mauvais type, identités contradictoires, doublons, deux lignes invalides consécutives : diagnostic sans valeur fabriquée ni analyse arbitraire.
- Inférences dupliquées, événement tronqué, boîte invalide : échec explicite, lecture valide de la ligne suivante.
- Horloge d’inférence distincte de l’image, alias de temps contradictoires et type d’événement modifié après indexation : analyse refusée.
- Ligne supérieure à 2 Mio et chemin `../` : entrée conservée et défaut explicite.
- Annulation à l’intérieur du `skipValue` des tenseurs : propagée et fichiers inchangés.
- Manifeste absent et PNG présente mais corrompue : causes distinctes, pas d’attente infinie.
- Fixtures longues de 5 406 / 21 622 entrées (~30 min / 2 h à 3 images/s), chacune avec 1 800 nombres bruts par inférence : compte complet et analyses des premières, médianes et dernières positions.
- Capture réelle opt-in : test `selectedRealCaptureProducesReadOnlyComparisonReceipt`, argument `gallerySessionId`. Sans cet argument, le test est explicitement ignoré, jamais déclaré preuve réelle réussie.

Le reçu réel est écrit hors de la capture, dans `files/gallery-validation.json`, et contient les lignes source, IDs, horloges, chemins, décisions, détections, causes et SHA avant/après. Le test synthétique écrit `files/gallery-synthetic-validation.json`. Le reçu ne lit aucun oracle. La comparaison indépendante doit être exacte pour les IDs/horloges/chaînes ; les nombres de détection passent par `Float`, comme le moteur Android, donc une tolérance de 1e-6 convient pour leur sérialisation en Double.

Revue indépendante JVM : quatorze assertions adversariales passées, dont la reproduction puis correction du désaccord d’horloge et de la mutation de type. Vérifications additionnelles : timestamps d’enregistrement postérieurs acceptés sans confusion avec l’observation ; UTF-8 invalide et champ JSON dupliqué signalés ; chemin sortant refusé ; nom JSON de 100 000 caractères rejeté avec diagnostic compact. La revue des clés de sélection UI n’a pas trouvé de réutilisation d’analyse entre deux lignes.

### Mesure sur JVM du Mac — acquise

Harnais du reviewer, source de production du chargeur et véritable JsonReader du SDK, un PNG synthétique partagé, 1 800 nombres bruts par inférence. Aucun fichier privé et aucun flux HTC ne sont utilisés.

| Entrées synthétiques | Taille events.jsonl | Ouverture | Heap retenue après GC, delta approximatif |
| ---: | ---: | ---: | ---: |
| 5 406 (~30 min) | 69 405 278 octets | 505,7 ms | 916 784 octets (0,87 Mio) |
| 21 622 (~2 h) | 277 630 340 octets | 1 857,3 ms | 5 917 048 octets (5,64 Mio) |

Les deux indexes conservent toutes les positions, avec zéro entrée invalide et zéro défaut sur ces fixtures valides. La mesure de heap après GC est bruitée et représente la mémoire retenue, pas le pic transitoire, la RSS ni une garantie sur HTC. Les coûts d’ouverture restent proportionnels au volume JSON parcouru. Ces chiffres ne constituent pas une comparaison avant/après à données strictement égales ; aucun gain chiffré relatif n’en est déduit.

Sources locales de la mesure : `/tmp/oria-review-wave2-09s_2eym/gallery-perf.json`, cas de revue `gallery-cases.json` et `gallery-extra.json` du même dossier. Leur synthèse est conservée ici ; ces chemins temporaires peuvent être nettoyés après la livraison.

### Runtime Android — acquis sur le HTC CN46V3M00284

Compilation intégrée, 83 tests JVM et lint passés. Les **10 tests instrumentés Android** (neuf fixtures + une recette réelle opt-in) passent en 22,021 s. Ils sont distincts des **14 assertions indépendantes sur JVM Mac** du reviewer. APK de production : version code 4, SHA-256 `896d97d057331ede9dc4930043509763345c9701b1bf069d3e6f5f2b778eaaa3`.

| Entrées synthétiques | Taille events.jsonl | Ouverture sur HTC | Heap retenue après GC, delta approximatif |
| ---: | ---: | ---: | ---: |
| 5 406 (~30 min) | 88 505 925 octets | 3 942 ms | 1 454 688 octets (1,39 Mio) |
| 21 622 (~2 h) | 354 005 900 octets | 14 178 ms | 5 677 056 octets (5,41 Mio) |

Les fixtures Android et Mac contiennent des valeurs JSON différentes : leurs volumes diffèrent et les temps ne constituent pas un benchmark matériel à données strictement égales. Les deux mesures portent sur des indexes et des événements synthétiques complets avec un PNG partagé. **La structure équivalente à deux heures est validée ; deux heures de caméra continue ne sont pas validées par ces tests.** Le delta de heap après GC reste une approximation de mémoire retenue, pas le pic mémoire ni la RSS.

Le reçu réel issu du chargeur a été comparé par l’orchestrateur à son oracle indépendant : **164 entrées et 240 détections correspondent**, identités, lignes source, chemins, horloges et motifs exacts ; tolérance de 1e-6 uniquement pour les champs Float. Zéro défaut déclaré, tous les payloads inchangés par SHA-256. Chargement et lecture complète des analyses : 1 540 ms.

La première comparaison a révélé un défaut de sérialisation du reçu de test : Android expose le même répertoire sous `/data/data/…` et `/data/user/0/…`. Le calcul relatif mélangeait ces alias et produisait des chemins `../`. Seul le test a été corrigé pour utiliser `directory.canonicalFile` ; aucun code de production ni capture n’a changé. Le test réel seul a ensuite été reconstruit et relancé avec succès en 1,729 s, puis les 164 résultats ont tous été vérifiés par l’oracle.

Reçus, oracle et comparaison conservés par l’orchestrateur dans les preuves `wave2/*.json` du passage de nuit. Le chargeur et le test n’ont pas lu cet oracle pour construire leurs résultats.
