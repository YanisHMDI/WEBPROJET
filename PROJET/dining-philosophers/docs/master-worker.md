# Partie 5 — Master–Worker : calcul distribué sur un graphe

Le code est dans `philosopher-service/.../graph/`, et l'API dans `web/GraphController.java`. Tout philosophe est
un Worker. Celui qui reçoit une requête `/api/master/...` devient le Master de ce calcul, et utilise les autres
philosophes comme Workers.

## 1. Architecture

```
                 POST /api/master/degrees  ou  /api/master/reachability
                                   │
                          ┌────────▼────────┐
                          │  Master (P0)    │  1. découpe le graphe en P partitions
                          │  GraphMaster    │  2. envoie une tâche par partition, en parallèle
                          └──┬─────┬─────┬──┘  3. relance si échec ou lenteur
                 tâches HTTP │     │     │     4. fusionne les résultats
                       ┌─────▼┐ ┌──▼───┐ ┌▼─────┐
                       │  P1  │ │  P2  │ │  P3  │  Workers (GraphWorker)
                       └──────┘ └──────┘ └──────┘
```

Le graphe est **orienté** : un arc u → v. Il est généré de façon reproductible par `Graph.random(nœuds, arcs,
graine)`, sans boucle u → u.

## 2. Degré de chaque nœud

**Partitionnement par nœud.** Le nœud v appartient à la partition v mod P, où P est le nombre de Workers.
Ce découpage est simple, sans état, et équilibré pour un graphe aléatoire.

**Données envoyées à chaque Worker** : tous les arcs qui touchent au moins un de ses nœuds (`DegreeTask`).

**Pas de double comptage.** Un arc u → v dont les deux extrémités sont dans des partitions différentes est
envoyé aux deux Workers. Mais chaque Worker ne compte que l'extrémité qui lui appartient : u pour l'un, v
pour l'autre. Chaque nœud n'ayant qu'un seul propriétaire, son degré est calculé une seule fois. Le Master
vérifie à chaque calcul que **la somme des degrés vaut 2|E|**, et compare le résultat au calcul séquentiel.

## 3. Accessibilité s → t

Il s'agit d'un parcours en largeur, niveau par niveau :

1. chaque Worker reçoit et **garde en mémoire** la liste des successeurs des nœuds de sa partition
   (`PartitionLoad`) ;
2. à chaque niveau, le Master envoie à chaque Worker les nœuds de la frontière qui lui appartiennent
   (`ExpandRequest`), et reçoit leurs successeurs ;
3. le Master fusionne, retire les nœuds déjà visités, puis s'arrête dès que **t est atteint** ou que **la
   frontière est vide** (t n'est alors pas accessible depuis s) ;
4. à la fin, le Master demande aux Workers de libérer la mémoire du calcul.

## 4. Worker en panne ou lent

| Situation | Ce que fait le Master |
|---|---|
| Le Worker répond une erreur ou est injoignable | la tâche est **relancée sur le Worker suivant** |
| Le Worker ne répond pas dans `philo.graph.task-timeout` (5 s par défaut) | une **copie** de la tâche part sur un autre Worker. **Le premier résultat arrivé gagne** |
| Le résultat tardif finit par arriver | il est **ignoré** : chaque partition n'est fusionnée qu'une fois |
| En cours de parcours, le Worker qui garde une partition tombe | la partition est **rechargée sur un autre Worker**, et l'expansion y est refaite |
| Tous les Workers sont en panne | le calcul échoue proprement, avec une réponse HTTP 409 |

**Pourquoi le résultat final reste exact.** Chaque tâche est **déterministe** : la refaire ailleurs donne
exactement le même résultat. Et le Master ne garde qu'un seul résultat par partition. Relancer ou dupliquer
une tâche ne peut donc ni perdre ni compter deux fois une partie du calcul.

**Vérifié :**
- par `GraphMasterTest`, avec des Workers simulés en panne, lents, ou qui tombent en plein parcours ;
- par `GraphHttpIntegrationTest`, avec 3 vrais services, dont un arrêté ;
- sur le vrai système, en tuant le processus P2 (`kill -9`). Le Master a réattribué sa partition
  (`reassigned: 1`) et les deux résultats sont restés exacts.

## 5. Performances

Pour reproduire les mesures :

```bash
./scripts/run-local.sh SPOILED    # 5 philosophes : P0 sera le Master, P1 à P4 les Workers
./scripts/bench-graph.sh          # chaque mesure est le meilleur de 3 essais
./scripts/stop-local.sh
```

Les mesures ont été faites sur un seul Mac (Apple Silicon), les 5 services tournant sur la même machine.

| Graphe (nœuds / arcs) | Workers | Degrés, distribué | Degrés, séquentiel | Accessibilité, distribué | Accessibilité, séquentiel |
|---|---|---|---|---|---|
| 10 000 / 50 000 | 1 | 15 ms | < 1 ms | 36 ms | 1 ms |
| 10 000 / 50 000 | 4 | 13 ms | < 1 ms | 35 ms | < 1 ms |
| 100 000 / 500 000 | 1 | 69 ms | 1 ms | 133 ms | 13 ms |
| 100 000 / 500 000 | 2 | 60 ms | 1 ms | 109 ms | 12 ms |
| 100 000 / 500 000 | 4 | 62 ms | 1 ms | 116 ms | 12 ms |
| 200 000 / 2 000 000 | 1 | 350 ms | 4 ms | 387 ms | 77 ms |
| 200 000 / 2 000 000 | 2 | 295 ms | 4 ms | 372 ms | 92 ms |
| 200 000 / 2 000 000 | 3 | 271 ms | 4 ms | 384 ms | 84 ms |
| 200 000 / 2 000 000 | 4 | 240 ms | 4 ms | 357 ms | 78 ms |

Tous les résultats ont été vérifiés exacts (`correct: true`).

## 6. Analyse : les limites du parallélisme

1. **Le calcul est trop léger pour être distribué.** Compter des degrés, c'est une seule passe sur les arcs.
   On parle de 4 ms pour 2 millions d'arcs. Envoyer ces mêmes arcs à un Worker coûte bien plus cher : il faut
   les sérialiser en JSON, les faire transiter en HTTP, puis les relire. Le temps distribué mesure donc
   surtout le **coût de communication**, d'où un écart de 60 à 90 fois avec le séquentiel.
2. **Le gain en ajoutant des Workers est faible.** Pour les degrés à 2 millions d'arcs, passer de 1 à 4
   Workers donne une accélération de 350 / 240 ≈ **1,46**, loin du ×4 idéal. C'est la **loi d'Amdahl** : une
   grande partie du travail reste séquentielle et faite par le Master (générer le graphe, le découper,
   fusionner les résultats). En plus, les arcs entre deux partitions sont envoyés deux fois, ce qui augmente
   le volume transféré avec P.
3. **L'accessibilité ne s'accélère pas.** Le parcours avance niveau par niveau, et chaque niveau demande un
   aller-retour vers chaque Worker concerné. Le nombre de niveaux (environ 7 à 10 ici) fixe une latence
   minimale, quel que soit le nombre de Workers : le parallélisme ne joue qu'à l'intérieur d'un niveau.
4. **Tout tourne sur une seule machine** : les « Workers » se partagent les mêmes cœurs, et le réseau est
   local. Sur de vraies machines, la puissance de calcul s'additionne, mais la latence réseau augmente.

**Quand le Master–Worker devient intéressant :**
- quand le calcul par donnée est coûteux (beaucoup de calcul pour peu d'octets transférés) ;
- quand le graphe ne tient pas dans la mémoire d'une seule machine ;
- quand les données sont **déjà** réparties sur les Workers, ce qui évite de les transférer à chaque calcul.

Pistes d'amélioration :
- garder les partitions chez les Workers entre deux calculs ;
- utiliser un format binaire plus compact que JSON ;
- confier plus d'une partition par Worker, pour mieux équilibrer la charge ;
- regrouper plusieurs niveaux du parcours dans un même échange avec chaque Worker.
