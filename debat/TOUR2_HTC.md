# Tour 2 — Réponse contradictoire HTC / Android

Agent `htc_integration`, 26 septembre 2026. Sources lues : `TOUR1_ML.md`, `TOUR1_PORTAGE.md`, cahier des charges version 1.0 et code HTC déjà cité au tour 1. Aucun essai exécuté. Ce rapport corrige et précise ma proposition ; il ne décrit pas une solution validée sur le téléphone prêté.

## Réponse aux quatre objections de l'orchestrateur

### 1. PixelCopy ne prouve pas l'âge des pixels à partir d'un timestamp reçu

**Objection acceptée.** La réception récente d'un paquet H.264 et la réussite d'une copie de surface ne prouvent pas que les pixels copiés correspondent à ce paquet. Il serait erroné d'attribuer à ce bitmap le temps du dernier callback vidéo ou le PTS du dernier buffer rendu sans corrélation vérifiée.

Dans ce mode, publier `demande de copie → pixels disponibles → décision` reste possible, mais ne valide pas N03 « réception de cette image → décision » ni la borne d'âge du résultat. Un watchdog de réception récent améliore la détection des pannes ; il ne rétablit pas cette preuve d'identité. Même des pixels identiques peuvent provenir d'une scène immobile ou d'un flux figé : un hash ne fournit pas cette preuve.

**Correction de décision :** PixelCopy est un outil de déblocage ou une démonstration partielle, avec champs de fraîcheur inconnus clairement déclarés. Aucun succès P0 n'est attribué à F02/N03 sur cette seule base. Le chemin nominal doit permettre une association vérifiée entre sortie décodée, PTS et réception du frame. Si le SDK fragmente une image sur plusieurs buffers, définir précisément première/dernière réception de l'unité d'accès, consigner le choix et ne pas confondre paquet et frame.

### 2. Contrat minimal pour la voix anonyme après timeout/reconnexion

**Objection acceptée.** La génération protège les objets possédés par l'application. Elle n'ajoute aucun identifiant à un événement anonyme livré par le SDK.

Contrat minimal recommandé pour `AlertSink` :

- États `Ready`, `InFlight(commandId, transportEpoch)`, `Uncertain` et `Unavailable`. L'identifiant interne sert aux traces ; il n'est jamais présenté comme un ID retourné par HTC.
- Une seule requête SDK en vol. Les callbacks ne peuvent terminer cette requête que si le contrat vérifié du SDK permet de les associer à l'unique requête et précise leur sens terminal. `SUCCESS` ne signifie pas automatiquement « phrase entendue ».
- Un timeout, une reconnexion ou une rupture de corrélation pendant un envoi entraîne `Uncertain`. Le message est de livraison inconnue ; il n'est ni confirmé ni réaffecté. Aucun nouvel envoi n'est autorisé dans cette époque de transport incertaine. Le traitement image peut continuer et l'UI annonce audio indisponible/incertain.
- Les callbacks tardifs de cette époque sont logués comme ambigus et ignorés pour le nouvel état métier. Un simple délai fixe, une nouvelle génération ou un bouton Réessayer ne suffisent pas à certifier une remise à zéro du transport.
- Retour à `Ready` uniquement après une procédure dont le contrat isole les callbacks anciens : annulation/drain confirmé par API, ou reconstruction de client avec isolation démontrée de ses listeners, ou autre procédure HTC documentée et testée. `disconnect/connect` ne reçoit pas cette propriété par hypothèse. Si aucune procédure n'est prouvable avec le SDK fourni, l'audio reste dégradé pour cette session ; l'exigence de reprise automatique n'est pas déclarée satisfaite.
- Les essais V03/V06 injectent timeout puis callback tardif, double callback, arrêt et reconnexion. Un callback A ne doit jamais confirmer B. Conserver séparément tentative et confirmation dans l'anti-répétition ; l'état incertain ne déclenche pas une boucle de retries.

Cette règle assume une dégradation visible après ambiguïté au lieu d'inventer une corrélation. L'étude du contrat SDK réel et les essais sur les lunettes peuvent permettre un mécanisme de reprise moins conservateur. Le starter ne fournit pas cette preuve : callback dans `ViveGlassKitManager.kt:236`, envoi à `:534`.

### 3. N07 exclut PixelCopy sur aperçu comme succès nominal

**Objection acceptée.** Je retire toute ambiguïté entre « repli utilisable pour avancer » et « chaîne conforme P0 ». N07 exige l'indépendance de l'aperçu ; la variante PixelCopy sur SurfaceView visible ne la satisfait pas. Elle nécessite une dérogation documentée avec P0 incomplet. Changer d'écran en gardant un aperçu caché fragile ne constitue pas une validation de N07.

Je maintiens MediaCodec sans Surface d'affichage et sortie Image exploitable comme premier essai. L'alternative hors écran (SurfaceTexture/OpenGL ou ImageReader compatible) devient pertinente si ce mode échoue sur le téléphone, sans promettre que toutes les combinaisons codec/format sont supportées. Le contrat FrameSource reste le même. La documentation indique que `getOutputImage()` peut retourner null lorsque le codec a été configuré avec une Surface : il faut un véritable changement de mode. [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec#getOutputImage(int)).

### 4. CPU puis XNNPACK dans le même benchmark

**Accord.** Le CPU sert d'abord à établir une sortie correcte. Une fois les mêmes tenseurs/boîtes/scores vérifiés, ajouter XNNPACK comme deuxième configuration du même ONNX sur le même téléphone et le même protocole. Ce n'est pas une deuxième conversion ni une raison de bloquer la preuve audio/vidéo. La correction doit être revérifiée pour le provider et le choix final dépend de toute la chaîne, de la cadence soutenue et de la température, pas uniquement d'un temps d'inférence favorable.

## Objections aux propositions des autres experts

### ML : `.scaleFill` n'est pas automatiquement le profil HTC de production

Je soutiens le profil historique `.scaleFill` pour des comparaisons contrôlées. Je conteste la formulation « isole les différences de runtime » si elle sert à comparer directement nouveau `.pt` end-to-end et ancien Core ML non end-to-end : checkpoint et branche restent deux variables distinctes. Comparer `.pt`/ONNX à **tenseur identique et branche identique** valide la conversion ; comparer `.scaleFill`/letterbox évalue un choix de préparation ; comparer Paris V1/SILMO traite deux références non prouvées équivalentes.

Sur des images de lunettes potentiellement allongées, forcer `.scaleFill` pour économiser une décision peut déformer les silhouettes. Je recommande deux profils nommés et des transformations inverses testées, puis un seul profil retenu par l'expérience ML-4. Aucun mélange de rotation du téléphone, taille de l'aperçu et repère tête. J'accepte la sortie end-to-end sans NMS supplémentaire par défaut, sous réserve du contrat effectivement exporté.

### Portage : suivi 2D utile, identité limitée et dépendante de la fraîcheur

J'accepte une association un-à-un des détections par classe, recouvrement et continuité. Elle corrige la fusion de toutes les personnes sous `worldAnchor=nil`. Je conteste toute durée de maintien portée directement depuis la mémoire AR : un grand mouvement de tête peut rendre l'appariement incertain en une image. Les observations périmées ou les trous de flux doivent invalider l'association avant de déclencher une phrase. L'IMU du téléphone ne peut pas justifier le contraire.

Le repli classe/zone proposé au tour 1 du portage peut réduire la complexité, mais il ne satisfait pas F06 tel qu'écrit si deux personnes d'une même zone deviennent indistinguables. Il peut être présenté comme alerte collective, avec une limitation de démonstration, ou provoquer une révision documentée de F06 ; il ne doit pas être déclaré suivi individuel équivalent. Le seuil IoU et le timeout de piste sont à valider sur fixtures, pas à reprendre d'un exemple public sans comparaison.

J'accepte pleinement la distinction sélection / soumission / confirmation de voix et l'arrêt des nouveaux envois plutôt qu'une promesse de silence instantané non prouvée. Il faut cependant ajouter l'état `Uncertain` et la quarantaine de transport décrits ci-dessus : une liste d'états sans règle concrète de non-réaffectation serait insuffisante.

### Cahier : différencier prêt visuel et audio opérationnel

F01 doit permettre d'observer séparément `perception prête` et `audio prêt`. Une perception correcte sans chemin de voix peut continuer pour diagnostic, mais l'ensemble ne doit pas afficher un unique « prêt » qui ferait croire que les lunettes alertent. Les refus micro/caméra doivent être différenciés ; le starter demande les deux dans son appel vidéo actuel. Ne pas prétendre avoir supprimé cette permission avant de vérifier l'API réelle.

## Choix maintenus et limites ouvertes

1. **Android Kotlin maintenant**, dans une copie du starter et identité inchangée. Le téléphone HTC est disponible ; son modèle, Android et codec restent à identifier. Les prototypes matériels priment désormais sur des suppositions basées sur le rapport organisateur.
2. **Cœur métier pur et fixtures communes maintenant ; pas de KMP imposé.** L'accès iOS éventuel demain déclenche seulement V09 : autorisation HTC, caméra accessible et voix pendant streaming. Aucun bénéfice supposé ne justifie de refaire aujourd'hui les adaptateurs Android en multiplateforme.
3. **Horloge vidéo indépendante de la lecture micro** et correction des pertes H.264 du starter sont indispensables. Dernière image seulement après décodage ; taille et âge des files encodées observés avec reprise contrôlée si nécessaire.
4. **Codec vers images traçables nominal ; PixelCopy partiel.** Le choix du mode exact de décodage reste provisoire jusqu'à V02 ; l'invariant de fraîcheur et l'indépendance de l'aperçu ne le sont pas.
5. **TTS HTC premier essai ; capacités à prouver.** Français, son dans les lunettes, Internet, conflits et queue résiduelle sont encore ouverts. Après perte de corrélation, pas d'envoi B tant qu'un callback A pourrait lui être faussement attribué.

Les points sur lesquels j'ai changé la proposition sont donc le statut explicitement incomplet de PixelCopy, l'interdiction de reconstruire artificiellement son timestamp et le contrat concret de quarantaine audio. Aucun désaccord restant ne nécessite un vote : V02 départage les chemins de pixels, V03 les capacités vocales, ML-4 les prétraitements et ML-5 le provider d'inférence.
