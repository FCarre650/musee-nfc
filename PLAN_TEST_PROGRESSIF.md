# Plan de test progressif

Ordre pensé pour valider le risque le plus important le plus tôt, et pour qu'un échec à une
étape reste isolé et facile à diagnostiquer (chaque palier ne dépend que des précédents). Suit
l'ordre É0→É9 du cadrage (`docs/cadrage-projet.md` §6).

Statut : **✅ déjà validé dans ce dépôt** (backend compilé, lancé, testé au curl et au navigateur
headless réel pendant la génération de ce projet) — **☐ à faire par vous**, principalement tout
ce qui touche à un téléphone/patch NFC physique, puisque cet environnement de génération n'a pas
accès à un lecteur NFC ni au SDK Android complet (voir README, section « Non compilé dans cet
environnement »).

---

## Palier 1 — Backend seul, sans téléphone (É0-É2, partie serveur)

Objectif : prouver que toute la logique métier et l'écran de supervision fonctionnent, avant même
de toucher à l'Android. C'est aussi votre **plan B de démo** si le NFC pose problème le jour J.

1. ✅ **Lancer le backend** — `cd backend && gradle run`. Doit afficher
   `Responding at http://0.0.0.0:8080` en quelques secondes.
2. ✅ **Ouvrir `http://localhost:8080/`** dans un navigateur → formulaire de connexion à
   l'identité visuelle « PC sécurité musée ».
3. ✅ **Se connecter avec `bruno` / `bruno123`** (rôle gardien) → doit être **refusé** avec un
   message explicite (« rôle insuffisant »). C'est le test du RBAC sur l'écran de supervision.
4. ✅ **Se connecter avec `claire` / `claire123`** (chef de poste) → l'écran de supervision
   s'affiche avec les 6 salles de démo. La « Salle Égypte antique » démarre verte (scan de seed
   récent), les autres rouges (jamais contrôlées).
5. ✅ **Simuler un scan sans téléphone**, dans un second terminal :
   ```bash
   TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
     -H 'Content-Type: application/json' -d '{"login":"bruno","password":"bruno123"}' \
     | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

   curl -X POST http://localhost:8080/api/scans \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d "{\"tagUid\":\"TEST-UID-RENAISSANCE\",\"checkpointCode\":\"chk-renaissance-001\",\"scannedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%S.000Z)\"}"
   ```
   → la « Salle Renaissance » doit repasser au vert **en direct** sur la page ouverte à l'étape 4,
   sans recharger : c'est l'effet Wow poussé par WebSocket, et il fonctionne sans aucun matériel
   NFC. Les autres checkpoints de test : `chk-egypte-001` / `TEST-UID-EGYPTE`,
   `chk-bijoux-001` / `TEST-UID-BIJOUX`, `chk-reserve-001` / `TEST-UID-RESERVE`,
   `chk-sculpture-001` / `TEST-UID-SCULPTURE`, `chk-contemporain-001` / `TEST-UID-CONTEMPORAIN`.
6. ✅ **Vérifier le refus d'un patch inconnu (R2)** :
   ```bash
   curl -X POST http://localhost:8080/api/scans -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"tagUid":"X","checkpointCode":"n-importe-quoi","scannedAt":"2026-01-01T00:00:00Z"}'
   ```
   → `404 Patch inconnu`.
7. ✅ **Vérifier le refus d'un UID incohérent (R3)** : même requête que l'étape 5 mais avec un
   `tagUid` différent de celui enregistré pour `chk-renaissance-001` → `409 Conflict`.
8. ✅ **Laisser une salle non scannée assez longtemps** pour la voir passer vert → orange → rouge
   toute seule (le compteur tourne côté navigateur ; réglez un seuil bas via
   `PATCH /api/checkpoints/{id}` avec un token `admin` pour accélérer le test en démo/répétition).

Si le palier 1 passe entièrement, vous avez déjà un **POC de secours démontrable sans NFC**
(cadrage §10, plan B).

## Palier 2 — Premier build Android (É1, préalable)

1. ☐ Ouvrir `android/` dans Android Studio, laisser le sync Gradle se terminer.
2. ☐ Renseigner `BASE_URL` dans `android/app/build.gradle.kts` avec l'IP de votre machine sur le
   Wi-Fi (pas `localhost`).
3. ☐ Lancer sur un émulateur ou un téléphone → l'écran de connexion doit s'afficher, se connecter
   avec `claire`/`claire123` doit fonctionner si le backend du palier 1 tourne toujours (même
   réseau). C'est un test réseau/auth qui ne nécessite pas encore de NFC.

## Palier 3 — Spike NFC (É1, le risque n°1)

1. ☐ Sur un téléphone avec NFC (ex. Xiaomi), aller sur l'écran « Scanner ».
2. ☐ Approcher n'importe quel tag NFC (même vierge, même non provisionné) : l'app doit détecter
   le tag et tenter un scan → refus attendu (« patch non provisionné »), ce qui prouve déjà que
   la **lecture d'UID bas niveau fonctionne**, l'essentiel de ce palier.

## Palier 4 — Provisioning (É3)

**Verrouillage physique (R1) temporairement désactivé dans l'app** (voir `NfcHelper.kt` :
`provisionAndLock`/`testLock` existent toujours mais ne sont plus appelés par `ProvisionScreen`,
qui utilise `writeCheckpointCode`) le temps de fiabiliser son comportement sur le parc de
téléphones de test — plusieurs blocages rencontrés (crash "Tag is out of date", lenteur
`disableReaderMode` sur MIUI) étaient liés à la fenêtre de temps avant l'écriture, pas au
verrouillage lui-même, mais on préfère stabiliser scan/lecture/écriture d'abord. Le risque R1
reste documenté et démontrable au pitch même sans démo live ; à réactiver (un seul point d'appel
dans `ProvisionScreen`) si le temps le permet.

Ordre important, surtout si vous réutilisez un patch déjà testé plus tôt dans la semaine : le
serveur refuse de re-provisionner un `tagUid` déjà associé à une salle (`409 Ce patch (UID) est
déjà associé à une salle`), et désactiver la salle (`isActive=false`) ne libère PAS le patch (le
contrôle porte sur l'UID, pas sur le statut actif). Pour rejouer ce palier avec le même patch
physique, supprimez d'abord la salle existante :
```bash
curl -X DELETE http://localhost:8080/api/checkpoints/<id> -H "Authorization: Bearer $ADMIN_TOKEN"
```
`<id>` s'obtient via `GET /api/checkpoints`. Ceci supprime aussi l'historique des scans de cette
salle (contrainte de clé étrangère) — normal pour un patch de test qu'on reprovisionne, à éviter
pour une vraie salle en usage. Sinon, prenez simplement un patch physique jamais provisionné.

1. ☐ Se connecter avec `admin`/`admin123`, aller sur l'onglet « Provisionner ».
2. ☐ Remplir une salle de test, cliquer « Provisionner », approcher un patch **NTAG215 vierge**
   (1er tap : lecture de l'UID puis création de la salle côté serveur). **Dès que le statut
   demande de réapprocher le patch, retirez-le puis re-tapez** (2e tap : écriture du code) — ne
   le laissez pas posé en continu entre les deux, ce sont deux taps distincts. C'est volontaire :
   un objet Tag Android devient invalide s'il est gardé en mémoire pendant l'appel réseau du 1er
   tap, d'où la nécessité d'un second tap tout frais pour l'écriture.
3. ☐ Vérifier côté supervision web que la salle apparaît dans la liste (`GET /api/checkpoints`
   ou re-render du snapshot) — ceci est vrai dès l'étape 2 même si l'écriture NFC du 2e tap a
   échoué, puisque la salle est créée côté serveur avant : ne pas le prendre comme preuve que le
   patch est écrit, seul le statut affiché après le 2e tap en fait foi.

## Palier 5 — Tranche verticale complète avec un vrai patch (É2, bout en bout)

1. ☐ Avec le patch provisionné au palier 4, se connecter en `bruno`/`bruno123` sur l'app.
2. ☐ Scanner le patch depuis l'écran « Scanner ».
3. ☐ Vérifier sur la page de supervision (ouverte dans un navigateur, connectée en `claire`) que
   la salle passe au vert **en direct**, avec le nom de Bruno affiché.

C'est le moment où le POC devient démontrable de bout en bout avec du vrai matériel.

## Palier 6 — Mode hors ligne (É6)

1. ☐ Passer le téléphone en mode avion, scanner un patch déjà provisionné.
2. ☐ Vérifier que l'app affiche « scan mis en file d'attente » (pas d'erreur bloquante).
3. ☐ Repasser en ligne, cliquer « Synchroniser maintenant » (ou attendre le prochain scan) →
   le scan doit apparaître côté supervision avec l'heure de réception serveur (pas l'heure du
   scan hors-ligne).

## Palier 7 — CRUD salles/patchs et historique (É7-É8)

1. ☐ Modifier le seuil d'alerte d'une salle depuis l'admin (`PATCH /api/checkpoints/{id}` — pas
   encore d'écran dédié dans l'app pour ça au-delà de la création, à ajouter si le temps le
   permet) et vérifier que la supervision change de couleur plus vite.
2. ☐ Consulter l'onglet « Historique » (rôle chef de poste/direction) → liste des scans
   enregistrés, la plus récente en premier.

## Palier 8 — Recette finale / répétition (É9)

1. ☐ Dérouler le plan de démo du cadrage §10 en entier, chronomètre en main.
2. ☐ Préparer le plan B (vidéo de la manip NFC + jeu de données pré-rempli — le palier 1 suffit
   pour ça, aucune app mobile nécessaire).
3. ☐ Vérifier que `BASE_URL` pointe vers l'IP du jour de la démo, pas une IP de test périmée.
