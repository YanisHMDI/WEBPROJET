# Le dîner des philosophes en microservices

Projet Spring Boot 3.3 (Java 17, Maven multi-modules) qui fait passer le problème des philosophes d'une
version naïve à un protocole distribué d'exclusion mutuelle.

Chaque philosophe est un microservice indépendant. Un moniteur observe l'ensemble et détecte en direct toute
fourchette utilisée par deux philosophes à la fois.

## Sommaire

1. [État d'avancement](#1-état-davancement)
2. [Démarrage rapide](#2-démarrage-rapide)
3. [Architecture](#3-architecture)
4. [Arborescence du projet](#4-arborescence-du-projet)
5. [Les parties réalisées](#5-les-parties-réalisées), de la [partie 1](#partie-1--philosophes-gâtés) à la [partie 3.3](#partie-33--protocole-distribué-par-jetons)
6. [Tests](#6-tests)
7. [API HTTP](#7-api-http)
8. [Configuration](#8-configuration)
9. [Ce qu'il reste à faire](#9-ce-quil-reste-à-faire)

---

## 1. État d'avancement

| Partie du sujet | État | Mode à lancer |
|---|---|---|
| 1. Philosophes gâtés | ✅ fait | `SPOILED` |
| 2. Superviseur naïf + recensement des failles | ✅ fait | `NAIVE_SUPERVISOR` |
| 3.1 Section critique sans verrou | ✅ fait | `PAIR_SUPERVISOR` + `UNSAFE` |
| 3.2 Section critique avec verrou | ✅ fait | `PAIR_SUPERVISOR` + `LOCKED` |
| 3.3 Protocole distribué par jetons | ✅ fait | `TOKEN` |
| 3.4 Comparaison avec ZooKeeper | ⏳ à faire | — |
| 4. Tests (unitaires, intégration, concurrence, résilience) | 🟡 fait pour les parties 1 à 3.3 | `mvn verify` |
| 5. Master–Worker sur un graphe | ⏳ à faire | — |
| 6. DevOps (Docker ✅, Kafka, Kubernetes, CI/CD, supervision) | 🟡 Docker seulement | `docker compose up` |
| 7. Livrables (code ✅, tests ✅, rapport) | 🟡 rapport à rédiger | — |

**77 tests, tous verts.**

---

## 2. Démarrage rapide

Prérequis : JDK 17 ou plus récent, et Maven 3.9 ou plus récent. Docker est optionnel.

```bash
mvn verify                       # compile les 4 modules et lance les 77 tests

./scripts/run-local.sh TOKEN     # lance le moniteur, le superviseur et 5 philosophes (ici la partie 3.3)
open http://localhost:8080       # tableau de bord
./scripts/stop-local.sh          # arrête tout
```

`run-local.sh [MODE] [PAIR_MODE] [N] [RACE_WINDOW]` lance N philosophes (5 par défaut) dans le mode choisi.
Les journaux sont écrits dans `logs/`. Chaque partie a sa commande, donnée dans la [section 5](#5-les-parties-réalisées).

Avec Docker :

```bash
MODE=TOKEN docker compose up --build
```

---

## 3. Architecture

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
                     │    ▲  messages HTTP    ▲   │  échangés directement entre philosophes,
                     │    └──────► P2 ◄───────┘   │  sans superviseur
                     └─────────────┬──────────────┘
      modes NAIVE_SUPERVISOR       │  demandes de fourchettes
      et PAIR_SUPERVISOR (2, 3.x)  ▼
                        ┌──────────────────────┐
                        │  supervisor-service  │ :8081   état des fourchettes
                        └──────────────────────┘
```

| Module | Rôle |
|---|---|
| `common` | Code partagé : horloge de Lamport, format des événements, disposition des fourchettes autour de la table |
| `monitor-service` | Reçoit les événements de tous les philosophes, reconstruit leur état, détecte les conflits et sert le tableau de bord |
| `supervisor-service` | Gère l'état des fourchettes pour les parties 2, 3.1 et 3.2 |
| `philosopher-service` | Un processus = un philosophe. Contient la boucle de vie et toutes les façons d'obtenir les fourchettes |

**Choix d'architecture central.** La boucle du philosophe (`Philosopher`) ne change jamais. Seule change la
façon d'obtenir et de rendre les fourchettes, définie par l'interface `DiningStrategy`. Chaque partie du sujet
est une implémentation de cette interface, choisie au démarrage par `philo.mode` :

| `philo.mode` | Stratégie | Partie |
|---|---|---|
| `SPOILED` | `SpoiledStrategy` | 1 |
| `NAIVE_SUPERVISOR` | `NaiveSupervisorStrategy` | 2 |
| `PAIR_SUPERVISOR` | `PairSupervisorStrategy` | 3.1 / 3.2 |
| `TOKEN` | `TokenStrategy` + `TokenNode` | 3.3 |

---

## 4. Arborescence du projet

```
dining-philosophers/
├── pom.xml                         POM parent : Spring Boot 3.3.5, Java 17, déclare les 4 modules
├── Dockerfile                      image générique, un module au choix (--build-arg MODULE=…)
├── docker-compose.yml              moniteur + superviseur + 5 philosophes
├── README.md
├── docs/
│   └── protocole-jetons.md         conception et justification du protocole de la partie 3.3
├── scripts/
│   ├── run-local.sh                lance tout en local, sans Docker
│   └── stop-local.sh
│
├── common/                         ── code partagé par tous les services
│   └── src/main/java/fr/cytech/philo/common/
│       ├── LamportClock.java       horloge logique thread-safe : tick() et onReceive(t)
│       ├── PhilosopherEvent.java   événement envoyé au moniteur (id, type, lamport, heure, fourchettes)
│       ├── EventType.java          HUNGRY, EATING, DONE
│       └── ForkTopology.java       table ronde : P_i utilise F_i et F_{(i+1) mod N}
│
├── monitor-service/                ── observateur (:8080)
│   └── src/main/
│       ├── java/fr/cytech/philo/monitor/
│       │   ├── MonitorApplication.java
│       │   ├── MonitorProperties.java
│       │   ├── service/EventStore.java       historique, état par philosophe, détection des conflits
│       │   ├── web/EventController.java      API REST /api/events, /api/state
│       │   └── model/                        Conflict, DashboardSnapshot, PhilosopherState,
│       │                                     PhilosopherView, RecordedEvent
│       └── resources/
│           ├── application.yml
│           └── static/index.html             tableau de bord (rafraîchi toutes les secondes)
│
├── supervisor-service/             ── gestionnaire central des fourchettes (:8081), parties 2 et 3.1/3.2
│   └── src/main/java/fr/cytech/philo/supervisor/
│       ├── SupervisorApplication.java
│       ├── SupervisorConfig.java             choisit UNSAFE ou LOCKED (supervisor.pair-mode)
│       ├── SupervisorProperties.java
│       ├── fork/
│       │   ├── ForkTable.java                propriétaire de chaque fourchette + compteur de violations
│       │   ├── NaiveForkService.java         partie 2 : fourchette par fourchette, sans contrôle
│       │   ├── PairStrategy.java             parties 3.1/3.2 : « les deux ou aucune »
│       │   ├── UnsafePairStrategy.java       partie 3.1 : vérifier puis réserver, sans verrou
│       │   ├── LockedPairStrategy.java       partie 3.2 : même chose dans une section critique
│       │   └── RaceWindow.java               pause réglable pour rendre les courses visibles
│       └── web/                              ForkController, PairController, GrantResponse, ApiErrors
│
└── philosopher-service/            ── un philosophe (:8090 et suivants)
    └── src/main/java/fr/cytech/philo/philosopher/
        ├── PhilosopherApplication.java
        ├── PhilosopherProperties.java        toute la configuration philo.*
        ├── PhilosopherConfig.java            assemble la stratégie selon philo.mode
        ├── PhilosopherRunner.java            fait tourner la boucle dans un thread dédié
        ├── TokenConfig.java                  beans du mode TOKEN
        ├── core/
        │   ├── Philosopher.java              boucle Penser → Faim → Manger, émission des événements
        │   ├── DiningStrategy.java           interface : acquireForks / releaseForks / currentForks
        │   ├── EventReporter.java            interface d'envoi au moniteur
        │   └── Sleeper.java                  abstraction de Thread.sleep (tests sans attente)
        ├── strategy/                         parties 1 à 3.2
        │   ├── SpoiledStrategy.java
        │   ├── NaiveSupervisorStrategy.java
        │   └── PairSupervisorStrategy.java
        ├── token/                            partie 3.3 : protocole à jetons
        │   ├── TokenNode.java                cœur du protocole (état local, règle de transfert)
        │   ├── TokenStrategy.java            branche le protocole sur la boucle du philosophe
        │   ├── TokenRequest.java             message : demande horodatée
        │   ├── TokenTransfer.java            message : jeton d'une fourchette
        │   ├── TokenRelease.java             message : fin de demande
        │   ├── ForkAssignment.java           règle d'attribution des deux fourchettes
        │   ├── TokenTransport.java           interface d'envoi des messages
        │   ├── HttpTokenTransport.java       envoi en HTTP
        │   ├── ChaosTransport.java           injection de pannes : pertes, doublons, latence
        │   ├── Dispatcher.java               interface d'exécution des envois
        │   ├── PeerDispatcher.java           un fil d'envoi par destinataire
        │   └── PeerDirectory.java            adresses des autres philosophes
        ├── client/
        │   ├── SupervisorClient.java         client HTTP du superviseur
        │   └── HttpEventReporter.java        envoi des événements au moniteur
        └── web/
            ├── PhilosopherController.java    /api/status, /api/start, /api/stop
            ├── TokenController.java          /api/token/* (mode TOKEN)
            └── ApiErrors.java                erreurs 400 au format ProblemDetail
```

Les tests suivent la même arborescence sous `src/test/java/` (détail en [section 6](#6-tests)).

---

## 5. Les parties réalisées

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
ZooKeeper (partie 3.4).

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

---

## 6. Tests

```bash
mvn verify                                                  # les 77 tests
mvn test -pl philosopher-service -am -Dtest='Token*'        # seulement ceux de la partie 3.3
```

| Module | Classe de test | Nb | Type | Ce qu'elle vérifie |
|---|---|---|---|---|
| common | `LamportClockTest` | 4 | unitaire | règles de Lamport, pas de perte de tick en concurrence |
| common | `ForkTopologyTest` | 3 | unitaire | disposition des fourchettes, bornes |
| monitor | `EventStoreTest` | 10 | unitaire | cycle d'états, événements hors ordre, tri de Lamport, conflits entre voisins **et** sur des fourchettes quelconques |
| monitor | `EventControllerTest` | 2 | web (MockMvc) | contrat HTTP du moniteur |
| supervisor | `NaiveForkServiceTest` | 4 | unitaire | les 4 failles de la partie 2 |
| supervisor | `PairStrategyConcurrencyTest` | 6 | concurrence | 3.1 : fourchette partagée ; 3.2 : un seul servi, test de charge |
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
| | **Total** | **77** | | |

---

## 7. API HTTP

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

---

## 8. Configuration

Chaque propriété peut se passer en argument (`--philo.mode=TOKEN`) ou en variable d'environnement
(`PHILO_MODE=TOKEN`).

| Propriété | Défaut | Rôle |
|---|---|---|
| `philo.id`, `philo.count` | 0, 5 | identifiant du philosophe et nombre total |
| `philo.mode` | `SPOILED` | partie à exécuter (voir section 3) |
| `philo.think-min/max`, `philo.eat-min/max` | 500ms / 2s | durées aléatoires de réflexion et de repas |
| `philo.retry-delay`, `philo.http-timeout` | 100ms, 2s | nouvel essai si refus (2, 3.x), délai HTTP |
| `philo.monitor-url`, `philo.supervisor-url` | localhost:8080 / 8081 | adresses des services |
| `philo.token.peers` | — | 3.3 : adresses de tous les philosophes, dans l'ordre des identifiants |
| `philo.token.assignment` | `RANDOM` | 3.3 : `RANDOM` ou `NEIGHBOURS` |
| `philo.token.retransmit-interval` | 1s | 3.3 : réémission d'une demande sans réponse |
| `philo.token.retry-delay` | 200ms | 3.3 : réémission d'un jeton non acquitté |
| `philo.token.drop-rate`, `philo.token.max-delay` | 0, 0ms | 3.3 : injection de pannes (pertes, doublons, latence) |
| `supervisor.pair-mode` | `LOCKED` | `UNSAFE` (3.1) ou `LOCKED` (3.2) |
| `supervisor.race-window` | 0ms | pause entre « vérifier » et « réserver » |
| `monitor.detect-neighbour-conflicts` | `true` | détection des conflits (`false` en partie 1) |

---

## 9. Ce qu'il reste à faire

### Partie 3.4 — ZooKeeper
- [ ] Ajouter ZooKeeper au `docker-compose.yml`, avec la dépendance `curator-recipes`.
- [ ] Mode `ZOOKEEPER` : un `InterProcessMutex` par fourchette, pris dans un ordre global, ou un `InterProcessMultiLock`.
- [ ] Comparer avec la 3.3 : messages par repas, latence, équité, tolérance aux pannes (crash d'un détenteur), complexité.

### Partie 4 — Tests à compléter
- [ ] Crash définitif d'un détenteur de jeton : montrer que la sûreté tient mais pas la vivacité.
- [ ] Résilience côté superviseur : si la réponse d'un `acquire` est perdue, la fourchette reste attribuée (voir `SupervisorClient`).

### Partie 5 — Master–Worker sur un graphe
- [ ] Rôle Master et endpoint Worker `POST /api/worker/degree`.
- [ ] Partitionnement par nœud : chaque Worker reçoit les arêtes incidentes à ses nœuds, donc pas de double comptage.
- [ ] Délai maximal et relance des tâches d'un Worker en panne ; résultats idempotents grâce à un identifiant de tâche.
- [ ] Mesures en faisant varier le nombre de Workers et la taille du graphe ; loi d'Amdahl et coût réseau.
- [ ] Accessibilité s → t en parallèle (parcours en largeur par niveaux).

### Partie 6 — DevOps
- [x] Dockerfile et docker-compose.
- [ ] Kafka pour les événements vers le moniteur (`KafkaEventReporter`, l'interface `EventReporter` existe déjà).
- [ ] Manifests Kubernetes (StatefulSet pour donner un identifiant stable aux philosophes).
- [ ] GitHub Actions : `mvn verify`, construction et envoi des images.
- [ ] Supervision : Micrometer, Prometheus et Grafana, logs centralisés.

### Livrables
- [ ] Rapport technique : architecture, protocoles, choix, difficultés et solutions.
- [ ] Captures du tableau de bord pour chaque partie, traces d'exécution analysées.
