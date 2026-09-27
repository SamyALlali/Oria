# Fusion sélective — 27 septembre 2026

## Périmètre et références immuables

Branche de travail **fusion**, créée depuis **main b650dad** (1.7-unified). Référence Rayan : **96d524a41986f3b6799c1743055a7a7e4f18fec9**, contenant la fusion fonctionnelle **40e80182e65b989dc946443467d5bbf26226be14** et le nouveau front. Ancêtre commun **f3454f69db44058d5bb9bf1c6832f49bafac2271**. Aucune fusion Git globale, aucun remplacement de MainActivity/Controller/SDK, aucune modification de main.

L’[inventaire fichier par fichier](FUSION_FILE_INVENTORY.csv) couvre **474 chemins**, comparés par leurs objets Git : **327 identiques et 147 différents**. Les décisions détaillées sont reliées aux trois revues : [navigation/audio/interface](FUSION_NAVIGATION_REVIEW.md), [moteur et sessions](FUSION_ENGINE_REVIEW.md), [Lab/rejeu/confidentialité](FUSION_LAB_REVIEW.md). Les rapports de Rayan ont servi de références ; leurs affirmations ne sont pas assimilées à des validations exécutées pendant cette fusion.

## Ce qui fonctionnait sur main et reste conservé

- Package `com.htc.vive.eagle.hackathon.starter`, intégration VIVE Eagle, décodeur H.264, horloge vidéo indépendante du microphone, images récentes après décodage.
- Deux analyses indépendantes : YOLO 333 ms et profondeur 252 un échantillon sur deux, CPU 4. Modèles, pixels, prétraitements, seuils et boîtes inchangés.
- Politique vocale RGB habituelle, politique expérimentale d’obstacles, alternance des deux offres fraîches, confirmations audio séparées des observations. Le suivi V2 n’est pas promu.
- Français et stéréo 70/30, « avant-gauche », « devant », « avant-droite ». Aucun nouveau test d’écoute humaine ne leur est attribué.
- Accueil accessible, grands contrôles, bouton démarrer/arrêter, diagnostic secondaire, assistance en veille sans durée arbitraire. Le service connecté/lecture et son verrou CPU restent inchangés.
- Captures longues, réserve disque512 Mio, intégrité des imports, fichiers privés locaux, galerie/corbeille/restauration, profondeur et inspection image par image dans Oria Lab.

L’ancienne branche Rayan part d’avant ces ajouts. Elle retire notamment nos modules profondeur Android 252, réintroduit des réglages poche/orientation, et utilise une profondeur MiDaS séquentielle. Reprendre son Controller aurait régressé.

## Décisions et ajouts

| Élément | Décision | Résultat sur fusion |
|---|---|---|
| Navigation guidée | Reprise adaptée | Recherche Android, destination confirmée explicitement, destinations enregistrées localement, itinéraire piéton FOSSGIS/OSRM, démo sans réseau, pause/reprise/recalcul/arrivée. Panneau ajouté à notre accueil. |
| Cycle navigation | Corrections nécessaires | Un calcul en vol, annulation réseau, jetons d’opération et d’abonnement GPS, gardes de génération, précision/fraîcheur, deux positions précises avant arrivée. Les résultats tardifs n’activent pas un ancien trajet. |
| Réseau et GPS | Limitation explicite | Consentement avant adresse/position réseau ; requêtes espacées d’au moins 1 s, cache géocode borné, corps HTTP limité, graphe piéton dédié et vérification `walking`. GPS seulement avec l’application visible. Écran verrouillé : navigation en pause, assistance existante conservée si démarrée. |
| Ordonnanceur audio | Idée reprise, transport adapté | Une voie commune pour dangers, navigation et réponse explicite. Une alerte fraîche peut interrompre une instruction ; attendre le vrai terminal Bluetooth et la route prête avant la phrase suivante. Pas de préemption aveugle des alertes entre elles. |
| Commandes vocales | Reprise partielle | Parseur français, dictée via ActivityResult corrélé du téléphone, guide vers/pause/reprise/arrêt/confirmation/sélection/répétition fraîche. Le démarrage de l’assistance reste son bouton/service autorisé. Les commandes non prises en charge ne changent pas silencieusement l’état. |
| Bouton IA | Reprise partielle | Écoute de l’événement depuis le Controller ; un appui annonce l’état, deux appuis demandent la dictée au premier plan. Le séquenceur est testé ; geste physique et microphone restent à vérifier. |
| Transcription mains libres HTC | Non reprise | Callbacks SDK anonymes : un ancien résultat après timeout pourrait être attribué à une nouvelle demande. Un UUID local ne résout pas l’absence d’identité côté SDK. Pas de prétention de microphone lunettes opérationnel. |
| Résolution complète des dangers | Diagnostic seulement | Candidats/retraits RGB observables, contrats de provenance/profondeur, arbitrage Swift et mémoire bornée, gardes session/horloge/producteur. `selected` peut être une mémoire ; `freshSelected` l’exclut. Aucun changement de sélection vocale live. |
| Priorisation générique/semantic Rayan | Non promue | Scores relatifs et RGB non comparables ; le Controller Rayan continuait de prononcer `eligibleAlert` RGB plutôt que le danger résolu. Pas de preuve que ce remplacement ferait mieux que l’alternance stable. |
| Profondeur monoculaire Rayan | Non reprise | Conserver Depth Anything et ses deux workers, seuils et fixtures. Aucun LiDAR/distance métrique inventé, aucune garantie de passage libre. |
| Rejeu complet Rayan | Non repris tel quel | Horloges d’observation réécrites et réservations/confirmations RGB non rejouées ; harnais non branché au Mac. Ce n’est pas une parité du runtime complet. |
| Compatibilité Lab | Reprise ciblée | Reader Mac accepte v1/v2 strictement typés, refuse conflits/valeurs booléennes, import immuable. Writer actuel demeure v1. Le système existant de rejeu et de profondeur est conservé. |
| Confidentialité | Reprise adaptée | Filtrage contextuel avant traces fichier/Logcat et enregistrement ; adresses/GPS/transcriptions et texte libre vocal retirés. Les 15 phrases fixes de danger restent rejouables. Métadonnées techniques conservées ; captures et destinations exclues des sauvegardes automatiques Android. Cela n’anonymise pas les images. |
| Grand payload profondeur | Correction d’un défaut mesuré | JSON 128×128 réel de fixtures≈308–309 Ko dépassait 256 Kio. Plafond par événement porté à 512 Kio ; file 20 Mio/réserve 512 Mio inchangées. Aucune limite de durée ajoutée. |
| Dashboard/front/expert | Conserver main + panneau | Notre accès principal accessible et son diagnostic existent déjà ; ne pas remplacer l’ensemble pour un style différent. Navigation et commande vocale ajoutées avec contrôles larges. |
| État runtime/circuit breakers Rayan | Idées partielles, pas de nouvel owner | Une seule génération Controller ; les deux workers ont des âges différents. Une horloge globale d’évidence rejetterait légitimement certains résultats profondeur. Conserver nos gardes, ajouter celles des nouvelles fonctions. |

## Revue et corrections vérifiables

Les trois agents ont possédé des fichiers distincts. Le moteur a relu le cycle GPS, le Lab a relu l’ordonnanceur/transport, l’orchestrateur a raccordé les nouvelles fonctions au Controller et à l’Activity.

Défauts corrigés : relance du routage à chaque fix ; callback d’ancien abonnement après pause/reprise ; première position près de l’arrivée prononçant une vieille consigne ; nouvelle soumission audio avant libération du lecteur ; route Bluetooth non réchauffée après interruption ; disparition de la destination lors du passage en arrière-plan sans perception ; filtre privé supprimant le champ technique `modelManifest.output.coordinates`.

Les preuves initiales d’échec sont conservées localement dans `validation/fusion-20260927/`. L’échec d’arrivée a été corrigé en gardant le test strict. L’exception du filtre est bornée au chemin et à la valeur technique exacte « letterboxed input pixels », jamais aux coordonnées GPS.

## Validation

- Référence main : **127 tests JVM**, APK compilé ; **174 tests Python**.
- Nouvelle suite Android : **200 tests JVM passent, compilation app et instruments réussie, lint sans erreur** ; commande `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
- Mac : **176 tests Python passent**. Aucun nouveau pipeline graphique ni modèle Mac.
- Parité des politiques RGB contre b650dad : **1 800 frames, 2 352 commandes**, mêmes champs historiques, décisions, tickets et annonces dans les deux modes de suivi. Le diagnostic ajouté est exclu de cette projection ; ce test ne compare pas des performances radio.
- Référence Swift : **10/10 fixtures de fonctions pures** exécutées sur Mac. Ce n’est pas une preuve de parité de tout le moteur Swift.
- Sonde réseau depuis le Mac, coordonnées publiques de démonstration : réponse `Ok`, **89,1 m, 5 manœuvres, mode walking** sur le graphe `routed-foot`. Aucun lieu de l’utilisateur transmis. Ni parcours réel ni GPS HTC validé.
- Recette HTC : **17/17 tests instrumentés passent**, incluant 3 tests privacy, 9 recorder, 1 navigation et cycle Activity réel, 1 Controller à deux modèles, 3 UI de reprise audio. Les captures des tests sont synthétiques et isolées. Les tests de durée utilisent une horloge simulée, pas quinze minutes de radio réelle.

## Procédure de démonstration

1. Démarrer Oria comme sur 1.7 pour la caméra et les alertes. Les deux modèles restent simultanés ; arrêt accessible depuis l’app/notification.
2. Dans Navigation, Choisir une destination → Démo sans réseau → saisir un lieu de démonstration → Préparer la démo → Confirmer.
3. Avancer d’une étape, mettre en pause, reprendre, poursuivre jusqu’à arrivée. Le texte reste disponible sans lunettes ; la voix nécessite la sortie Bluetooth réelle prête.
4. Pour le trajet réel, sélectionner Trajet piéton réel, autoriser explicitement la recherche réseau, rechercher/choisir, autoriser la localisation si nécessaire, puis confirmer à nouveau. Garder Oria visible pour le GPS.
5. La dictée ouvre le service vocal du téléphone (préférence hors ligne, dépendante du service installé). Vérifier une destination avant confirmation. Le bouton IA physique et l’écoute du nouveau partage audio demandent une recette humaine.
6. Arrêter navigation et Oria après l’essai. Aucun flux/capture n’est laissé actif à la livraison.

## Limites restantes

Cette branche est un candidat **1.8-fusion-preview**, pas une promotion automatique sur main. À tester humainement : écoute et délai de préemption dans les lunettes, vraie localisation piétonne/recalcul sur trajet, gestes IA, dictée disponible sur ce HTC, TalkBack, assistance prolongée écran verrouillé avec navigation mise en pause. Les preuves humaines des anciennes versions restent historiques. La détection des murs/obstacles reste expérimentale même si l’utilisateur a obtenu un essai positif.

La lecture v2 du Mac prendra effet au prochain lancement du serveur ; le serveur déjà ouvert n’a pas été interrompu pour préserver ses rapports temporaires. Les captures originales et `pitch-oria/` restent hors des commits de fusion.

## Installation finale

**Recette finale réussie : 17/17 instruments en 18,124 s.** Le HTC identifié est **CN46V3M00284 / HTC U24 pro**, package inchangé. L’APK 1.7-unified et les préférences privées ont été sauvegardés localement avant mise à jour. Mise à jour `install -r`, aucune désinstallation ni purge. Empreinte APK local et installé vérifiée identique : `777e0716342f491870fc4e86e4180271f82b9a54cb4cb534bd537bfcb673a660`. Signature identique à 1.7. [Manifeste final](../artifacts/fusion-20260927-build.json). Les 2 sessions simulateur 4 s sont arrêtées explicitement ; aucun flux ni capture actif après les tests.
