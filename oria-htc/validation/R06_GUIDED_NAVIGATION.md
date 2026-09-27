# R06 — Navigation guidée Oria

## Verdict du 27 septembre 2026

La navigation guidée est **implémentée, compilée et validée par scénario simulé/JVM**. Le fournisseur réseau réel répond depuis le Mac, mais le trajet physique Android reste ouvert : le HTC connecté refuse l’APK debug local à cause d’une signature différente de celle du package autorisé déjà installé. Aucune désinstallation et aucune perte de données n’ont été provoquées.

## Référence Swift réellement portée

La lecture de `Destination.swift`, `DestinationStorage.swift`, `ItineraireView.swift`, `AddressInputView.swift`, `DestinationManagementUITests.swift` et `NavigationUITests.swift` confirme que la référence récente apporte le modèle nom/adresse, la normalisation implicite, l’ajout, la modification, la suppression, la persistance et l’interface accessible. Elle ne contient pas encore de moteur MapKit, de progression GPS ni de recalcul à porter. L’implémentation Android conserve donc ces responsabilités utiles sans prétendre réutiliser un moteur absent.

## Contrats et comportement livrés

- Contrats purs : `DestinationRepository`, `LocationProvider`, `Geocoder`, `RouteProvider`, `NavigationEngine` et `NavigationSpeechScheduler`, relié à l’ordonnanceur R05.
- Destinations locales : ajout, modification, suppression et persistance `SharedPreferences`, avec espaces normalisés et UUID stable pendant une modification.
- Saisie manuelle ou dictée Android, recherche, suggestions, sélection et confirmation explicite.
- Mode réel : localisation Android de premier plan, géocodage de la plateforme et itinéraire piéton via l’instance publique OSRM `routing.openstreetmap.de` ; aucun jeton ni secret.
- Attribution visible : `© OpenStreetMap contributors · itinéraire OSRM`. Le mode réel explique avant activation que l’origine et la destination sont envoyées au service public.
- Mode simulé : trajet déterministe clairement marqué, trois manœuvres et arrivée, sans GPS ni donnée réelle.
- Progression : instruction courante, distance estimée jusqu’à la manœuvre, seuils d’annonce, deux observations pour confirmer l’arrivée.
- Sécurité temporelle : génération de navigation, version de route, version d’instruction, horloge monotone et token pour chaque calcul asynchrone.
- Recalcul : deux observations hors itinéraire invalident la route et toutes ses paroles avant de demander une nouvelle version.
- Pause, perte GPS, changement de destination, arrêt, arrivée et échec invalident les instructions anciennes.
- Danger fort/critique : préemption par R05 ; la même manœuvre ne redevient prononçable qu’après une nouvelle position fraîche démontrant qu’elle reste utile.
- Une ancienne réponse de géocodage/route ne peut pas remplacer une destination plus récente.

La localisation est demandée uniquement lorsque l’utilisateur confirme un trajet réel. L’application n’ajoute pas de permission de localisation arrière-plan ni de service de navigation en arrière-plan. Cette portée suit les recommandations Android de demander l’accès en contexte et de traiter la localisation approximative comme un état possible : <https://developer.android.com/develop/sensors-and-location/location/permissions/runtime>.

OSRM est un moteur utilisant les données OpenStreetMap. L’instance publique est utilisée sans garantie de disponibilité et seulement pour cette démonstration ; attribution et `User-Agent` explicite sont conservés. Références : <https://github.com/Project-OSRM/osrm-backend> et <https://project-osrm.org/>.

## Vérifications effectuées

```text
ANDROID_HOME=/Users/rayan/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Résultat : **BUILD SUCCESSFUL**, **147 tests JVM**, zéro échec/erreur, APK debug construit et lint réussi. Identité APK contrôlée : `com.htc.vive.eagle.hackathon.starter`, version `1.4-night`, code 5.

Les nouveaux tests couvrent notamment : deux manœuvres, géocodage en échec, calcul de route en échec, GPS perdu/retrouvé, précision insuffisante, pause/reprise, sortie de route, recalcul versionné, ancienne génération, callback de l’ancienne destination, danger pendant instruction, instruction périmée, persistance CRUD, simulation complète et arrivée.

Sonde réseau publique, sans localisation personnelle : coordonnées centrales de Paris vers un second point public. Réponse OSRM observée : `code=Ok`, une route, **89,1 m**, **73,2 s**, **5 étapes**, 14 points de géométrie.

APK produit : SHA-256 `63156d81f4e8b4e463666cbf04dd98089697bb938b5a297beb380e9263a0eed5`.

## Validation matérielle encore ouverte

Le HTC `CN4B53M00860` est visible par ADB, mais `adb install -r` retourne :

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.htc.vive.eagle.hackathon.starter signatures do not match newer version
```

Cette incompatibilité empêche seulement une mise à jour `-r`. La consigne organisateur confirmée est de sauvegarder l’ancien starter puis de le remplacer par notre version, qui conserve exactement le package autorisé. Aucune clé HTC supplémentaire ni second package ne sont attendus. Après ce remplacement, il reste à contrôler la connexion Eagle, les permissions et le scénario réel court exigé par R06.

Recette restante : trajet piéton court et contrôlé, validation des permissions précise/approximative, deux manœuvres, préemption par un danger, instruction périmée non reprise, perte GPS, sortie de route/recalcul, pause/reprise et arrivée. Cette recette ne doit pas présenter Oria comme un système de sécurité ou de navigation certifié.
