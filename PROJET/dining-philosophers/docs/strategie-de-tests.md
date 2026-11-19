# Partie 4 — Stratégie de tests

Tous les tests se lancent avec `mvn verify`, soit 111 tests. Ils sont rangés ici selon les 5 points du sujet.

> Sur un portable, lancez plutôt `caffeinate -i mvn verify` sous macOS. Les tests de concurrence durent
> quelques secondes chacun. Si la machine se met en veille pendant ce temps, les durées mesurées n'ont plus de
> sens.

## 4.1 Tests unitaires (JUnit 5 et Mockito)

Une pièce est testée seule, et ses voisins sont simulés.

| Classe | Ce qui est vérifié |
|---|---|
| `TokenNodeTest` (20 cas) | **demandes horodatées** : diffusion, règle de Lamport. **Jetons** : transfert à la demande la plus prioritaire, préemption d'un affamé, départage par identifiant, conservation pendant le repas. **Attribution et libération** : libération, demandes périmées. **Erreurs** : doublons ignorés, jeton réémis jusqu'à l'accusé de réception, messages invalides rejetés |
| `TokenStrategyTest` | réémission pendant l'attente, abandon de la demande si le philosophe est interrompu |
| `ForkAssignmentTest` | règles d'attribution des fourchettes |
| `PhilosopherTest` | ordre HUNGRY → EATING → DONE → restitution, et restitution même si le repas est interrompu |
| `SupervisorStrategiesTest`, `SupervisorClientTest` | stratégies et client du superviseur. Une panne du superviseur est traitée comme un refus |
| `NaiveForkServiceTest` | les 4 failles du superviseur naïf (partie 2) |
| `EventStoreTest`, `LamportClockTest`, `ForkTopologyTest`, `GraphTest` | moniteur, horloge, disposition des fourchettes, graphe |
| `GraphMasterTest` | degrés et accessibilité comparés au calcul séquentiel, pour 1 à 4 Workers |

## 4.2 Tests d'intégration (outils de test Spring Boot)

| Classe | Ce qui est vérifié |
|---|---|
| `TokenHttpIntegrationTest` | 3 vrais services Spring Boot, sur des ports aléatoires, échangent demandes et jetons en HTTP. Un mini-moniteur ne détecte aucun conflit, et chaque jeton est à un seul endroit |
| `GraphHttpIntegrationTest` | un Master et 2 Workers en HTTP : degrés et accessibilité exacts, même après l'arrêt d'un Worker |
| `ZooKeeperStrategyTest` | un vrai serveur ZooKeeper, démarré dans la JVM du test (`curator-test`), et un client par philosophe |
| `SupervisorApiTest`, `EventControllerTest`, `PhilosopherApplicationTest` | contrats HTTP (MockMvc) et choix de la stratégie selon le mode |
| `KafkaEventListenerTest` | partie 6 : un vrai broker Kafka, démarré dans la JVM du test (`@EmbeddedKafka`). Les événements publiés arrivent au tableau de bord |
| `EventControllerTest` | partie 6 : les métriques du moniteur sont bien exposées à Prometheus |

## 4.3 Tests de concurrence

Plusieurs philosophes demandent à manger en même temps. Un **oracle global** (`DiningTable`) vérifie à chaque
repas qu'aucune fourchette n'est utilisée par deux philosophes.

| Classe | Scénarios | Résultat |
|---|---|---|
| `TokenProtocolSimulationTest` | 5 ou 7 philosophes, fourchettes quelconques ou table ronde, sans aucune pause | 0 violation, tout le monde mange, et le moins servi a au moins le quart des repas du plus servi |
| `ZooKeeperStrategyTest` | 5 philosophes ; 2 philosophes qui prennent leurs verrous dans l'ordre inverse | 0 violation, pas d'interblocage |
| `TokenVsZooKeeperComparisonTest` | les deux solutions pour N = 3, 5 et 9 | 0 violation (mesures dans `comparaison-zookeeper.md`) |
| `PairStrategyConcurrencyTest` | 3.1 : la course est démontrée ; 3.2 : test de charge N × 300 repas | 3.2 : 0 violation |

## 4.4 Tests de résilience

| Panne simulée | Classe | Résultat |
|---|---|---|
| 20 % de messages perdus, accusés perdus (donc doublons), latence | `TokenProtocolSimulationTest` | 0 violation, tout le monde mange, chaque jeton conservé |
| Philosophe arrêté proprement (`/api/stop`) | `TokenHttpIntegrationTest` | il cède ses jetons, et les autres continuent |
| **Crash définitif** d'un détenteur de jetons | `TokenCrashTest` | sûreté conservée. Sans abandon, **tout le système se bloque** en cascade. Avec abandon après `give-up-after`, ceux dont la propre fourchette n'est pas perdue continuent, plus lentement |
| Crash d'un philosophe sous ZooKeeper (expiration de session) | `ZooKeeperStrategyTest` | ses verrous sont libérés automatiquement |
| Philosophe interrompu pendant son repas sous ZooKeeper | `ZooKeeperStrategyTest` | ses verrous sont bien rendus (bug trouvé et corrigé) |
| Réponse perdue côté superviseur | `PairStrategyConcurrencyTest` | redemander renvoie « oui » (idempotence) : les fourchettes ne restent plus bloquées |
| Superviseur injoignable | `SupervisorClientTest` | traité comme un refus, et le philosophe réessaie |
| Kafka injoignable, ou message Kafka illisible | `KafkaEventReporterTest`, `KafkaEventListenerTest` | le philosophe n'est jamais bloqué, et un message illisible est écarté sans bloquer les suivants |
| Worker en panne, lent, ou qui tombe en plein calcul | `GraphMasterTest`, `GraphHttpIntegrationTest` | tâche relancée ou copiée, résultat tardif ignoré, résultat final exact |

## 4.5 Analyse : ce que les tests prouvent, et ce qu'ils ne prouvent pas

**Ce que les tests vérifient vraiment.**

- **Les comportements locaux**, de façon déterministe : chaque règle du protocole dans `TokenNodeTest`, avec
  des envois exécutés sans threads.
- **Des milliers d'entrelacements réels** : environ 1 000 à 3 000 repas par scénario de concurrence. Une
  violation de l'exclusion mutuelle y aurait de grandes chances d'apparaître, et aucune n'est apparue.
- **Les pannes que nous savons provoquer** : pertes, doublons, latence, arrêt, crash, expiration de session,
  réponse perdue.

**Ce que les tests ne prouvent pas.**

- **Tous les entrelacements possibles.** Une exécution de test n'en explore qu'une partie. Une course très
  rare peut passer inaperçue. L'absence de violation observée n'est pas une preuve d'absence de violation.
- **Les propriétés « pour toujours ».** Le « pas de famine » est vérifié sur 3 secondes, avec un seuil
  d'équité. Une propriété comme « toute demande finit par être servie » porte sur des exécutions infinies, et
  un test ne peut pas la démontrer.
- **Les hypothèses du modèle.** Les preuves de `protocole-jetons.md` supposent que les messages finissent par
  arriver. Une partition réseau permanente sort de ce cadre.

**Ce qui demanderait une vérification formelle.**

Il faudrait écrire un modèle **TLA+** du protocole à jetons et le faire vérifier par le model checker **TLC**,
pour de petits N (3 ou 4 philosophes) et des messages perdus ou dupliqués. On pourrait ainsi vérifier :

- l'invariant « au plus un détenteur par jeton », dans tous les états accessibles ;
- la vivacité « toute demande finit par être servie », sous hypothèse d'équité faible.

Ce serait la seule façon de couvrir tous les entrelacements. Les tests restent complémentaires : ils vérifient
le vrai code Java, que le modèle TLA+ ne fait que représenter.

**Limites de cette stratégie de test.**

- Les mesures de performance sont faites sur une seule machine, avec un réseau local.
- Le réseau simulé des jetons livre les messages sans coût réseau.
- Les tests de concurrence dépendent du temps. Nous avons choisi des seuils larges (au moins 5 repas, équité
  ≥ 1/4) pour qu'ils restent stables sur une machine lente, au prix d'une sensibilité moindre.
