# Suivi du POC — état des lieux et reste à faire

> Document de pilotage, distinct du cadrage (`cadrage-projet.md`, qui reste la référence pour le
> pitch/l'offre). Objectif ici : dire où on en est **réellement dans le code** au commit
> `d4cba65` (fin de la branche `fix/nfc-provisioning-reliability`), trancher les questions
> laissées ouvertes dans les notes, et prioriser ce qu'il reste à faire. En cas d'écart entre une
> note prise en cours de semaine et le code actuel, c'est le code qui fait foi — ce document
> signale ces écarts plutôt que de les ignorer.

---

## 1. État du dépôt / actions Git

- Le dernier commit utile est sur `fix/nfc-provisioning-reliability` (`d4cba65`) : provisioning
  fiabilisé (scan en deux taps), verrouillage découplé du provisioning, ciblage NTAG215 corrigé.
- **Tag et suppression de branches demandés mais non exécutables depuis cette session** : le
  push vers ce dépôt est restreint à la branche désignée de la session
  (`claude/nfc-rounds-supervision-o5a97h`) — toute tentative de `git push` d'un tag ou de
  suppression d'une autre branche distante échoue en `403`. À faire vous-même, en local :

  ```bash
  git fetch origin
  git tag -a nfc-provisioning-reliability fix/nfc-provisioning-reliability \
    -m "POC rondes NFC — provisioning fiabilisé (scan x2, verrouillage découplé), NTAG215"
  git push origin nfc-provisioning-reliability

  git push origin --delete fix/nfc-lock-reliability
  git push origin --delete fix/nfc-provisioning-bugs
  ```

  Ces deux branches ont été vérifiées : leurs commits (`91e86b9`, `8a4d8ea`, `82ef49a`) ne sont
  **pas** des ancêtres de `fix/nfc-provisioning-reliability` — ce sont bien des tentatives de
  verrouillage abandonnées/remplacées, pas du travail non fusionné. Suppression sans risque de
  perte. La branche `fix/nfc-provisioning-reliability` elle-même n'est pas supprimée (seulement
  taguée), comme demandé.

## 2. Nettoyage repéré dans le projet

- **`README.md` §« Sécurité » désynchronisé** : il annonce le « verrouillage par mot de passe du
  patch après provisioning » comme démontrable en l'état. Ce n'est plus vrai depuis le découplage
  (`ProvisionScreen` appelle `writeCheckpointCode`, pas `provisionAndLock`) — `PLAUSIBLE` R1 reste
  documenté mais n'est plus actif. À corriger dans le README quand la question du §3.2 sera
  tranchée (soit on réactive le verrouillage, soit on assume et reformule le README en
  conséquence).
- **`docs/cadrage-projet.md` §1, tableau des rôles** : indique que le *chef de poste* peut
  « créer et associer un patch à une salle ». Dans le code, `POST /api/checkpoints` exige le rôle
  `ADMIN` (`CheckpointRoutes.kt`) — le chef de poste en est donc exclu aujourd'hui. Écart doc/code
  à trancher (voir §3.1) puis à aligner des deux côtés.
- Rien d'autre d'anormal repéré (pas de fichiers de build committés, pas de secrets en clair dans
  le dépôt hors le mot de passe de provisioning déjà documenté comme limite assumée du POC).

## 3. Décisions à trancher avant de continuer à coder

### 3.1 Qui peut provisionner une salle/un patch ?
Code actuel : `ADMIN` uniquement. Cadrage : chef de poste + direction. À trancher — proposition :
garder `ADMIN` uniquement pour le POC (cohérent avec « la direction joue le rôle d'admin », évite
de complexifier la démo) et corriger le cadrage plutôt que le code, sauf si vous voulez
explicitement démontrer le CRUD au niveau chef de poste.

### 3.2 Un compte `ADMIN` peut-il scanner une salle (faire une ronde) ?
Code actuel : oui — `POST /api/scans` n'exige que `GUARD` minimum, donc tous les rôles peuvent
scanner. Note de l'équipe : logiquement non, la direction n'est pas censée faire des rondes. À
trancher : si la réponse est non, ajouter une vérification de rôle exact (`GUARD`/`SUPERVISOR`,
pas `ADMIN`) sur `POST /api/scans` — actuellement pas implémenté.

### 3.3 Verrouillage physique du patch (R1)
Actuellement désactivé côté UI (fonctions `provisionAndLock`/`testLock` présentes dans
`NfcHelper.kt` mais plus appelées) suite aux blocages rencontrés (crash « Tag is out of date »,
lenteur sur MIUI). Décision à prendre : réactiver (un seul point d'appel à changer dans
`ProvisionScreen`) ou assumer un pitch « documenté, non démontré en live » pour R1. C'est le sujet
de sécurité le plus attendu par ce client (« entreprise de sécurité ») — voir priorité en §4.

### 3.4 Patch physique orphelin après suppression d'une salle
`DELETE /api/checkpoints/{id}` supprime la salle et son historique côté serveur, mais **le patch
physique garde le `checkpointCode` écrit dessus** — rien ne l'efface sur la puce elle-même. Ce
code devient inerte (un scan renverrait 404, plus aucune salle n'y correspond côté serveur) : ce
n'est donc pas une brèche de sécurité, plutôt un residu à nettoyer avant de réutiliser le patch
pour autre chose. Aujourd'hui, seul un outil NFC externe (hors app) permet de le blanchir.
Décision à prendre, couplée à R1 (§3.3) : une action « effacer/reprovisionner ce patch » dans
l'app toucherait à `NfcHelper` de la même façon qu'un futur déverrouillage — les traiter ensemble
plutôt que d'ajouter une écriture NFC de plus isolément. Proposition : assumer la limite pour la
démo (mentionnée à l'oral), la reprendre seulement si R1 est réactivé.

## 4. Bugs signalés précédemment — statut vérifié dans le code actuel

| Bug signalé | Statut sur `fix/nfc-provisioning-reliability` |
|---|---|
| Verrouillage du patch aléatoire | Contourné en désactivant le verrouillage (§3.3), pas corrigé — le bug lui-même (fiabilité de `provisionAndLock`) n'a pas été résolu, juste mis de côté. |
| On ne voit pas le nom de la salle sur le tag | Non revérifié dans le code par cette analyse — à valider en test physique (Palier 4/5 du plan de test). |
| Erreur au nouveau scan | Probablement couvert par les 3 fixes de la branche (`crash Tag invalidé`, `stopListeningForTags` MIUI, `mauvaise puce ciblée`) mais à reconfirmer en test physique, pas de test automatisé pour ce cas. |
| Seuil d'alerte modifiable en ligne de commande mais pas via WebSocket | **Corrigé.** `PATCH` et `DELETE /api/checkpoints/{id}` déclenchent maintenant `SupervisionHub.broadcast(...)`, comme `POST /api/scans`. Vérifié avec un client WebSocket réel : la supervision reçoit bien le nouvel état sans reload. |
| Contraste : certains titres illisibles | **Corrigé.** `LoginScreen` s'affichait avant tout `Scaffold` (c'est le tout premier écran de l'app), donc son texte héritait de la couleur de contenu par défaut de Compose (noir) au lieu de celle du thème, invisible sur le fond `@color/ink` posé au niveau de la fenêtre Android. Ajout d'une `Surface` racine dans `MainActivity.kt` qui applique le thème dès le premier écran. Les autres écrans (déjà dans un `Scaffold`) n'étaient pas concernés. |

## 5. Reste à faire pour le POC (Must Have + Should Have retenus) — par ordre de priorité

Cet ordre suit la logique « risque sécurité d'abord » du cadrage §6, appliquée à ce qu'il reste
concrètement à faire à partir de l'état actuel du code.

1. ~~**CRUD salles/patchs côté front**~~ — **fait.** Onglet « Salles » (réservé `ADMIN`) : liste,
   modification (nom/zone/seuil/actif), suppression, en plus du provisioning existant regroupé
   dans le même onglet (sous-onglet « Provisionner »). Backend inchangé (déjà prêt).
2. ~~**CRUD comptes gardiens**~~ — **fait, périmètre volontairement réduit à création + suppression**
   (pas de blocage/modification de rôle cette itération, cf. décision du 22/07). Onglet « Comptes »
   (réservé `ADMIN`) : `GET/POST/DELETE /api/guards` (nouveau). La suppression est refusée (409) si
   le compte a des passages enregistrés, pour ne jamais perdre l'historique d'audit d'une ronde.
3. ~~**Corriger le manque de push WebSocket sur `PATCH`/`DELETE /api/checkpoints/{id}`**~~ —
   **fait**, à l'occasion du point 1 ci-dessus (nécessaire pour que l'édition depuis l'app se
   voie en direct sur la supervision). Vérifié avec un client WebSocket réel.
4. **Trancher §3.1, §3.2, §3.3 ci-dessus** — reporté volontairement à une décision avec le
   professeur avant d'attribuer les actions par rôle ; l'admin peut donc, pour l'instant, toujours
   scanner une salle, et seul lui peut créer une salle (inchangé).
5. **Sécurité du patch (R1)** — réactiver le verrouillage NFC ou documenter clairement le choix
   inverse pour la soutenance. C'est le point le plus probable à être challengé par le client et
   les équipes concurrentes vu le contexte de l'appel d'offres.
6. **Signalement « patch HS / anomalie »** — le champ `status` (`OK`/`ANOMALY`) existe côté
   modèle et route (`ScanRoutes.kt`), mais aucune UI mobile ne permet de l'envoyer autrement
   qu'en valeur par défaut `OK`. À câbler côté `ScanScreen`/`ProvisionScreen` si retenu pour la
   démo (répond directement à la question client « patch arraché/détruit »).
7. **Vérification terrain des bugs restants du §4** (nom de salle sur le tag, erreur au nouveau
   scan) — repasser le plan de test progressif (paliers 3 à 7) avec un vrai téléphone/patch pour
   confirmer que rien n'est resté cassé après les 3 fixes Android de la branche.
8. **Répétition de la démo complète** (cadrage §10, Palier 8 du plan de test) une fois les points
   4 à 6 stabilisés.

## 6. Autres fonctionnalités — priorisées, non implémentées cette semaine

Distinction à garder claire à l'oral : **« à faire si le temps le permet »** (peut encore
atterrir dans le POC) vs **« documenté seulement »** (ne sera pas codé, mais doit être présenté
comme compris et anticipé — ça rapporte des points sur « compréhension du besoin » sans risque de
bug en démo).

### À faire si le temps le permet (Could Have du cadrage)
1. Historique filtrable par salle / gardien / période — le back stocke déjà tout, à vérifier si
   les filtres existent réellement côté API/UI ou seulement le listing brut.
2. Export des données d'historique (CSV/PDF).
3. Rondes types (parcours ordonné de salles) et suivi d'avancement.
4. Notification push au chef de poste en cas d'alerte critique.

### Documenté seulement (Won't Have assumé, à présenter comme tel)
1. Anti-clonage cryptographique réel — migration vers NTAG 424 DNA + SUN (message signé AES,
   compteur anti-rejeu). Le POC reste sur NTAG215 (UID + verrouillage simple), documenté en R3/R4
   du cadrage.
2. Recoupement de présence (balise BLE/Wi-Fi de salle) pour contrer un scan à distance (R5).
3. MDM, certificate pinning, détection de root sur les téléphones gardiens.
4. Conformité RGPD/CSE complète (base légale formalisée, durée de conservation, consultation du
   CSE) — le principe est acté dans le cadrage (§3, R11), pas mis en œuvre techniquement.
5. Multi-musée en production (le modèle de données est déjà prêt avec `museum_id`, mais le POC
   reste mono-musée en usage réel).
6. Synchronisation avec la gestion des plannings de gardiens (affectations, congés).
7. Formation approfondie des gardiens au-delà d'une session initiale (accompagnement au
   déploiement, hors développement).

## 7. Angles morts de sécurité — liste précise pour la fin du POC

Reprise du tableau de risques du cadrage (§3, R1-R12) à l'aune de l'état réel du code, pour
trancher précisément quoi coder cette semaine vs quoi présenter comme anticipé à l'oral.

### Concrètement implémentable avant la fin du POC (faible effort, gain démo/sécurité réel)
- **R1 — verrouillage du patch** : le code existe déjà (`provisionAndLock`/`testLock`), juste
  désactivé côté UI. Réactivation = un point d'appel à changer dans `ProvisionScreen` + tests
  terrain. C'est le seul item de cette liste qui demande un vrai arbitrage temps/risque (voir §3.3
  et la question posée en fin de message).
- **R7 — signaler un patch HS/anomalie** : le champ `status` (`OK`/`ANOMALY`) existe déjà côté
  modèle et route (`ScanRoutes.kt`) ; il manque juste un bouton dans `ScanScreen` pour l'envoyer.
  Petit ajout, répond directement à la question client « patch arraché/détruit ».
- **R5 — heuristique anti-téléportation (nouveau, pas dans le cadrage initial)** : sans matériel
  supplémentaire (pas de BLE/Wi-Fi de salle), on peut détecter un cas grossier de scan à distance
  bon marché : si le même gardien scanne deux salles éloignées en moins de X secondes, c'est
  physiquement impossible. Simple règle serveur sur `guardId` + `receivedAt` + salles distinctes.
  Ne remplace pas un vrai recoupement de présence, mais coûte peu et se démontre facilement.

### Anticipé et documenté seulement (à assumer clairement à l'oral, pas de code prévu)
- **R3/R4 — anti-clonage et anti-rejeu cryptographiques réels** : nécessite des tags NTAG 424 DNA
  + SUN (message signé AES, compteur anti-rejeu), donc un autre matériel que nos NTAG215. Hors
  portée d'une semaine, indépendamment du temps — c'est une contrainte matérielle, pas un choix.
- **R5 — recoupement de présence par balise** (au-delà de l'heuristique ci-dessus) : BLE/Wi-Fi
  de salle, nécessite du matériel et une vraie campagne de test en musée.
- **R9 — TLS pinning, détection de root, MDM** : le POC tourne en HTTP sur réseau local (limite
  déjà assumée dans le README) ; ces protections ont du sens en production, pas sur un POC d'une
  semaine testé sur un seul réseau Wi-Fi contrôlé.
- **R10 — chiffrement au repos de la base** : H2 fichier local non chiffré. Les mots de passe le
  sont (BCrypt), c'est le point qui compte pour la démo ; le chiffrement de la base elle-même est
  une bascule d'infra (production), pas un développement.
- **R11 — RGPD/CSE** : question juridique/organisationnelle (base légale, durée de conservation,
  consultation du CSE), pas technique — se traite dans l'offre commerciale, jamais dans le code.
- **R12 — redondance/disponibilité serveur** : une seule instance en POC ; la stratégie de
  scalabilité (multi-instance + Redis pub/sub pour le WebSocket) est documentée au cadrage §4 ;
  pas besoin de l'implémenter pour convaincre sur un POC mono-musée.
- **Patch orphelin après suppression d'une salle (§3.4)** : voir décision couplée à R1 ci-dessus.

---

*Document généré à partir des notes de l'équipe et d'une relecture du code sur
`fix/nfc-provisioning-reliability` (commit `d4cba65`). Les notes plus anciennes contredites par
le code (ex. verrouillage « implémenté ») ont été corrigées ci-dessus plutôt que reportées
telles quelles.*
