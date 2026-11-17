# Partie 3.3 — Protocole distribué par jetons

Ce document décrit le protocole implémenté dans `philosopher-service/.../token/`, justifie ses propriétés
(sûreté, absence d'interblocage, absence de famine, équité) et en donne les limites. Il sert de base à la
section « protocoles » du rapport.

## 1. Modèle

- N microservices philosophes P_0 … P_{N-1}, Spring Boot, communiquant **uniquement** par HTTP. Aucun
  superviseur.
- N fourchettes F_0 … F_{N-1}. Chaque fourchette a **un jeton unique**, qui représente le droit exclusif de
  l'utiliser. Au départ, P_i détient le jeton de F_i (« P_i gère F_i »).
- Chaque philosophe a une horloge de Lamport (`LamportClock`) : `tick()` à chaque événement local ou envoi,
  `onReceive(t)` = `max(h, t) + 1` à chaque réception.
- Réseau : messages pouvant être perdus, dupliqués, retardés, réordonnés. Pas de crash définitif d'un
  philosophe (voir §7 pour ce cas).

## 2. Règle d'attribution des fourchettes

À chaque repas, la règle `ForkAssignment` choisit deux fourchettes :

| Règle | Fourchettes | Usage |
|---|---|---|
| `RANDOM` (défaut) | F_i + une fourchette tirée uniformément parmi les N-1 autres | « fourchettes quelconques, sans contrainte de voisinage » |
| `NEIGHBOURS` | F_i et F_{(i+1) mod N} | table ronde classique, pour comparer |

Garder F_i dans la paire garantit que chaque jeton est régulièrement réclamé par le philosophe qui « gère »
la fourchette. Le protocole lui-même ne dépend pas de la règle : la demande transporte ses deux fourchettes.

## 3. Messages

| Message | Contenu | Envoyé à |
|---|---|---|
| `TokenRequest` | `requester`, `meal` (n° de repas), `lamport`, `forks` | tous les autres (diffusion) |
| `TokenTransfer` | `fork`, `seq` (n° de transfert), `from`, `to`, `lamport`, `released[]` | le nouveau détenteur |
| `TokenRelease` | `requester`, `meal`, `lamport` | tous les autres (diffusion) |

Endpoints : `POST /api/token/request`, `POST /api/token/transfer`, `POST /api/token/release`,
`GET /api/token/state` (observation). Une réponse 2xx vaut accusé de réception.

## 4. Algorithme

**Priorité.** Les demandes sont totalement ordonnées par `(lamport, requester)` : la plus petite est la plus
prioritaire (ordre total de Lamport, départage par identifiant).

Chaque philosophe maintient : les jetons qu'il détient (`held`), la dernière demande connue de chaque
philosophe (`pending`), et pour chaque philosophe le dernier repas qu'il sait terminé (`released[]`).

1. **Avoir faim.** P_i choisit ses deux fourchettes, crée `R = (i, meal+1, tick(), forks)`, l'ajoute à
   `pending` et la diffuse.
2. **Réception d'une demande.** Le récepteur la mémorise (sauf doublon ou demande périmée), puis applique la
   règle de transfert.
3. **Règle de transfert** (appliquée après chaque événement). Pour chaque jeton F détenu :
   - si je mange avec F, je le garde ;
   - sinon, soit `best` la demande la plus prioritaire de `pending` qui a besoin de F ;
   - si `best` est la mienne ou n'existe pas, je garde F ; **sinon j'envoie F à `best.requester`**, même si
     j'ai faim (préemption d'un affamé moins prioritaire).
4. **Manger.** P_i mange dès qu'il détient les deux jetons de sa demande.
5. **Fin du repas.** P_i diffuse `TokenRelease`, retire sa demande, puis réapplique la règle de transfert : ses
   jetons partent vers les demandes en attente.

**Concurrence locale.** Les messages arrivent sur les threads Tomcat pendant que la boucle du philosophe
tourne. Tout l'état de `TokenNode` est protégé par un moniteur (`synchronized`). Les appels HTTP sont
préparés sous verrou mais **exécutés après l'avoir relâché**, par un fil d'envoi par destinataire
(`PeerDispatcher`). Sinon deux philosophes qui s'envoient un message au même moment attendraient chacun la
réponse de l'autre en tenant leur verrou : ce serait un interblocage distribué.

## 5. Pannes réseau : délais, pertes, doublons

| Problème | Mécanisme |
|---|---|
| Demande perdue | Le philosophe affamé **réémet** sa demande toutes les `retransmit-interval` (1 s), avec le même horodatage : il ne perd pas sa place. |
| Demande reçue deux fois | Ignorée : on ne garde qu'une demande par philosophe, celle de plus grand `meal`. |
| Demande réémise après la fin du repas | Ignorée : `meal <= released[requester]`. |
| Jeton perdu, ou accusé perdu | L'émetteur **réémet le jeton jusqu'à l'accusé** (toutes les `retry-delay`). Le jeton ne disparaît jamais. |
| Jeton reçu deux fois | Chaque transfert a un numéro `seq` strictement croissant le long du trajet du jeton. Un jeton dont le `seq` n'est pas supérieur au dernier vu est ignoré (mais acquitté). **Une réémission ne peut donc jamais créer un second jeton.** |
| Libération perdue | 3 essais, puis rattrapage : (a) une demande `meal = k` implique que `k-1` est terminé ; (b) chaque jeton transporte le vecteur `released[]` de son émetteur, qui purge les demandes périmées à chaque transfert. |

## 6. Propriétés

**Sûreté (exclusion mutuelle).** Un jeton est à un seul endroit : il est retiré de `held` *avant* d'être
envoyé, et un récepteur n'accepte un jeton que si son `seq` est nouveau. Comme `seq` augmente à chaque
transfert, deux copies acceptées impliqueraient deux transferts de même numéro depuis le même détenteur,
ce qui est impossible. Un philosophe ne mange qu'avec les deux jetons, donc **deux philosophes ne mangent
jamais avec la même fourchette**. Cette propriété ne dépend ni des délais ni des pertes.

**Pas d'interblocage.** Soit R\* la demande non terminée la plus prioritaire du système. Tout détenteur d'un
jeton dont R\* a besoin finit par connaître R\* (réémission). Il ne garde ce jeton que s'il mange avec, ce
qui dure un temps fini. Sinon il le cède, puisque R\* est la meilleure demande valide. Une entrée périmée
plus prioritaire peut détourner le jeton, mais chaque saut purge des entrées grâce au vecteur `released[]`
transporté, donc il n'y a qu'un nombre fini de détours. R\* obtient donc ses deux jetons et mange. L'attente
circulaire est cassée par la **préemption** : un affamé qui n'a pas commencé à manger ne garde jamais un
jeton contre une demande plus prioritaire.

**Pas de famine, équité.** Soit R une demande d'horodatage t. Dès qu'un philosophe a reçu R, son horloge
dépasse t, donc toutes ses demandes suivantes sont **moins prioritaires** que R. Une fois R reçue par tous,
chaque autre philosophe peut passer devant R au plus une fois (sa demande en cours). R est donc servie après
**au plus N-1 repas** prioritaires : l'attente est bornée, et l'ordre de service est l'ordre logique des
demandes.

**Coût.** Par repas : N-1 demandes + N-1 libérations + au plus 2 transferts de jeton, soit environ 2N messages
(hors réémissions). C'est le même ordre que Ricart-Agrawala. Chandy-Misra se contente des voisins, mais
suppose des fourchettes fixes.

## 7. Limites (à discuter dans le rapport)

- **Crash définitif d'un philosophe qui détient des jetons** : ces jetons sont perdus. La sûreté est
  conservée, mais les philosophes qui ont besoin de ces fourchettes attendent indéfiniment (perte de
  vivacité). Régénérer un jeton exigerait de savoir avec certitude que son détenteur est mort, ce qui est
  impossible en asynchrone pur sans consensus. C'est précisément ce que ZooKeeper apporte (sessions et nœuds
  éphémères) : point de comparaison de la partie 3.4.
- **Jeton en route vers un philosophe en panne** : l'émetteur le réémet indéfiniment, et le jeton reste
  inutilisable tant que le destinataire ne revient pas. On a choisi la sûreté plutôt que la disponibilité.
- **Un philosophe arrêté mais joignable** (`POST /api/stop`) ne bloque personne : il abandonne sa demande et
  cède ses jetons à qui les demande (vérifié par `TokenHttpIntegrationTest`).
- **Composition fixe** : la liste des pairs (`philo.token.peers`) est statique. Il n'y a pas d'ajout dynamique.
- **Diffusion en O(N)** par demande : coût croissant avec N.

## 8. Ce que les tests vérifient

| Test | Type | Propriété |
|---|---|---|
| `TokenNodeTest` (20 cas, Mockito) | unitaire | horodatage et diffusion, règle de Lamport, transfert au plus prioritaire, préemption, départage par id, conservation pendant le repas, doublons et demandes périmées ignorés, réémission jusqu'à l'accusé, purge par le vecteur `released`, messages invalides rejetés |
| `TokenStrategyTest` (Mockito) | unitaire | réémission pendant l'attente, abandon propre si interruption |
| `ForkAssignmentTest` | unitaire | règle d'attribution |
| `TokenProtocolSimulationTest` | concurrence + résilience | 5 à 7 philosophes complets pendant 3 s, avec un **oracle global** : 0 violation, tout le monde mange, équité (min ≥ max / 4), chaque jeton à exactement un endroit à la fin. Un scénario injecte 20 % de pertes, des accusés perdus (doublons) et de la latence. |
| `TokenHttpIntegrationTest` | intégration | 3 vrais services Spring Boot en HTTP sur ports aléatoires : 0 conflit observé, jetons conservés, philosophe arrêté qui cède ses jetons, endpoint d'état |

Résultats typiques (simulation, 3 s) : environ 215 repas par philosophe sans panne, attente maximale de
20 à 30 ms ; avec 20 % de pertes, environ 45 repas par philosophe, attente maximale d'environ 200 ms,
environ 100 doublons de jeton ignorés par nœud, 0 violation.

**Limites des tests.** Ils explorent beaucoup d'entrelacements, mais pas tous. L'absence de violation
observée n'est pas une preuve. Une vérification formelle (modèle TLA+ vérifié avec TLC, pour de petits N, de
l'invariant « au plus un détenteur par jeton » et de la propriété de vivacité « toute demande est servie »)
couvrirait tous les entrelacements. Les tests ne couvrent pas non plus le crash définitif d'un détenteur
(§7), volontairement hors du modèle.
