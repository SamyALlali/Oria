# Registre des écarts avant finalisation d’Oria

Baseline : import du commit source `2a62a1a3d3a5fd361416ce184710f1f57865f10b` dans `SILMO_Hackathon_2026/Oria`.

| Prompt | État au départ | Preuve attendue pour fermer | Dépendance matérielle |
|---|---|---|---|
| R01 — sessions | Validé logiciellement : machine d’état, dépendances et 92 tests JVM ; recette physique ouverte jusqu’au remplacement du starter installé | verrouillage, notification Arrêter, perte Bluetooth et absence de reprise ancienne sur l’APK final | HTC + lunettes pour mode poche |
| R02 — suivi V2 | Qualifié sur la scène historique documentée et deux scènes synthétiques annotées ; états d'occultation/perte explicites, opt-in conservé | récupérer/rejouer 0548b68a, au moins deux captures Eagle annotées et recette audio lunettes | lunettes et scènes réelles |
| R03 — profondeur | MiDaS v2.1 Small MIT intégré en option ; preuve relative, confiance, alignement, obstruction générique et fallback RGB testés | mesures HTC latence/mémoire/chauffe et scènes Eagle annotées avant activation | HTC pour latence/mémoire |
| R04 — résolution | Moteur complet intégré en comparaison silencieuse : sept sources, propriété, hold/release, snapshot et approche optionnelle ; 10/10 fixtures Swift/Kotlin | comparer sur nouvelles captures Eagle avant de remplacer la voix RGB dans R05 | non pour le cœur ; HTC pour recette et promotion |
| R05 — audio | Ordonnanceur unifié, préemption, mode silencieux et bips optionnels implémentés ; voix 70/30 confirmée antérieurement | confort humain des nouveaux bips et transitions physiques sur l’APK final | lunettes + validation humaine |
| R06 — navigation | Cœur versionné, destinations, GPS/géocodage Android, route OSRM, audio R05 et scénario simulé complet validés logiciellement | trajet réel court sur l’APK final ; permissions, recalcul et écoute à confirmer | GPS/réseau + HTC/lunettes ; remplacement du starter en cours |
| R07 — interaction | Parseur/exécuteur versionné, simple/double-appui, confirmation de destination et restauration caméra implémentés ; 154 tests JVM verts | recette sans écran sur l’APK final : microphone lunettes, conflits SDK, huit intentions et appuis répétés | HTC + lunettes ; remplacement du starter en cours |
| R08 — dashboard | Vues produit/développeur, snapshots immuables à 4 Hz, métriques, six previews et 161 tests JVM verts ; projection mesurée entre 0,781 et 2,404 µs | TalkBack physique, test de compréhension < 10 s et comparaison FPS/chauffe visible-caché sur l’APK final | HTC + TalkBack ; remplacement du starter en cours |
| R09 — parité | Validé logiciellement : schéma v2 compatible v1, confidentialité, replay partagé déterministe, 165 tests JVM et corpus Swift/Kotlin identique | rejouer l’archive 0548b68a et une capture réelle v2 ; exécuter l’instrumentation sur l’APK final | captures réelles finales + HTC/lunettes |
| R10 — robustesse | Durcissement logiciel validé : matrice de 10 pannes, clôtures de génération, métriques, délestage sans ralentir la détection, horloge virtuelle 30 min, 3 replays complets et 173 tests JVM | remplacer l’ancien starter par l’APK final puis exécuter 30 minutes physiques, 3 démos physiques, batterie faible/verrouillage/arrière-plan/reconnexion | HTC + lunettes ; installation de remplacement à valider |
| R11 — gel | Candidat 1.5-jury-r11/code 6 identifié, 174 JVM, 68/68 Mac, fixtures Swift, 3 instruments HTC, 3 scénarios virtuels et plans B/checklist validés | sauvegarder/remplacer l’ancien starter puis réussir 3 démonstrations physiques de 5 min | tout le matériel jury |
| R12 — revue | Terminée sur le tag R11 : 70/100, deux blocages matériels explicités et aucun correctif produit appliqué | valider l’installation de remplacement, exécuter la recette physique complète et livrer les preuves manquantes | HTC/lunettes/GPS/Bluetooth |

## Points ouverts de la baseline

1. Installer un Python compatible avec le verrou `onnxruntime==1.22.0`, recréer `oria-htc/ml/.venv` puis obtenir 68/68 tests Mac.
2. Rejouer sur HTC l’assertion de package corrigée ; elle ne doit pas être comptée comme validée par la seule recompilation.
3. Conserver visible l’échec de parité CPU ONNX stricte ; ne pas changer les tolérances pour le masquer.
4. Exécuter les validations instrumentées sans runner qui désinstalle l’application et sans perdre les données locales.
5. Confirmer physiquement le mode poche, l’arrêt par notification, l’endurance et l’écoute PCM 70/30.
6. Récupérer l’APK historique seulement depuis sa release ou sa sauvegarde attestée ; ne jamais confondre l’APK debug recompilé avec celui installé au SHA `aaec205c…b8dc`.
