# Relecture indépendante — galerie Android et export Mac

27 septembre 2026. Périmètre : conservation des identités/horloges, annulation, lecture sans chargement des données brutes inutiles et export ZIP en flux. Le cœur de tri, les seuils et le modèle restent inchangés. Aucun ADB ni Gradle utilisé par le reviewer.

## Défauts initiaux reproduits

- **Galerie Android** : la fonction `loadGallery` de la version de référence a été extraite sans changer sa logique et exécutée en JVM avec les implémentations JSON du SDK Android local (annotations de plateforme retirées pour compilation). Une fixture contient quatre positions : image 101 présente, ligne JSON invalide, image 102 manquante, image 103 présente. Résultat : `[101, 103]`, `error = null`. La ligne invalide et l'image manquante disparaissent silencieusement. Cette expérience porte sur la logique du chargeur et ne constitue pas un essai du runtime HTC.
- **Export Mac** : le handler JavaScript de référence a été exécuté avec une réponse réseau simulée et un espion `blob()`. Il demande effectivement un Blob de réponse complet avant le téléchargement. La réponse simulée annonce 6 Gio ; aucun fichier ou tampon de 6 Gio n'a été alloué et aucune consommation mémoire réelle de navigateur n'est déduite de ce test.

Les fixtures et journaux de revue sont temporaires, entièrement synthétiques et sans donnée utilisateur, dans `/tmp/oria-review-wave2-09s_2eym/`.

## Contrats convenus avant intégration

### Galerie

Une entrée par ligne non vide de `frames.jsonl`, avec son numéro de ligne source. Une image absente ou une entrée invalide occupe sa position et affiche son motif. Les identifiants de session/frame et l'horloge ne reçoivent pas de valeur par défaut pouvant créer une fausse association. Les détections sont jointes par identité, jamais par le rang compacté d'une liste. Les comptes de lignes, d'images disponibles et d'erreurs restent visibles ; plafonner les détails ne doit pas masquer leur total.

Le chargeur conserve des offsets et métadonnées compactes. Il saute les tenseurs pendant l'indexation et lit les détections de l'image demandée seulement. Une annulation ou un changement de sélection ne doit pas publier les résultats de la sélection précédente. Les captures ne sont jamais modifiées par la relecture.

### Export Mac

Préparation par POST protégé, job opaque aléatoire de 256 bits, puis téléchargement GET natif en flux avec reprise Range. Le ZIP est un snapshot immuable : réservation de la capture pendant préparation, puis réservation distincte du ZIP dérivé pendant lecture. Renommer ou archiver la capture après préparation ne modifie ni le nom ni le contenu du snapshot.

L'expiration après au plus 30 minutes sans utilisation concerne uniquement un ZIP dérivé inactif ; un ZIP prêt sans lecteur peut aussi être évincé plus tôt lorsque les quatre emplacements sont occupés. Les lectures de statut ne prolongent pas ce délai. Elle utilise une horloge monotone et est suspendue tant qu'un lecteur est actif. Elle n'impose aucune limite d'enregistrement ou de mode poche. Annulation, expiration ou déconnexion HTTP libèrent proprement leurs réservations ; un ZIP sous lecture n'est supprimé qu'après fermeture du dernier lecteur. Un ZIP annulé encore lu continue de compter dans la capacité jusqu’à la fermeture du dernier lecteur. Le nettoyage ne touche ni archive utilisateur ni instance de serveur encore active.

## Verdict final

**Aucun bloqueur restant sur le gel relu.** Deux points identifiés pendant la revue ont été corrigés et vérifiés : l'association de la galerie malgré une horloge source contradictoire, et le nom affiché de l'export qui doit provenir du snapshot côté serveur. Le cœur déterministe, le modèle et les seuils n'ont pas été modifiés par le reviewer.

## Preuves indépendantes — galerie

Le chargeur source a été compilé hors Gradle et exécuté avec le vrai `JsonReader` du SDK Android local. Les annotations de plateforme ont été retirées de cette copie temporaire des classes SDK ; la logique du parseur n'a pas été modifiée. Ce test valide la logique du chargeur, pas Compose ni le runtime HTC.

La fixture initiale conserve maintenant les quatre positions et leurs lignes source 1–4 : deux fichiers PNG disponibles et deux entrées invalides avec leurs causes. Les positions invalides ne récupèrent pas les détections de l'image suivante.

**Quatorze assertions adversariales supplémentaires passent** :

1. Même numéro de frame dans deux sessions vidéo distinctes : détections séparées.
2. Identité de frame dupliquée : analyses refusées comme ambiguës.
3. Événement d'inférence dupliqué : analyse refusée comme ambiguë.
4. Identifiants absents ou de mauvais type : aucune conversion vers une identité par défaut.
5. Détection invalide : analyse non exploitable signalée.
6. Ligne supérieure à 2 Mio : position conservée comme invalide.
7. Cent cinquante erreurs : total conservé, détails limités à cent.
8. Annulation pendant le parcours des données de tenseur.
9. Inférence à 99 ms pour une image à 1 000 ms : analyse refusée avec diagnostic.
10. Type d'événement changé après indexation : changement détecté lors de la lecture différée.
11. `atMs` et `recordedAtMs` postérieurs : acceptés, puisqu'ils décrivent le traitement ou l'enregistrement.
12. `receivedAtMs` et `observedAtMs` contradictoires : événement refusé.
13. UTF-8 invalide : position conservée et ligne suivante correctement associée.
14. Clé JSON répétée et parcours hors capture : refusés dans les vérifications de structure et de chemin.

Le harnais contient neuf assertions dans `gallery_cases.py` et cinq dans `gallery_extra.py`. Certaines assertions regroupent plusieurs propriétés proches ; le chiffre quatorze désigne ces assertions exécutées, pas un décompte de tous les sous-cas. Le nom de champ de 100 000 caractères est aussi refusé avec un diagnostic compact par la limite de 128 caractères.

Les gardes de publication UI ont été relues : UUID de capture pour l'index, UUID + ligne source pour l'image et son analyse. Elles ne dépendent pas du seul chemin PNG, qui peut être identique ou absent sur plusieurs lignes. `ensureActive()` intervient avant publication et le lecteur vérifie l'annulation pendant le remplissage JSON. La navigation réelle Compose relève des essais Android de l'orchestrateur.

### Mesures synthétiques de mémoire retenue

Chaque entrée possède une inférence avec 1 800 nombres bruts et référence le même PNG synthétique. Mesures d'un processus JVM Mac limité à 128 Mio de tas, après échauffement du parseur et GC avant/après chargement :

| Entrées | Taille de `events.jsonl` | Chargement | Variation du tas retenu après GC |
|---:|---:|---:|---:|
| 5 406 | 69 405 278 octets | 506 ms | 916 784 octets |
| 21 622 | 277 630 340 octets | 1 857 ms | 5 917 048 octets |

Aucune entrée invalide ni erreur dans ces deux expériences. Ce sont deux mesures ponctuelles de logique sur Mac, sans répétition statistique : elles ne représentent ni le pic d'allocation, ni les performances ou l'endurance du HTC. Les données brutes ne restent pas dans l'index retenu.

## Preuves indépendantes — export Mac

**Quatre tests indépendants passent**, avec captures synthétiques et serveur HTTP local temporaire :

1. Le ZIP conserve son label et son contenu après renommage puis archivage de la source. Un lecteur traverse 24 heures virtuelles, puis une annulation : le fichier reste intact jusqu'à la fermeture ; un nouveau lecteur est refusé ; seul le dérivé est supprimé.
2. Quatre ZIP annulés encore lus occupent toujours les quatre emplacements. Une nouvelle préparation est refusée jusqu'à libération. Une deuxième instance préserve le cache de l'instance active et un fichier utilisateur inconnu.
3. Une panne disque injectée produit `failed`, libère la capture et supprime uniquement la sortie temporaire possédée. Les empreintes des fichiers de capture restent identiques.
4. Un POST sans token reçoit 403. La préparation protégée fournit un identifiant opaque ; le GET natif complet et un GET Range de 64 octets fonctionnent (200/206, taille et `Content-Disposition` vérifiés). Après annulation, le lien reçoit 404.

Ces quatre tests ont été relancés après les corrections et restent verts. Le nom `displayNameSnapshot`, lu sous réservation côté serveur, est renvoyé par création/statut et utilisé pour le titre UI. Un renommage intervenant autour de la préparation ne peut donc plus faire diverger ce titre et le label figé dans le ZIP. Le téléchargement ZIP n'appelle plus `Response.blob()` ; le serveur lit par blocs de 1 Mio. Le Blob de la courte préécoute audio est un mécanisme distinct.

Après gel Mac, le reviewer a aussi relancé les **neuf tests `test_exports` du propriétaire : tous passent**, notamment la déconnexion HTTP réelle par RST, la réservation de deux préparations, le nettoyage d'orphelins et la réserve disque pendant l'écriture des métadonnées ZIP. Le test synthétique de 128 Mio produit un ZIP de 134 447 444 octets, avec un pic `tracemalloc` de 2 124 091 octets lors de ce passage. Cela mesure les allocations Python suivies, pas la mémoire totale de l'OS ou du navigateur.

Les tests JavaScript d'interaction passent également, dont téléchargement natif, titre de snapshot et annulation d'une réponse devenue périmée.

## Portée et limites

Cette revue n'a utilisé ni ADB ni Gradle et n'a modifié aucune capture utilisateur. Elle ne revendique pas de validation physique HTC, acoustique ou d'endurance longue réelle. L'orchestrateur a communiqué séparément dix tests HTC réussis, une parité exacte des oracles 164/240 et un export Mac natif aux 169 fichiers utiles de SHA-256 égaux ; ces preuves d'intégration lui appartiennent et ne sont pas présentées comme des essais exécutés par le reviewer.

Les fixtures, harnais et résultats de cette revue sont dans `/tmp/oria-review-wave2-09s_2eym/`, notamment `gallery-cases.json`, `gallery-extra.json`, `gallery-perf.json` et `review_exports.py`. Les empreintes ci-dessous identifient les sources du gel relu ; elles sont également enregistrées dans `review-source-sha256.json` au même endroit.

## Empreintes des sources relues

| Fichier | SHA-256 |
|---|---|
| `android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/recording/OriaLabGallery.kt` | `45367402f8ced53ebe9784966d0cfd4dee3e5fd9ded4e3f074b50d50e71e0667` |
| `android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/oria/ui/OriaLabScreen.kt` | `b8c5fbaa65fd8e9c7375d2432a2687fad5c541b58bfc09f8a175f97e8e0b4ec4` |
| `oria-lab-desktop/export_jobs.py` | `e744c62302ad2aa1a8a5dd5fc830716b008c6b894593745d3519df7d486c3d07` |
| `oria-lab-desktop/replay.py` | `4a75390bb25dd505184e72751f16c865611a285864380b6955177ad58d439a07` |
| `oria-lab-desktop/server.py` | `0e1ddfc733765028ec1dd7afdaec9a3e9aa2565aa41b1472ca733805af99ac51` |
| `oria-lab-desktop/static/app.js` | `33cdaef9ed05e73659836594325b60eb110ce0151d68346d529448cf9a0daf18` |
| `oria-lab-desktop/test_exports.py` | `5420331805e50c5af69d23f52701cb09832cef428474cf59b0194f6a106723fc` |
| `oria-lab-desktop/test_ui.js` | `4cfa11d552a71966c53cfd5887729d7554d8470a8d5b4daac23f11587b98eaf9` |
