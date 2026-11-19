# Partie 6 — Approche DevOps

Cette partie couvre cinq points du sujet :

1. la **conteneurisation** avec Docker ;
2. la **communication asynchrone** avec Kafka ;
3. l'**orchestration** avec Kubernetes ;
4. l'**automatisation** avec GitHub Actions ;
5. la **supervision, la journalisation et la gestion des pannes**.

## 1. Conteneurisation (Docker)

Un seul `Dockerfile` sert aux trois services, avec un argument `MODULE`. La construction se fait en deux
étapes :

- **compilation** avec Maven. Les dépendances sont mises en cache tant que les `pom.xml` ne changent pas ;
- **exécution** sur un JRE seul. L'image est plus légère, le conteneur tourne sans droits administrateur, et
  la JVM règle sa mémoire sur la limite du conteneur (`MaxRAMPercentage=75`).

Une seule commande lance tout le système :

```bash
MODE=TOKEN docker compose up --build
```

| Service | Adresse | Rôle |
|---|---|---|
| monitor | <http://localhost:8080> | tableau de bord et panneau de contrôle |
| supervisor | :8081 | parties 2, 3.1 et 3.2 |
| philo-0 à philo-4 | :8090 à :8094 | les 5 philosophes |
| kafka | :9092 | bus d'événements |
| zookeeper | :2181 | partie 3.4 |
| prometheus | <http://localhost:9090> | métriques et alertes |
| grafana | <http://localhost:3000> | tableau de bord « Dîner des philosophes », sans mot de passe |

## 2. Communication asynchrone (Kafka)

Les philosophes publient leurs événements HUNGRY, EATING et DONE dans le topic `philo.events`. Le moniteur
lit ce topic avec le groupe de consommateurs `monitor`.

| | HTTP (`PHILO_EVENTS=HTTP`) | Kafka (`PHILO_EVENTS=KAFKA`, défaut en Docker) |
|---|---|---|
| Couplage | le philosophe attend la réponse du moniteur | le philosophe dépose l'événement et continue |
| Moniteur arrêté | les événements émis pendant l'arrêt sont perdus | ils attendent dans Kafka, et le moniteur les rattrape à son redémarrage |
| Ordre | chaque requête peut arriver dans le désordre | **la clé du message est l'identifiant du philosophe** : ses événements vont dans la même partition, donc restent dans l'ordre |
| Message illisible | erreur 400 | écarté par `ErrorHandlingDeserializer`, sans bloquer les messages suivants (testé) |

Kafka sert aux **événements d'observation**. Les échanges du protocole à jetons et du Master–Worker restent
en HTTP, comme le demande le sujet (« les philosophes communiquent par échange de messages HTTP »). Ces
échanges attendent une réponse immédiate : un accusé de réception ou un résultat de calcul.

Ce qui est testé :
- `KafkaEventListenerTest` : un vrai broker Kafka démarré dans le test, avec un message illisible suivi d'un
  message valide ;
- `KafkaEventReporterTest` : la clé des messages, et le fait qu'une panne de Kafka n'arrête jamais le
  philosophe.

## 3. Orchestration (Kubernetes)

Le déploiement se fait depuis le dossier `k8s/`, avec Kustomize :

```bash
kubectl apply -k k8s
kubectl -n philosophes port-forward svc/monitor 8080:8080        # tableau de bord
kubectl -n philosophes port-forward svc/prometheus 9090:9090
```

- **Identité stable des philosophes.** Les philosophes sont un `StatefulSet` de 5 pods, nommés `philosopher-0`
  à `philosopher-4`. Chaque pod reçoit son identifiant `PHILO_ID` depuis l'étiquette
  `apps.kubernetes.io/pod-index`, posée par Kubernetes 1.28 et plus. Un service **headless** donne à chacun
  une adresse DNS stable, `philosopher-<i>.philosophers`. C'est indispensable au protocole à jetons : chaque
  philosophe doit savoir joindre P_i.
- **Configuration.** Elle est regroupée dans un `ConfigMap`, `philo-config`. Pour changer de partie :

  ```bash
  kubectl -n philosophes edit configmap philo-config        # PHILO_MODE: ZOOKEEPER, par exemple
  kubectl -n philosophes rollout restart statefulset philosopher
  ```

- **Gestion des pannes.** Chaque service a des **sondes de santé**, fournies par Spring Boot Actuator :
  - `livenessProbe` (`/actuator/health/liveness`) : si la JVM ne répond plus, Kubernetes redémarre le
    conteneur ;
  - `readinessProbe` (`/actuator/health/readiness`) : un pod ne reçoit du trafic que lorsqu'il est prêt.

  Des limites de CPU et de mémoire empêchent aussi un service de prendre toutes les ressources du nœud.
- **Supervision.** Prometheus découvre tout seul les pods annotés `prometheus.io/scrape: "true"`.

## 4. Automatisation (GitHub Actions)

Le fichier `.github/workflows/ci.yml`, à la racine du dépôt, se déclenche à chaque push ou pull request qui
touche le projet. Il enchaîne trois tâches :

| Tâche | Ce qu'elle fait |
|---|---|
| Build et tests | `mvn verify` en Java 17. Les rapports de test sont gardés comme artefacts pendant 14 jours |
| Validation de la configuration | les manifests Kubernetes contre les schémas officiels (`kubeconform`), le `docker-compose.yml`, et la configuration Prometheus et ses alertes (`promtool`) |
| Images Docker | construit les 3 images, puis, sur `main`, les publie sur `ghcr.io` avec les étiquettes `latest` et le numéro du commit |

Les images publiées sont celles que référencent les manifests Kubernetes. Un commit sur `main` qui passe tous
les tests est donc directement déployable.

## 5. Supervision, journalisation et pannes

**Métriques.** Chaque service expose `/actuator/prometheus`, grâce à Micrometer.

| Métrique | Service | Utilité |
|---|---|---|
| `philo_meals_total{philosopher, mode}` | philosophe | débit et équité |
| `philo_fork_wait_seconds` (histogramme) | philosophe | temps d'attente des fourchettes, 95e centile |
| `monitor_conflicts` | moniteur | violations de l'exclusion mutuelle observées |
| `monitor_meals{philosopher}`, `monitor_events`, `monitor_events_stale` | moniteur | vue globale, événements hors ordre |
| `supervisor_violations`, `supervisor_forks_taken` | superviseur | parties 2, 3.1 et 3.2 |
| `jvm_*`, `http_server_requests_*` | tous | mémoire, threads, temps de réponse HTTP |

**Alertes**, définies dans `ops/prometheus/alerts.yml` et visibles dans Prometheus, onglet *Alerts* :

| Alerte | Condition |
|---|---|
| `ServiceInjoignable` | un service ne répond plus depuis 15 s |
| `ExclusionMutuelleViolee` | `monitor_conflicts > 0` |
| `SuperviseurViolation` | `supervisor_violations > 0` |
| `Famine` | un philosophe n'a pas mangé depuis 2 minutes (famine ou interblocage) |
| `AttenteLongue` | 95e centile de l'attente au-dessus de 5 s |

**Grafana.** Le tableau de bord « Dîner des philosophes » est provisionné automatiquement. Il montre les
conflits, les services joignables, le débit, l'attente p95, les repas par philosophe, les événements et la
mémoire.

**Journalisation.** Les services écrivent sur la sortie standard, où Docker et Kubernetes collectent les
journaux. Docker Compose fait tourner ces journaux : 10 Mo au plus, 3 fichiers. On les consulte avec
`docker compose logs -f philo-2` ou `kubectl -n philosophes logs philosopher-2`. Chaque ligne indique le
service, et les journaux du protocole indiquent le philosophe (P0, P1…).

**Gestion des pannes, récapitulatif.**

| Niveau | Mécanisme |
|---|---|
| Conteneur | `restart: unless-stopped` en Docker, sondes de santé en Kubernetes |
| Application | délais HTTP partout ; réémissions et doublons gérés dans le protocole à jetons ; abandon d'une demande bloquée ; superviseur idempotent ; sessions ZooKeeper ; relance et exécution spéculative dans le Master–Worker |
| Observation | Kafka garde les événements si le moniteur est arrêté |
| Détection | alertes Prometheus |

## Ce qui a été vérifié, et ce qui reste à faire chez vous

**Vérifié sur la machine de développement :**
- les 3 services, les métriques Prometheus et les sondes de santé, en lancement local ;
- Kafka, avec un broker démarré dans les tests ;
- `docker compose config` ;
- le rendu des manifests par `kubectl kustomize`.

**Non exécuté ici**, faute de démon Docker et de cluster Kubernetes sur la machine :
- le `docker compose up` complet ;
- le déploiement sur un vrai cluster ;
- la première exécution de la CI sur GitHub.

Pour tester le déploiement Kubernetes en local, installez **kind** ou **minikube**, chargez les images
construites localement, puis lancez `kubectl apply -k k8s`.
