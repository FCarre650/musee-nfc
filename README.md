# Musée NFC — Supervision des rondes de sécurité

POC pour la réponse à l'appel d'offres « rondes NFC en musée ». Le dossier de cadrage complet
(besoin client, analyse de risques, architecture, offre commerciale, planning) est dans
[`docs/cadrage-projet.md`](docs/cadrage-projet.md). Le déroulé de test étape par étape est dans
[`PLAN_TEST_PROGRESSIF.md`](PLAN_TEST_PROGRESSIF.md) — **commencez par ce fichier**.

## Ce que contient le dépôt

```
backend/    Backend Ktor (Kotlin) : API REST + JWT + WebSocket + base H2 + écran de supervision (HTML servi statique)
android/    App Android native (Kotlin + Jetpack Compose) : scan NFC, provisioning, historique, tout rôle confondu
docs/       Dossier de cadrage complet (besoin, risques, architecture, offre)
```

Une seule stack (Kotlin) de bout en bout. Le PC sécurité n'installe rien : il ouvre une page web
servie par le backend lui-même. Le détail de ce choix est dans `docs/cadrage-projet.md` §4.

## Démarrage rapide — backend (testé et fonctionnel dans cet environnement)

Prérequis : JDK 17+ (le projet cible le JDK 17).

```bash
cd backend
gradle run          # ou ./gradlew run si vous avez généré le wrapper (voir plus bas)
```

Le serveur écoute sur `http://localhost:8080`. Au premier démarrage, une base H2 locale
(`backend/data/`) est créée et peuplée avec un jeu de données de démo :

| Rôle | Identifiant | Mot de passe |
|---|---|---|
| Gardien | `bruno` | `bruno123` |
| Chef de poste | `claire` | `claire123` |
| Direction (admin) | `admin` | `admin123` |

Ouvrez `http://localhost:8080/` dans un navigateur, connectez-vous avec `claire` ou `admin` :
c'est l'écran de supervision (le PC sécurité). Les gardiens (`bruno`) n'y ont pas accès — le
formulaire de connexion le refuse explicitement, conformément aux rôles du cadrage.

**Pas de wrapper Gradle commité.** Dans cet environnement sandboxé, le téléchargement du binaire
`gradle-wrapper.jar` est bloqué par la politique réseau (`services.gradle.org` inaccessible). Deux
options pour vous :
- **IntelliJ IDEA** : `File > Open`, sélectionnez `backend/build.gradle.kts`. IntelliJ utilise son
  propre Gradle embarqué, aucune installation requise.
- **En ligne de commande** : installez Gradle 8.14+ (`sdk install gradle` ou votre gestionnaire de
  paquets), ou lancez une fois `gradle wrapper --gradle-version 8.14.3` depuis `backend/` pour
  générer `./gradlew` (nécessite un accès réseau non restreint, qui existe sur votre machine).

## Démarrage — application Android

Ouvrez `android/` dans **Android Studio** (`File > Open`). Le projet utilise Kotlin 2.0.21, AGP
8.5.2, Compose et KSP — Android Studio les résout automatiquement au premier sync.

Avant de lancer sur un téléphone réel :
1. Dans `android/app/build.gradle.kts`, remplacez `BASE_URL` (bloc `buildTypes.debug`) par l'IP de
   votre machine sur le réseau Wi-Fi (ex. `http://192.168.1.42:8080`) — le téléphone et le backend
   doivent être sur le même réseau. `localhost` depuis le téléphone pointerait vers le téléphone
   lui-même.
2. Un patch NFC **NTAG215** (quelques centimes l'unité) et un téléphone Android avec NFC. Le code
   cible spécifiquement les adresses de pages de config du NTAG215 (`NfcHelper.kt`) ; un NTAG213
   ou NTAG216 nécessiterait d'adapter ces constantes (voir commentaire en tête de ce fichier).

**Non compilé dans cet environnement.** Le code a été écrit avec soin (conventions Android/Compose
standards) mais je n'ai pas pu le construire ici : le dépôt Maven de Google
(`dl.google.com`, nécessaire pour Android Gradle Plugin et AndroidX) est bloqué par la politique
réseau du sandbox. Le premier build dans Android Studio est donc le premier vrai test de
compilation — voir `PLAN_TEST_PROGRESSIF.md` pour l'ordre recommandé afin d'isoler vite un
problème éventuel.

## Sécurité — ce qui est réellement implémenté dans ce POC

Voir `docs/cadrage-projet.md` §3 pour l'analyse de risques complète (12 menaces identifiées).
Résumé de ce qui est **démontrable en l'état** :

- Rejet d'un scan sur un `checkpointCode` inconnu (patch jamais provisionné) — HTTP 404.
- Rejet d'un scan dont l'UID ne correspond pas au patch enregistré pour cette salle — HTTP 409.
- Verrouillage par mot de passe du patch après provisioning (NTAG21x, `NfcHelper.provisionAndLock`)
  — une réécriture non autorisée est refusée par la puce elle-même.
- Authentification JWT + rôles (RBAC) vérifiés à chaque route (`GUARD` / `SUPERVISOR` / `ADMIN`).
- `received_at` serveur fait foi, jamais l'horodatage du téléphone.
- Mots de passe hachés (BCrypt), jetons stockés en Keystore Android (EncryptedSharedPreferences).

Ce qui est **documenté mais non implémenté** cette semaine (anti-clonage cryptographique NTAG 424
DNA, MDM, recoupement BLE de présence, etc.) : voir §3 et §11 du cadrage.

## Licence / cadre

Projet réalisé dans le cadre d'une réponse à un appel d'offres pédagogique. Pas de licence
publique associée.
