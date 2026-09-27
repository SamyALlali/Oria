# Revue indépendante finale — Oria R12

Date : 27 septembre 2026

Révision auditée : `259baa8e9a4fde0c46bbfb0712744ffd9e2095d9` (`oria-1.5-jury-r11`)

Verdict : **70 / 100 — démonstrateur logiciel solide, livraison physique non recevable en l’état**

## Portée et méthode

Le premier passage a été réalisé sans modifier le code produit. Le tag R11 a été cloné dans un répertoire temporaire, puis les builds et tests ont été relancés depuis ce clone. Les modifications locales extérieures à `Oria/` n’ont pas été copiées ni supprimées.

L’audit a porté sur le code Kotlin, les tests JVM et instrumentés, les modèles et manifestes, le moteur de décision, le suivi, la profondeur relative, l’audio, la navigation, Oria Lab, les rapports R01–R11, l’APK R11 et les checklists de démonstration.

La note évalue les preuves disponibles, pas l’intention ni le volume de code. Une preuve simulée n’est pas comptée comme une preuve lunettes, et une affirmation dont la trace n’est pas disponible dans le dépôt est signalée comme telle.

## Note détaillée

| Domaine | Note | Preuves et réserves |
|---|---:|---|
| Héritage et parité | **17 / 20** | Le moteur Swift n’a pas été remplacé par un score générique : `DangerResolution.kt` conserve sept sources, propriété, mémoire visuelle, arbitrage, hold/release et branches métriques gardées. Les 18 fixtures de politique et 10 fixtures de résolution restent communes à Swift/Kotlin. Le `.pt` nommé porte `a591f2db…3f55` et les deux ONNX portent exactement `abab2174…4742`. La filiation des 708 tenseurs est documentée, mais l’ancien checkpoint `7dd79d15…40c1` n’est pas présent : cette identité ne peut donc pas être recalculée depuis le clone. |
| Perception | **10 / 15** | Prétraitement letterbox déterministe, six fixtures exactes et XNNPACK vert sur HTC. Les coordonnées sont définies dans l’image redressée et non miroir, et les alertes automatiques sont bloquées tant que l’orientation n’est pas confirmée. La qualité sur images Eagle finales n’est pas qualifiée. MiDaS fournit uniquement une profondeur inverse relative, sans faux mètres, mais reste désactivé par défaut faute de recette HTC/Eagle. |
| Décision et suivi | **12 / 15** | Le moteur complet, le moteur RGB historique, les générations, la fraîcheur, la mémoire vocale, les ambiguïtés et l’expiration sont testés. `STABLE_RGB_V2` reste correctement un candidat et ne prétend pas fournir une identité monde. Toutefois, la voix continue d’être déclenchée par `RgbAlertEngine.eligibleAlert` ; le moteur R04 sert à la résolution, à la priorité et aux traces, sans promotion terrain complète. Les nouvelles scènes R02 restent synthétiques et la scène réelle brute n’est pas rejouable depuis le dépôt. |
| Audio et accessibilité | **7 / 10** | Ordonnanceur unique, priorités, expiration, génération et préemption danger sont explicites. La sortie Bluetooth locale peut être annulée ; la sortie propriétaire HTC traite honnêtement une annulation incertaine. La stéréo 70/30 et les confirmations historiques sont documentées, mais les fichiers de confirmation humaine ne sont pas présents. Les nouveaux bips, la préemption réelle navigation → danger et TalkBack n’ont pas été validés sur le candidat R11. |
| Navigation | **9 / 15** | Géocodage Android, GPS monotone, itinéraire piéton OSRM, versions de route/instruction, recalcul, arrivée et invalidation asynchrone sont réellement implémentés. Les tests couvrent deux manœuvres, perte GPS, sortie de route, anciennes générations et préemption. La recette complète est simulée : aucun trajet réel R11 n’a été exécuté. Le mode réel dépend du réseau, du géocodeur système et d’un service OSRM public, sans route hors ligne. |
| Stabilité et performance | **6 / 10** | 174 tests JVM passent ; files bornées, sélection de dernière image, délestage, gardes à 500 ms, tokens et générations limitent les sorties tardives. Sur HTC, XNNPACK passe à 6/6 avec p95 d’inférence à 154,50 ms et le replay de 60 s accepte 223/224 décisions, à 3,706 Hz, âge p95 308 ms. La parité CPU stricte reste rouge à 5/6, l’endurance modèle de 600 s est ignorée sans argument, et ni les 30 minutes physiques ni les trois démonstrations physiques R11 ne sont acquises. |
| Replay et explicabilité | **5 / 5** | Oria Lab conserve les entrées, décisions, motifs de rejet, horloges et provenance sans inventer une preuve acoustique. Les 68 tests Mac, 11 tests de transfert, 3 tests d’intégrité, 4 tests R02 et les interactions JavaScript passent. Les captures endommagées ou ambiguës sont refusées sans réécriture silencieuse. |
| Démonstration et reproductibilité | **4 / 10** | APK, rollback, SHA-256, package, version, certificat, vidéo de secours et checklist minute par minute sont identifiés. Le clone construit après exécution du script officiel de préparation Gradle. L’APK R11 conserve le package autorisé mais doit remplacer l’ancien starter après sauvegarde, car une mise à jour `-r` entre leurs certificats est impossible ; ce remplacement prévu n’a pas encore été validé. L’APK n’est ni versionné ni publié et n’embarque pas le commit source. Le manifeste mentionne le parent `e3310bc`, tandis que le tag final est `259baa8`. Une reconstruction produit un APK de hash différent. Enfin, 59 liens Markdown locaux sont cassés, principalement vers des preuves matérielles non livrées. |

**Total : 70 / 100.**

## Résultats reproduits

### Clone propre

- Le clone brut ne construit pas immédiatement, car le wrapper attend `gradle-dist/gradle-8.13-bin.zip`, volontairement ignoré.
- `python3 scripts/prepare_gradle.py` télécharge Gradle 8.13 et vérifie correctement `20f1b117…d78`.
- `clean testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug` : succès.
- JVM : **174 tests, 0 échec, 0 erreur, 0 ignoré**.
- Lint : **0 erreur, 105 avertissements**, dont une référence statique à `ViveGlassSimulator` susceptible de conserver un `Context`.
- Politique : **6 tests Python + 63 tests Kotlin** réussis.
- Desktop : **68/68** réussis.
- Transfert : **11/11** ; intégrité : **3/3** ; qualification R02 : **4/4** ; UI JavaScript : succès.

### HTC U24 pro connecté

- XNNPACK : **6/6 fixtures**, p95 **154,50 ms**, statut `PASSED`.
- CPU : **5/6 fixtures**, `sdk_sample_1` divergent sur une ligne brute sous le seuil applicatif, statut `FAILED`.
- Endurance modèle 600 s : **ignorée explicitement**, argument `mlEnduranceSeconds=600` absent.
- Décodage : **100 images décodées, 12 livrées**, sans erreur.
- Replay combiné 60 s : **223/224 décisions acceptées**, 3,706 Hz, âge p95 308 ms, arrêt tardif rejeté, aucune voix après stop. La vidéo est le sample HTC, le transport SDK est faux et le sink vocal est factice.

### APK et modèles

- APK R11 local : `bc854692…a323`, package `com.htc.vive.eagle.hackathon.starter`, version `1.5-jury-r11`/code 6.
- Signature APK v2, certificat debug `15876fd1…c411`.
- Ancien starter installé : certificat différent `60372c0d…beb7`; Android refuse uniquement la mise à jour `-r`. Le remplacement après sauvegarde, avec le même package autorisé, reste à exécuter.
- ONNX exporté et embarqué : `abab2174…4742`, identiques.
- MiDaS embarqué : `2d8c6cb8…fd58`, profondeur relative uniquement.
- Aucun secret manifeste, keystore ou fichier de clé suivi n’a été trouvé par la recherche statique.

## Défauts bloquants

### B1 — Le remplacement contrôlé du starter par le candidat R11 n’est pas encore validé

Le certificat debug du candidat diffère de celui de l’ancien starter installé, ce qui interdit seulement la mise à jour `-r`. Selon la consigne de l’organisateur, il faut conserver `com.htc.vive.eagle.hackathon.starter`, sauvegarder l’ancienne installation, puis la remplacer par notre version modifiée. Il ne faut ni créer un nouveau package ni obtenir une clé HTC. Cette installation de remplacement n’est pas encore prouvée sur le candidat R11.

Conséquence : la porte « APK final installé et trois démonstrations consécutives » est ouverte.

### B2 — Les scénarios matériels décisifs ne sont pas validés sur R11

Il manque sur le même APK : orientation Eagle, perception réelle, trajet réel à deux manœuvres, préemption danger entendue, reprise fraîche, recalcul, arrivée, écran verrouillé, 30 minutes et trois démonstrations physiques de cinq minutes.

Conséquence : les simulations prouvent la logique, mais pas l’intégration finale caméra/GPS/Bluetooth/lunettes.

## Défauts majeurs non bloquants pour une démo RGB contrôlée

1. Le fallback CPU annoncé n’est pas strictement conforme : 5/6 fixtures seulement. Le mode XNNPACK est vert, mais le repli ne doit pas être présenté comme équivalent.
2. La profondeur monoculaire n’est ni qualifiée ni activée par défaut. Elle ne doit pas être présentée au jury comme une distance métrique ou une capacité R11 acquise.
3. Le lien APK → source n’est pas autoportant : aucun commit n’est embarqué et la reconstruction n’est pas bit-à-bit identique (`ad63a192…e829` contre `bc854692…a323`).
4. Les preuves historiques sont insuffisamment transportables : **59 liens locaux cassés**, notamment les confirmations humaines, rapports d’appareil, captures et logs instrumentés.
5. Le lint conserve 105 avertissements. La fuite potentielle de `Context` via le simulateur mérite une correction après le gel ; les autres sont majoritairement dépendances, ressources et compatibilité.

## Courses, fraîcheur et sorties anciennes

Les mécanismes observés sont cohérents :

- canal d’une image avec remplacement borné ;
- worker d’inférence unique ;
- génération vérifiée avant et après l’inférence ;
- timestamps monotones et âge maximal de 500 ms ;
- tokens d’opération pour géocodage et calcul d’itinéraire ;
- versions de route et d’instruction ;
- annulation/invalidation audio et refus des callbacks anciens ;
- verrous ou atomiques autour du décodeur, des files et de la livraison.

Aucune course reproductible produisant une sortie ancienne n’a été trouvée dans les tests relancés. Cela ne remplace pas la recette de déconnexion/reconnexion et de verrouillage sur l’APK final.

Deux anomalies mineures de maintenance ont été vues sans effet démontré : `PocketSessionService.release(...)` est appelé deux fois dans `stop()`, et une clé `publications` est ajoutée deux fois dans une trace dashboard.

## Cinq corrections les plus rentables

1. **Installer le starter modifié** : sauvegarder l’ancienne installation, la remplacer par l’APK R11 portant le même package autorisé, vérifier la connexion Eagle, puis tester le rollback. Aucune clé HTC supplémentaire n’est attendue.
2. **Exécuter une recette physique consolidée sur cet APK exact** : trois démos, 30 minutes, orientation, deux manœuvres réelles, danger, reprise, recalcul, arrivée, verrouillage et changement de route audio. Archiver hash APK, firmware, logs et confirmation humaine.
3. **Rendre le fallback honnête** : corriger la parité CPU ou refuser explicitement ce fournisseur et échouer proprement si XNNPACK est indisponible.
4. **Rendre la release traçable** : embarquer commit/tag dans l’APK, publier un manifeste signé avec hashes, et conserver l’APK exact dans une release identifiable.
5. **Réparer le paquet de preuves** : fournir des extraits anonymisés vérifiables ou remplacer les 59 liens cassés par un inventaire indiquant clairement « preuve privée non livrée » ; ajouter un contrôle automatique des liens.

## Éléments à ne plus changer avant la démonstration

- modèle ONNX, classes, seuil applicatif et prétraitement letterbox ;
- garde de fraîcheur à 500 ms et règles de génération ;
- moteur déterministe, politique RGB de référence et mode de suivi par défaut ;
- ordre de priorité danger/navigation et convention stéréo 70/30 ;
- package HTC autorisé ;
- format Oria Lab et corpus de fixtures ;
- wording « profondeur relative », « trajet simulé » et limites de sécurité.

Toute modification de ces éléments doit créer une nouvelle version et relancer l’intégralité de la recette R11/R12.

## Conclusion

Oria n’est pas une façade autour d’un score générique : l’héritage de décision, les règles RGB, le suivi, la fraîcheur, l’audio et la navigation sont substantiels et testés. Le principal risque n’est plus le cœur déterministe ; c’est l’écart entre ce cœur et le binaire réellement autorisé sur les lunettes.

La note peut raisonnablement dépasser 85/100 après installation de remplacement, recette physique complète sur l’APK exact, qualification du fallback et livraison des preuves. Aucun correctif produit n’a été appliqué pendant cette revue R12.
