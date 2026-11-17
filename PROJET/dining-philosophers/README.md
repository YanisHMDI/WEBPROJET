# Le dîner des philosophes en microservices

Projet Spring Boot (Java 17, Maven multi-modules). Le projet couvre les **parties 1, 2, 3.1, 3.2 et 3.3** du sujet. Le protocole à jetons est décrit et justifié dans [docs/protocole-jetons.md](docs/protocole-jetons.md).

## Architecture

```
                    ┌────────────────────┐
   événements       │  monitor-service   │  :8080  tableau de bord + historique (ordre de Lamport)
   HUNGRY/EATING/   └────────▲───────────┘
   DONE + horloge            │
                ┌────────────┴─────────────┐
                │  philosopher-service ×N  │  :8090..  boucle Penser → Faim → Manger
                └────────────┬─────────────┘
       demandes de           │  (modes NAIVE_SUPERVISOR / PAIR_SUPERVISOR)
       fourchettes           ▼
                    ┌────────────────────┐
                    │ supervisor-service │  :8081  état des fourchettes
                    └────────────────────┘
```

| Module | Rôle |
|---|---|
| `common` | `LamportClock`, `PhilosopherEvent`, `EventType`, `ForkTopology` (P_i utilise F_i et F_{i+1 mod N}) |
| `monitor-service` | Reçoit les événements, reconstruit l'état de chaque philosophe, ignore les événements arrivés hors ordre, **détecte deux voisins qui mangent en même temps**, sert le tableau de bord |
| `supervisor-service` | Partie 2 : API fourchette par fourchette (naïve). Parties 3.1/3.2 : API « les deux ou rien », sans ou avec verrou |
| `philosopher-service` | Une instance = un philosophe. La boucle (`Philosopher`) est indépendante de la façon d'obtenir les fourchettes (`DiningStrategy`) |

**Point d'extension clé** : `philosopher-service/.../core/DiningStrategy.java`. Chaque partie du sujet = une implémentation :

| Mode (`philo.mode`) | Stratégie | Partie |
|---|---|---|
| `SPOILED` | `SpoiledStrategy` (rien à partager) | 1 |
| `NAIVE_SUPERVISOR` | `NaiveSupervisorStrategy` (gauche puis droite, en gardant la gauche) | 2 |
| `PAIR_SUPERVISOR` | `PairSupervisorStrategy` (les deux en une requête) + `supervisor.pair-mode=UNSAFE` ou `LOCKED` | 3.1 / 3.2 |
| `TOKEN` | `TokenStrategy` + `TokenNode` : protocole à jetons pair-à-pair, sans superviseur | 3.3 |
| *à faire* `ZOOKEEPER` | verrou distribué Curator | 3.4 |

## Lancer

Prérequis : JDK 17+, Maven 3.9+ (ou Docker).

```bash
mvn verify                                           # build + tous les tests

# En local (sans Docker)
./scripts/run-local.sh SPOILED                       # partie 1
./scripts/run-local.sh NAIVE_SUPERVISOR              # partie 2 → finit souvent en interblocage
./scripts/run-local.sh PAIR_SUPERVISOR UNSAFE 5 20ms # partie 3.1 → violations visibles
./scripts/run-local.sh PAIR_SUPERVISOR LOCKED        # partie 3.2 → aucune violation
./scripts/run-local.sh TOKEN                         # partie 3.3 → jetons, sans superviseur
DROP_RATE=0.2 MAX_DELAY=50ms ./scripts/run-local.sh TOKEN   # 3.3 avec pannes réseau simulées
./scripts/stop-local.sh

# Avec Docker
MODE=PAIR_SUPERVISOR PAIR_MODE=UNSAFE RACE_WINDOW=20ms docker compose up --build
DETECT_CONFLICTS=false docker compose up --build     # partie 1
```

Tableau de bord : <http://localhost:8080>. Pour l'interblocage de la partie 2, mettez des durées courtes
(`--philo.think-max=200ms --philo.eat-max=200ms`) : tous les philosophes finissent bloqués en « a faim ».

`supervisor.race-window` insère une pause entre « vérifier » et « réserver ». À 0 ms le code est normal ; à 20 ms
les courses deviennent quasi systématiques, ce qui rend les bugs démontrables.

## API

| Service | Méthode | Chemin | Effet |
|---|---|---|---|
| monitor | POST | `/api/events` | reçoit `{philosopherId, type, lamport, timestamp, forks}` |
| monitor | GET | `/api/events?limit=200` | historique trié par (lamport, id) |
| monitor | GET | `/api/state` | état, repas, conflits |
| monitor | DELETE | `/api/events` | réinitialise |
| supervisor | GET | `/api/forks` | propriétaires + nombre de violations |
| supervisor | GET | `/api/forks/{f}/available` | partie 2 |
| supervisor | POST | `/api/forks/{f}/acquire?philosopher=p` | partie 2 |
| supervisor | POST | `/api/forks/{f}/release?philosopher=p` | partie 2 |
| supervisor | POST | `/api/pairs/{p}/acquire` | 3.1/3.2 : `{granted: true/false}` |
| supervisor | POST | `/api/pairs/{p}/release` | 3.1/3.2 |
| philosopher | GET | `/api/status` | état, repas, horloge |
| philosopher | POST | `/api/start`, `/api/stop` | démarre / arrête la boucle |
| philosopher | POST | `/api/token/request` | 3.3 : demande horodatée `{requester, meal, lamport, forks}` |
| philosopher | POST | `/api/token/transfer` | 3.3 : jeton `{fork, seq, from, to, lamport, released}` |
| philosopher | POST | `/api/token/release` | 3.3 : fin de demande `{requester, meal, lamport}` |
| philosopher | GET | `/api/token/state` | 3.3 : jetons détenus, en transit, file d'attente, statistiques |

## Tests

| Test | Ce qu'il montre |
|---|---|
| `LamportClockTest` | règles de Lamport, pas de perte de tick en concurrence |
| `EventStoreTest`, `EventControllerTest` | cycle d'états, événements hors ordre ignorés, tri de Lamport, détection de conflit (voisins ou fourchettes annoncées) |
| `NaiveForkServiceTest` | **les 4 failles de la partie 2** : interblocage (attente circulaire), course check-then-act, libération par un non-propriétaire, absence de contrôle global |
| `PairStrategyConcurrencyTest` | 3.1 : deux voisins obtiennent la même fourchette ; 3.2 : un seul est servi ; stress N × 300 repas sans violation ni blocage |
| `SupervisorApiTest` | contrat HTTP du superviseur (MockMvc) |
| `PhilosopherTest` | ordre HUNGRY → acquire → EATING → DONE → release (DONE avant la restitution : pas de faux conflit), horloge croissante, fourchettes rendues même si interrompu (Mockito) |
| `SupervisorStrategiesTest` | gauche puis droite, retentatives (Mockito) |
| `SupervisorClientTest` | contrat HTTP côté philosophe, panne serveur = refus (MockRestServiceServer) |
| `TokenNodeTest`, `TokenStrategyTest`, `ForkAssignmentTest` | 3.3 unitaire (Mockito) : horodatage, priorité, préemption, doublons, demandes périmées, réémissions |
| `TokenProtocolSimulationTest` | 3.3 concurrence + résilience : 5 à 7 philosophes, oracle global, 20 % de pertes / doublons / latence → 0 violation, pas de famine, jetons conservés |
| `TokenHttpIntegrationTest` | 3.3 intégration : 3 vrais services Spring Boot en HTTP, 0 conflit, philosophe arrêté qui cède ses jetons |

## Ce qu'il reste à faire

### Partie 3.3 — protocole distribué à jetons
- [x] Algorithme : priorité de Lamport par fourchette, avec préemption des affamés moins prioritaires (voir `docs/protocole-jetons.md`).
- [x] Règle d'attribution : F_i plus une fourchette quelconque (`RANDOM`), ou la table ronde (`NEIGHBOURS`).
- [x] Diffusion, réception et mémorisation des demandes horodatées ; file triée par (lamport, id).
- [x] Concurrence locale : état sous verrou, envois HTTP hors verrou.
- [x] Réémission des demandes, jetons réémis jusqu'à l'accusé, doublons ignorés (numéro de transfert).
- [x] Analyse de l'interblocage, de la famine et de l'équité, avec les limites (crash d'un détenteur de jeton).
- [ ] Rapport : captures du tableau de bord et traces analysées en mode TOKEN.

### Partie 3.4 — ZooKeeper
- [ ] Ajouter ZooKeeper au `docker-compose.yml`, dépendance `curator-recipes`.
- [ ] `ZooKeeperStrategy` : un `InterProcessMutex` par fourchette, pris **dans un ordre global** (plus petit indice d'abord) pour éviter l'interblocage, ou `InterProcessMultiLock`.
- [ ] Comparer avec 3.3 : nombre de messages par repas, latence, équité, tolérance aux pannes, complexité.

### Partie 4 — tests à compléter
- [x] Tests unitaires du protocole à jetons (réception de demande, transfert, file d'attente, doublons).
- [x] Tests d'intégration multi-services : plusieurs contextes Spring sur des ports aléatoires (`TokenHttpIntegrationTest`).
- [x] Concurrence sur le protocole distribué (`TokenProtocolSimulationTest`, oracle global).
- [x] Résilience 3.3 : pertes, doublons et latence (`ChaosTransport`), philosophe arrêté qui détient un jeton.
- [ ] Résilience : crash définitif d'un détenteur de jeton (limite connue), Resilience4j côté superviseur.
- [x] Section « ce que les tests prouvent / ce qui demanderait une vérification formelle » pour 3.3 (`docs/protocole-jetons.md` §8).
- [ ] Limite connue à traiter : si la réponse d'un `acquire` est perdue, la fourchette reste attribuée (voir `SupervisorClient`).

### Partie 5 — Master–Worker sur un graphe
- [ ] Endpoints `POST /api/worker/degree` (sous-graphe → degrés) et rôle Master.
- [ ] Partitionnement : par nœud (hachage / plages d'identifiants), chaque Worker reçoit **les arêtes incidentes à ses nœuds** → pas de double comptage, car chaque nœud n'a qu'un propriétaire.
- [ ] Timeout + relance des tâches d'un Worker en panne ; résultats idempotents (id de tâche, dernier résultat ignoré).
- [ ] Mesures : temps vs nombre de Workers et taille du graphe ; discuter la loi d'Amdahl et le coût réseau.
- [ ] Accessibilité s → t parallèle : BFS par niveaux, chaque Worker étend la frontière de ses nœuds, le Master fusionne et s'arrête quand t est atteint ou la frontière est vide.

### Partie 6 — DevOps
- [x] Dockerfile + docker-compose (fournis).
- [ ] Kafka pour les événements vers le moniteur (remplacer `HttpEventReporter` par un `KafkaEventReporter` : l'interface est déjà là).
- [ ] Manifests Kubernetes (Deployment / StatefulSet pour les philosophes avec un id stable, Service, ConfigMap).
- [ ] GitHub Actions : `mvn verify`, build des images, push.
- [ ] Supervision : Actuator est déjà là → ajouter Micrometer + Prometheus/Grafana, logs centralisés.

### Livrables
- [ ] Rapport technique (architecture, protocoles, choix, difficultés).
- [ ] Captures du tableau de bord pour chaque partie, traces d'exécution analysées.
