# Le dîner des philosophes en microservices

Projet Spring Boot 3.3 (Java 17, Maven multi-modules) qui fait passer le problème des philosophes d'une
version naïve à un protocole distribué d'exclusion mutuelle.

Chaque philosophe est un microservice indépendant. Un moniteur observe l'ensemble et détecte en direct toute
fourchette utilisée par deux philosophes à la fois.

## Sommaire

1. [État d'avancement](#1-état-davancement)
2. [Les étapes en bref](#2-les-étapes-en-bref)
3. [Démarrage rapide](#3-démarrage-rapide)
4. [Architecture](#4-architecture)
5. [Arborescence du projet](#5-arborescence-du-projet)
6. [Les parties réalisées](#6-les-parties-réalisées), de la [partie 1](#partie-1--philosophes-gâtés) à la [partie 5](#partie-5--master-worker-sur-un-graphe)
7. [Tests](#7-tests)
8. [API HTTP](#8-api-http)
9. [Configuration](#9-configuration)
10. [Ce qu'il reste à faire](#10-ce-quil-reste-à-faire)

---

## 1. État d'avancement

| Partie du sujet | État | Mode à lancer |
|---|---|---|
| 1. Philosophes gâtés | ✅ fait | `SPOILED` |
| 2. Superviseur naïf + recensement des failles | ✅ fait | `NAIVE_SUPERVISOR` |
| 3.1 Section critique sans verrou | ✅ fait | `PAIR_SUPERVISOR` + `UNSAFE` |
| 3.2 Section critique avec verrou | ✅ fait | `PAIR_SUPERVISOR` + `LOCKED` |
| 3.3 Protocole distribué par jetons | ✅ fait | `TOKEN` |
| 3.4 Comparaison avec ZooKeeper | ✅ fait | `ZOOKEEPER` |
| 4. Tests (unitaires, intégration, concurrence, résilience, analyse) | ✅ fait | `mvn verify` |
| 5. Master–Worker sur un graphe (degrés, accessibilité, pannes, performances) | ✅ fait | `/api/master/...` |
| 6. DevOps (Docker ✅, Kafka, Kubernetes, CI/CD, supervision) | 🟡 Docker seulement | `docker compose up` |
| 7. Livrables (code ✅, tests ✅, rapport) | 🟡 rapport à rédiger | — |

**105 tests, tous verts.**

---

## 2. Les étapes en bref

Ce qui a été fait jusqu'ici, dans l'ordre, et comment.

**Étape 1 — Des philosophes qui pensent et mangent (partie 1).**
Chaque philosophe est un petit programme Spring Boot qui tourne en boucle : penser, avoir faim, manger.
À chaque changement d'état, il envoie un message au **moniteur**. Ce message est daté par une **horloge de
Lamport**, un compteur qui augmente à chaque événement. Le moniteur affiche tout sur une page web. Ici, rien
n'est partagé, donc tout le monde peut manger en même temps.

**Étape 2 — Un superviseur naïf (partie 2).**
Un service **superviseur** garde la liste des fourchettes. Le philosophe demande sa fourchette gauche, puis sa
droite, en gardant la gauche pendant qu'il attend. Résultat : si tous prennent leur gauche en même temps,
**tout le monde est bloqué** (interblocage). Quatre tests montrent les quatre défauts de cette version.

**Étape 3 — Les deux fourchettes d'un coup, sans verrou (partie 3.1).**
Le philosophe demande « les deux ou aucune » en une seule requête. Le serveur traite plusieurs requêtes en
parallèle : deux philosophes peuvent voir la même fourchette libre au même instant et l'obtenir tous les deux.
Une petite pause ajoutée entre « vérifier » et « réserver » rend ce bug visible à chaque fois.

**Étape 4 — On ajoute un verrou (partie 3.2).**
« Vérifier puis réserver » devient une **section critique** : un verrou ne laisse passer qu'une requête à la
fois. Résultat : plus aucun conflit, ni aucun blocage. Il reste un défaut : un philosophe peut attendre
longtemps si ses voisins passent toujours avant lui (**famine**).

**Étape 5 — On corrige le moniteur.**
Le moniteur signalait parfois de **faux conflits** : le philosophe rendait ses fourchettes *avant* de dire
« j'ai fini », alors son voisin pouvait annoncer « je mange » trop tôt. Maintenant, il dit « j'ai fini »
*avant* de rendre ses fourchettes. Les messages indiquent aussi quelles fourchettes sont utilisées.

**Étape 6 — Plus de superviseur : le protocole à jetons (partie 3.3).**
Les philosophes s'organisent seuls, en s'envoyant des messages HTTP :
- chaque fourchette a un **jeton unique** : seul celui qui le tient peut l'utiliser ;
- un philosophe affamé envoie à tous « je veux telle et telle fourchette », avec la date de son horloge ;
- celui qui tient un jeton le donne à la **demande la plus ancienne**. Même un affamé cède son jeton à plus
  ancien que lui, donc personne ne reste bloqué. Et la plus ancienne demande finit toujours servie, donc
  personne n'attend indéfiniment ;
- un message perdu est renvoyé jusqu'à confirmation de réception. Chaque jeton porte un numéro, pour qu'un
  message reçu deux fois ne crée jamais un second jeton.

Résultat : **0 conflit**, même avec 20 % de messages perdus. Le détail et les justifications sont dans
[docs/protocole-jetons.md](docs/protocole-jetons.md).

**Étape 7 — Même problème, avec ZooKeeper (partie 3.4).**
Au lieu de notre protocole, chaque fourchette devient un **verrou distribué** géré par ZooKeeper, via la
bibliothèque Curator. Pour éviter l'interblocage, on prend toujours les deux verrous dans le même ordre : plus
petit numéro d'abord. Si un philosophe meurt, ZooKeeper libère ses verrous tout seul.

On a mesuré les deux solutions dans les mêmes conditions. Les **jetons sont environ 5 fois plus rapides et
plus équitables**, mais **ZooKeeper résiste à la panne d'un philosophe**, ce que les jetons ne savent pas faire.
Le détail est dans [docs/comparaison-zookeeper.md](docs/comparaison-zookeeper.md).

**Étape 8 — Tout tester, y compris les pannes (partie 4).**
105 tests, lancés par `mvn verify` :
- **unitaires** : une pièce testée seule, les autres étant simulées avec Mockito ;
- **intégration** : de vrais services qui se parlent en HTTP, et un vrai serveur ZooKeeper ;
- **concurrence** : plusieurs philosophes à la fois, un contrôleur vérifie à chaque repas qu'aucune fourchette
  n'est partagée ;
- **pannes** : messages perdus, retardés ou doublés, crash d'un philosophe, réponse perdue, Worker en panne.

Les tests de pannes ont mené à trois corrections :
- un affamé qui attend trop longtemps **abandonne** sa demande puis réessaie, ce qui limite l'effet d'un crash ;
- le superviseur répond « oui » à une demande répétée, pour le cas où la première réponse s'est perdue ;
- un philosophe arrêté pendant son repas rend bien ses verrous ZooKeeper.

Ce que les tests prouvent, et ce qu'ils ne prouvent pas, est expliqué dans
[docs/strategie-de-tests.md](docs/strategie-de-tests.md).

**Étape 9 — Calcul distribué sur un graphe (partie 5).**
Un philosophe joue le **Master**, les autres les **Workers**. Le Master découpe le graphe : le nœud v va au
Worker v mod P. Il envoie une tâche à chaque Worker et rassemble les résultats. Deux calculs sont proposés :
- le **degré** de chaque nœud. Chaque Worker ne compte que ses propres nœuds, donc rien n'est compté deux fois ;
- l'**accessibilité** s → t, par un parcours en largeur niveau par niveau.

Si un Worker tombe, sa tâche est relancée ailleurs. S'il est lent, une copie part sur un autre Worker et le
premier résultat gagne. Le résultat reste exact, car chaque tâche donne toujours le même résultat.

Mesuré : sur ces calculs très légers, **le distribué est bien plus lent que le séquentiel**, car le transfert
des données coûte plus cher que le calcul. Le détail et l'analyse sont dans
[docs/master-worker.md](docs/master-worker.md).

**Étape 10 — Lancer et organiser.**
Un script (`run-local.sh`) ou Docker Compose lance tout en une commande. Le code est rangé par service, puis
par partie du sujet, dans le package `fr.cytech.hamoudilitim.philo`.

---

## 3. Démarrage rapide

Prérequis : JDK 17 ou plus récent, et Maven 3.9 ou plus récent. Docker est optionnel.

```bash
mvn verify                       # compile les 4 modules et lance les 105 tests

./scripts/run-local.sh TOKEN     # lance le moniteur, le superviseur et 5 philosophes (ici la partie 3.3)
open http://localhost:8080       # tableau de bord
./scripts/stop-local.sh          # arrête tout
```

`run-local.sh [MODE] [PAIR_MODE] [N] [RACE_WINDOW]` lance N philosophes (5 par défaut) dans le mode choisi.
Les journaux sont écrits dans `logs/`. Chaque partie a sa commande, donnée dans la [section 6](#6-les-parties-réalisées).

Avec Docker :

```bash
MODE=TOKEN docker compose up --build
```

---

## 4. Architecture

```
                        ┌──────────────────────┐
     événements         │   monitor-service    │ :8080   tableau de bord, historique trié par Lamport,
     HUNGRY / EATING /  │                      │         détection des conflits de fourchettes
     DONE + horloge     └──────────▲───────────┘
     + fourchettes                 │
                     ┌─────────────┴──────────────┐
                     │   philosopher-service × N  │ :8090, 8091…   boucle Penser → Faim → Manger
                     │                            │
                     │   P0 ◄──────────────► P1   │  mode TOKEN (3.3) : demandes et jetons
                     │    ▲  messages HTTP    ▲   │  échangés directement entre philosophes.
                     │    └──────► P2 ◄───────┘   │  Partie 5 : P0 Master, les autres Workers
                     └──────┬──────────────┬──────┘
      modes NAIVE_SUPERVISOR│              │ mode ZOOKEEPER (3.4) :
      et PAIR_SUPERVISOR    ▼              ▼ un verrou par fourchette
              ┌──────────────────────┐  ┌──────────────────┐
              │  supervisor-service  │  │    ZooKeeper     │ :2181
              │  :8081  fourchettes  │  │  (image Docker)  │
              └──────────────────────┘  └──────────────────┘
```

| Module | Rôle |
|---|---|
| `common` | Code partagé : horloge de Lamport, format des événements, disposition des fourchettes autour de la table |
| `monitor-service` | Reçoit les événements de tous les philosophes, reconstruit leur état, détecte les conflits et sert le tableau de bord |
| `supervisor-service` | Gère l'état des fourchettes pour les parties 2, 3.1 et 3.2 |
| `philosopher-service` | Un processus = un philosophe. Contient la boucle de vie, toutes les façons d'obtenir les fourchettes, et les rôles Master et Worker de la partie 5 |

**Choix d'architecture central.** La boucle du philosophe (`Philosopher`) ne change jamais. Seule change la
façon d'obtenir et de rendre les fourchettes, définie par l'interface `DiningStrategy`. Chaque partie du sujet
est une implémentation de cette interface, choisie au démarrage par `philo.mode` :

| `philo.mode` | Stratégie | Partie |
|---|---|---|
| `SPOILED` | `SpoiledStrategy` | 1 |
| `NAIVE_SUPERVISOR` | `NaiveSupervisorStrategy` | 2 |
| `PAIR_SUPERVISOR` | `PairSupervisorStrategy` | 3.1 / 3.2 |
| `TOKEN` | `TokenStrategy` + `TokenNode` | 3.3 |
| `ZOOKEEPER` | `ZooKeeperStrategy` | 3.4 |

Le calcul Master–Worker de la partie 5 est disponible dans tous les modes.

---

## 5. Arborescence du projet

Seuls les fichiers essentiels sont gardés : 4 modules Maven, un par service, plus le code partagé.

En Java, le code d'un module se trouve toujours dans `src/main/java/fr/cytech/hamoudilitim/philo/<module>/`. Le schéma
ci-dessous montre directement ce qu'il y a au fond de ce dossier. Les tests sont rangés de la même façon dans
`src/test/java/`.

```
dining-philosophers/
├── pom.xml  Dockerfile  docker-compose.yml  README.md
├── docs/
│   ├── protocole-jetons.md         partie 3.3 : conception et justification du protocole
│   ├── comparaison-zookeeper.md    partie 3.4 : mesures et comparaison
│   ├── strategie-de-tests.md       partie 4 : les tests et leurs limites
│   └── master-worker.md            partie 5 : conception, mesures, analyse
├── scripts/                        run-local.sh, stop-local.sh, bench-graph.sh (mesures de la partie 5)
│
├── common/                         code partagé
│   ├── LamportClock.java           horloge logique
│   ├── PhilosopherEvent.java       événement envoyé au moniteur
│   ├── EventType.java              HUNGRY, EATING, DONE
│   └── ForkTopology.java           table ronde : P_i utilise F_i et F_{i+1}
│
├── monitor-service/                tableau de bord (:8080)
│   ├── EventStore.java             historique, état de chaque philosophe, détection des conflits
│   ├── EventController.java        API /api/events, /api/state
│   ├── MonitorModel.java           données renvoyées par l'API
│   ├── MonitorApplication.java, MonitorProperties.java
│   └── static/index.html           la page du tableau de bord
│
├── supervisor-service/             gestion centrale des fourchettes (:8081), parties 2, 3.1, 3.2
│   ├── SupervisorController.java   API /api/forks (partie 2), /api/pairs (3.1, 3.2)
│   ├── SupervisorApplication.java, SupervisorConfig.java, SupervisorProperties.java
│   └── fork/
│       ├── ForkTable.java          qui tient chaque fourchette, compteur de violations
│       ├── NaiveForkService.java   partie 2
│       ├── PairStrategy.java       3.1 / 3.2 : « les deux ou aucune »
│       ├── UnsafePairStrategy.java 3.1 : sans verrou
│       ├── LockedPairStrategy.java 3.2 : avec verrou
│       └── RaceWindow.java         pause pour rendre les courses visibles
│
└── philosopher-service/            un philosophe (:8090 et suivants)
    ├── PhilosopherApplication.java, PhilosopherConfig.java, PhilosopherProperties.java, PhilosopherRunner.java
    ├── PeerDirectory.java          adresses des autres philosophes
    ├── core/                       la boucle du philosophe, commune à toutes les parties
    │   ├── Philosopher.java        Penser → Faim → Manger
    │   ├── DiningStrategy.java     comment obtenir et rendre les fourchettes (une version par partie)
    │   ├── ForkAssignment.java     règle d'attribution des deux fourchettes (3.3 et 3.4)
    │   ├── EventReporter.java, HttpEventReporter.java   envoi des événements au moniteur
    │   └── Sleeper.java
    ├── strategy/                   parties 1, 2, 3.1, 3.2
    │   ├── SpoiledStrategy.java          partie 1
    │   ├── NaiveSupervisorStrategy.java  partie 2
    │   ├── PairSupervisorStrategy.java   3.1 / 3.2
    │   └── SupervisorClient.java         appels HTTP au superviseur
    ├── token/                      partie 3.3 : protocole à jetons
    │   ├── TokenNode.java          le cœur du protocole
    │   ├── TokenStrategy.java      branche le protocole sur la boucle, abandon si attente trop longue
    │   ├── TokenMessages.java      les 3 messages : demande, jeton, libération
    │   ├── TokenTransport.java     envoi en HTTP, injection de pannes
    │   └── Dispatcher.java         envois en arrière-plan, un fil par destinataire
    ├── zookeeper/                  partie 3.4
    │   └── ZooKeeperStrategy.java  un verrou ZooKeeper par fourchette
    ├── graph/                      partie 5 : Master–Worker
    │   ├── Graph.java              le graphe, son générateur, les calculs séquentiels de référence
    │   ├── GraphMaster.java        découpe, distribue, relance en cas de panne, fusionne
    │   ├── GraphWorker.java        calcule sur sa partition
    │   ├── GraphMessages.java      messages entre Master et Workers
    │   └── WorkerClient.java       appels HTTP vers les Workers
    └── web/
        ├── PhilosopherController.java   /api/status, /api/start, /api/stop
        ├── TokenController.java         /api/token/* (partie 3.3)
        └── GraphController.java         /api/master/*, /api/worker/* (partie 5)
```

---

## 6. Les parties réalisées

### Partie 1 — Philosophes gâtés

**Ce que demande le sujet.** N philosophes indépendants, avec des durées aléatoires et aucune ressource
partagée. Un moniteur reçoit les événements HUNGRY, EATING et DONE, chacun estampillé par une horloge logique
locale, reconstruit l'historique et l'affiche sur un tableau de bord.

**Ce qui est fait.**
- Chaque philosophe est un microservice qui exécute la boucle `Philosopher` : penser pendant une durée
  aléatoire, envoyer HUNGRY, obtenir les fourchettes, envoyer EATING, manger, envoyer DONE, rendre les
  fourchettes.
- Chaque événement porte la valeur de l'horloge de Lamport du philosophe (`LamportClock`).
- `SpoiledStrategy` n'attend rien et ne rend rien : rien n'est partagé.
- Le moniteur (`EventStore`) :
  - trie l'historique selon l'ordre total de Lamport (horloge, puis identifiant) ;
  - conserve un événement arrivé en retard dans l'historique, sans le laisser écraser l'état courant ;
  - compte les repas de chaque philosophe.
- Le tableau de bord (`index.html`) montre la table, l'état et les fourchettes de chaque philosophe,
  l'historique et les conflits.

**À noter pour le rapport.** En partie 1, les philosophes n'échangent aucun message. L'ordre de Lamport entre
deux philosophes différents est donc un ordre total arbitraire, et non un ordre de causalité.

```bash
./scripts/run-local.sh SPOILED     # la détection de conflit est désactivée : manger ensemble est permis
```

### Partie 2 — Superviseur naïf

**Ce que demande le sujet.** Le philosophe demande au superviseur si une fourchette est disponible, la prend,
puis recommence pour la seconde. Le superviseur ne fait aucun contrôle global : il attribue une fourchette
libre et accepte toute restitution. Il faut implémenter cette version naïve, puis recenser ses failles par
des tests.

**Ce qui est fait.**
- `NaiveForkService` (superviseur) : « vérifier » puis « attribuer » sont deux étapes séparées. Le superviseur
  ne vérifie pas qui rend la fourchette.
- `NaiveSupervisorStrategy` (philosophe) : il prend sa fourchette gauche, puis attend la droite **en gardant
  la gauche**.
- Les 4 failles sont démontrées par `NaiveForkServiceTest` :

| Test | Faille démontrée |
|---|---|
| `circularWaitDeadlock` | **interblocage** : chacun tient sa gauche et attend sa droite pour toujours |
| `checkThenActRace` | **condition de course** : deux philosophes voient la fourchette libre et l'obtiennent tous les deux |
| `releaseByNonOwner` | un philosophe peut **rendre la fourchette d'un autre** |
| `noGlobalConsistency` | aucune vérification qu'un philosophe a ses deux fourchettes pour manger |

```bash
./scripts/run-local.sh NAIVE_SUPERVISOR
# avec des durées courtes, tout le monde finit bloqué en « a faim » (interblocage)
```

### Partie 3.1 — Section critique sans verrou

**Ce que demande le sujet.** Le philosophe demande ses deux fourchettes en une seule requête. Comme le serveur
web traite les requêtes sur plusieurs threads, il faut montrer que la modification de l'état des fourchettes
n'est pas atomique.

**Ce qui est fait.**
- `UnsafePairStrategy` : le superviseur vérifie que F_i et F_{i+1} sont libres, puis réserve les deux, sans
  verrou. Deux voisins traités par deux threads Tomcat peuvent voir la même fourchette libre au même moment.
- `RaceWindow` insère une pause réglable entre « vérifier » et « réserver ». À 0 ms le code est normal ; à
  20 ms la course devient quasi systématique, donc démontrable.
- `ForkTable` compte une **violation** chaque fois qu'une fourchette déjà prise est attribuée à un autre.
- Démonstration : test `unsafeStrategyGrantsSharedFork`, et le tableau de bord affiche des conflits.

```bash
./scripts/run-local.sh PAIR_SUPERVISOR UNSAFE 5 20ms
```

### Partie 3.2 — Section critique avec verrou

**Ce que demande le sujet.** Verrouiller la section critique « vérifier F_i et F_{i+1}, puis réserver les deux
ou refuser ».

**Ce qui est fait.**
- `LockedPairStrategy` : même algorithme, protégé par un `ReentrantLock` équitable.
- Propriétés obtenues :
  - **exclusion mutuelle** : une fourchette n'a jamais deux propriétaires ;
  - **pas d'interblocage** : on ne garde jamais une fourchette en attendant l'autre ;
  - **libération sûre** : seul le propriétaire peut rendre une fourchette.
- Tests : `lockedStrategyGrantsOnlyOne` ; `lockedStrategyUnderStress` (N × 300 repas sans violation ni
  blocage) ; `lockedReleaseIgnoresNonOwner`.
- Limites, à discuter dans le rapport :
  - la **famine** reste possible : deux voisins peuvent indéfiniment passer devant celui qui est entre eux ;
  - le verrou unique sérialise toutes les demandes.

```bash
./scripts/run-local.sh PAIR_SUPERVISOR LOCKED     # 0 violation sur le tableau de bord
```

**Correction apportée au moniteur.** À l'origine, le philosophe rendait ses fourchettes avant d'envoyer DONE.
Son voisin pouvait alors annoncer EATING avant que ce DONE n'arrive, et le moniteur signalait un faux conflit,
même en mode LOCKED. DONE est maintenant envoyé **avant** la restitution, y compris quand le repas est
interrompu. Le moniteur ne peut donc plus signaler de faux conflit. Ce point est vérifié par `PhilosopherTest`.

### Partie 3.3 — Protocole distribué par jetons

**Ce que demande le sujet.** Plus de superviseur : les philosophes communiquent par messages HTTP. Chaque
fourchette a un jeton unique qui circule entre les philosophes. Un philosophe affamé diffuse une demande
horodatée et ne mange qu'avec les deux jetons. Le protocole doit :
- traiter les demandes simultanées ;
- gérer les délais et les retransmissions ;
- garantir l'exclusion mutuelle et l'équité ;
- éviter l'interblocage et la famine.

**Ce qui est fait.** Le principe en quatre étapes (conception complète et justifications dans
[docs/protocole-jetons.md](docs/protocole-jetons.md)) :

1. **Attribution des fourchettes** (`ForkAssignment`). Avec la règle `RANDOM` (par défaut), un philosophe
   prend F_i plus une fourchette tirée au hasard parmi les autres. Avec `NEIGHBOURS`, il prend F_i et F_{i+1}.
2. **Demande.** Le philosophe diffuse à tous `TokenRequest(id, n° de repas, horloge de Lamport, fourchettes)`.
3. **Priorité et règle de transfert.** La demande la plus ancienne (horloge, puis identifiant) est
   prioritaire. Le détenteur d'un jeton le cède à la demande la plus prioritaire qui en a besoin, sauf s'il
   mange avec. **Un affamé cède aussi** son jeton à une demande plus prioritaire que la sienne : c'est ce qui
   empêche l'interblocage.
4. **Fin du repas.** Le philosophe diffuse `TokenRelease`, puis ses jetons partent vers les demandes en
   attente.

**Résistance aux pannes réseau.**

| Problème | Mécanisme |
|---|---|
| Demande perdue | réémise toutes les secondes, avec le même horodatage : le philosophe garde sa place |
| Jeton perdu, ou accusé de réception perdu | réémis jusqu'à l'accusé de réception |
| Jeton reçu deux fois | un numéro de transfert croissant l'identifie : le doublon est ignoré, jamais de second jeton |
| Libération perdue | rattrapée : chaque jeton transporte la liste des repas terminés |

**Concurrence locale.** L'état d'un philosophe est protégé par un verrou. Les appels HTTP sont lancés
**après** avoir relâché ce verrou, par un fil d'envoi par destinataire (`PeerDispatcher`). Sinon deux
philosophes qui s'envoient un message au même moment s'attendraient mutuellement.

**Propriétés.** Les justifications sont dans `docs/protocole-jetons.md`, §6.
- **Exclusion mutuelle** garantie, même avec des pertes et des doublons.
- **Pas d'interblocage.**
- **Pas de famine, attente bornée** : une demande est servie après au plus N-1 repas prioritaires.

**Limite.** Si un philosophe plante définitivement en tenant un jeton, ce jeton est perdu. Aucune règle n'est
violée, mais ceux qui ont besoin de cette fourchette attendent indéfiniment. C'est le point de comparaison avec
ZooKeeper (partie 3.4). La partie 4 mesure cette limite et l'atténue, voir plus bas.

**Résultats mesurés.**
- Avec le vrai système de 5 services et 20 % de messages perdus : 0 conflit, chaque jeton est à exactement un
  endroit, et 25 à 32 doublons ont été ignorés par philosophe.
- En simulation sur 3 s : environ 215 repas par philosophe sans panne, et environ 45 avec 20 % de pertes.

```bash
./scripts/run-local.sh TOKEN
DROP_RATE=0.2 MAX_DELAY=50ms ./scripts/run-local.sh TOKEN   # pannes réseau simulées
ASSIGNMENT=NEIGHBOURS ./scripts/run-local.sh TOKEN          # table ronde classique
curl localhost:8090/api/token/state                         # jetons, file d'attente, statistiques de P0
```

### Partie 3.4 — Comparaison avec ZooKeeper

**Ce que demande le sujet.** Mettre en place une seconde solution, fondée sur un verrou distribué (ZooKeeper
ou un autre outil de coordination), puis la comparer au protocole à jetons.

**Ce qui est fait.**
- `ZooKeeperStrategy` : chaque fourchette est un verrou Curator `InterProcessMutex`. Prendre le verrou, c'est
  créer un nœud **éphémère et séquentiel** dans ZooKeeper. Le plus petit numéro tient le verrou, les autres
  attendent leur tour, dans l'ordre d'arrivée.
- **Pas d'interblocage** : les deux verrous sont pris dans un ordre global, plus petit indice d'abord.
- **Panne** : les nœuds éphémères disparaissent à l'expiration de la session du philosophe, donc ses
  fourchettes sont libérées automatiquement.
- **Comparaison chiffrée**, pour N = 3, 5 et 9 (`TokenVsZooKeeperComparisonTest`). Les jetons sont environ 5 fois
  plus rapides, plus équitables, et sans infrastructure. ZooKeeper envoie un nombre de messages constant et
  récupère la panne d'un philosophe. Les tableaux et l'analyse sont dans
  [docs/comparaison-zookeeper.md](docs/comparaison-zookeeper.md).

```bash
docker run -d -p 2181:2181 zookeeper:3.9 && ./scripts/run-local.sh ZOOKEEPER
MODE=ZOOKEEPER docker compose up --build      # ou tout en Docker, ZooKeeper compris
```

### Partie 4 — Tests et analyse

**Ce que demande le sujet.** Écrire des tests unitaires, d'intégration, de concurrence et de résilience, puis
distinguer ce qu'ils prouvent de ce qui demanderait une vérification formelle.

**Ce qui est fait.** Les 105 tests sont rangés selon ces 5 catégories dans
[docs/strategie-de-tests.md](docs/strategie-de-tests.md). Les tests de pannes ajoutés dans cette partie, et ce
qu'ils ont permis de corriger :

| Panne | Constat | Correction |
|---|---|---|
| Crash d'un philosophe qui tient des jetons (`TokenCrashTest`) | sûreté conservée, mais **tout le système se bloque** en cascade : un affamé bloqué garde ses jetons, qui bloquent d'autres affamés | `philo.token.give-up-after` : après 10 s d'attente, l'affamé abandonne, rend ses jetons et redemande. Seuls ceux dont la propre fourchette est perdue restent bloqués |
| Réponse perdue côté superviseur | la fourchette restait attribuée à un philosophe qui croyait ne pas l'avoir | le superviseur verrouillé répond « oui » à une demande répétée (idempotence) |
| Philosophe arrêté pendant un repas, sous ZooKeeper | ses verrous n'étaient pas rendus, à cause du thread interrompu | `releaseForks` efface le drapeau d'interruption le temps de rendre les verrous |
| Crash sous ZooKeeper | ses verrous sont libérés à l'expiration de sa session | rien à corriger |

**Ce que les tests ne prouvent pas.** Ils explorent beaucoup d'exécutions, mais pas toutes. Une vérification
exhaustive demanderait un modèle **TLA+** vérifié par TLC, pour de petits N.

### Partie 5 — Master–Worker sur un graphe

**Ce que demande le sujet.** Un philosophe Master répartit un grand graphe entre des philosophes Workers pour
calculer le degré de chaque nœud, puis l'accessibilité s → t. Il faut gérer un Worker en panne ou lent, et
mesurer les performances.

**Ce qui est fait.** Le détail est dans [docs/master-worker.md](docs/master-worker.md).
- **Partitionnement** : le nœud v va à la partition v mod P. Chaque Worker reçoit les arcs qui touchent ses
  nœuds, et ne compte que ses propres extrémités : **pas de double comptage**. Le Master vérifie que la somme
  des degrés vaut 2|E| et compare au calcul séquentiel.
- **Accessibilité** : parcours en largeur par niveaux. Chaque Worker garde les successeurs de ses nœuds, et
  le Master fusionne les frontières jusqu'à atteindre t, ou jusqu'à ce que la frontière soit vide.
- **Pannes** : une tâche échouée est relancée sur un autre Worker. Une tâche trop lente est copiée sur un
  autre Worker, et le premier résultat gagne. Une partition perdue en plein parcours est rechargée ailleurs.
  C'est vérifié en tests, et sur le vrai système en tuant un Worker avec `kill -9`.
- **Performances** : avec 2 millions d'arcs, le calcul des degrés prend 350 ms avec 1 Worker et 240 ms avec 4,
  contre 4 ms en séquentiel. Le coût de communication domine (loi d'Amdahl).

```bash
./scripts/run-local.sh SPOILED
curl -X POST localhost:8090/api/master/degrees -H 'Content-Type: application/json' \
     -d '{"nodes":100000,"edges":500000,"seed":1,"workers":4}'
curl -X POST localhost:8090/api/master/reachability -H 'Content-Type: application/json' \
     -d '{"nodes":100000,"edges":500000,"seed":1,"source":0,"target":99999}'
./scripts/bench-graph.sh                      # toutes les mesures de performance
```

---

## 7. Tests

```bash
mvn verify                                                  # les 105 tests
mvn test -pl philosopher-service -am -Dtest='Token*'        # seulement ceux de la partie 3.3
caffeinate -i mvn verify                                    # macOS : empêche la mise en veille pendant les tests
```

Le classement des tests selon les 5 points de la partie 4 du sujet est dans
[docs/strategie-de-tests.md](docs/strategie-de-tests.md).

| Module | Classe de test | Nb | Type | Ce qu'elle vérifie |
|---|---|---|---|---|
| common | `LamportClockTest` | 4 | unitaire | règles de Lamport, pas de perte de tick en concurrence |
| common | `ForkTopologyTest` | 3 | unitaire | disposition des fourchettes, bornes |
| monitor | `EventStoreTest` | 10 | unitaire | cycle d'états, événements hors ordre, tri de Lamport, conflits entre voisins **et** sur des fourchettes quelconques |
| monitor | `EventControllerTest` | 2 | web (MockMvc) | contrat HTTP du moniteur |
| supervisor | `NaiveForkServiceTest` | 4 | unitaire | les 4 failles de la partie 2 |
| supervisor | `PairStrategyConcurrencyTest` | 7 | concurrence | 3.1 : fourchette partagée ; 3.2 : un seul servi, test de charge, demande répétée après une réponse perdue |
| supervisor | `SupervisorApiTest` | 3 | web (MockMvc) | contrat HTTP du superviseur |
| philosopher | `PhilosopherTest` | 4 | unitaire (Mockito) | ordre HUNGRY → EATING → DONE → restitution, horloge, interruption |
| philosopher | `SupervisorStrategiesTest` | 4 | unitaire (Mockito) | gauche puis droite, retentatives |
| philosopher | `SupervisorClientTest` | 4 | web (MockRestServiceServer) | client HTTP du superviseur, panne = refus |
| philosopher | `PhilosopherApplicationTest` | 2 | Spring Boot | choix de la stratégie selon le mode |
| philosopher | `TokenNodeTest` | 20 | unitaire (Mockito) | 3.3 : horodatage, diffusion, priorité, cession d'un affamé, doublons, demandes périmées, réémissions, messages invalides |
| philosopher | `TokenStrategyTest` | 2 | unitaire (Mockito) | 3.3 : réémission pendant l'attente, abandon si interruption |
| philosopher | `ForkAssignmentTest` | 2 | unitaire | 3.3 : règles d'attribution |
| philosopher | `TokenProtocolSimulationTest` | 4 | concurrence + résilience | 3.3 : 5 à 7 philosophes avec un contrôleur global ; 0 violation, tout le monde mange, équité, jetons conservés, y compris avec 20 % de pertes, des doublons et de la latence |
| philosopher | `TokenHttpIntegrationTest` | 3 | intégration | 3.3 : 3 vrais services Spring Boot en HTTP ; 0 conflit, philosophe arrêté qui cède ses jetons |
| philosopher | `TokenCrashTest` | 2 | résilience | 4 : crash d'un détenteur de jetons, sans puis avec abandon |
| philosopher | `ZooKeeperStrategyTest` | 4 | intégration + concurrence | 3.4 : vrai serveur ZooKeeper ; 0 violation, ordres opposés sans interblocage, verrous libérés après un crash ou un arrêt |
| philosopher | `TokenVsZooKeeperComparisonTest` | 3 | mesure | 3.4 : les deux solutions pour N = 3, 5 et 9 |
| philosopher | `GraphTest` | 4 | unitaire | 5 : graphe reproductible, somme des degrés = 2\|E\|, accessibilité séquentielle |
| philosopher | `GraphMasterTest` | 11 | unitaire + résilience | 5 : résultats exacts pour 1 à 4 Workers ; Worker en panne, lent, ou qui tombe en plein parcours |
| philosopher | `GraphHttpIntegrationTest` | 3 | intégration | 5 : Master et Workers en HTTP, y compris après l'arrêt d'un Worker |
| | **Total** | **105** | | |

---

## 8. API HTTP

| Service | Méthode | Chemin | Effet |
|---|---|---|---|
| monitor | POST | `/api/events` | reçoit `{philosopherId, type, lamport, timestamp, forks}` |
| monitor | GET | `/api/events?limit=200` | historique trié par (lamport, id) |
| monitor | GET | `/api/state` | état, repas et fourchettes de chacun, conflits |
| monitor | DELETE | `/api/events` | réinitialise |
| supervisor | GET | `/api/forks` | propriétaires et nombre de violations |
| supervisor | GET | `/api/forks/{f}/available` | partie 2 |
| supervisor | POST | `/api/forks/{f}/acquire?philosopher=p` | partie 2 |
| supervisor | POST | `/api/forks/{f}/release?philosopher=p` | partie 2 |
| supervisor | POST | `/api/forks/reset` | réinitialise les fourchettes |
| supervisor | POST | `/api/pairs/{p}/acquire` | 3.1/3.2 : `{granted: true/false}` |
| supervisor | POST | `/api/pairs/{p}/release` | 3.1/3.2 |
| philosopher | GET | `/api/status` | état, repas, horloge |
| philosopher | POST | `/api/start`, `/api/stop` | démarre / arrête la boucle |
| philosopher | POST | `/api/token/request` | 3.3 : demande `{requester, meal, lamport, forks}` |
| philosopher | POST | `/api/token/transfer` | 3.3 : jeton `{fork, seq, from, to, lamport, released}` |
| philosopher | POST | `/api/token/release` | 3.3 : fin de demande `{requester, meal, lamport}` |
| philosopher | GET | `/api/token/state` | 3.3 : jetons détenus, en transit, file d'attente, statistiques |
| philosopher | POST | `/api/master/degrees` | 5 : `{nodes, edges, seed, workers?}` → degrés, temps, compteurs de relance |
| philosopher | POST | `/api/master/reachability` | 5 : `{nodes, edges, seed, source, target, workers?}` → accessible ou non |
| philosopher | POST | `/api/worker/degree`, `/api/worker/partition`, `/api/worker/expand` | 5 : tâches envoyées par le Master |
| philosopher | DELETE | `/api/worker/jobs/{jobId}` | 5 : libère la mémoire d'un calcul |

---

## 9. Configuration

Chaque propriété peut se passer en argument (`--philo.mode=TOKEN`) ou en variable d'environnement
(`PHILO_MODE=TOKEN`).

| Propriété | Défaut | Rôle |
|---|---|---|
| `philo.id`, `philo.count` | 0, 5 | identifiant du philosophe et nombre total |
| `philo.mode` | `SPOILED` | partie à exécuter (voir section 4) |
| `philo.think-min/max`, `philo.eat-min/max` | 500ms / 2s | durées aléatoires de réflexion et de repas |
| `philo.retry-delay`, `philo.http-timeout` | 100ms, 2s | nouvel essai si refus (2, 3.x), délai HTTP |
| `philo.monitor-url`, `philo.supervisor-url` | localhost:8080 / 8081 | adresses des services |
| `philo.peers` | — | 3.3 et 5 : adresses de tous les philosophes, dans l'ordre des identifiants |
| `philo.assignment` | `RANDOM` | 3.3 et 3.4 : `RANDOM` ou `NEIGHBOURS` |
| `philo.token.retransmit-interval` | 1s | 3.3 : réémission d'une demande sans réponse |
| `philo.token.retry-delay` | 200ms | 3.3 : réémission d'un jeton non acquitté |
| `philo.token.give-up-after` | 10s | 4 : abandon d'une demande bloquée, puis nouvelle demande (0 = jamais) |
| `philo.token.drop-rate`, `philo.token.max-delay` | 0, 0ms | 3.3 : injection de pannes (pertes, doublons, latence) |
| `philo.zookeeper.connect` | `localhost:2181` | 3.4 : adresse du serveur ZooKeeper |
| `philo.zookeeper.session-timeout` | 5s | 3.4 : délai après lequel un philosophe muet est déclaré mort, et ses verrous libérés |
| `philo.graph.task-timeout` | 5s | 5 : délai avant de copier une tâche sur un autre Worker |
| `philo.graph.worker-delay` | 0ms | 5 : simule un Worker lent |
| `supervisor.pair-mode` | `LOCKED` | `UNSAFE` (3.1) ou `LOCKED` (3.2) |
| `supervisor.race-window` | 0ms | pause entre « vérifier » et « réserver » |
| `monitor.detect-neighbour-conflicts` | `true` | détection des conflits (`false` en partie 1) |

---

## 10. Ce qu'il reste à faire

### Partie 6 — DevOps
- [x] Dockerfile et docker-compose.
- [ ] Kafka pour les événements vers le moniteur (`KafkaEventReporter`, l'interface `EventReporter` existe déjà).
- [ ] Manifests Kubernetes (StatefulSet pour donner un identifiant stable aux philosophes).
- [ ] GitHub Actions : `mvn verify`, construction et envoi des images.
- [ ] Supervision : Micrometer, Prometheus et Grafana, logs centralisés.

### Livrables
- [ ] Rapport technique : architecture, protocoles, choix, difficultés et solutions.
- [ ] Captures du tableau de bord pour chaque partie, traces d'exécution analysées.
