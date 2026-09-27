# Oria 1.7 — assistance unifiée et veille automatique

## Demande et comportement

L’utilisateur a confirmé que les murs étaient détectés lors de son essai personnel de1.6. Cette observation concerne cet essai et ne généralise pas la qualité à tous les obstacles.

La version **1.7-unified / code8** réunit objets YOLO et obstacles possibles dans la même session. Le sélecteur de modèle, le réglage poche, les tests de voix gauche/centre/droite, la confirmation manuelle du repère et les contrôles rotation/miroir disparaissent de l’accueil. Le profil0° sans miroir précédemment validé par l’utilisateur sur ce HTC est appliqué automatiquement. Phrases : « Obstacle possible devant / avant-gauche / avant-droite ». Les gains stéréo70/30 restent inchangés.

**Démarrer Oria** prépare automatiquement le service Android, sa notification Arrêter et son verrou CPU renouvelé, puis démarre la perception. Aucune minuterie maximale. Notifications autorisées, lunettes réelles connectées, deux modèles prêts et voix Bluetooth disponible sont requis ; un refus est affiché sans prétendre que l’assistance fonctionne. La veille et Home sont pris en charge tant que la session et son service restent vivants. Retirer la tâche, détruire l’Activity ou tuer le processus arrête encore la session. Aucune relance autonome après perte de connexion.

**Oria Lab reste un enregistrement au premier plan** : garder l’onglet/l’application ouverts pendant une capture. Son passage en arrière-plan finalise la capture et arrête cette session. Cette exception est indiquée près des commandes Lab ; ce changement ne réécrit aucune capture originale.

## Pipeline et audio

Chaque modèle possède son worker et une file de taille1 qui remplace uniquement l’image décodée en attente. Une copie des pixels est faite avant de céder l’original à YOLO : les deux workers ne recyclent jamais le même bitmap. Toutes les dépendances H.264 restent décodées. YOLO reçoit les échantillons333 ms ; la profondeur reçoit un échantillon sur deux (cadence nominale666 ms, déterminée par le flux). Son index d’observation reste indépendant des numéros d’images sautées. CPU4 retenu pour la profondeur, XNNPACK inchangé pour YOLO.

La fraîcheur d’entrée reste500 ms, celle de sortie/premier PCM500 ms pour objets et1500 ms pour profondeur. Les offres courantes restent distinctes et sont invalidées au résultat suivant, à expiration ou à l’arrêt. L’arbitre alterne les sources lorsque deux offres fraîches sont prêtes (objet en première égalité), réserve un seul ticket et partage le lecteur Bluetooth. Pause commune1 s après retour audio ; répétitions métier et confirmations propres à chaque moteur inchangées. Aucun résultat n’est transformé en mesure de distance et aucune identité commune objet/profondeur n’est inventée : deux formulations sur la même zone restent possibles.

Les15 phrases automatiques sont préparées ensemble ; cache vocal borné32, limite de phrases préparées16. Aucun test vocal humain ne bloque les alertes. Une erreur réelle de route, une livraison ambiguë, un arrêt ou un résultat périmé restent bloquants. La reprise proposée après erreur locale connue prépare le transport sans simuler une écoute réussie. Les captures distinguent toujours inférences YOLO et événements profondeur et déclarent les deux modèles/paramètres.

## Vérification

Compilation, **127 tests JVM** et lint réussis ; **3 tests UI instrumentés** passent sur CN46V3M00284. Revues croisées UI, cycle de vie, propriété des pixels, sessions et arbitrage sans blocage restant. APK précédent et724 fichiers privés sauvegardés et relus avant mise à jour ; installation uniquement `adb install -r`, signature conservée, aucun effacement.

Benchmark concurrent sur le HTC : deux ordres CPUprofondeur2/4 puis4/2, une chauffe par modèle,10 appels YOLO et5 profondeur par configuration, image synthétique467×832. CPU4 : âge synthétique YOLO p50210/210 ms, profondeur343/352 ms, maxima265/260 et349/387 ms. CPU2 : YOLO221/219 ms, profondeur456/452 ms. Zéro dépassement500 ms surYOLO dans ce microbenchmark. Cette mesure n’inclut ni radio, ni décodeur, ni géométrie/politique, ni voix, ni chauffe prolongée ; elle justifie l’allocationCPU4 mais ne vaut pas une latence réelle complète.

Test du **vrai Controller avec le simulateur SDK sur le HTC** : deux sessions4 s, toutes deux avec11 inférences YOLO et respectivement5/6 analyses de profondeur. Aucun envoi de voix en simulation ni résultat publié après arrêt. Âges médians réception→résultat YOLO306/285 ms et profondeur456/433,5 ms ; le premier résultat YOLO600 ms a été rejeté comme périmé, les21 autres acceptés. Ces mesures incluent la chaîne Android de simulation et les politiques, pas la radio des lunettes, ni leur caméra réelle, ni une écoute humaine. Le simulateur a été déconnecté, l’adaptateur physique rétabli, les flux arrêtés et l’application relancée à l’arrêt.

La tentative de connexion physique a constaté Bluetooth `STATE_DISCONNECTED`. **Continuité réelle écran éteint et annonces simultanées dans les lunettes non validées sur1.7** ; les confirmations humaines antérieures ne sont pas reportées comme nouvelles preuves. Aucun flux physique laissé actif. Journaux, sauvegardes et mesures brutes privés sous `validation/unified-20260927/`.

La recette restante consiste à démarrer Oria avec les lunettes, présenter objets et parois, éteindre l’écran, vérifier les deux familles d’annonces et arrêter depuis la notification. Mesurer ensuite une session longue et les effets de batterie/chauffe. Aucun enregistrement de scène privé n’est publié.
