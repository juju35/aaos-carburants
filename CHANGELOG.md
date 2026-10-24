# Changelog

## 1.1.7

### Fixes
- With location turned off in the car, the app said location wasn't allowed. It now says location is off and to turn it on in the car's settings, keeps listening, and loads the stations as soon as it is on.

## 1.1.6

### Changes
- Station list: the station name is the title again, then one followed fuel per line ("Gazole 2,250 €", then "SP98 1,990 € · 2.3 km"), the lowest price of each fuel in green and shortages in red. With one fuel, the last line is distance and opening status; with three or more, the distance comes first on the last line.

## 1.1.5

### Changes
- Station list on a narrow (portrait) screen: the first followed fuel's price is the row's title ("Gazole 2,250 €"), the other fuels go on the next line, then distance and station name. With one fuel: price, station name, then distance and opening status. Nothing is cut off any more with two fuels.

## 1.1.4

### Changes
- Station list: the prices of the followed fuels are the row's title, in large type and on two lines if needed, so they no longer get cut off with two fuels or on a portrait screen ("Gazole 2,250 € · SP98 1,899 €"). The station name follows, then distance and opening status. Titles take no colour: the cheapest stations come first.

## 1.1.3

### Changes
- Station list: the first line shows the price of each followed fuel, always with its name ("Gazole 2,250 € · SP98 1,899 €"), the lowest of each fuel in green; distance and opening status move to the second line.

## 1.1.2

First version tested on Android Automotive (emulator with the templates host): earlier versions couldn't start in a car.

### Fixes
- The templates host refused the app: the manifest named the minimum car API level `androidx.car.app.minCarAppApiLevel` instead of `androidx.car.app.minCarApiLevel`, and the `androidx.car.app.MAP_TEMPLATES` permission the map list needs was missing.
- Station list: the logo is the station's marker, on the map and next to its row (the host refuses a row with both an image and a marker, large row images and colours on image markers). The prices show again after the distance.
- Opening hours are read in French time, whatever the car's time zone.
- Price ages in French ("il y a 12 min", "à l'instant"), never in the future when a station's clock is ahead.
- Stations with no known brand: a pump badge instead of the address initials, and the address in normal case ("21 avenue Charles Tillon").

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
