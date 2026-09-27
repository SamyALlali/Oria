# R11 — Checklist opérateur jury

Version candidate : **Oria 1.5-jury-r11 / code 6**

Tag attendu : `oria-1.5-jury-r11`. Le scénario dure cinq minutes et doit réussir trois fois après reset complet.

## Avant l’arrivée du jury

- [ ] HTC identifié par son numéro ADB ; ne jamais viser tous les appareils.
- [ ] Batterie ≥ 70 %, téléphone alimenté, au moins 5 Gio libres.
- [ ] Lunettes chargées, firmware/VIVE Connect affichés et connexion stable.
- [ ] Package, version, code et SHA de l’APK conformes au manifeste R11.
- [ ] Modèle XNNPACK prêt ; suivi `LEGACY_IOU` ; profondeur désactivée par défaut.
- [ ] Autorisations caméra, Bluetooth, notifications, localisation et microphone vérifiées selon le scénario.
- [ ] Rotation/miroir vérifiés avec une scène asymétrique pour le montage du jour.
- [ ] Voix française locale prête et route Bluetooth VIVE confirmée par gauche/centre/droite.
- [ ] Wi-Fi principal validé ; partage de connexion de secours prêt.
- [ ] Destination réelle courte reconnue ; trajet simulé testé et clairement étiqueté « simulation ».
- [ ] Vidéo SDK de secours et replay local ouverts une fois avant la présentation.
- [ ] APK autorisé de rollback et empreintes disponibles dans le bundle local.
- [ ] Mode Ne pas déranger actif ; notifications privées masquées.

## Scénario jury — 5 minutes

| Temps | Action | Preuve attendue |
|---:|---|---|
| 0:00 | Ouvrir Oria, connecter les lunettes, montrer la version et démarrer | modèle/voix prêts, images fraîches, aucune ancienne alerte |
| 0:30 | Choisir la destination préparée | destination confirmée ; mode réel, ou simulation explicitement annoncée |
| 1:00 | Présenter deux objets/personnes dans deux zones | plusieurs pistes ; une seule priorité vocale |
| 1:30 | Rapprocher le danger prioritaire | escalade compréhensible sans chevauchement navigation |
| 2:00 | Simple appui IA / description | description courte, puis retour au flux |
| 2:30 | Déclencher les deux premières manœuvres | instructions versionnées, fraîches et audibles |
| 3:15 | Introduire un danger pendant une instruction | navigation interrompue par l’alerte prioritaire |
| 3:45 | Retirer le danger et avancer | reprise uniquement sur une donnée fraîche |
| 4:10 | Quitter légèrement le trajet préparé | recalcul ; aucune ancienne manœuvre rejouée |
| 4:40 | Atteindre la destination | annonce d’arrivée unique |
| 5:00 | Arrêter perception et navigation | aperçu purgé, silence, aucune sortie tardive |

## Reset entre les trois passages

1. Arrêter perception et navigation ; attendre l’annulation audio.
2. Vérifier qu’aucune capture Oria Lab n’est active.
3. Revenir à l’accueil ; reconnecter seulement si l’état n’est pas propre.
4. Relancer avec une nouvelle génération ; aucune sortie du passage précédent ne doit apparaître.
5. Noter début, fin, résultat, chauffe, batterie et incident pour chaque passage.

## Plans B, dans cet ordre

1. **Réel complet** : lunettes, perception, navigation réelle et audio Bluetooth.
2. **Navigation simulée** : perception réelle et audio, trajet local clairement marqué « simulé ».
3. **Replay instrumenté local** : vidéo SDK → décodeur → XNNPACK → politique ; voix factice et absence du SDK lunettes annoncées.
4. **Vidéo de secours** : `validation-project/app/src/main/res/raw/video_sample.mp4`, échantillon HTC SDK, jamais présenté comme capture Eagle live.
5. **Dashboard/rapport** : décisions déterministes, matrice de pannes et preuves historiques si aucun flux n’est disponible.

## Interdictions après gel

- Aucun changement de code, dépendance, modèle, seuil, clé, firmware ou configuration risquée sur le candidat.
- Toute correction crée une nouvelle version, un nouvel APK, une nouvelle empreinte et relance la recette complète.
- Ne jamais désinstaller le package autorisé juste avant le jury.
- Ne jamais présenter un replay, un trajet simulé ou un sink vocal factice comme une preuve live.
