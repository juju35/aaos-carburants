# Changelog

## 1.1.1

### Changes
- The list shows sooner: it waits 2.5 s at most for the station names file (car app quality: content within 10 s); names that arrive later are added with the brands and logos.
- Outside France, the empty list says the prices cover France only.
- Google Play: store listing, graphics, privacy policy and Data safety answers in `play/`; the Release workflow uploads the bundle to the Android Automotive OS internal testing track when the Play secret is set.

## 1.1.0

### Features
- Logos of 42 French fuel networks shipped with the app (Leclerc, Intermarché, Carrefour, U, Auchan, Casino, TotalEnergies, Esso, Shell, BP, Avia, Agip, Eni, Elan, Dyneff…): shown at once, even offline. Sub-brands match their network (Carrefour Market, Intermarché Super, Super U, U Express…), from the brand or else the station name.
- Other brands: logo of the parent organisation in Wikidata (P749, else P361) when the brand has none of its own, and the brand found from its name through OpenStreetMap's name-suggestion-index when the OSM station doesn't say.

## 1.0.0

### Features
- Android Automotive app (Android 12 / API 31 and later), Car App Library templates, POI category.
- Map and list of the nearest stations (10 km, widened to 30 km when there are fewer than 3), with logo, name, distance, prices and opening status; the cheapest in green on the list and the map.
- Choose the fuels your car takes (Gazole, SP95, SP98, E10, E85, GPLc): the list shows each of their prices, sorts and highlights on the cheapest of them. Sort by price or distance. Both remembered.
- Station detail: your cheapest fuel there and its freshness, your other fuels, the others, address, hours, services, and **Y aller** to start navigation in the car's navigation app.
- Automatic refresh after 2 km or 10 minutes.
- Names, brands and logos as in ha-carburants-france: community names file, OpenStreetMap, OSM name-suggestion-index, Wikidata logos; TotalEnergies and Total Access logos shipped.
