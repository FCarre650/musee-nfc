# Notes de soutenance — script oral

> Préparé pour la présentation du 24/07. Pas du code : des notes pour parler, à adapter à l'oral,
> pas à lire mot pour mot. Sourcé sur `cadrage-projet.md`, `suivi-poc.md`, le PPT
> (`Projet_Sécurité_Musée_Rondes_NFC.pdf`) et une relecture du code pour vérifier chaque
> affirmation technique avant de la mettre dans la bouche de quelqu'un devant le client.

---

## A. Slide "Angles morts identifiés" — script complet

### A.1 Transition depuis la slide risques (à dire avant de changer de slide)

> « On vient de vous montrer comment on répond aux risques qu'on a identifiés nous-mêmes, en
> analysant la mécanique du système : quelqu'un pourrait-il tromper ce système, et comment on
> l'en empêche. Mais un cahier des charges ne dit jamais tout — le vôtre non plus, et c'est normal,
> personne ne pense à tout formuler à l'oral. Une partie de notre travail cette semaine a donc été
> de combler ces trous par des hypothèses métier. On vous les présente maintenant, une par une,
> avec la raison derrière chacune, pour que vous puissiez nous dire si on a bien deviné. »

Cette phrase fait le pont entre "on répond à des menaces qu'on a nous-mêmes formulées" (slide
risques) et "on répond aussi à des questions que vous ne nous avez jamais posées" (cette slide).
C'est exactement le critère d'évaluation « compréhension du besoin réel du client » — à dire
explicitement si l'occasion se présente.

### A.2 Les 4 bullets, un par un

**1. Réseau au sous-sol → approche offline-first**

> « Un musée a forcément des zones sans réseau — sous-sol, réserve, salles blindées. On a choisi
> une architecture "offline-first" : le téléphone du gardien enregistre le scan localement, dans
> sa propre base de données embarquée, même sans connexion. Dès qu'il retrouve du réseau, ça se
> synchronise tout seul, sans action du gardien.
>
> Le point qui compte pour vous, en tant qu'entreprise de sécurité : ce n'est **pas** l'heure du
> téléphone qui fait foi pour dater le passage, c'est l'heure à laquelle **notre serveur** reçoit
> l'information. Un téléphone, on peut trafiquer son horloge. Notre serveur, non — il n'est jamais
> entre les mains d'un gardien. Donc même quelqu'un qui changerait l'heure de son téléphone pour
> antidater un passage ne gagnerait rien : le seul horodatage qui compte est hors de son
> contrôle. »

*(Vérifié dans le code : `Scans.receivedAt` posé par le serveur à la réception, `scannedAt` du
téléphone gardé mais purement indicatif — `ScanRoutes.kt`.)*

**2. Patch arraché / détruit → la salle passe en rouge automatiquement**

> « Que se passe-t-il si le patch NFC d'une salle est arraché, cassé, ou volé ? Notre réponse :
> on n'a rien eu de spécial à construire, et c'est volontaire. Le système est pensé en "échec
> sécurisé" — si la salle n'est plus scannable, elle n'est simplement plus scannée, donc son
> compteur continue de tourner et elle passe orange puis rouge automatiquement, exactement comme
> si personne n'y était allé. L'absence de preuve déclenche l'alerte, elle ne la cache jamais.
> C'est le comportement qu'on veut par défaut dans un système de sécurité : en cas de doute, on
> alerte, on ne reste jamais silencieux. »

**3. Qui voit quoi → 3 rôles stricts**

> « Qui a le droit de voir et de faire quoi. On a tranché sur trois rôles strictement séparés —
> gardien, chef de poste, direction — avec des droits qui s'empilent : le gardien scanne, le chef
> de poste voit tout et supervise, la direction gère en plus les comptes et les salles.
>
> Techniquement, ça s'appelle le RBAC — contrôle d'accès basé sur les rôles : chaque action de
> notre API revérifie le rôle du gardien avant de l'exécuter, pas seulement l'affichage à l'écran.
> Donc même quelqu'un qui trafiquerait l'application ne pourrait pas se donner plus de droits — le
> serveur revérifie systématiquement, à chaque requête. C'est ce qui nous permet de dire, dès
> aujourd'hui, qu'on anticipe une exigence de minimisation des accès type RGPD/CSE : chacun ne
> voit que ce dont il a besoin pour son métier, pas plus. »

**4. Généralisation → architecture multi-tenant**

> « Dernière hypothèse, plus commerciale que sécuritaire, mais tout aussi structurante : vous
> n'allez pas vous arrêter à un musée. Dès la conception de la base de données, chaque donnée est
> rattachée à un identifiant de musée. Ça s'appelle une architecture multi-tenant — littéralement
> "multi-locataires" : un seul logiciel, déployé une seule fois, peut servir plusieurs musées
> complètement étanches les uns des autres, chacun ne voyant que ses propres salles et ses propres
> gardiens. Concrètement pour vous : le jour où vous passez de 1 à 50 musées, on ne réécrit rien,
> on ajoute des lignes dans la base. Le logiciel se paie une fois, pas cinquante fois. »

---

## B. Banque de réponses — angles morts NON présents sur la slide

À garder sous le coude pour les questions, pas à présenter spontanément (sauf B.1, cité en
exemple par vous). Chacun vulgarise un terme technique puis donne notre position.

**B.1 — TLS / attaque de l'homme du milieu (MITM)**

> « Le protocole HTTPS, sécurisé par un certificat TLS, chiffre toute communication entre le
> téléphone du gardien et notre serveur. Sans ça, quelqu'un connecté au même réseau Wi-Fi pourrait
> techniquement intercepter les mots de passe ou les jetons de connexion qui circulent — on
> appelle ça une attaque de l'homme du milieu ("Man-in-the-Middle") : quelqu'un qui s'insère
> silencieusement dans la conversation entre deux appareils sans qu'aucun des deux ne s'en rende
> compte. Pour le POC de cette semaine, on tourne encore en HTTP simple sur un réseau Wi-Fi
> contrôlé et fermé — assumé et documenté. En production, le certificat TLS est non négociable dès
> le premier jour. »

**B.2 — Révocation immédiate d'un compte (jeton JWT)**

> « Que se passe-t-il si vous supprimez le compte d'un gardien qui vient de partir ? Aujourd'hui,
> son jeton de connexion — un JWT, une sorte de badge numérique signé électroniquement — reste
> valide jusqu'à 12 heures après sa création, même si le compte est supprimé entre-temps, parce
> qu'on ne revérifie pas la base à chaque requête pour des raisons de performance. C'est un
> compromis qu'on assume et qu'on préfère vous dire nous-mêmes : en production, on réduirait cette
> durée de vie ou on ajouterait une liste de révocation immédiate. Un système de sécurité qui
> prétend n'avoir aucune faille n'est pas crédible — le nôtre en a, on les connaît, et on sait
> comment les fermer. »

*(Vérifié dans le code : `JwtConfig.kt`, validité 12h, aucune re-vérification `isActive` par
requête — `Roles.kt`. Point identifié après coup, en construisant le CRUD comptes.)*

**B.3 — Traçabilité des actions de configuration (pas seulement des rondes)**

> « On trace scrupuleusement qui a scanné quoi et quand. Ce qu'on ne trace pas encore, c'est qui a
> modifié la configuration elle-même — qui a désactivé une salle, changé un seuil d'alerte. En
> production, on ajouterait un journal d'audit séparé pour ces actions-là aussi : "qui a eu le
> droit de couper la surveillance de quoi" est presque aussi sensible que les rondes elles-mêmes. »

**B.4 — Le scan prouve une présence, pas une inspection**

> « Une limite qu'on assume ouvertement : notre système prouve qu'un téléphone s'est physiquement
> approché d'un patch, pas que le gardien a réellement inspecté la salle. C'est une limite de
> toute technologie NFC de ce type, pas seulement de notre solution. On l'anticipe déjà dans notre
> modèle de données avec un statut par scan (normal / anomalie constatée) et une note libre, prêts
> à être activés dans l'app si vous le souhaitez. »

**B.5 — Continuité si le téléphone du gardien tombe en panne**

> « Pas juste "pas de réseau" : et si l'appareil tombe en panne pendant la ronde ? Notre réponse
> aujourd'hui reste organisationnelle plutôt que technique : un ou deux téléphones de secours par
> équipe, comme n'importe quel autre outil professionnel. Une vraie procédure de continuité serait
> à formaliser avec vous en phase de déploiement. »

**En rafale, si on vous pousse encore plus loin (une phrase chacun, pas développées) :**
- *Qui est de service maintenant* : la supervision montre le dernier passage, pas qui est censé
  être en poste à cet instant précis — une salle rouge en pleine journée n'a pas le même sens
  qu'une salle rouge une nuit sans personnel.
- *Seuils dynamiques* : le seuil est fixe par salle aujourd'hui, pas encore différencié
  jour/nuit ou ouvert au public/fermé — évolution simple, pas codée cette semaine.
- *Accès physique au poste de supervision* : pas de verrouillage/déconnexion automatique de
  l'écran web au PC sécurité si quelqu'un s'assoit devant sans surveillance.

---

## C. Petites notes — expliquer l'architecture (slide "Architecture Technique")

### C.1 Le fil conducteur à garder en tête : suivre un seul scan

Le moyen le plus clair d'expliquer l'archi à l'oral, c'est de raconter le trajet d'**un** scan,
pas de décrire les trois blocs indépendamment :

1. Le gardien approche son téléphone du patch → l'app lit l'identifiant du patch en NFC.
2. Le téléphone envoie ça à notre backend, avec le jeton qui prouve qui est connecté.
3. Le backend vérifie que le patch est connu, pose l'heure serveur, enregistre en base.
4. Le backend **pousse** immédiatement la mise à jour à tous les écrans de supervision ouverts —
   la salle repasse au vert en direct, sans que personne n'ait besoin de rafraîchir la page.

> « Le point qui compte pour un client sécurité : le téléphone ne parle jamais directement à la
> base de données. Il ne connaît que notre API et détient un jeton à portée limitée. Un téléphone
> volé ou compromis ne donne donc pas accès à la base — juste à une API qui revérifie chaque
> requête. »

### C.2 Les trois blocs, en une phrase vulgarisée chacun

- **Mobile (Android natif, Kotlin + Jetpack Compose)** : le seul endroit qui doit parler
  "bas niveau" à la puce NFC — lecture, écriture, verrouillage. C'est pour ça qu'on a choisi du
  natif Android plutôt qu'un framework multiplateforme (Flutter, React Native) : ces frameworks
  passent par des plugins tiers plus limités sur ces opérations précises, or le NFC est le cœur
  du sujet de sécurité.
- **Backend (Ktor, Kotlin)** : le cerveau. Toute la logique de décision (qui a le droit de faire
  quoi, quel est l'état d'une salle) vit ici, jamais côté téléphone — un téléphone piraté ne peut
  donc pas mentir sur son propre état, puisqu'il ne décide de rien, il ne fait que transmettre.
- **Supervision (page web servie par ce même backend)** : le PC sécurité n'installe rien. Il ouvre
  une page dans un navigateur. Une seule techno (Kotlin) pour tout le projet, pas trois — un vrai
  argument commercial : moins de composants, moins de surface d'attaque, moins cher à maintenir.

### C.3 Deux précisions techniques à avoir en tête si on vous challenge

**Base de données : le POC tourne sur H2, pas PostgreSQL** — le slide dit PostgreSQL parce que
c'est la cible de production. Si quelqu'un regarde le code ou pose la question : le POC utilise
H2, une base embarquée en fichier local (zéro installation, pratique pour une démo sur un simple
laptop). Le point à assumer avec confiance : on passe par un ORM (Exposed) qui abstrait
totalement l'accès aux données — changer de base en production revient à changer une ligne de
configuration (l'URL de connexion + le driver), pas à réécrire du code métier. C'est un choix
d'outillage pour la semaine, pas un mensonge sur l'architecture cible.

**WebSocket avec repli automatique en "polling"** — c'est réellement codé, pas juste sur le
papier : si la connexion WebSocket tombe (Wi-Fi qui coupe, etc.), l'écran de supervision retente
la reconnexion toutes les 8 secondes et, en attendant, se rabat sur un rafraîchissement toutes les
5 secondes pour ne jamais rester complètement figé. Un petit indicateur visuel sur l'écran
("temps réel" vs "reconnexion…") montre lequel des deux modes est actif — bon réflexe à montrer
en démo si vous avez le temps, ça illustre concrètement la robustesse annoncée sur le slide
Périmètre Fonctionnel.

---

*Notes préparées à partir de `docs/cadrage-projet.md`, `docs/suivi-poc.md`, du PPT de soutenance
et d'une relecture du code (`ScanRoutes.kt`, `JwtConfig.kt`, `Roles.kt`, `Database.kt`,
`supervision.js`) pour vérifier chaque affirmation technique avant de la mettre à l'oral.*
