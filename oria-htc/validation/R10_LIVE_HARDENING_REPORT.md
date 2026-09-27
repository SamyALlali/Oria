# R10 — Durcissement pour la démonstration complète

Date : **27 septembre 2026**
Périmètre : sources `Oria/oria-htc`, profil de démonstration HTC U24 pro + VIVE Eagle.

## Verdict honnête

Le durcissement logiciel R10 est intégré et validé hors appareil : **173 tests JVM réussis**, compilation de l’APK, compilation des tests instrumentés et lint réussis. La matrice de pannes, les clôtures de génération, les métriques de performance et le contrôleur de charge sont testés.

La porte physique R10 n’est **pas entièrement fermée** : le téléphone connecté `CN4B53M00860` contient le package HTC autorisé signé différemment. `adb install -r` refuse donc le build avec `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. L’application autorisée et ses données n’ont pas été désinstallées. Aucun essai physique de trente minutes ni trois démonstrations physiques consécutives ne sont revendiqués pour ce build.

## Profil stable retenu

- Détecteur ONNX : **XNNPACK avec repli CPU**, deux threads, seul profil ayant passé les six fixtures de parité Android.
- Suivi : **LEGACY_IOU**, profil live historique stable ; le suivi V2 reste opt-in.
- Détection sémantique : fréquence conservée (`detectionStride=1`) dans tous les profils de charge.
- Profondeur monoculaire : optionnelle ; fréquence 1/1 en nominal, 1/2 en charge réduite, 1/4 en protection. Le RGB et les alertes restent actifs en cas d’indisponibilité.
- Dashboard : 4 Hz nominal, 2 Hz réduit, 1 Hz protégé.
- Audio : PCM Bluetooth local prioritaire pendant la vidéo ; navigation interrompue par un danger puis reprise uniquement si l’instruction reste fraîche.
- Navigation réelle : calcul OSRM dépendant du réseau ; perception, voix locale et route déjà acquise restent utilisables hors ligne. La navigation simulée est locale.

Le contrôleur déleste donc d’abord l’affichage et les diagnostics, puis la profondeur optionnelle. Il ne ralentit jamais le détecteur métier.

## Matrice de pannes

| Panne injectée/contractualisée | Fonctions conservées | Sorties coupées | Récupération |
|---|---|---|---|
| Lunettes | aucune sortie live | perception, audio, navigation | reconnexion puis nouvelle session |
| Flux H.264 | interface, destination | perception, audio danger | nouvelle session vidéo |
| Décodeur | interface, destination | perception, audio danger | recréation décodeur + session |
| Détecteur | interface, navigation | perception, audio danger | rechargement modèle + session |
| Profondeur | RGB, suivi, alertes, navigation | profondeur | réactivation hors session |
| Bluetooth audio | perception, suivi, navigation visuelle | voix, bips | route rétablie + nouvelle session audio |
| Microphone | perception, alertes, navigation | commandes vocales | nouvelle écoute après disponibilité |
| GPS | perception, alertes, route affichée | nouvelles instructions vocales | position fraîche |
| Réseau | perception, voix locale, route courante | nouveau calcul en ligne | réseau ou simulation |
| Fournisseur d’itinéraire | perception, voix locale, destination | parole de navigation | nouveau calcul/version de route |

Toutes les pannes ont un message et une condition de récupération explicites. Une panne terminale ou un redémarrage incrémente la génération : une sortie calculée avant la panne est refusée après récupération.

## Mesures ajoutées

Le dashboard développeur expose désormais :

- caméra reçue → décision, p50 et p95 ;
- décision → soumission audio Android, p50 et p95 ;
- images reçues, décisions utiles, rejets et cadence utile ;
- mémoire Java utilisée, CPU processus, batterie, température batterie et état thermique Android ;
- profil de charge actif et fréquence réduite du dashboard/profondeur.

La seconde mesure s’arrête à la soumission Android : elle ne prétend pas mesurer le délai jusqu’au son effectivement entendu dans les lunettes.

## Preuves exécutées

| Preuve | Résultat | Portée réelle |
|---|---:|---|
| Tests JVM Android | **173/173** | contrats, moteurs, reprises, trois replays complets déterministes |
| Horloge virtuelle 30 min | **7 201 décisions**, p95 synthétique 80 ms | structure/compteurs seulement, aucune caméra physique |
| Trois scénarios complets consécutifs | **3/3**, empreinte identique et reset de tous les propriétaires | replay déterministe, pas trois démos humaines |
| APK debug | SHA-256 `bfbee2018ac6ff819ed2891a2114b8b9bcabff77c359074109a800fd805b41e6` | build local non installé |
| `assembleDebugAndroidTest` | réussi | compilation des instruments seulement |
| `lintDebug` | réussi | analyse statique Android |
| Installation HTC | refus de signature attendu | aucune donnée/app autorisée supprimée |

Les longues structures existantes de galerie (~30 min et ~2 h) prouvent l’indexation de données synthétiques. Elles ne prouvent ni une caméra continue, ni la profondeur, la navigation, l’audio, la chauffe ou la batterie. La recette physique historique la plus récente reste celle de **131,118 s**, avec 362 analyses fraîches ; elle ne devient pas rétroactivement une endurance R10.

## Classement des 2 échecs et 2 tests ignorés historiques

1. Assertion d’ancien identifiant de package : **corrigée dans la source et recompilée**, mais pas relancée sur le package HTC signé ; preuve appareil encore requise.
2. Parité stricte du provider CPU : **échec connu maintenu** (299/300 sur `sdk_sample_1`, divergence très sous le seuil métier). Aucun seuil n’a été élargi. CPU seul reste exclu du profil stable.
3. Endurance XNNPACK ignorée dans la campagne globale : une preuve historique séparée de 600,026 s existe, mais pas pour ce build complet.
4. Capture physique opt-in ignorée : reste dépendante des lunettes et du remplacement de l’ancien starter par l’APK final portant le même package.

## Porte R10

- Trente minutes sans crash, chaîne physique complète : **NON EXÉCUTÉ**.
- Trois démonstrations physiques réussies avec reset intégral : **NON EXÉCUTÉ**.
- Aucune sortie périmée : **VALIDÉE logiciellement** par génération, fraîcheur, invalidation audio/navigation et tests ; confirmation physique après reconnexion encore requise.

Conclusion : le code est prêt pour la recette physique. R10 ne doit être déclaré physiquement terminé qu’après remplacement sauvegardé de l’ancien starter par l’APK final portant le même package, trente minutes continues avec lunettes/perception/profondeur si activée/navigation/audio, puis trois scénarios complets et les cas batterie faible, verrouillage, arrière-plan et reconnexion.
