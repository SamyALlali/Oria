# Tour 1 — Modèle, export et inférence mobile

Date : 26 septembre 2026. Contribution indépendante de l’agent `modele_mobile`. Revue statique des quatre documents préparatoires, des fonctions Swift concernées, du manifeste Paris V1 et des sources officielles Ultralytics 8.4.27/ONNX Runtime. Aucun chargement du checkpoint, export, benchmark ou test matériel exécuté. Cette note propose des décisions ; elle ne constitue pas une validation.

Contrainte utilisateur ajoutée pendant ce tour : un téléphone HTC prêté est disponible pour les essais live ; Kotlin est accepté. Un accès développement iOS serait possible demain, sans confirmation. Android Kotlin est donc le premier jalon. La disponibilité du téléphone permet de prioriser ML-5 dès qu’un export valide existe, mais ne donne encore ni son modèle exact, ni ses capacités, ni des résultats de mesure.

## Proposition défendue

Utiliser le `.pt` explicitement fourni comme candidat SILMO. Établir une référence ONNX FP32, entrée fixe 416 × 416, batch 1, branche end-to-end conservée, puis un adaptateur Android au contrat **mesuré**. Le moteur métier reçoit des détections normalisées dans le repère de l’image portée par les lunettes et ignore les particularités des tenseurs. La conversion inverse du modèle Core ML n’apporte rien au premier jalon puisque le checkpoint est disponible.

Le CPU EP d’ONNX Runtime sert à vérifier la correction. **Je conteste l’idée d’en faire par défaut le choix de performance final : pour un modèle FP32, inclure XNNPACK dans le premier comparatif sur téléphone**, tout en gardant le même ONNX. Cela ne nécessite pas de lancer une compétition entre formats. La documentation officielle recommande XNNPACK comme première piste pour un modèle non quantifié et souligne que les accélérateurs dépendent du modèle et de l’appareil. [ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/)

Commencer la comparaison de géométrie par un profil qui reproduit le redimensionnement `.scaleFill` de la référence Swift. Cela isole les différences de runtime sans changer simultanément le prétraitement. Conserver **letterbox comme alternative explicite à évaluer**, pas comme remplacement implicite : il préserve les proportions, mais modifie la distribution des pixels et impose d’annuler correctement padding et échelle. Le choix final pour HTC reste ouvert jusqu’à une comparaison sur ses images. Ne pas copier l’orientation iPhone `.right` : une mire gauche/droite/haut/bas et le format réel du flux déterminent celle des lunettes.

## Faits qui contraignent la décision

| Fait établi | Source | Conséquence |
|---|---|---|
| Le `.pt` fourni a six classes ordonnées, une configuration `end2end=true` et une taille d’entraînement 416 ; son chargement reste non testé. | [Métadonnées locales](</Users/sam/Documents/ChatGPT/Hackathon SILMO/ORIA_MODEL_METADATA.json:1>) | Ces propriétés sont une base de préparation, pas la signature mesurée d’un export. |
| Paris V1 documente `[1,10,3549]`, `endToEnd=false`, `nms=false`. | [Manifeste iOS](<projet Swift externe : ORIA_SWIFT_SOURCE>:15) | Son parseur ne constitue pas un parseur universel YOLO26. |
| Les empreintes et métadonnées des checkpoints sont distinctes. | [Audit](</Users/sam/Documents/ChatGPT/Hackathon SILMO/AUDIT_PACKAGE_HTC.md:29>) | Cela n’établit pas à lui seul que tous les poids diffèrent. Leur équivalence n’a pas été vérifiée ; aucune validation Paris V1 ne se transfère automatiquement au candidat SILMO. |
| Swift force `.scaleFill` et `.right`. | [setupVision](<projet Swift externe : ORIA_SWIFT_SOURCE>:1182), [runDetection](<projet Swift externe : ORIA_SWIFT_SOURCE>:1268) | Le contrat de transformation doit être explicite et indépendant de la taille de l’aperçu Android. |
| Swift lit quatre coordonnées centre/largeur/hauteur suivies de scores de classes, déduit le nombre de canaux de la forme, puis applique une NMS agnostique. | [processDetections](<projet Swift externe : ORIA_SWIFT_SOURCE>:1317), [outputLayout](<projet Swift externe : ORIA_SWIFT_SOURCE>:1489), [nms](<projet Swift externe : ORIA_SWIFT_SOURCE>:1803) | Reprendre aveuglément ce code pourrait interpréter une classe comme un score et une borne comme une largeur. |

Dans Ultralytics 8.4.27, l’exporteur permet de choisir `end2end`, conserve cette branche pour ONNX et force `nms=false` si une NMS est demandée sur un modèle end-to-end. Le code de tête décrit une sortie post-traitée `xyxy, score, classe`, de forme `[batch, min(max_det, anchors), 6]`. Pour le candidat et les paramètres proposés, `[1,300,6]` est donc une **attente issue du code**, à confirmer par l’export et l’exécution. Aucune NMS supplémentaire n’est présumée nécessaire. [Exporteur versionné](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/engine/exporter.py#L393), [Tête de détection versionnée](https://github.com/ultralytics/ultralytics/blob/v8.4.27/ultralytics/nn/modules/head.py#L182)

## Alternatives sérieuses et objections

1. **Même checkpoint, export non end-to-end, post-traitement Swift porté.** Avantage : permet un diagnostic plus direct de la logique historique de NMS et un contrôle explicite des candidats. Inconvénient : changer de branche n’est pas uniquement reformater les sorties ; il faut comparer les prédictions du checkpoint avec la même branche. À retenir si l’export end-to-end est incompatible avec le runtime retenu, ou si son comportement échoue à une évaluation définie. Un besoin de recopier moins de code ne suffit pas.
2. **LiteRT avec export dédié.** Alternative de déploiement si le chemin ONNX correct reste trop lent ou incompatible sur le téléphone réel. Avant adoption : vérifier conversion, opérations, délégation et parité. Ne pas présumer qu’une extension `.tflite` résout la performance. La quantification arrive après une référence correcte et demande un jeu de calibration pertinent.
3. **Letterbox pour le mode HTC.** Alternative probablement intéressante pour limiter la déformation, mais aucune donnée actuelle n’établit sa supériorité avec ce checkpoint et cette caméra. Comparer le même modèle, les mêmes images et des transformations inverses vérifiées. Conserver le profil Swift pour les références historiques.

**Objection au “maximum huit boîtes” repris partout :** le plafond du Swift suit son propre score/NMS. Avec une autre branche, filtrer huit résultats uniquement par confiance peut écarter un objet central pertinent. Garder la sortie bornée du modèle et définir séparément la sélection métier ; documenter un éventuel plafond après priorité visuelle. Le plafond huit reste une caractéristique du profil de fidélité iOS, pas une propriété universelle du modèle.

**Objection aux seuils de confiance présentés comme validés :** 0,70 pour la détection puis 0,82–0,86 pour certaines alertes visuelles sont des paramètres hérités. Leur reprise ne garantit ni rappel ni fréquence d’alertes sur le nouveau checkpoint. Les métriques historiques embarquées (notamment rappel 0,43043) n’ont pas de protocole réévalué ici et ne prédisent pas la performance avec les lunettes. Aucun objectif de mAP ou de sécurité ne peut être déclaré satisfait à partir d’elles.

## Expériences minimales à inscrire au cahier des charges

| ID proposé | Expérience à réaliser | Critère et preuve attendus |
|---|---|---|
| ML-1 | Export reproductible depuis une copie, environnement isolé Ultralytics 8.4.27 ; ONNX FP32, batch 1, fixe 416, `end2end=true`, `nms=false`. | Manifeste : SHA du checkpoint et de l’export, versions réellement installées, commande, opset, noms/types/formes, ordre des classes, unités et post-traitement. Aucun choix d’opset « magique » sans compatibilité du runtime. |
| ML-2 | Comparer `.pt`, ONNX sur Mac, puis ONNX Android sur **les mêmes tenseurs prétraités**. | Comparaison indépendante de l’ordre des boîtes en cas d’ex æquo ; classes, coordonnées et scores concordants. Tolérances initiales proposées : coordonnées ≤ 1 pixel à 416, scores ≤ 0,001 pour les boîtes appariées ; toute apparition/disparition au seuil doit être expliquée. Ces seuils d’essai restent à approuver par les premiers résultats, jamais à élargir silencieusement. |
| ML-3 | Vérifier la géométrie avec images asymétriques/mire ; coins, centre et cas près des frontières de zone 0,39/0,61. | Transformations aller/retour exactes à l’arrondi près ; gauche/droite cohérentes dans l’image des lunettes, sans dépendre de l’aperçu. Cas portrait/paysage, miroir, recadrage et padding couverts. |
| ML-4 | Comparer `.scaleFill` et letterbox sur un petit lot représentatif fixe et annoté, avec le même checkpoint et la même branche. | Publier différences de détections et d’alertes, notamment objets proches des bords et silhouettes allongées. Les images synthétiques de géométrie seules ne mesurent pas la qualité de détection. |
| ML-5 | Mesurer CPU EP et XNNPACK avec le même ONNX, warm-up puis session prolongée. | p50/p95/max inférence, conversion image et chaîne réception→décision, cadence effective, âge des résultats, mémoire, évolution thermique sur téléphone identifié. Cible provisoire de 4 décisions/s et p95 réception→décision < 500 ms ; aucune promesse avant mesure. |
| ML-6 | Comparer politique Swift/Kotlin sur fixtures de détections identiques ; comparer séparément les deux références de modèle. | Ne pas confondre fidélité d’export, fidélité de politique et changement de modèle. Si le corpus brut OriaLab manque, marquer le replay historique non réalisé. |

ML-2 doit éviter le piège de comparer le `predict()` Python avec son prétraitement implicite à un tenseur Android redimensionné autrement. Deux comparaisons sont nécessaires : une comparaison des tenseurs d’entrée, puis une comparaison des sorties. Les images HTC doivent être datées et leur source (simulateur ou réel) indiquée.

## Réouverture des décisions

- Reconsidérer runtime/provider si la cible de latence ou la stabilité thermique échoue après correction des copies inutiles et files d’attente ; la cadence et les résultats périmés doivent être mesurés avant d’accuser uniquement YOLO.
- Reconsidérer branche end-to-end si export/opérateurs échouent ou si un lot fixe révèle une régression documentée ; conserver les deux artefacts et leurs manifestes.
- Reconsidérer prétraitement et seuils si la géométrie est correcte mais les alertes manquent ou se multiplient sur des scènes annotées ; ne pas changer plusieurs facteurs à la fois.
- Ne pas renommer un échec ML en « obstacle absent ». Une sortie vide, un modèle indisponible et une image trop ancienne sont trois états différents.

Je soutiens un MVP local déterministe. L’architecture agentique est utile pour développer et contester les choix ; un LLM dans la boucle de chaque image n’apporte aucune preuve de valeur ici et ajouterait une dépendance à la cadence des alertes.

## Option iOS ultérieure

Conserver le checkpoint `.pt` original, les manifestes d’export et le contrat de détections/fixtures dans des formats indépendants de Kotlin. Si l’accès iOS est confirmé, produire un **nouvel artefact Core ML du candidat SILMO**, avec son propre nom et son manifeste ; ne pas écraser `best.mlpackage` Paris V1. Un checkpoint peut produire plusieurs exports, chacun avec sa branche et ses transformations vérifiées. La disponibilité d’un export ONNX Android ne prouve pas la réussite ni la parité du futur export Core ML. Partager schémas, paramètres justifiés et fixtures avant de choisir une bibliothèque de code commune ; aucun passage à Kotlin Multiplatform n’est nécessaire pour cette option encore conditionnelle.
