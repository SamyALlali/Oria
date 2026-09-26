# Coordination de la reprise — 26 septembre 2026

Cette répartition remplace celle de l’implémentation initiale, sans recopier les sources originales.

| Responsable | Fichiers possédés dans cette reprise | Réalisation |
|---|---|---|
| Orchestrateur | Contrôleur/cœur si nécessaire, ressources de nommage, build, ADB, README/recette/manifeste/cahier/registre | Intégration, compilation, téléphone ciblé et preuves |
| portage_decision | MainActivity.kt, ui/tab/AppDestination.kt, echonav/ui/ | EchoNav accueil, Diagnostic HTC secondaire, commandes fixes |
| htc_integration | ViveGlassKitManager.kt, echonav/video/ | Audit et corrections de cycle de vie ; revue croisée UI |
| modele_mobile | echonav/ml/, ml/, fixtures/, ML_NEW_DEVICE_PREPARATION.md | Intégrité/reproductibilité, analyse des nouvelles mesures |

Un seul opérateur ADB : l’orchestrateur, numéro ciblé `CN46V3M00284`. Aucun benchmark concurrent ni commandes ADB déléguées. L’agent HTC a relu les modifications de navigation ; l’agent UI a vérifié le branchement du nouvel arrêt des médias. Ces revues statiques sont distinctes de la recette sur l’appareil.

Le modèle, les seuils, le contrat `[1,3,416,416]` → `[1,300,6]` et les fixtures restent inchangés. Les rapports historiques du téléphone `CN4B53M00860` restent préservés ; le nouvel appareil dispose de son dossier propre. Voir `validation/RECETTE.md` pour l’état effectivement obtenu.

## Réouverture D12 après mesure de la voix

Le SDK refuse sa synthèse pendant la vidéo. Après revue de son bytecode, la voie locale Bluetooth a été confiée à portage_decision (`echonav/audio/`), avec revue indépendante HTC de la route, du focus et des callbacks. L’orchestrateur possède l’intégration dans EchoNavController, la sélection UI et les essais ADB. Le manager HTC passe uniquement EchoNav sur la surcharge vidéo seule ; le diagnostic historique conserve ses entrées.

Après écoute manuelle simultanée confirmée, les premières annonces d’objets expirent à cause de l’amorçage audio par phrase. Le même agent prépare la réutilisation de route pendant une session ; l’agent HTC relit génération, annulation et drainage par phrase. Le ML reste inchangé ; son agent prépare les analyses des traces et de l’endurance. Le verdict final est établi sur les données du nouveau build, jamais transféré du build précédent.

## Stéréo et rythme D13/D14

À la demande explicite de l’utilisateur, l’agent portage a ajouté normalisation PCM16 stéréo, canal par zone et neuf tests ; le contrôleur/cœur/UI sont intégrés par l’orchestrateur, relus par HTC. 48 tests passent et l’utilisateur confirme l’écoute stéréo sur le nouveau téléphone. L’agent ML a analysé l’endurance à 250 ms : continuité réelle mais débit chaud dégradé. Il propose l’expérience333 ms ; l’orchestrateur l’applique seulement au manager EchoNav, avec revue HTC du placement après décodage et critères mesurables fixés avant essai. Aucun changement du modèle ou de sa parité. Les scripts d’analyse attribuent chaque run à son APK et sa période ; les données ne sont pas déplacées d’un run à l’autre.

## EchoTest — répartition du 26 septembre 2026

- HTC : `echonav/recording/`, hooks manager/vidéo, contrat de session.
- Portage : contrôleur, navigation et UI EchoTest, export SAF.
- ML : `echotest-desktop/`, import, lecteur, prétraitement et réinférence Mac ; extension diagnostique optionnelle des sorties brutes ONNX.
- Orchestrateur : adaptateur `echotest-policy/` recompilant le cœur Kotlin existant, intégration, ADB unique, build/recette et registres.

L’endurance333ms est terminée avant cette extension : 620,346 s, 1 632 décisions fraîches, 2,631 Hz, âge p95 406 ms, critères D14 réussis. Ses preuves restent attachées à l’APK40175f…, distinct de la future surcharge de capture.

## Clôture EchoTest — 26 septembre 2026

Les trois agents ont livré le recorder/SDK, le lecteur Mac/ML et l'interface/contrôleur ; l'orchestrateur a livré le runner du vrai cœur Kotlin, le transfert USB, l'intégration et la recette. Revue croisée des hooks, limites mémoire et chronologie de replay réalisée avant build. La correction d'initialisation différée de navigation est couverte par deux tests ; le crash et le premier échec de compilation des tests restent archivés.

Build final `9dbaec…`, 50 tests JVM et installation compatible sans effacement. Trois captures réelles ont été finalisées, la capture d'une minute a été exportée par USB et SAF avec contenus identiques, et le lecteur Mac a été utilisé image par image puis en recalcul. Rapport de référence : `validation/new-device-CN46V3M00284/echotest-validation.json`. La preuve stéréo utilisateur appartient à `412eec…`, l'endurance physique333 à `40175f…` et les nouvelles captures à `9dbaec…` ; aucune preuve n'est réattribuée.

## Audit utilisateur0548 et stéréo70/30 — livraison

Capture sélectionnée par manifeste : dernière capture complète de60s, datée26/09/2026 à17:16:37,025. ML :164réinférences et14planches ; portage :164rejeux métier exacts et25transactions vocales réelles ; HTC : mixPCM70/30 et revue visuelle seconde moitié ; orchestrateur : première moitié,13cibles d'annonces, intégration,51tests, installation compatible et synthèse. Le moteur et le modèle sont inchangés.

Les défauts d'identité/rappel et faux positifs sont conservés comme constat de l'audit, sans ajuster les seuils pour cette scène. Source/audio7030 et résultats historiques100/0 sont séparés. Rapport `validation/user-scene-0548b68a/AUDIT.md`, réexécutions déterministes vérifiées. Correction UI Mac des événements audio mal libellés pendant attente validée manuellement.
