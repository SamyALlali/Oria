# Oria — travail de nuit du 27 septembre 2026

## Demande et périmètre

Demande utilisateur : enlever les limites de durée des captures et du mode poche, travailler avec trois agents toute la nuit, tester le HTC branché et améliorer les problèmes mesurés. Relais dans ce chat toutes les 30 minutes, jusqu’à 08 h 30 Europe/Paris le 27 septembre. Automatisation : `oria-d-veloppement-de-nuit`. Cette heure borne notre campagne de travail, pas les sessions de l’application.

Le téléphone branché à 00 h 41 est **CN46V3M00284**, HTC U24 pro, Android 14. APK installé avant travail : `f49def1953699349d1c189402c2a7b0229e6bd17db1ca7c0f29c54c9873eeb43`, version 1.0, signature habituelle compatible. Sauvegarde APK + fichiers privés relue : `validation/night-20260927-CN46V3M00284/backup-manifest.json` ; 511 entrées TAR, restauration non testée. Batterie 96 %, 28 °C, charge secteur, /data libre 196 Gio. Aucun effacement ni désinstallation prévu.

## Responsabilités en cours

- Orchestrateur : service poche, intégration, versions/APK, ADB, recette matérielle, documentation, commit/push. Les trois agents ne pilotent pas ADB et ne lancent pas Gradle en parallèle.
- Agent Android/captures : recorder, stockage, OriaLabScreen et tests associés. Retirer 60 s, 250 Mio par capture et quota artificiel 1 Gio ; conserver file bornée et réserve libre 512 Mio, arrêt explicite et arrêt au passage en arrière-plan.
- Agent Mac : lecteur, index HTTP léger, traces JSONL indexées sur disque, mémoire des jobs bornée, imports/exports de longues captures avec réserve disque. Ne redémarre pas le serveur 62044 sans coordination.
- Agent suivi : core et adaptateur Kotlin, audit horloges/événements périmés et tests adversariaux. LEGACY_IOU reste le défaut ; aucun réglage YOLO à l’aveugle.

## Décisions en cours

La suppression demandée porte sur les durées. Le plafond 250 Mio serait encore un arrêt pratique vers 3 minutes (~74–87 Mio/min mesurés sur les captures), il est donc également supprimé au profit de la disponibilité réelle du stockage. Ni purge automatique ni capture automatique au démarrage. Métadonnées de limite durée/taille nulles explicites.

Le mode poche continuera tant que la session active est valide, jusqu’à un arrêt explicite ou à une interruption réelle. Le maintien CPU emploiera une réservation renouvelée contrôlée par propriétaire/token ; le délai du verrou ne doit pas redevenir une limite de session. Destruction de l’Activity/processus et capture en arrière-plan restent des arrêts explicites documentés, pas de relance silencieuse.

## Prochaines preuves

1. Tests recorder au-delà de 60 s et 15 min avec horloge injectée, manque d’espace, arrêt propre et intégrité des payloads.
2. Tests poche au-delà de 15 min/24 h, aucune relance via callback périmé, notification Arrêter et libération du verrou.
3. Build/unit/lint + compilation instrumentée, revue croisée, mise à jour compatible du HTC sauvegardé.
4. Tests instrumentés de stockage et parcours réel permis par l’état des lunettes. Les tests matériels sont surveillés, bornés, et arrêtés ensuite : ne pas laisser une caméra/enregistrement tourner entre deux relances de travail.
5. Rejeu de la capture réelle immuable, parité baseline, benchmark index/mémoire, fixtures de longues sessions, recette UI corbeille HTML corrigée.
6. Bilan versionné distinguant réussite/échec/non exécuté, APK identifié, commit/push code stable. Les captures et traces privées ne sont pas publiées.

## État d’avancement

À compléter après chaque résultat significatif. Le candidat hors téléphone précédent `3ec0e7a`/APK `f4f5c8bd…` est historique ; il ne contient pas encore la suppression des durées. Ne pas attribuer ses tests au nouveau build.


### Première livraison de nuit — Android, Mac et transfert validés

- Retrait des limites durée/taille implémenté ; réserve de 512 Mio + file bornée en capture et export. Sept tests instrumentés recorder réussis sur CN46V3M00284, dont horloge simulée supérieure à 15 min et writer réellement arrêté pour manque d’espace sans perte du paquet précédent.
- Mode poche sans durée maximale : verrou CPU de 2 min renouvelé toutes les 30 s, arrêt manuel/perte de session conservés. Relecture indépendante de deux agents sans blocage ; aucun essai écran verrouillé avec lunettes connectées encore acquis.
- 83 tests JVM Android passent, lint zéro erreur (100 avertissements, 2 indications). Deux corrections successives du core ont des APK/proofs distincts ; le build final est `4a1e34eebee2e6a7074b14a7d8296314c48104207cf366f04bc306fe79edc911`, versionCode 3 / 1.2-night. Installation et instruments finaux dans le sous-dossier `final/`.
- Core : 40 tests ciblés + 6 adaptateur, 24 h virtuelles dans chacun des modes (518 920 évaluations). Quatre régressions reproductibles corrigées, parité 164/164 évaluations et 12/12 annonces simulées conservée pour les deux modes. Preuves avant/après copiées dans ce dossier de campagne.
- Lunettes toujours déconnectées après une tentative de connexion réelle : pas de nouvelle écoute humaine ou preuve de flux physique. Sur l’APK final : **10 tests instrumentés passent**, dont les 7 recorder, XNNPACK (six fixtures), décodage H.264 et replay de 60 s. Replay : 1 787 images décodées, 224 décisions toutes fraîches, 3,727 Hz, âge p95 294 ms, inférence p95 157,17 ms. Transport SDK non exercé et voix factice. Les cinq manifestes des captures préexistantes sont inchangés octet pour octet. Accueil et Oria Lab lancés avec modèle prêt ; texte sans limite vérifié. Rapports : `night-20260927-CN46V3M00284/final/summary.json`, `capture-preservation.json` et XML UI.
- Mac : l’index de la scène de 164 PNG passe de 11,44 Mo à 34,6 Ko. **36 tests Python passent**, plus les interactions JavaScript. JSONL lus à la demande, rapports écrits progressivement et téléchargés en flux. Pic Python de chargement 3,15 Mio et de job A/B 2,39 Mio, parité 164/164 politiques inchangée. Recette UI : capture synthétique ouverte, confirmation corbeille annulée puis validée, restauration et image suivante ; scène réelle A/B, navigation vers un écart et rapport JSON de 164 images téléchargé (11 611 286 octets, SHA `9315719bdd4c73ba547b819ee6573db6266bfbc21f8424eb820d58b936903f09`). Résultats A/B inchangés : 26 images avec écart métier, 5 avec une annonce différente, 125 avec identifiants différents, 12 annonces par variante. Aucune écoute automatique. Mesures et captures UI dans `final/`.

### File de travaux suivante

1. Terminer recette du build final et tenter une seule connexion réelle si les lunettes sont disponibles. Si elles restent déconnectées, marquer le blocage matériel et continuer hors lunettes, sans répéter une boucle Bluetooth infructueuse.
2. Finir revue croisée du lecteur Mac, tests de longs fichiers, état des jobs/annulation/export et ancienne capture. Redémarrer le serveur 62044 seulement hors job actif, puis recette visuelle bibliothèque/corbeille HTML et A/B.
3. Évaluer mémoire de la galerie Android sur une longue fixture : PNG une à la fois, listes de frames/détections encore linéaires. Mesurer avant de décider pagination/index ; ne pas tronquer silencieusement les captures.
4. Vérifier les voies d’arrêt/race et notifications du mode poche dès que le matériel le permet. Une endurance du seul modèle ne prouve pas écran verrouillé + SDK + voix. Ne pas lancer de caméra ou de capture non surveillée entre passages.
5. Actualiser guides/manifeste d’installation et bilan, commit/push les changements testés. Faire une dernière revue des modifications de nuit et laisser l’app arrêtée sur son accueil, sans session automatique.

### Points supplémentaires trouvés pendant l’intégration

- Le transfert USB conservait 2 Gio / 10 000 fichiers : obstacle réel à une capture de 30 min ou 2 h. Corrigé sans modifier l’APK : 128 Gio / 100 000 fichiers, réserve de 512 Mio, ZIP64 en flux. **11 tests passent**, revue indépendante sans bloqueur. Deux courses concurrentes corrigées : réservation exclusive du `.part` et publication sans remplacement d’une archive existante. Transfert réel de 91 739 169 octets réussi, SHA `fe203cb695115dfac7d7b87716d094070614eb26bfd1a45764dbadc0249a49ae` ; 168 payloads (164 PNG, vidéo et 3 JSONL) strictement identiques par SHA256 à la copie Mac existante. Preuves dans `final/usb-transfer/`.
- Pendant une navigation Mac, le titre de la nouvelle image précédait brièvement son résultat. Corrigé et testé : l’ancien visuel/détail est masqué pendant le chargement, puis seule la génération courante apparaît. Test JS avec réponse suspendue et vérification navigateur après rechargement : image 6 cohérente dans titre, métriques et décision.
- Le téléchargement du rapport JSON est désormais en flux. L’export ZIP depuis le navigateur utilise encore `blob()` : point à mesurer/corriger au prochain passage pour les longues scènes. Les index et listes d’annonces restent O(n) ; ne pas prétendre que toute la mémoire est constante.
- Après les instruments et l’inspection UI, Oria a été arrêté explicitement avec `am force-stop`. Aucune capture/caméra/annonce laissée active sur le HTC. Le serveur Mac reste sur `127.0.0.1:62044`.

### Relais pour le prochain passage de nuit

Première livraison compilée/installée et recette terminée ; voir aussi `artifacts/night-20260927-v1.2-validation.json`. Ne pas répéter tous les benchmarks déjà passés sans nouveau changement ou échec. Continuer sur les limites mesurées :

1. Export ZIP du navigateur : remplacer le blob intégral par une publication temporaire réservée et un téléchargement en flux, avec tests d’annulation, manque de disque et concurrence. Conserver origine/jeton et absence d’exposition réseau.
2. Galerie Android : lire `LONG_CAPTURE_FOLLOWUP.md`. Mesure JVM Mac de 2 h synthétiques : 21 622 frames, 1 669 ms et 10,57 Mio de heap conservée ; un parseur ignorant les sorties brutes inutilisées gagne environ 27 %, sans changer les résultats. Défaut prioritaire reproduit : une PNG manquante réduit silencieusement 20 entrées à 19 sans erreur. Ajouter un diagnostic explicite des entrées manquantes/invalides avant de décider une pagination plus large. Ces chiffres ne sont pas des mesures HTC.
3. Les lunettes restent déconnectées. Ne pas répéter les tentatives toutes les minutes ; un changement matériel réel sera nécessaire pour valider écran verrouillé et écoute 70/30. Le téléphone est branché, installé et sauvegardé.
4. Toute nouvelle modification Android impose un nouveau build identifié et une mise à jour compatible, suivis de tests pertinents. Les preuves de la version 1.2-night gardent leur SHA ci-dessus.
5. Serveur Mac lancé dans la session exec 69914 au port 62044 ; maintien éveillé temporaire du Mac dans la session 16467 jusqu’à 08 h 30. Onglet conservé dans le navigateur, scène réelle 164 images, lecture arrêtée. Aucune caméra ou voix laissée active sur le téléphone.

Le relais planifié reste actif jusqu’à 08 h 30 Europe/Paris, puis doit produire un bilan de la campagne et cesser les travaux programmés. Les trois agents ont des périmètres distincts ; vérifier leur état avant de leur redonner une tâche.
