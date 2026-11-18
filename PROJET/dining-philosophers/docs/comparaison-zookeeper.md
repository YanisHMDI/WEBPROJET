# Partie 3.4 — Jetons ou ZooKeeper ?

Ce document compare notre protocole à jetons (partie 3.3) à une seconde solution, qui délègue l'exclusion
mutuelle à **Apache ZooKeeper** via la recette « verrou distribué » d'**Apache Curator**.

## 1. La solution ZooKeeper (`zookeeper/ZooKeeperStrategy.java`)

- Chaque fourchette F_i est un verrou `InterProcessMutex` sous `/philo/forks/F<i>`.
- **Prendre** un verrou, c'est créer un nœud **éphémère et séquentiel** sous ce chemin. Le plus petit numéro
  détient le verrou, et chaque autre demandeur surveille seulement son prédécesseur. Les demandeurs sont donc
  servis dans l'ordre d'arrivée.
- **Pas d'interblocage** : les deux verrous sont toujours pris dans le même ordre global, plus petit indice
  d'abord. Cela interdit l'attente circulaire. Le test `oppositeOrdersDoNotDeadlockThanksToTheGlobalOrder`
  fait tourner le cas d'école : P0 veut (F3, F1) pendant que P1 veut (F1, F3).
- **Panne** : les nœuds sont éphémères. Si un philosophe meurt, ZooKeeper supprime ses nœuds quand sa session
  expire, et ses fourchettes sont libérées sans intervention (test
  `forksOfACrashedPhilosopherAreReleasedByZooKeeper`).
- **Arrêt pendant un repas** : un thread interrompu ne peut plus parler à ZooKeeper. `releaseForks` efface donc
  le drapeau d'interruption le temps de rendre les verrous. Sans cela, les fourchettes restaient prises jusqu'à
  l'expiration de la session (bug trouvé par les tests, et corrigé).

Pour lancer cette solution :

```bash
docker run -d -p 2181:2181 zookeeper:3.9
./scripts/run-local.sh ZOOKEEPER
# ou tout en Docker
MODE=ZOOKEEPER docker compose up --build
```

## 2. Mesures

Ces chiffres viennent de `TokenVsZooKeeperComparisonTest`. Les deux solutions tournent dans les mêmes
conditions : même nombre de philosophes N, réflexion de 0 à 5 ms, repas de 1 à 5 ms, fourchettes quelconques,
3 secondes chacune. L'oracle de `DiningTable` a compté **0 violation** pour les deux.

| N | Solution | Repas en 3 s | Attente moyenne | Attente max | Repas min / max par philosophe | Messages par repas |
|---|---|---|---|---|---|---|
| 3 | Jetons | 754 | 4,5 ms | 12 ms | 250 / 253 | 5,7 |
| 3 | ZooKeeper | 150 | 41,2 ms | 102 ms | 43 / 56 | ≈ 6 à 8 (estimé) |
| 5 | Jetons | 866 | 8,6 ms | 102 ms | 169 / 178 | 9,7 |
| 5 | ZooKeeper | 172 | 62,8 ms | 207 ms | 28 / 40 | ≈ 6 à 8 (estimé) |
| 9 | Jetons | 1 404 | 9,9 ms | 152 ms | 150 / 161 | 17,8 |
| 9 | ZooKeeper | 268 | 73,7 ms | 299 ms | 19 / 38 | ≈ 6 à 8 (estimé) |

**Comment lire ces chiffres honnêtement.**

- **Les conditions ne sont pas strictement égales.** Les jetons passent par un réseau en mémoire, sans coût
  réseau. ZooKeeper est un vrai serveur joint en TCP local, qui écrit chaque modification sur disque avant de
  répondre. Une partie de l'écart de débit vient de là, et non de l'algorithme lui-même.
- **Les messages par repas des jetons sont mesurés** à partir des compteurs de `TokenNode`. On retrouve la
  formule attendue d'environ 2N : N-1 demandes, N-1 libérations et jusqu'à 2 transferts de jeton.
- **Les messages par repas de ZooKeeper sont estimés** d'après la recette de verrou. Par verrou : une création
  de nœud, une lecture des voisins, éventuellement une surveillance, puis une suppression. Pour deux verrous,
  cela fait 6 à 8 requêtes, **quel que soit N**.

## 3. Comparaison

| Critère | Protocole à jetons (3.3) | ZooKeeper (3.4) |
|---|---|---|
| Exclusion mutuelle | garantie par l'unicité des jetons et les numéros de transfert | garantie par ZooKeeper : un seul plus petit numéro |
| Interblocage | évité par la préemption : un affamé cède à plus prioritaire | évité par l'ordre global de prise des verrous |
| Famine et équité | attente bornée à N-1 repas, ordre de Lamport. Mesuré : min/max ≥ 0,93 | ordre d'arrivée sur chaque verrou. Mesuré : min/max entre 0,5 et 0,77, car prendre les verrous l'un après l'autre allonge l'attente de ceux qui visent une fourchette très demandée |
| Messages par repas | environ 2N : croît avec le nombre de philosophes | environ 6 à 8 : constant |
| Latence | faible : un échange direct entre pairs | plus élevée : chaque opération passe par le serveur, qui écrit sur disque |
| **Crash d'un philosophe** | **ses jetons sont perdus.** Sans mécanisme d'abandon, tout le système finit bloqué. Avec l'abandon, seuls ceux dont la propre fourchette est perdue restent bloqués (`TokenCrashTest`) | **ses verrous sont libérés automatiquement** à l'expiration de sa session (`forksOfACrashedPhilosopherAreReleasedByZooKeeper`) |
| Point unique de défaillance | aucun : tout est entre pairs | le serveur ZooKeeper. En production, on le réplique sur 3 ou 5 nœuds, mais c'est une infrastructure de plus à faire tourner |
| Session perdue (réseau coupé avec ZooKeeper) | sans objet | le philosophe peut croire tenir un verrou que ZooKeeper a déjà rendu. Curator signale l'état `LOST`, et nous l'écrivons dans les logs |
| Complexité du code | élevée : protocole, réémissions, doublons, preuves (environ 400 lignes) | faible : environ 100 lignes, la difficulté est déléguée à ZooKeeper |

## 4. Conclusion

- **Le protocole à jetons** est plus rapide, plus équitable et sans infrastructure. Mais il est difficile à
  écrire et à prouver, et il ne sait pas récupérer un jeton perdu : il faudrait savoir avec certitude qu'un
  philosophe est mort, ce qui demande un consensus.
- **ZooKeeper** apporte justement ce consensus « tout fait ». Les sessions et les nœuds éphémères règlent la
  panne d'un philosophe, en échange d'un service central, d'un aller-retour par opération et d'un débit plus
  faible.

C'est le compromis classique entre faire soi-même, pour la performance et l'absence d'infrastructure, et
s'appuyer sur un service de coordination éprouvé, pour la robustesse et la simplicité.
