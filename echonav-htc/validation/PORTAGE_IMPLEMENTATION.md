# Portage Kotlin — résultat d’implémentation et vérification

> Rapport historique de l’implémentation initiale et des essais sur **CN4B53M00860**. Les décisions de navigation/installations anciennes sont conservées comme historique ; la reprise actuelle ouvre EchoNav directement avec Diagnostic HTC secondaire. État courant et preuves du nouveau **CN46V3M00284** : [RECETTE.md](RECETTE.md) et `new-device-CN46V3M00284/`.

26 septembre 2026. Auteur : agent portage. Cette livraison couvre exclusivement le cœur métier pur, ses tests et les fixtures de comparaison. Elle ne valide pas l’acquisition HTC, YOLO sur téléphone ou le haut-parleur physique.

## Fichiers livrés

- `android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/echonav/core/RgbAlertPolicy.kt` : catégories, vocabulaire, directions, qualification visuelle, priorité RGB et règles de fraîcheur.
- `android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/echonav/core/RgbAlertEngine.kt` : session, suivi 2D local, stabilisation, choix d’annonce, réservation/confirmation/échec/incertitude audio.
- Deux classes de tests dans `app/src/test/java/.../echonav/core/`.
- `fixtures/policy_cases.jsonl`, exécuteur Swift portable, résultats Swift/Kotlin et manifeste de parité.

Pour la livraison initiale du cœur métier, `Contracts.kt`, Gradle, `MainActivity`, les adaptateurs HTC/ML et les sources originales n’ont pas été modifiés par cet agent. L’évolution d’interface demandée ensuite est consignée en fin de rapport.

## Provenance et adaptations

Référence : `/Users/sam/Documents/ChatGPT/echonav/EchoNav_main`, commit `8fbb4e03911bfc16e90e072f3446a492bc61c2ea`. SHA-256 du moteur Swift lu par l’exécuteur : `f10525f34c34ab46fbf189426337e24fdb4990256ecd90d72d54a8f997c45f54`.

| Responsabilité | Source Swift | Résultat Kotlin |
|---|---|---|
| Catégories, quatre classes alertables, libellés | `SemanticObjectCategory`, ligne 11 | Porté ; les six classes du modèle sont reconnues, feu/panneau n’engendrent pas d’alerte. Pas de septième classe inventée. |
| Zones et phrases | `CorridorZone` 163, `corridorZone` 1562 | Porté : gauche `<0,39`, droite `>0,61`, sinon devant ; phrases sans mètres. |
| Qualification RGB | `qualifiesForVisualOnlySemanticCue` 3378 et seuils 3402 | Corps logique porté pour personne/véhicule/deux-roues/poteau : confiance, aire et bas de boîte. |
| Indices visuels et priorité | `visualOnlyProxyDistance` 3414, biais de catégories | **Adapté** : les composantes taille/bas/centre deviennent une saillance sans unité, additionnée au biais et à une composante de confiance. Aucun faux nombre de mètres, niveau métrique ou « danger proche ». |
| Hystérésis et stabilité | `stabilizedCandidate` 3005, maintien/remplacement 3109–3149 | **Adapté** : confirmation sur deux observations ; marge de confiance de sortie 0,04 ; maintien de sélection 1 100 ms et remplacement à +0,28. Une observation perdue n’est pas maintenue comme preuve sonore. |
| Identité | `SpatialAlertVoiceMemory` 3892 et ancrage AR 3551 | **Adapté** : appariement classe + IoU un-à-un, sans pose ni ancre monde ; mémoire vocale séparée. |
| Répétition | délais Swift de niveau moyen et mémoire 18 s | 6 500 ms après confirmation SDK, pacing global 1 000 ms ; mémoire vocale au plus 32 entrées ; expiration strictement après 18 000 ms depuis dernière observation. |
| Cycle de session | `ProximitySessionSafetyState` 562 | **Adapté** : session/génération, rejet d’anciennes observations et résultats, arrêt idempotent ; aucune profondeur fictive. |
| Livraison audio | `emitVoiceCueIfNeeded` 3461 | **Corrigé pour HTC** : proposition ≠ réservation ≠ confirmation SDK. Échec libère sans consommer le délai de rappel ; incertitude bloque le dispatch. |
| Profondeur, murs, approche métrique | attribution/obstruction/ancres AR | Inactifs : pas de LiDAR, pose, distance ou règle de rapprochement artificiels. |
| NMS et top huit | parsing Swift 1317, NMS 1803 | Non repris dans le cœur : contrat de perception séparé, test de plus de huit candidats et classes qui se recouvrent. |

Les délais moyens sont utilisés pour des annonces descriptives, sans exposer de sévérité physique. Les autres niveaux Swift ne sont pas copiés artificiellement dans le mode RGB.

## API et intégration

Toutes les méthodes doivent être sérialisées sur un même propriétaire ou protégées par un verrou externe. L’horloge monotone est fournie à chaque appel ; aucun thread, délai ou horloge cachés n’existent dans le cœur.

1. `start(sessionId, nowMs)` : nouvelle génération, état perceptif remis à zéro. Les timestamps des frames doivent provenir de la même horloge monotone et être postérieurs au début de session. Préférer un identifiant de session unique à chaque démarrage.
2. `evaluate(frame, nowMs)` : retourne tracks, candidat visuel sélectionné, éventuelle `eligibleAlert`, raison de suppression et état audio. `current(nowMs)` fournit un état après expiration sans créer de nouvelle opportunité d’envoi.
3. `onSubmitted(alert, nowMs)` **avant** l’appel SDK : retourne un `VoiceTicket` opaque si session, image et offre sont encore valides. Une seule commande peut être réservée. Un `null` interdit le dispatch.
4. Callback corrélé : `onConfirmed(ticket, nowMs)` pour un retour SDK valide, `onFailure(ticket, nowMs)` pour un refus/échec terminal corrélé. Le sens du succès HTC doit être documenté séparément ; il ne mesure pas l’audibilité.
5. Timeout/corrélation incertaine : `onAmbiguous(ticket, nowMs)` → `UNKNOWN`. `stop()` invalide tous les tickets et marque aussi l’audio inconnu si une phrase avait été envoyée. Démarrer une nouvelle session ne rétablit pas silencieusement l’audio.
6. `resetAudioAfterVerifiedReset()` n’est utilisable qu’après une procédure SDK de reset/annulation réellement validée. L’intégration ne doit pas l’exposer comme simple bouton de retry.

`VoiceAlert` fournit `id`, `text`, `trackId`, `frameId`, `sessionId`, `generation`, `observedAtMs`. `RgbCandidate` fournit `text`, catégorie, zone, score sans unité et détection. `RgbTrack` fournit identité temporaire, boîte, zone, compte de confirmation, date d’observation et visibilité dans la dernière frame. La piste prioritaire pour l’écran et la prochaine annonce peuvent différer : un deuxième objet est éligible pendant le cooldown du premier.

Un callback HTC anonyme ne devient pas corrélé parce qu’il existe un ticket Kotlin. Le contrôleur reste responsable de ne jamais attribuer un succès tardif de A à B. L’état `UNKNOWN` rend ce problème observable et bloque les nouveaux envois.

## Vérifications effectivement exécutées

Commande depuis `android-project/` :

```sh
./gradlew :app:testDebugUnitTest --tests '*RgbAlertEngineTest' --tests '*RgbSwiftFixturesTest' --console=plain
```

Dernier passage : **BUILD SUCCESSFUL**, 22 tests moteur + 1 test de fixtures, **23 réussis, zéro échec, zéro ignoré**. Preuves XML : `app/build/test-results/testDebugUnitTest/TEST-com.htc.vive.eagle.hackathon.starter.echonav.core.RgbAlertEngineTest.xml` et fichier homologue `RgbSwiftFixturesTest.xml`, horodatés `2026-09-26T10:40:48Z`.

Les cas couvrent notamment : plus de huit candidats, recouvrement interclasses, deux personnes, association un-à-un, croisement sans promesse de réidentification physique, retour après mouvement de tête, oscillation aux seuils, perte/confirmation, fraîcheur inclusive à 500 ms, rejet de la commande à 501 ms, mémoire conservée sans nouvelle observation, réservation unique, échec sans faux cooldown de succès, rappels, ancienne génération, callbacks tardifs, timeout ambigu, arrêt répété, temps inversé et ressources bornées.

Commande depuis `fixtures/` :

```sh
python3 run_swift_reference.py
```

Résultat : **18 fixtures communes concordent exactement** entre les corps de fonctions Swift extraits et Kotlin. Swift 6.2.1 s’est exécuté sur le Mac. Les résultats et empreintes figurent dans `fixtures/policy_parity_report.json`. Ce test portable vérifie zones, qualification visuelle et frontières de TTL ; il ne prétend pas exécuter la boucle iOS/ARKit complète, le modèle ni les 76 tests du dépôt EchoNav.

## Paramètres provisoires et limites restantes

- L’association est volontairement simple : IoU minimal 0,25, perte après 750 ms, deux observations pour confirmation. Ce sont des choix de prototype, à éprouver sur le HTC U24 pro et les lunettes, pas des valeurs déduites du Swift.
- À 4 Hz théoriques, deux observations ajoutent normalement un intervalle d’analyse avant la première annonce. La cadence réelle et les images manquantes peuvent augmenter ce délai. La priorité est une heuristique qualitative qui demande des scènes représentatives.
- Après une occultation longue ou un mouvement de tête, l’identité est recréée. Cela peut produire une réannonce avant le délai d’un même objet physique ; le pacing global reste assuré. La recette matérielle F06 et le comportement anti-spam en mouvement **restent à valider**. Le test de retour de tête expose cette limite, il ne la dissimule pas.
- L’algorithme glouton peut échanger les identités physiques lors d’un croisement ambigu. Il conserve des pistes locales distinctes et ne promet pas de réidentification dans le monde.
- Aucun temps jusqu’au haut-parleur, aucune capacité de coupure de phrase, aucun résultat de navigation sûre ni performance YOLO ne sont attestés par ces tests.
- Les seuils visuels d’origine peuvent réduire le rappel sur la caméra HTC. Toute adaptation doit être comparée sur des exemples fixés avant réglage ; aucune validation historique Paris V1 n’est héritée.

## Revue croisée du contrôleur — observations transmises à l’orchestrateur

Relecture en lecture seule du contrôleur pendant son intégration ; les numéros peuvent bouger avec ses corrections. Ces points ne sont pas des changements réalisés par l’agent portage dans le contrôleur.

1. **État perceptif périmé.** À la lecture, `staleFrame()` incrémentait seulement un compteur ; la branche d’inférence lente conservait l’ancien aperçu, les anciennes boîtes et « Perception active ». Avec un flux frais mais une inférence systématiquement supérieure à 500 ms, ce faux état pouvait durer indéfiniment sans déclencher le watchdog vidéo. Proposition transmise : garde périodique fondée sur la dernière observation réellement acceptée, état limité et purge des observations affichées, et prise en compte de `evaluation.frameStatus` avant de compter une décision comme acceptée.
2. **Exception de soumission vocale traitée comme refus certain.** `sendSpeech(false)` libérait la réservation en échec, tandis que `speakEchoNavText` retournait également `false` sur toute exception levée par le SDK. Un appel potentiellement accepté avant l’exception rend sa livraison inconnue : traiter cette exception comme `UNKNOWN`, réserver `false` au refus prouvé avant appel. Sinon un succès tardif de A pourrait être attribué à B.
3. **Retour antérieur à la requête.** Le collecteur TTS contrôlait l’epoch mais pas `event.receivedAtMs` face à `pending.submittedAt`. Un événement déjà reçu et en attente de traitement ne doit pas terminer une nouvelle commande. Utiliser ce garde temporel et vérifier le retour booléen de la confirmation moteur. L’epoch transport est horodaté à la livraison et n’est pas un identifiant de commande ; le garde ne résout pas toute possibilité de callback tardif anonyme.

Les protections observées déjà cohérentes sont : un propriétaire Main pour les mutations métier/audio, une requête en vol, revalidation de fraîcheur au dispatch, invalidation après stop, états SDK/audibilité séparés et absence de bouton qui réinitialiserait artificiellement l’audio inconnu.

### Recontrôle après corrections de l’orchestrateur

Les trois corrections ont été vérifiées dans le code : observation acceptée mémorisée puis expiration/purge de l’interface, contrôle `frameStatus == ACCEPTED`, exception SDK laissée remonter vers l’état audio inconnu et garde `event.receivedAtMs < pending.submittedAt`. La persistance de l’état de livraison avant appel empêche aussi une recréation d’Activity/processus de rétablir arbitrairement la voix.

Un point supplémentaire a été transmis : la réservation moteur vérifie l’âge avant le `SharedPreferences.commit()` synchrone effectué par `sendSpeech`. Une observation âgée de 450 ms avant une écriture de 100 ms devient périmée avant l’appel HTC. Il faut donc revalider après cette écriture et immédiatement avant le dispatch, ou déplacer la réservation après la persistance. Si aucun appel SDK n’a encore eu lieu, l’expiration est un abandon local connu, pas une livraison inconnue. Ce scénario est testable avec une horloge/écriture injectées. Les booléens de retour de `onConfirmed`/`onFailure` restent également à traiter explicitement pour ne pas afficher une confirmation que le moteur aurait refusée.

Ce recontrôle est statique. Le harness séparé installé sur le téléphone ne constitue pas un essai du contrôleur prototype sous l’identité whitelisted ; l’utilisateur ayant refusé le remplacement du starter signé différemment, cette recette UI complète reste non exécutée.

### Dernière revue indépendante pendant l’endurance ML

Lecture seule, sans commande ADB ni exécution concurrente sur le téléphone. L’endurance ML conduite par l’orchestrateur ne valide pas à elle seule le contrôleur, le transport caméra ou la voix.

**Garde après persistance : corrigée.** `sendSpeech` reçoit désormais `observedAtMs`. Après le `commit()` et la préparation locale, il vérifie à nouveau l’âge maximal, l’absence de timestamp futur, `running` et la session du ticket juste avant `manager.speakEchoNavText`. Aucun appel de journalisation ou écriture disque ne se trouve entre cette dernière garde et l’appel au manager. Une expiration avant appel est abandonnée localement et ne crée pas arbitrairement une livraison inconnue.

**Retours moteur : corrigés lors de la dernière relecture.** Les branches `SUCCESS` et erreur contrôlent désormais les booléens `onConfirmed`/`onFailure`. Un refus mène à l’état inconnu et termine le traitement avant purge de la demande ou effacement de la persistance. Le `ticket == null` correspond au test vocal manuel, qui ne réserve pas de politique d’objet. Les trois abandons connus avant appel SDK passent par `releaseLocalSpeech` : son retour est vérifié et une réservation incohérente reste explicitement bloquée. Cette correction est confirmée par lecture du code, pas par un scénario UI exécuté sur lunettes.

**Cycle de vie observé : cohérent dans ce périmètre.** `stop()` invalide la génération, annule le démarrage, interdit les nouvelles demandes, met en quarantaine une parole en vol et purge les frames en attente ainsi que l’aperçu affiché. Le démarrage remet les métriques de session à zéro. Les résultats d’inférence sont revérifiés après le travail hors UI ; `close()` ferme les ressources du modèle sur leur worker après le travail déjà en cours. Le traitement des callbacks reste sur Main, et les états persistés ne sont pas effacés par un simple redémarrage de session ou de processus. Le calcul p95 observé utilise maintenant le rang supérieur `ceil(0,95 × n) - 1` sur un tableau non vide. La corrélation exacte d’un callback anonyme et la durée de parole résiduelle demeurent des validations matérielles, non des faits établis par cette lecture.

**Cohérence des preuves : vérifiée sans relancer les tests.** Les 18 résultats Kotlin enregistrés, les 18 résultats Swift enregistrés et les valeurs attendues JSONL concordent. L’empreinte des fixtures et celle du source Swift actuel correspondent toujours au manifeste de parité. Le README décrit correctement le périmètre réduit des fonctions pures et les 22 autres tests moteur ; il ne prétend pas avoir exécuté l’application iOS complète. Le contrôleur observé pour cette clôture de revue porte le SHA-256 `d399b043e7e7b878a4f2f58d6ee2254e2a47b14e4fe36653c6b3418710e6085a`.

Aucun constat restant identifié dans les corrections relues n’exige une nouvelle modification du contrôleur dans ce périmètre. Ce verdict de revue n’est pas une recette réelle sous l’identité whitelisted. Aucun ADB, nouveau test ou changement de code n’a été effectué par cet agent pendant le build final de l’orchestrateur.

## Évolution demandée ensuite — onglet EchoNav · Samy

À la demande explicite de l’utilisateur, `MainActivity` réutilise désormais le shell `SampleApp` et conserve les quatre onglets HTC **Glasses, Chat, Audio, Camera**, auxquels s’ajoute **EchoNav · Samy**. La route technique reste `echonav`. Glasses est la destination initiale ; aucun onglet Rayan vide n’a été créé, afin que le binôme ajoute son propre travail.

Un listener de destination couvre clics et retour Android : il libère la page précédente, arrête EchoNav à sa sortie et transmet le mode actif au manager avant la nouvelle page. Le callback de permission microphone Android vérifie que la demande de démarrage appartient encore à l’onglet EchoNav actif. La voix manuelle Chat est branchée sur le point d’entrée commun fourni par l’orchestrateur. Les fichiers modifiés pour cette évolution sont `MainActivity.kt` et `ui/tab/AppDestination.kt` ; l’écran EchoNav et ses insets sont conservés. Le libellé final de destination est `EchoNav · Samy`, sans changement de route ni de contrat métier.

Relecture statique croisée par l’agent HTC : aucun blocage propre au shell identifié ; les protections des callbacks et permissions HTC restent sous la responsabilité du manager, revu séparément. Aucun build ni ADB n’a été exécuté par cet agent pour cette évolution. La compilation finale appartient à l’orchestrateur. La validation de navigation et de rendu sur le téléphone reste **non exécutée** : le prototype ne peut pas remplacer le starter de signature différente sans l’autorisation que l’utilisateur a refusée.
