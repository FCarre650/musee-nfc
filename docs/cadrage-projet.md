# Projet Sécurité Musée — Rondes NFC
## Dossier de réponse à l'appel d'offres

> **Contexte réévalué.** Ce n'est pas un exercice de code : c'est une offre commerciale évaluée comme un appel d'offres, notée par le client (une entreprise de sécurité) et par les équipes concurrentes. On juge : compréhension du besoin réel, POC qui marche en live, choix techniques justifiés, traitement de la sécurité (le sujet), réalisme du chiffrage/planning, qualité orale, crédibilité de l'entreprise, effet Wow.

> **Le besoin, formulé du point de vue du client.** Le centre de gravité n'est pas le scan : c'est l'écran de supervision du PC sécurité qui montre d'un coup d'œil, pour chaque salle, depuis combien de temps elle n'a pas été contrôlée et qui l'a contrôlée en dernier, avec une alerte visuelle immédiate (vert/orange/rouge) et une mise à jour automatique. Le scan NFC est la source de vérité qui alimente cet écran.

---

## 1. Compréhension du besoin & questions au client + hypothèses par défaut

| Point non tranché par le client | Hypothèse retenue pour l'offre |
|---|---|
| Absence de réseau dans certains endroits | L'app enregistre en local et synchronise après reconnexion → c'est l'heure de **réception serveur** qui fait foi, jamais l'heure du téléphone. |
| Salles avec des délais différents | Seuil de contrôle configurable par salle (ex. 60 min par défaut, 30 min pour les salles sensibles). |
| Scanner depuis chez soi | Non : le patch doit être verrouillé après provisioning (POC). En production : contrôle du réseau de l'entreprise, géolocalisation, ou téléphones fournis par le musée. |
| Patch détruit / arraché | La salle non scannée passe en alerte automatiquement ; prévoir une option « signaler patch HS » et un stock de patchs de rechange. |
| Créer / associer un patch à une salle | Géré par un rôle habilité (chef de poste / direction), via un écran CRUD salles ↔ patchs. |
| Formation approfondie des gardiens au-delà d'une session initiale | Hors périmètre semaine, à traiter dans l'offre commerciale (accompagnement au déploiement). |
| Synchronisation avec les plannings de gardiens (affectations, congés) | Hors périmètre semaine, piste d'évolution documentée. |
| Qui a le droit de voir quoi | Trois rôles : **gardien**, **chef de poste**, **direction** (la direction joue le rôle d'administrateur). |

### Rôles et droits

**Gardien**
- Scanner un passage.
- Signaler un changement de statut du patch (ex. patch HS, patch disparu).

**Chef de poste** (hérite des droits gardien, plus) :
- Créer et associer un patch à une salle.
- Voir les passages de toutes les salles.
- Visualiser, pour chaque salle, le délai depuis le dernier passage et l'identité du gardien concerné (écran de supervision).

**Direction** (hérite des droits gardien, plus) :
- Créer et associer un patch à une salle.
- Visualiser les passages de chaque salle et l'écran de supervision.
- Gestion CRUD des comptes gardiens (ajouter, bloquer, supprimer, modifier les droits).

---

## 2. Périmètre (MoSCoW aligné sur la consigne)

### MUST HAVE — sans ça l'offre est rejetée
Prioritaire pour la présentation ; le reste est intégré ensuite ou prévu sur le long terme.

- Scan NFC réel d'un patch physique, avec identification de la salle.
- Identification du gardien (le système sait QUI a scanné).
- Horodatage + enregistrement persistant du passage.
- Écran de supervision : temps écoulé depuis le dernier contrôle, nom du dernier contrôleur, état vert/orange/rouge.
- Mise à jour automatique de la supervision (choix retenu : **WebSocket**, voir §4).

### SHOULD HAVE — fortement valorisé
- CRUD salles/patchs + association patch ↔ salle.
- Historique des passages, filtrable par salle, par gardien et par période.
- Gestion des rôles / comptes (gardien / chef de poste / administrateur).
- Seuils d'alerte configurables par salle.

### COULD HAVE (si le temps)
- Marquer un scan comme « salle OK » ou « anomalie constatée » avec une note libre.
- Signaler un patch endommagé (déclenche un ordre de remplacement).
- Exporter les données historiques.
- Notification push au chef de poste en cas d'alerte critique.
- Rondes types (parcours ordonné de salles) et suivi de l'avancement.

### WON'T (cette semaine, mais documenté dans l'offre)
- Anti-clonage cryptographique complet (NTAG 424 DNA + SUN), MDM, notifications push.
- Multi-musées en production (le POC reste mono-musée ; l'architecture — `museum_id` partout — est prévue pour l'échelle).

---

## 3. Analyse de risques — le sujet

Le client est une entreprise de sécurité : un système de contrôle de ronde qui peut être fraudé n'a aucune valeur, puisqu'il sert justement à prouver qu'un humain est physiquement passé quelque part. On présente une analyse de risques complète ; on n'implémente pas tout, mais on prouve qu'on a tout identifié et qu'on a une réponse.

| # | Menace | Vecteur | Impact | Réponse | Dans le POC ? |
|---|---|---|---|---|---|
| R1 | Réécriture du patch (patch non verrouillé) | Sabotage, faux ID de salle | Fausse preuve de passage | Verrouiller / protéger par mot de passe après provisioning | **Démontré en live** (NfcHelper.provisionAndLock) |
| R2 | Patch inconnu / falsifié (tag vierge présenté) | Faux passage | Fausse preuve de passage | Refus si UID / code de checkpoint non enrôlé | **Dans le POC** (backend, 404) |
| R3 | Clonage du patch (« Magic Tag » qui recopie l'UID) | Scan depuis n'importe où | Ronde fictive | Production : NTAG 424 DNA + SUN (message signé AES, unique par scan) | Documenté + vérification croisée UID/code dans le POC |
| R4 | Rejeu (rejouer un ancien scan) | Faux passage | Ronde fictive | Compteur signé (SUN) + horodatage serveur | Horodatage serveur dans le POC ; compteur signé en production |
| R5 | Scan à distance (copie du patch emportée) | Ronde fictive | Ronde fictive | Patch fixé de manière inviolable + R1/R3 + recoupement réseau (balise BLE/Wi-Fi de salle) | Documenté |
| R6 | Usurpation du gardien (compte partagé / vol de session) | Impossible d'identifier le véritable auteur | Perte de traçabilité | Authentification forte, interdiction des comptes partagés, JWT, MFA en production | **Authentification réelle dans le POC** (JWT, pas de session partagée) |
| R7 | Patch arraché / détruit (vandalisme, incident) | Salle non contrôlable | Angle mort de sécurité | Salle signalée en rouge + bouton « Signaler HS » + remplacement | **Alerte automatique dans le POC** (état RED si jamais scanné / trop ancien) |
| R8 | Falsification de l'heure (téléphone rooté) | Antidatage d'un passage | Fausse preuve de passage | `received_at` côté serveur fait foi, `scanned_at` du téléphone purement indicatif | **Dans le POC** |
| R9 | Interception réseau (MITM) | Vol de données ou de jetons | Compromission de comptes | TLS/HTTPS, certificate pinning (production), jetons en Keystore | Keystore dans le POC ; TLS à activer au déploiement (HTTP toléré en réseau local pour la démo) |
| R10 | Fuite de données au repos (accès BDD / application) | Exposition de données RH | Non-conformité, image | Mots de passe hachés (BCrypt/Argon2), base protégée, minimisation des données | **Dans le POC** (BCrypt) |
| R11 | RGPD / droit du travail (surveillance des salariés) | Non-conformité, litige avec le CSE | Risque juridique | Base légale, durée de conservation limitée, information des salariés, consultation du CSE | Traité dans l'offre (hors code) |
| R12 | Indisponibilité (panne serveur) | Plus de supervision | Angle mort de sécurité | Sauvegardes, health-check, redondance en production | Documenté |

---

## 4. Architecture technique

```
┌────────────────────────┐     HTTPS / JWT      ┌──────────────────────────┐
│  App Android (Kotlin)  │ ─────────────────▶   │      Backend Ktor        │
│  - NFC lecture/écriture│                      │  - API REST (scans, CRUD)│
│  - Auth gardien        │ ◀─────────────────   │  - Auth JWT + rôles      │
│  - Room (file offline) │    sync des scans    │  - Règles (seuils/salle) │
└────────────────────────┘                      │  - Horodatage serveur    │
          │ tap                                  │  - WebSocket (push live) │
          ▼                                      └───────┬──────────┬───────┘
   [ Patch NFC salle ]                                   │ push     │
   UID + code checkpoint + écriture verrouillée           ▼          ▼
                                              ┌──────────────┐  ┌───────────┐
   PC sécurité ──▶ Écran supervision  ◀───────│  WebSocket    │  │ H2 (POC)  │
   (page HTML servie par le backend,          └──────────────┘  │ → Postgres│
    vert/orange/rouge, temps réel)                               │ en prod  │
                                                                   └───────────┘
```

### Un seul poste web, pas deux stacks

Le PC sécurité n'a pas besoin d'une application : il a besoin d'un seul écran en lecture seule. Ce n'est pas un « front web » à construire, c'est une page HTML/JS vanilla que le backend Kotlin sert lui-même (`GET /`). Le navigateur n'est qu'une surface d'affichage passive — il ouvre `http://<ip-serveur>:8080/`, rien à installer. Tout le reste (scan, provisioning, historique, gestion des comptes, quel que soit le rôle) vit dans l'app Android. Une seule stack (Kotlin), un backend, une app mobile, une page HTML servie statiquement — pas de React/Vue, pas de Kotlin Multiplatform : un écran passif ne justifie pas une deuxième chaîne de build.

### Choix des technologies

- **Android natif Kotlin + Jetpack Compose** : le NFC bas niveau (lecture UID, écriture NDEF, verrouillage par mot de passe NTAG21x) est nativement maîtrisé — c'est exactement le cœur sécurité du projet. Flutter/React Native passeraient par des plugins plus limités pour ces opérations.
- **Backend Ktor (Kotlin)** : léger, WebSocket natif (clé pour la supervision temps réel), coroutines — une seule techno pour toute l'équipe (mobile + serveur), donc moins de risque en une semaine. Spring Boot se défend (écosystème plus riche) mais est plus lourd à configurer pour ce périmètre et ce délai.
- **H2 en POC, PostgreSQL visé en production** : Exposed (l'ORM utilisé) abstrait l'accès aux données — passer d'H2 à PostgreSQL, c'est changer l'URL de connexion et le driver, rien d'autre ne bouge dans le code métier. Argument commercial direct : « notre POC tourne sur un simple laptop, l'architecture est prête pour le cloud ».
- **WebSocket plutôt que polling** : le client veut voir une alerte « immédiatement » — le push est plus convaincant qu'un rafraîchissement périodique. Repli en polling documenté si la connexion WS est instable le jour de la démo.

### Sécurité dans l'architecture

Le téléphone ne parle jamais directement à la base de données : il ne connaît que l'API et détient un jeton à portée limitée. Tout transite en HTTPS/TLS (HTTP toléré en réseau local pour le POC). Le JWT répond au « qui » (authentification), les rôles au « quoi » (autorisation, RBAC vérifié à chaque route). `received_at` côté serveur fait foi (R8). Les mots de passe sont hachés (BCrypt), jamais stockés en clair. Les jetons vivent dans le Keystore Android, jamais en `SharedPreferences` en clair. Le backend valide chaque entrée et rejette tout checkpoint inconnu ou UID incohérent (R2/R3).

### Scalabilité 1 → 50 musées

1. **Multi-tenant dès le modèle de données** : une colonne `museum_id` sur `museums`, `guards`, `checkpoints` isole chaque musée dans un déploiement unique — le logiciel se paie une fois, le coût marginal par musée est du matériel + paramétrage.
2. **API stateless** → mise à l'échelle horizontale (plusieurs instances derrière un load-balancer).
3. **WebSocket à l'échelle** : une connexion WS est rattachée à une seule instance ; au-delà d'une instance, un bus pub/sub (Redis) relaie les événements entre instances. En POC, une seule instance suffit, donc pas de Redis — mais le chemin est documenté pour ne pas être pris au dépourvu à l'oral.

---

## 5. Modèle de données

- **museums** *(multi-tenant)* : `id`, `name`, `created_at`
- **guards** : `id`, `museum_id (FK)`, `full_name`, `badge_number`, `login`, `password_hash`, `role` (GUARD / SUPERVISOR / ADMIN), `is_active`, `created_at`
- **checkpoints** (salles/patchs) : `id`, `museum_id (FK)`, `tag_uid`, `checkpoint_code` (UUID écrit sur le patch), `room_name`, `zone`, `alert_threshold_min`, `is_active`, `created_at`
- **scans** (le cœur) : `id`, `guard_id (FK)`, `checkpoint_id (FK)`, `scanned_at` (téléphone, indicatif), `received_at` (serveur, fait foi), `status` (OK / ANOMALY), `note`, `device_id`

**Vue de supervision (calculée)** : par salle → `last_scan = MAX(received_at)`, `last_guard`, `elapsed = now - last_scan`, `état = vert/orange/rouge` selon `elapsed` vs `alert_threshold_min` (seuils : < 2/3 du seuil = vert, < seuil = orange, ≥ seuil ou jamais scanné = rouge).

Index recommandés : `scans(checkpoint_id, received_at)`, `scans(guard_id, received_at)`, `checkpoints(museum_id, tag_uid)`.

Évolutions prévues et non retenues pour le POC (documentées) : `audit_log` (traçabilité inviolable), `incidents` (anomalie détaillée + photo), `patrols`/`patrol_assignments` (rondes types).

---

## 6. Ordre de réalisation — la logique « tranche verticale »

Trois règles issues des pièges classiques à éviter :

1. **Cadrage avant code** — deux heures le premier jour économisent une journée en fin de semaine.
2. **Le NFC en premier, pas vendredi** — c'est le risque n°1, on l'attaque dès que possible.
3. **Tranche verticale avant largeur** — plutôt que « tout le mobile, puis tout le backend, puis la supervision », on fait traverser un seul scan toute la chaîne (patch → backend → supervision qui l'affiche) le plus tôt possible. Ça dérisque l'intégration et donne un POC démontrable très tôt, avant d'ajouter offline / CRUD / historique / rôles.

| Étape | Contenu | Pourquoi à cette place |
|---|---|---|
| É0 | Cadrage : besoin, questions client, MoSCoW figé, analyse de risques v1, technologies retenues, modèle de données, dépôt Git | Rien ne démarre sans une cible commune |
| É1 | Spike NFC : lecture de l'UID d'un patch physique | Dérisque immédiatement le principal risque technique |
| É2 | Tranche verticale minimale : un gardien scanne une salle (code connu) → backend minimal → supervision affichant « Salle X, il y a N s, par Y » | Valide toute la chaîne de bout en bout, POC de secours atteint tôt |
| É3 | Sécurisation du patch : écriture du code, verrouillage, refus des patchs inconnus ou incohérents | Cœur de la sécurité, point le plus scruté par ce client |
| É4 | Authentification réelle des gardiens et gestion des rôles (JWT) | Identification fiable de l'utilisateur |
| É5 | Supervision complète : états vert/orange/rouge, temps écoulé en temps réel, WebSocket, dernier contrôleur | Fonctionnalité principale, cœur de la démonstration |
| É6 | Fonctionnement hors ligne et synchronisation (cas du sous-sol) | Contrainte terrain réelle ; l'heure serveur reste la référence |
| É7 | CRUD salles/patchs, association, seuils | Fonctionnalités Should Have, rend la solution exploitable |
| É8 | Historique des contrôles (salle, gardien, période) | Suivi et analyse |
| É9 | Durcissement sécurité, recette finale, répétition démo | En dernier, périmètre stabilisé |

**Ligne de coupe en cas de retard : É1 → É2 → É3 → É4 → É5** = le minimum pour une offre crédible.

---

## 7. Planning de la semaine (flux parallèles)

| Jour | Objectif commun | Détail |
|---|---|---|
| J1 | Cadrage (matin) + spike NFC (après-midi) | Tous : É0. Mobile : É1. Backend : squelette + modèle de données. Offre : questions client + risques v1. |
| J2 | Tranche verticale de bout en bout (É2) — POC de secours atteint | Intégration mobile ↔ backend ↔ supervision : un scan NFC apparaît en temps réel. |
| J3 | Sécurisation du patch (É3) + authentification/rôles (É4) | Mobile : écriture + verrouillage NFC, login. Backend : JWT, refus tags inconnus. |
| J4 | Supervision complète (É5) + hors ligne (É6) + CRUD salles/patchs (É7) | Flux en parallèle. Offre : chiffrage, planning, slides. |
| J5 | Historique (É8) + durcissement/recette (É9) + répétition démo | Gel du code à midi, répétition l'après-midi. |

---

## 8. Méthodologie & organisation d'équipe

**Méthode retenue : Kanban + sprint d'une semaine (Scrum allégé).**

Pourquoi pour ce client : le besoin n'est pas figé (le client a des angles morts qu'on doit lui faire découvrir), il faut livrer un POC vite et itérer avec ses retours → approche empirique/agile. Un cycle en V supposerait des specs figées dès le départ : inadapté ici, trop rigide pour une semaine. Un Scrum complet (sprints de 2 semaines, cérémonies lourdes) n'a pas de sens sur 5 jours : on garde l'esprit (backlog priorisé, revue) sans la lourdeur.

**Rituels** : daily de 10 min, board « À faire / En cours / Fait » (GitHub Projects ou Trello), revue le J5.
**Outils** : Git + branches + pull requests (revue croisée obligatoire).

**Organisation d'équipe (anti « développeur star »)** : un seul point de défaillance rend l'offre non crédible. Flux Mobile, Flux Backend/Data, Flux Supervision/Front, Flux Offre/Sécurité/Soutenance (transverse). Chacun sait présenter au moins un autre flux que le sien. Pairing sur le NFC (risque n°1).

**Stratégie de tests & recette**
- Unitaires : logique des seuils (vert/orange/rouge), règles de sécurité (refus tag inconnu, mismatch UID), calcul du temps écoulé.
- Intégration : cycle scan → sync → supervision ; comportement hors ligne.
- Tests terrain NFC : sur les vrais patchs et les vrais téléphones, tôt et souvent.
- Recette : checklist des must-have + déroulé exact de la démo, validée en répétition J5.

**Gestion des risques projet** : NFC (spike J1), intégration (tranche verticale J2), démo live (plan B vidéo + jeu de données pré-rempli), verrouillage de patch irréversible pendant les tests (garder des patchs vierges de rechange).

---

## 9. Offre commerciale

> Chiffres illustratifs à adapter à vos hypothèses réelles (taux journalier, nombre de salles, réutilisation de matériel). Ce n'est pas un conseil financier mais un modèle d'estimation à défendre à l'oral.

Coût pour l'entreprise = pas seulement le salaire : brut → + charges patronales (~42 %) → + frais de structure (locaux, matériel, licences, encadrement, congés, temps non productif) → coût complet ≈ 300–450 €/jour pour un développeur junior/confirmé.

### Chiffrage détaillé (charge du produit réel)

| Poste | Jours·homme |
|---|---|
| Cadrage, specs, architecture, design sécurité | 4 |
| App Android (NFC, auth, scan, offline, UI) | 12 |
| Backend (Ktor, API, JWT, sync, WebSocket, seuils, CRUD) | 10 |
| Écran de supervision temps réel | 7 |
| Rôles / comptes / admin | 4 |
| Durcissement sécurité (NTAG 424/SUN, TLS pinning, tests intrusion) | 6 |
| Tests / QA / recette | 6 |
| CI/CD, déploiement, doc, formation | 4 |
| Gestion de projet (~12 %) | 6 |
| **Total** | **≈ 59 j·h** |

### Coûts matériels (par musée)
Patchs NTAG213 ≈ 0,30–0,80 €/u, ou NTAG 424 DNA ≈ 1–3 €/u (option anti-clonage). Exemple 40 salles + rechange ≈ 150 €. Téléphones gardiens : 6 × ≈ 200 € ≈ 1 200 € (ou réutilisation du parc existant).

### Coûts récurrents
Hébergement cloud ≈ 20–80 €/mois. Compte Google Play : 25 $ (unique). Maintenance ≈ 15–20 %/an du coût de build ≈ 3 500–4 600 €/an.

### Chiffrage exemple (illustratif)
Développement 59 j × 400 € ≈ 23 600 € ; + matériel ≈ 1 350 € ; + store 23 € → **année 1 ≈ 25 000 €** ; récurrent ≈ 4 000–5 000 €/an.

### Passage à l'échelle 1 → 50 musées
Le logiciel est mutualisé (multi-tenant) : le build ne se paie qu'une fois. Par musée additionnel, on ajoute surtout du matériel (patchs, éventuellement téléphones), de l'onboarding/paramétrage (≈ 1–2 j/musée) et une part d'hébergement croissante. Argument commercial : coût marginal par musée faible → la marge s'améliore avec l'échelle.

### Planning du projet réel
≈ 59 j·h → environ 3 semaines pour une équipe de 4 (hors coordination), en trois incréments : (1) tranche verticale + sécurité du patch, (2) supervision temps réel + hors ligne + CRUD, (3) durcissement + multi-tenant + recette.

---

## 10. L'entreprise & la soutenance

Se vendre : nom + positionnement clair, par exemple « la preuve de ronde infalsifiable ». Posture d'entreprise de sécurité qui parle à une entreprise de sécurité : franchise sur ce qui est fait vs documenté.

**Effets Wow à orchestrer**
- NFC live qui marche devant tout le monde (le risque n°1 devenu démonstration).
- La salle qui vire au rouge en direct : lancer un chrono sur une salle au début de la soutenance ; vers la 8ᵉ minute, la supervision la fait passer orange puis rouge en temps réel devant le jury.
- Sécurité en live : tenter de réécrire un patch verrouillé → refusé ; scanner un patch inconnu → rejeté.

**Plan de démo (10–30 min)** : provisioning (associer + verrouiller un patch) → ronde (login gardien, scan, « salle OK », horodaté, par X) → sécurité (réécriture refusée, tag inconnu rejeté, heure serveur qui fait foi) → supervision temps réel (le rouge en direct) → ouverture sur NTAG 424/SUN et la scalabilité.

**Plan B** : vidéo de secours de la manip NFC + jeu de données pré-rempli. La démo live rapporte plus, mais le repli évite le zéro.

**Anticiper les questions des concurrents** : « comment empêchez-vous le scan depuis chez soi ? » (R5) ; « que se passe-t-il hors réseau ? » (offline + heure serveur) ; « votre chiffrage tient-il à 50 musées ? » (§9) ; « pourquoi Ktor et pas Spring ? » (§4). Préparer aussi ses propres questions aux autres équipes : challenger leur sécurité et leur chiffrage fait partie du métier.

---

## 11. Évolutions production

Patchs NTAG 424 DNA + SUN (AES-128, message signé unique par tap → anti-clonage/anti-rejeu réels) ; tags tamper-evident ; recoupement de présence (balise BLE/Wi-Fi de salle) ; MDM + certificate pinning + détection root ; notifications de retard ; conformité RGPD/CSE complète ; multi-tenant en production + observabilité (métriques, alerting, sauvegardes).
