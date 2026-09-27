# Revue indépendante de l’interface Oria — 27 septembre 2026

## Périmètre et résultat

Revue du changement visuel Android demandé par l’utilisateur, réalisée par un troisième agent distinct des auteurs d’`OriaScreen.kt`, d’`OriaLabScreen.kt` et des composants communs. Lecture des écrans, de la navigation, du thème et des appels au contrôleur ; comparaison des changements avec les gardes existantes. Aucun changement du moteur, du modèle ou des captures dans cette revue.

**Aucun défaut bloquant identifié par la revue du code.** Ce résultat ne vaut pas validation TalkBack ni recette avec une personne déficiente visuelle. Le relecteur n’a lancé ni Gradle ni ADB. Les résultats de compilation, captures d’écran et essais sur téléphone sont à consigner séparément par l’intégrateur.

## Arbitrages adoptés

1. Une commande principale pleine largeur reste en bas de chaque écran, avec une hauteur minimale de 64 dp. Sur Oria, elle permet aussi d’arrêter la préparation du mode poche. Les tests vocaux, réglages et commandes de galerie sont disposés verticalement pour laisser les textes s’agrandir.
2. Chaque interrupteur et la confirmation des directions forment un seul contrôle nommé. Le parent porte le rôle et la valeur ; le Switch/Checkbox visuel interne n’ajoute pas un second élément TalkBack. La confirmation image **et** voix reste explicite, avec possibilité de faire vérifier l’image par un accompagnant.
3. Les états utiles, les tests vocaux et le mode poche sont accessibles dans Oria ; aperçu et réglages techniques sont repliables. Les compteurs, les détections et la dernière demande vocale n’utilisent pas de région d’annonce automatique. Oria Lab réserve les annonces polies aux résultats ponctuels de gestion/export.

## Grille de revue liée au code

| Point | Constat dans le changement | Limite restante |
| --- | --- | --- |
| Arrêt disponible | Bouton Oria actif pour `running || pocketPreparing`, appel inchangé à `controller.stop()` ; arrêt de capture/vidéo conservé dans Lab. | Vérifier la position et l’accès au bouton dans TalkBack, en grande police et en paysage. |
| Contrôles nommés | `OriaSettingToggle` regroupe label, état et rôle `Switch` ; `OriaHomeCheck` porte le rôle `Checkbox`. Les commandes répétées de Lab incluent le nom de la capture. | Vérifier l’annonce effectivement produite par TalkBack et l’ordre de parcours. Des captures volontairement nommées identiquement peuvent rester difficiles à distinguer. |
| Agrandissement | Polices exprimées en sp ; texte courant 16–18 sp ; pas de réduction du facteur Android ; boutons avec hauteur minimale, sans maximum de lignes imposé. Tests vocaux, rotations et pagination en colonne. | Le code ne prouve pas l’absence de débordement à 200 %, notamment dans les dialogues et sur petit écran/paysage. |
| Contraste | Thèmes clair/sombre suivant le système, paires de texte actives calculées ci-dessous. Les états sont aussi écrits en toutes lettres. | Ni test physique en extérieur, ni validation des pixels vidéo, des boîtes, du focus système ou des contrôles désactivés. |
| Voix et repère | `voiceTestEnabled`, confirmation explicite du repère et restrictions de changement de backend/suivi conservés. Aucun état UI ne transforme un callback audio en preuve d’écoute. | Tester l’utilisation simultanée de TalkBack et de la voix Oria avec l’utilisateur. |
| Données fraîches | L’aperçu affiche `state.preview` courant ; le ratio peut être retenu sans retenir les pixels. Dans Lab, `GalleryEntryKey` et l’identité de session protègent les chargements asynchrones ; les boîtes ne sont superposées que si le bitmap sélectionné existe. | Les tests métier/galerie et une recette UI complètent la lecture, ils ne sont pas exécutés par ce relecteur. |
| Captures et navigation | `canManage`, `storageBusy`, gestion de `pendingExport`, annulation des coroutines et gardes de finalisation conservés. MainActivity change le rendu des onglets, pas la propriété du flux lors des changements de page. | La liste des captures reste une Column défilante préexistante ; sa performance avec une très grande collection n’est pas mesurée ici. |

La correction finale de l’état d’accueil a aussi été relue : `audioUnknown`, les annonces suspendues et une sortie Bluetooth indisponible prennent la priorité sur le message de disponibilité. Les conditions de démarrage et de test vocal restent inchangées. La disponibilité affichée ne repose donc plus uniquement sur la confirmation des directions.

## Contrastes calculés

Calcul de luminance relative sRGB sur les couleurs opaques déclarées dans `OriaUiTheme.kt`, pour les douze couples `onColor`/fond de chaque thème : primary, primaryContainer, secondary, secondaryContainer, tertiary, tertiaryContainer, background, surface, surfaceVariant, error, errorContainer et inverseSurface.

| Mesure | Clair | Sombre |
| --- | ---: | ---: |
| Minimum des douze couples | 6,33:1 (`onSurfaceVariant`) | 6,75:1 (`onSurfaceVariant`) |
| Action principale | 8,01:1 | 10,01:1 |
| Texte sur surface | 14,60:1 | 14,27:1 |

Les 24 couples dépassent 4,5:1. Les chiffres sont arrondis uniquement pour leur présentation ; aucun ratio n’est proche du seuil. Ce calcul est une mesure des paires de palette, pas une certification d’accessibilité de l’application.

## Recette manuelle à compléter

- À 200 % de police : accéder aux onglets, à l’arrêt fixe, aux trois tests vocaux, à la confirmation du repère et aux réglages ; ouvrir aussi une capture, la pagination et les dialogues de renommage/corbeille. Restaurer ensuite le réglage initial du téléphone.
- Avec TalkBack : vérifier noms, valeurs et ordre de focus ; confirmer qu’un interrupteur se lit une seule fois ; contrôler l’accès à l’arrêt sans parcourir les détails techniques. Ne pas considérer un arbre UIAutomator comme une preuve d’écoute.
- Pendant un essai utilisateur : vérifier que les changements de détections ne déclenchent pas de seconde voix TalkBack automatique et que les annonces Oria restent compréhensibles. Cet essai nécessite la chaîne audio réelle et une confirmation humaine.
- Conserver la vérification initiale image/voix ; ne pas présenter la refonte visuelle comme une validation de navigation sûre ou comme une correction de la coupure du mode poche signalée par l’utilisateur.

## Références utilisées

Les recommandations de cibles et rôles suivent les [comportements d’accessibilité Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) et la [sémantique Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics). Android recommande une cible d’au moins 48 dp ; les commandes modifiées ici visent 56 ou 64 dp. La nécessité d’une validation manuelle avec services d’accessibilité vient de la [documentation de test Android](https://developer.android.com/develop/ui/compose/accessibility/testing).

Le seuil 4,5:1 et la méthode de contraste sont référencés dans [WCAG 2.2 — contraste minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). Ils servent ici de critère de conception des couleurs, sans revendiquer une conformité globale.
