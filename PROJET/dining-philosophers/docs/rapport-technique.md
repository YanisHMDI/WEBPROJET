# Rapport technique — Le dîner des philosophes en microservices

Projet de développement web, CY Tech. Le code source, les instructions d'installation et d'exécution sont dans
le dépôt (voir `README.md`). Ce rapport présente :

1. l'architecture retenue ;
2. les protocoles conçus ;
3. les choix d'implémentation ;
4. les difficultés rencontrées et les solutions apportées ;
5. les résultats des tests et des mesures.

Le détail technique de certaines parties est renvoyé vers des documents annexes, dans `docs/` :

| Document | Partie |
|---|---|
| `protocole-jetons.md` | 3.3 : conception et justification du protocole |
| `comparaison-zookeeper.md` | 3.4 : mesures et comparaison |
| `strategie-de-tests.md` | 4 : tests et leurs limites |
| `master-worker.md` | 5 : conception, mesures, analyse |
| `devops.md` | 6 : Docker, Kafka, Kubernetes, CI/CD, supervision |

---

## 1. Vue d'ensemble

| Partie | Réalisation | Mode |
|---|---|---|
| 1 | Philosophes indépendants, horloge de Lamport, moniteur et tableau de bord | `SPOILED` |
| 2 | Superviseur naïf, et ses 4 failles démontrées par des tests | `NAIVE_SUPERVISOR` |
| 3.1 | Demande des deux fourchettes sans verrou, condition de course démontrée | `PAIR_SUPERVISOR` + `UNSAFE` |
| 3.2 | Section critique verrouillée | `PAIR_SUPERVISOR` + `LOCKED` |
| 3.3 | Protocole distribué à jetons, sans superviseur | `TOKEN` |
| 3.4 | Verrou distribué ZooKeeper, et comparaison chiffrée | `ZOOKEEPER` |
| 4 | 111 tests : unitaires, intégration, concurrence, résilience, et analyse | `mvn verify` |
| 5 | Master–Worker : degrés et accessibilité, relances, mesures | `/api/master/...` |
| 6 | Docker, Kafka, Kubernetes, GitHub Actions, Prometheus, Grafana, alertes | `docker compose up` |

## 2. Architecture

### 2.1 Les services

Quatre modules Maven composent le projet. Trois d'entre eux sont des microservices Spring Boot 3.3, en Java 17.

| Module | Rôle |
|---|---|
| `common` | Code partagé : horloge de Lamport, format des événements, disposition des fourchettes |
| `monitor-service` (:8080) | Reçoit les événements (HTTP ou Kafka), reconstruit l'état de chaque philosophe, détecte les conflits. Sert le tableau de bord, et relaie les commandes du panneau de contrôle vers les philosophes |
| `supervisor-service` (:8081) | État centralisé des fourchettes, pour les parties 2, 3.1 et 3.2 |
| `philosopher-service` (:8090 et suivants) | Un processus = un philosophe. Contient la boucle de vie, les stratégies d'accès aux fourchettes, et les rôles Master et Worker |

L'infrastructure ajoutée en partie 6 comprend Kafka (événements), ZooKeeper (partie 3.4), Prometheus et
Grafana (supervision).

### 2.2 Le choix central : une boucle fixe, une stratégie par partie

La boucle du philosophe (`core/Philosopher`) est la même dans toutes les parties :

> penser → émettre HUNGRY → obtenir les fourchettes → émettre EATING → manger → émettre DONE → rendre les
> fourchettes

Seule l'étape « obtenir et rendre les fourchettes » change d'une partie à l'autre. Elle est définie par
l'interface `DiningStrategy` :

| Stratégie | Partie |
|---|---|
| `SpoiledStrategy` | 1 |
| `NaiveSupervisorStrategy` | 2 |
| `PairSupervisorStrategy` | 3.1 / 3.2 |
| `TokenStrategy` | 3.3 |
| `ZooKeeperStrategy` | 3.4 |

Ce choix a trois conséquences :
- chaque partie se lance en changeant **une seule propriété** (`philo.mode`) ;
- les mêmes outils de test et de mesure (`DiningTable`, le moniteur, les métriques) s'appliquent à toutes
  les solutions, ce qui rend la comparaison de la partie 3.4 équitable ;
- la boucle se teste sans réseau : toutes ses dépendances sont injectées, et le temps est abstrait par
  `Sleeper`.

Les mesures de la partie 6 suivent le même principe. `MeteredStrategy` entoure la stratégie choisie, sans la
modifier.

### 2.3 Le moniteur, un observateur et non un arbitre

Le moniteur ne décide de rien : il observe. Il a trois rôles :

1. **Ordonner les événements.** Il les trie selon l'ordre total de Lamport : horloge, puis identifiant.
2. **Écarter les événements hors ordre.** Un événement plus ancien que le dernier reçu du même philosophe est
   gardé dans l'historique, mais ne modifie pas l'état courant.
3. **Signaler les conflits.** Deux philosophes qui mangent en même temps avec une fourchette commune forment
   un conflit. Les fourchettes sont annoncées dans l'événement EATING, ce qui couvre aussi le cas des
   fourchettes « quelconques » de la partie 3.3.

## 3. Protocoles

### 3.1 Partie 2 : superviseur naïf

Le philosophe prend sa fourchette gauche, puis attend la droite **en gardant la gauche**. Les 4 failles sont
démontrées par des tests déterministes :

| Faille | Explication |
|---|---|
| Interblocage | tous tiennent leur gauche et attendent leur droite |
| Course « vérifier puis prendre » | deux philosophes voient la même fourchette libre |
| Restitution par un non-propriétaire | un philosophe peut rendre la fourchette d'un autre |
| Aucun contrôle global | rien ne vérifie qu'un philosophe a ses deux fourchettes pour manger |

### 3.2 Parties 3.1 et 3.2 : les deux fourchettes ou aucune

La demande porte maintenant sur les deux fourchettes d'un coup, ce qui supprime la détention partielle. Sans
verrou (3.1), deux threads Tomcat peuvent quand même lire « libre » en même temps. Une pause réglable,
`RaceWindow`, rend ce bug reproductible à chaque exécution. Avec un `ReentrantLock` autour de « vérifier puis
réserver » (3.2), l'exclusion mutuelle est garantie.

Il reste une limite : la famine. Deux voisins peuvent toujours passer devant celui qui est entre eux.

### 3.3 Partie 3.3 : protocole distribué par jetons

Le détail et les justifications sont dans `protocole-jetons.md`.

- **Jetons.** Chaque fourchette F_f a un jeton unique. Au départ, P_i détient celui de F_i.
- **Attribution** (`ForkAssignment`). À chaque repas, un philosophe prend F_i plus une fourchette tirée au
  hasard parmi les autres (règle `RANDOM`). La règle `NEIGHBOURS` garde la table ronde classique, pour
  comparer.
- **Demande.** Un affamé diffuse `TokenRequest(id, n° de repas, horloge de Lamport, fourchettes)`. Les
  demandes sont totalement ordonnées par (horloge, identifiant).
- **Règle de transfert.** Le détenteur d'un jeton le cède à la demande la plus prioritaire qui en a besoin,
  sauf s'il mange avec. Un affamé qui n'a pas commencé à manger cède **aussi** son jeton à plus prioritaire
  que lui : c'est cette **préemption** qui casse l'attente circulaire.
- **Repas.** Un philosophe mange dès qu'il détient ses deux jetons. À la fin, il diffuse `TokenRelease`.

**Propriétés.**

| Propriété | Pourquoi elle tient |
|---|---|
| Exclusion mutuelle | un jeton quitte son détenteur avant d'être envoyé, et un jeton reçu n'est accepté que si son numéro de transfert est nouveau |
| Pas d'interblocage | la demande la plus prioritaire du système n'attend que des repas en cours |
| Pas de famine | une demande est servie après au plus N-1 repas prioritaires. Après réception d'une demande, l'horloge de chacun la dépasse, donc toute nouvelle demande est moins prioritaire |
| Coût | environ 2N messages par repas |

**Pannes réseau.**

| Problème | Mécanisme |
|---|---|
| Demande perdue | réémise avec le même horodatage : le philosophe garde sa place |
| Jeton perdu | réémis jusqu'à l'accusé de réception |
| Jeton en double | ignoré grâce à son numéro de transfert |
| Libération perdue | rattrapée par le vecteur des repas terminés, transporté par chaque jeton |
| Demande bloquée trop longtemps (crash d'un détenteur) | abandonnée, puis refaite (voir la partie 4) |

### 3.4 Partie 3.4 : ZooKeeper

Chaque fourchette est un verrou Curator `InterProcessMutex` : un nœud éphémère et séquentiel dans ZooKeeper,
où les demandeurs sont servis dans l'ordre d'arrivée. Les deux verrous sont pris dans un ordre global, plus
petit indice d'abord, ce qui évite l'interblocage. À l'expiration de la session d'un philosophe mort, ZooKeeper
supprime ses nœuds, ce qui libère ses fourchettes.

### 3.5 Partie 5 : Master–Worker

Le détail est dans `master-worker.md`.

- **Partitionnement par nœud** : v va à la partition v mod P. Chaque Worker reçoit les arcs qui touchent ses
  nœuds, et ne compte que ses propres extrémités. Il n'y a donc pas de double comptage, ce que le Master
  vérifie : la somme des degrés doit valoir 2|E|.
- **Accessibilité** : parcours en largeur par niveaux. Les Workers gardent les successeurs de leurs nœuds, et
  le Master fusionne les frontières.
- **Pannes** :
  - un échec fait relancer la tâche sur le Worker suivant ;
  - une lenteur fait lancer une copie de la tâche ailleurs, et le premier résultat gagne ;
  - une partition perdue en plein parcours est rechargée sur un autre Worker.

  Les tâches sont déterministes et chaque partition n'est fusionnée qu'une fois. Le résultat reste donc exact.

## 4. Choix d'implémentation

| Choix | Raison |
|---|---|
| Spring Boot 3.3 et `RestClient` | API HTTP simple, délais configurables, Actuator pour la santé et les métriques |
| État du protocole sous `synchronized`, **envois HTTP hors verrou** (`Dispatcher`) | un appel réseau sous verrou provoquerait un interblocage entre deux philosophes qui s'écrivent en même temps |
| Un fil d'envoi par destinataire (`PeerDispatcher`) | ordre préservé vers chaque pair, et un pair en panne ne retarde que sa propre file |
| Injection de pannes intégrée (`ChaosTransport`, `RaceWindow`, `worker-delay`) | rendre les bugs et les pannes reproductibles, en test comme en démonstration |
| Oracle global de test (`DiningTable`) | vérifier l'exclusion mutuelle de l'extérieur, indépendamment du protocole testé |
| ZooKeeper embarqué (`curator-test`), Kafka embarqué (`spring-kafka-test`) | tests d'intégration réels sans Docker |
| Kafka seulement pour les événements | le sujet impose HTTP entre philosophes ; Kafka découple l'observation, et le moniteur rattrape les événements émis pendant son absence |
| `StatefulSet` Kubernetes | identifiant stable par pod (`PHILO_ID`) et DNS stable, indispensables au protocole à jetons |
| Panneau de contrôle relayé par le moniteur | le navigateur ne parle qu'à une adresse, et on peut arrêter un philosophe en direct pour une démonstration |

## 5. Difficultés rencontrées et solutions

| # | Difficulté | Solution |
|---|---|---|
| 1 | Le moniteur signalait de **faux conflits**, même en mode verrouillé. Le philosophe rendait ses fourchettes avant d'envoyer DONE, et son voisin pouvait annoncer EATING trop tôt | DONE est envoyé **avant** la restitution, même quand le repas est interrompu. Le moniteur ne peut alors plus signaler de faux conflit |
| 2 | Une condition de course est **rare**, donc difficile à démontrer | une pause réglable entre « vérifier » et « réserver » (`RaceWindow`) rend la course quasi systématique |
| 3 | **Interblocage distribué** possible si un philosophe appelle un pair en tenant son verrou | préparer les messages sous verrou, puis les envoyer après l'avoir relâché |
| 4 | Un accusé de réception perdu fait **réémettre** un jeton : risque de deux jetons | un numéro de transfert strictement croissant, et un jeton déjà vu est ignoré mais acquitté |
| 5 | Les libérations perdues laissaient des **demandes périmées** dans les files | « demander le repas k implique que le repas k-1 est fini », et chaque jeton transporte le vecteur des repas terminés |
| 6 | **Crash d'un détenteur de jetons** : la sûreté tient, mais tout le système se bloque en cascade, car un affamé bloqué garde ses jetons | abandon de la demande après `give-up-after` : l'affamé rend ses jetons et redemande. Seuls ceux dont la propre fourchette est perdue restent bloqués |
| 7 | **Réponse HTTP perdue** côté superviseur : la fourchette restait attribuée à un philosophe qui croyait ne pas l'avoir | le superviseur verrouillé répond « oui » à une demande répétée (idempotence) |
| 8 | ZooKeeper : un philosophe **interrompu** pendant son repas ne rendait pas ses verrous, car Curator refuse de communiquer depuis un thread interrompu | effacer le drapeau d'interruption le temps de la restitution, puis le rétablir |
| 9 | Tests aléatoires **non déterministes** : un graphe aléatoire pouvait terminer le parcours avant la panne simulée | un graphe construit à la main (une chaîne) pour les scénarios de panne |
| 10 | Tests HTTP sur **ports aléatoires** : le port fixé dans `application.yml` l'emportait | réglages passés en arguments de ligne de commande, qui sont prioritaires, et annuaire des pairs modifiable à chaud (`PeerDirectory`) |
| 11 | Mesures faussées par la **mise en veille** du portable : des tests de 3 s semblaient durer 10 minutes | `caffeinate -i mvn verify`, et la cause a été confirmée dans le journal d'alimentation du système |
| 12 | Le calcul distribué se révèle **plus lent** que le séquentiel | ce n'est pas un bug, c'est un résultat : transférer coûte plus que calculer. Il est analysé avec la loi d'Amdahl (voir 6.3) |

## 6. Résultats

### 6.1 Tests

`mvn verify` lance 111 tests, et tous passent. Ils sont rangés selon les 5 points de la partie 4 dans
`strategie-de-tests.md`. Les tests d'intégration utilisent de vrais services Spring Boot en HTTP, un vrai
serveur ZooKeeper et un vrai broker Kafka.

Ce qui a été vérifié :

| Cas | Résultat |
|---|---|
| Jetons, de 5 à 7 philosophes, sans pause | 0 violation, tout le monde mange, équité min/max ≥ 0,25 (mesuré : environ 0,93 à 0,99) |
| Jetons avec 20 % de pertes, doublons et latence | 0 violation, environ 100 doublons ignorés par nœud, chaque jeton à un seul endroit à la fin |
| Vrai système de 5 services avec 20 % de pertes | 0 conflit |
| ZooKeeper | 0 violation, pas d'interblocage avec des ordres opposés, verrous libérés après un crash |
| Master–Worker | résultats exacts pour 1 à 4 Workers, et après l'arrêt brutal d'un Worker (`kill -9`) sur le vrai système |

### 6.2 Jetons contre ZooKeeper

Même charge, 3 s chacune, avec N = 5 philosophes :

| | Jetons | ZooKeeper |
|---|---|---|
| Repas | 866 | 172 |
| Attente moyenne | 8,6 ms | 62,8 ms |
| Équité (min/max des repas) | 0,95 | 0,70 |
| Messages par repas | 9,7, soit environ 2N | 6 à 8, constant |
| Crash d'un philosophe | jetons perdus | verrous libérés automatiquement |

Les jetons sont plus rapides et sans infrastructure, mais difficiles à prouver et fragiles en cas de crash.
ZooKeeper est plus simple et robuste, au prix d'un service central et d'un aller-retour par opération. Les
mesures pour N = 3 et N = 9 sont dans `comparaison-zookeeper.md`.

### 6.3 Master–Worker

Avec 2 millions d'arcs, le calcul des degrés prend :

| Workers | Temps distribué |
|---|---|
| 1 | 350 ms |
| 4 | 240 ms |
| séquentiel | 4 ms |

L'accélération de 1 à 4 Workers est de 1,46, quand l'idéal serait 4. Le coût de communication et la part
séquentielle du Master dominent : c'est la loi d'Amdahl. Le parcours en largeur est en plus limité par son
nombre de niveaux.

## 7. Limites et perspectives

- **Crash d'un détenteur de jeton.** Le protocole à jetons ne sait pas régénérer un jeton perdu : il faudrait
  détecter la mort avec certitude, ce qui demande un consensus. C'est exactement l'apport de ZooKeeper.
- **Vérification formelle.** Elle n'est pas faite. Un modèle TLA+ vérifié par TLC couvrirait tous les
  entrelacements, ce que les tests ne peuvent pas garantir.
- **Superviseur unique.** Il n'est pas répliqué : c'est un point unique de défaillance, par construction du
  sujet, et sa limite.
- **Mesures.** Elles sont faites sur une seule machine. Sur un vrai réseau, la latence augmenterait, mais la
  puissance de calcul aussi.
- **Déploiement.** Le `docker compose up` complet, le déploiement Kubernetes et la première exécution de la CI
  n'ont pas pu être lancés sur la machine de développement, qui n'avait ni démon Docker ni cluster. Les
  fichiers sont validés : `docker compose config`, `kubectl kustomize`, et dans la CI `kubeconform` et
  `promtool`.

## 8. Utilisation de l'IA

> À rédiger par l'équipe : quels outils ont été utilisés, pour quelles tâches, comment les propositions ont
> été comprises, vérifiées et testées, et ce que l'équipe a conçu et validé elle-même (sujet, §8.2).
