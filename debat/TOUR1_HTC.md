# Tour 1 — HTC, acquisition vidéo et restitution sonore

Auteur : agent `htc_integration`. Inspection du 26 septembre 2026, en lecture seule des sources. Ce rapport formule des choix d'architecture ; aucun build, simulateur ou matériel n'a été exécuté.

Mise à jour utilisateur pendant le tour : un téléphone HTC prêté est disponible pour les essais ; son modèle exact reste à relever. Un accès de développement iOS pourrait être fourni demain, sans confirmation. Kotlin Android dans le starter devient donc la voie opérationnelle immédiate. La perspective iOS ne constitue pas une preuve de SDK HTC iOS, de streaming vidéo ou de sortie sonore utilisables.

## Proposition indépendante

Retenir un unique pipeline local Android, dans une copie du starter avec son identité conservée : adaptateur HTC → réception H.264 → décodeur qui expose ses images → dernier frame décodé disponible → moteur ML → politique Oria adaptée → ordonnanceur vocal → `speakText`. Les agents collaborent au développement et à la revue ; ils ne dialoguent pas à chaque image dans l'application.

Pour le premier prototype d'accès aux pixels, je privilégie **MediaCodec sans Surface d'affichage, avec sortie Image YUV accessible**, sous réserve des capacités du codec du téléphone réel. La conversion respecte crop, rowStride, pixelStride et colorimétrie, puis produit un repère caméra documenté. L'aperçu consomme une sortie optionnelle du pipeline et ne possède plus son cycle de vie. Une seule inférence et une seule image récente en attente. Les images conservées sont copiées/possédées avant libération du buffer codec ; aucun Image ne reste détenu pendant une inférence lente.

Ce choix est une proposition à valider sur le téléphone, pas une API déjà intégrée ou une garantie de débit. L'accès `getOutputImage` exige le mode de sortie approprié : l'ajouter au codec actuel configuré vers une Surface ne donne pas ce chemin. [Référence MediaCodec](https://developer.android.com/reference/android/media/MediaCodec#getOutputImage(int)).

**Alternative sérieuse : conserver le rendu Surface du starter et échantillonner avec PixelCopy.** C'est une preuve de bout en bout plus courte si le codec Image bloque. Elle conserve une dépendance à la surface, ajoute une copie et ne fournit pas intrinsèquement le PTS exact de l'image copiée. PixelCopy utilise le buffer le plus récemment soumis et peut redimensionner vers le bitmap : il faut maîtriser ce redimensionnement pour éviter de fausser le prétraitement YOLO. Cette alternative doit être nommée « mode démonstration avec aperçu actif » si sa fraîcheur et son comportement sans aperçu ne peuvent pas être garantis. [Référence PixelCopy](https://developer.android.com/reference/android/view/PixelCopy#request(android.view.Surface,android.graphics.Bitmap,android.view.PixelCopy.OnPixelCopyFinishedListener,android.os.Handler)).

Une sortie Surface vers ImageReader ou une surface OpenGL hors écran reste un autre adaptateur possible si les capacités réelles le justifient. Ne pas construire simultanément trois chemins. Si ImageReader est retenu, consommation de l'image récente et libération immédiate des anciennes avec une marge de buffers suffisante sont nécessaires. [Référence ImageReader](https://developer.android.com/reference/android/media/ImageReader#acquireLatestImage()).

## Faits vérifiés et conséquences

Les références de code ci-dessous sont sous `/Users/sam/Downloads/eagle-hackathon-starter-usb/android-project/app/src/main/java/com/htc/vive/eagle/hackathon/starter/`, sauf mention explicite.

| Fait local | Preuve | Implication |
|---|---|---|
| Le starter transmet les buffers vidéo compressés au player | `ViveGlassKitManager.kt:351`, `:357` | Pas de flux RGB directement disponible. |
| Le player copie offset/size avant de retourner au SDK | `util/StreamingPlayer.kt:116` | Réutiliser cette propriété de durée de vie ; ajouter réception monotone et identifiant de session. |
| Le codec rend actuellement sur Surface | `util/H264Decoder.kt:273`, `:312` | Il faut réellement changer/adapter le chemin de sortie. |
| Création du décodeur lors de l'attachement de l'aperçu ; détachement arrête le streaming | `ViveGlassKitManager.kt:453`, `:464`, `:472` | Déplacer la propriété du traitement vers la session, ou limiter explicitement le mode de repli à l'aperçu actif. |
| **La vidéo dépend d'une horloge audio** | `util/StreamingPlayer.kt:26`, `:56` ; `util/H264Decoder.kt:224` | Couper naïvement l'audio entrant peut empêcher le démarrage vidéo. Découpler le traitement vidéo de la lecture micro. |
| Horloge audio nulle tant qu'aucune ancre de lecture n'est établie | `util/AudioDecoder.kt:159`, `:311` | La présence de buffers vidéo ne prouve pas leur décodage effectif. |
| Le retour micro demande le haut-parleur intégré du téléphone | `util/AudioDecoder.kt:321` | Le routage de ce retour audio ne prouve pas celui des alertes dans les lunettes. Éviter la boucle acoustique ; vérifier la route réellement utilisée. |
| Grandes files et pertes possibles de buffers encodés | `util/H264Decoder.kt:30`, `:42`, `:109`, `:218`, `:235`, `:291` | `queue.offer` peut échouer ; un buffer déjà retiré est perdu si aucun input codec n'est libre. Corriger le backpressure, ne pas appliquer « dernière image » aux paquets H.264. |
| Tampon nominal de démarrage de lecture | `util/StreamingPlayer.kt:22`, `:23`, `:137` | La valeur 0,2 s et la cadence codée 24 ne sont ni une mesure de latence ni le FPS réel demandé (30). |
| `speakText` transmet au SDK avec locale mise en cache | `ViveGlassKitManager.kt:534` | Premier candidat pour la voix, pas une preuve FR/hors ligne/interruptible. |
| Le callback vocal rapporte succès/erreur/conflit/locale sans identifiant dans cette signature | `ViveGlassKitManager.kt:236` | Un succès ne prouve pas l'audition réelle ; sérialiser et tracer les événements pour éviter les associations ambiguës. |
| Erreur et arrêt player contiennent `TODO()` | `ViveGlassKitManager.kt:370`, `:377` | Remplacer dans la future copie avant de déclarer le pipeline robuste. |
| Les événements surchauffe, batterie basse et conflit sont seulement logués dans cette branche | `ViveGlassKitManager.kt:331` | L'état de disponibilité doit les traiter, avec arrêt/invalidation si le flux n'est plus frais. |
| Le simulateur est un adaptateur interne interchangeable | `ViveGlassKitManager.kt:523` | Ce n'est pas à lui seul un émulateur complet du matériel. |

L'identité à conserver est `com.htc.vive.eagle.hackathon.starter` (`android-project/app/build.gradle.kts:8`, `:14`, README du package). Garder aussi le namespace/signature existants tant qu'aucun problème réel n'exige une adaptation. L'identité d'application et la compatibilité du certificat installé sont deux vérifications différentes ; une erreur d'installation ne justifie pas une désinstallation automatique.

## Audio : capacité limitée par la preuve

Premier adaptateur : phrases françaises brèves et déterministes via `glass.speakText`. Un seul envoi en vol et au plus un candidat en attente, remplacé par le candidat actuel prioritaire. À l'envoi, revalider session, fraîcheur et nécessité de l'alerte. La mémoire anti-répétition doit distinguer décision, tentative d'envoi et résultat de livraison ; un envoi refusé ne doit pas devenir une annonce réputée entendue.

Le starter ne prouve ni annulation de la phrase en cours ni spatialisation sonore. Conserver les indications parlées « à gauche / devant / à droite ». Ne pas promettre que STOP peut couper un son déjà remis au SDK. Garantir immédiatement l'arrêt des nouveaux envois, purger l'attente et mesurer la queue sonore résiduelle. Si l'arrêt immédiat est essentiel et `speakText` ne le permet pas, comparer un chemin TTS Android ou sons préenregistrés **seulement après preuve du routage vers les lunettes et de leur coexistence avec la vidéo**.

Le callback sans identifiant demande une expérience spécifique : envoi A, arrêt/reconnexion, envoi B, callback tardif A. Les générations protègent les décisions internes, mais ne suffisent pas à identifier un callback externe anonyme. Si sa corrélation reste ambiguë, maintenir un état audio incertain et ne pas attribuer le callback arbitrairement à B. Le timeout débloque l'état du pipeline ; il ne transforme pas un succès inconnu en échec certain ou en autorisation d'empiler des phrases.

## Objections adressées aux autres choix

1. **« 4 Hz implique 250 ms de latence » est faux.** La file codec, le prétraitement, une inférence longue et la voix s'additionnent. Mesurer réception téléphone → décision, puis décision → son séparément. Sans horloges de capture comparables, ne pas annoncer lunettes → son comme mesure instrumentée.
2. **« Réutiliser l'intégralité de la boucle Swift » risque de bloquer le MVP.** Les entrées ARKit/profondeur n'existent pas encore ici. Reprendre les règles indépendantes de ces entrées, et définir un état prêt propre au RGB.
3. **« ONNX rapide » ne résout pas une image retournée ou obsolète.** Le contrat entre acquisition et ML doit porter rotation/miroir/rectangles/crop, PTS ou preuve de fraîcheur, et génération. Tester des cibles réellement placées à gauche et droite.
4. **« Haut-parleurs présents » ne suffit pas à valider les alertes.** Le SDK peut avoir un conflit de ressources ou une dépendance réseau. La preuve vidéo + TTS ensemble doit être parmi les premières expériences matérielles.
5. **« Une application en arrière-plan » n'est pas acquis.** Pour le premier scénario, retenir une session au premier plan explicitement. Le changement d'écran ou verrouillage doit mener à un arrêt/degradé clair tant qu'un service adapté n'a pas été implémenté et testé. Découpler l'aperçu de l'inférence ne prouve pas un fonctionnement écran verrouillé.

6. **« Kotlin maintenant implique Kotlin Multiplatform maintenant » n'est pas acquis.** KMP peut partager un cœur métier pur, mais ne résout ni le SDK HTC, ni MediaCodec, ni Core ML, ni le routage audio iOS. Le coût de configuration/export du framework et d'adaptation Swift intervient avant une seconde intégration matérielle prouvée. Je recommande des contrats de données simples et fixtures communes immédiatement, un cœur Kotlin sans dépendances Android pour les règles, et le maintien du moteur Swift comme référence exécutable. Réexaminer KMP si un SDK/entitlement HTC iOS réellement exploitable est fourni, si le temps disponible permet les deux plates-formes, et si un lot de règles communes déjà validées justifie son coût. Une preuve iOS demain doit couvrir caméra réelle → frames accessibles → son simultané, identité/autorisation et distribution sur l'appareil ; une simple ouverture Xcode ne suffit pas.

## Expériences falsifiables à inscrire au cahier des charges

Tous les tests ci-dessous sont **NON EXÉCUTÉS** à cette date.

| Test | Résultat exigé pour valider le choix | Réouverture si échec |
|---|---|---|
| HTC-01 : starter original recompilé dans une copie, appareil ciblé explicitement | Application autorisée, connexion SDK, identité et certificat consignés | Vérifier outillage/signature/whitelist sans changer d'application par défaut. |
| HTC-02 : extraction de 60 s de frames avec repères colorés et asymétriques | Dimensions/couleurs/crop/orientation cohérents, pas d'image corrompue, pas de buffer détenu après libération | Comparer le repli Surface + PixelCopy ; adapter colorimétrie avant ML. |
| HTC-03 : lecture micro désactivée, vidéo continue et phrase TTS émise | Vidéo reste décodée ; phrase effectivement entendue dans les lunettes, sans retour micro sur téléphone | Découpler horloge ; vérifier route et conflit de ressources. |
| HTC-04 : surcharge artificielle du détecteur et reprise | Taille des files décodées bornée ; aucune décision trop ancienne ; pas de corruption H.264 après reprise | Corriger backpressure/attente input codec ; reprise contrôlée du flux si nécessaire. |
| HTC-05 : arrêt, reconnexion, destruction/recréation de surface, callbacks tardifs | Aucun nouvel envoi d'ancienne session ; comportement de la phrase déjà en vol documenté | Renforcer ownership de session et corrélation audio. |
| HTC-06 : TTS français avec streaming puis réseau Internet coupé | Route réellement entendue, délai et support FR consignés dans chaque condition | Déclarer dépendance réseau si nécessaire ; alternative audio mesurée. Le calcul local doit rester indépendant. |
| HTC-07 : 10 minutes avec vidéo + ML + voix | Pas de crash, pas de file croissante, mesures de latence/température et état batterie | Optimiser le goulot mesuré, réduire cadence si besoin ; ne pas annoncer autonomie sur ce seul test. |

L'application peut être testée dans un émulateur Android si les dépendances l'acceptent ; le simulateur HTC intégré vérifie un chemin SDK/média simulé ; un replay métier vérifie les politiques indépendamment du SDK ; seul le couple téléphone/lunettes confirme transport radio, capture réelle, orientation réelle, son, coexistence et performances. Chaque résultat doit nommer son niveau.

## Décisions proposées à l'orchestrateur

- Arrêter l'architecture logique et les contrats de session dès maintenant ; laisser l'adaptateur exact de pixels conditionné au test HTC-02.
- Retenir l'extraction directe du codec comme essai prioritaire, PixelCopy comme repli explicite ; pas d'engagement de performance avant mesure.
- Prioriser la preuve vidéo réelle + image utilisable + TTS réel avant le portage complet de la politique.
- Conserver le SDK TTS comme premier choix, avec état audio honnête et sans promesse de préemption non vérifiée.
- Ajouter le découplage horloge audio/vidéo et les pertes H.264 du starter aux corrections obligatoires du futur pipeline.

Je ne tranche pas le backend ML ni les seuils de risque : les experts correspondants doivent défendre leur choix avec comparaisons. Je demande en retour un contrat géométrique unique, un seuil d'expiration explicite et un résultat de décision dont la fraîcheur est revalidée au moment de l'envoi sonore.
