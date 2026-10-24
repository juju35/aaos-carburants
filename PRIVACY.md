# Politique de confidentialité — Carburants

*Dernière mise à jour : 24 octobre 2026*

Carburants (`me.jstephan.carburants`) est une application Android Automotive OS publiée par juju35.
Elle ne demande aucun compte, n'affiche aucune publicité et ne contient aucun outil de mesure d'audience,
de suivi ou de rapport de plantage.

## Position du véhicule

L'application utilise la position du véhicule (précise ou approximative, selon l'autorisation accordée)
uniquement pour trouver les stations-service proches, pendant qu'elle est ouverte.

- Les coordonnées sont envoyées, chiffrées (HTTPS), dans la requête de recherche adressée à l'API publique
  de data.economie.gouv.fr, qui renvoie les stations autour de ce point. Aucun identifiant n'accompagne la requête.
- Les coordonnées ne sont ni enregistrées ni transmises par l'application à d'autres services.
- La position n'est pas utilisée quand l'application est fermée.

## Autres services contactés

Pour afficher le nom, l'enseigne et le logo des stations, l'application télécharge aussi :

| Service | Ce qui est envoyé |
| --- | --- |
| raw.githubusercontent.com (GitHub) | Rien : téléchargement de fichiers publics (noms des stations, liste des enseignes), au plus une fois par semaine |
| geo.api.gouv.fr (État français) | Le texte saisi dans la recherche de ville, pour proposer des communes |
| overpass-api.de (OpenStreetMap) | Les coordonnées des stations affichées (pas celles du véhicule) |
| wikidata.org, commons.wikimedia.org | Les identifiants Wikidata des enseignes |

Comme tout serveur web, ces services peuvent journaliser l'adresse IP de la connexion, selon leurs propres
politiques de confidentialité.

## Données enregistrées sur l'appareil

- Vos choix : carburants affichés et ordre de tri. Ils peuvent être inclus dans la sauvegarde Android
  du profil, pour être retrouvés sur un nouveau véhicule.
- Des caches (noms des stations, enseignes, logos), jamais sauvegardés.

Désinstaller l'application, ou effacer ses données dans les paramètres, supprime tout.

## Enfants

L'application s'adresse aux conducteurs et ne vise pas les enfants.

## Contact

Questions ou demandes : https://github.com/juju35/aaos-carburants/issues

---

# Privacy policy — Carburants (English)

Carburants has no account, no ads, no analytics, tracking or crash reporting. The vehicle's location is used
only while the app is open, to find nearby fuel stations: the coordinates are sent over HTTPS, with no
identifier, in the search request to the public data.economie.gouv.fr API, and are neither stored nor sent
anywhere else. To show station names, brands and logos, the app also downloads public files from GitHub, sends
the coordinates of the stations shown (not the vehicle's) to overpass-api.de, the text typed in the town search to
geo.api.gouv.fr, and brand identifiers to Wikidata
and Wikimedia Commons. The app keeps your fuel and sort choices (which Android may back up) and caches on the
device; uninstalling or clearing the app's data removes them. Contact:
https://github.com/juju35/aaos-carburants/issues
