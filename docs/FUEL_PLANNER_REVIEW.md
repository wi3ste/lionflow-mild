# Fuel Trip Planner — consumi, tempi e mappa

## Cosa è cambiato

Il collaudo sulla Peugeot 208 Hybrid 100 ha riportato 51,74 L e 100,35 € su 714 km. La visualizzazione precedente riportava la curva prudenziale come consumo e valore attesi. A parità di previsione sottostante, dividere 51,74 L per 1,10 darebbe 47,04 L. È una decomposizione matematica del dato, non una nuova misura dell’auto. Il nuovo calcolo seleziona i campioni dopo aver analizzato l’itinerario e separa consumo atteso, margine e riserva. Mantiene il margine già salvato per il veicolo e propone il 5% per una configurazione nuova.

L’API B2C descrive viaggi con distanza in km, durata in secondi, data di partenza e arrivo, informazioni cinematiche, consumi per energia, posizioni opzionali, stato di completamento e segmenti per **propulsione**. Un link opzionale ai waypoint non equivale a un tracciato incorporato nel payload. La specifica indica L per il totale carburante e cL/100 km per la media. LionFlow conserva le conversioni euristiche `litersOf` e `per100Of` per le schermate esistenti. Per la calibrazione usa unità esplicite o una sola conversione compatibile fra totale, distanza e media, compresi i casi di km/L. Rifiuta dati incompleti, unità sconosciute, conflitti espliciti e durata o velocità incoerenti. La velocità grezza di `kinetic` da sola non basta. [Specifica Stellantis B2C](https://github.com/Stellantis/stellantis.github.io/blob/master/assets/openapi/api-b2c.yaml)

Gli estremi Stellantis plausibili migliorano il punteggio di similarità. Per classificare un viaggio come passaggio sullo stesso tragitto serve un tracciato locale già registrato, con orari, distanza ed estremi compatibili e nove punti allineati al percorso. Il planner evita richieste ulteriori ai waypoint Stellantis. Import esistenti e `CarTrips` senza carburante misurato restano esclusi dalla calibrazione. Le tracce e il backtest restano sul telefono.

## Benchmark riproducibili

`FuelPersonalizationTest` usa osservazioni sintetiche urbane, extraurbane e autostradali. Il benchmark lascia fuori un viaggio alla volta e applica entrambi i modelli agli stessi campioni eleggibili. Per il modello precedente usa la velocità media del viaggio escluso come proxy dei segmenti e il vecchio margine del 10%, come nel riepilogo precedente. MAE e bias sono in L/100 km, MAPE è la deviazione percentuale assoluta media. I risultati rappresentano il test sintetico.

| Tipo | Campioni | MAE precedente → nuovo | MAPE precedente → nuovo | Bias precedente → nuovo |
| --- | ---: | ---: | ---: | ---: |
| Breve, <25 km | 6 | 0,454 → 0,067 | 10,06% → 1,45% | +0,454 → −0,033 |
| Medio, 25–119 km | 4 | 0,218 → 0,050 | 4,58% → 1,04% | +0,218 → 0,000 |
| Lungo, ≥120 km | 4 | 0,737 → 0,050 | 13,67% → 0,93% | +0,737 → 0,000 |

Con i dati sintetici di velocità, su 714 km il modello seleziona viaggi autostradali di circa 150 km e esclude un picco anomalo. La schermata diagnostica delle build debug mostra risultati analoghi calcolati con lo storico del dispositivo. Per lo stesso tratto, le strategie possono coincidere se i candidati verificati rendono una soluzione più rapida ed economica delle altre. Il dettaglio mostra quanti impianti passano da corridoio a verifica, il range delle quotazioni, la freschezza e il limite di deviazione. **Verifica altri distributori** consente di estendere la ricerca se la coincidenza dipende dalla copertura del primo passaggio.

| Scenario simulato | Versione precedente | Revisione |
| --- | --- | --- |
| Cache fredda, 18 candidati e punteggi stabili | Fino a 22 verifiche logiche, compresi 4 rientri | Primo piano dopo 4 verifiche, chiusura dopo 8 nella fixture |
| Cache calda, stesso tragitto e dati riutilizzabili | 1 routing di base, deviazioni positive già in cache | 0 routing, 0 verifiche di rete |
| Diretto, riserva rispettata | 0 verifiche sui distributori, routing di base a ogni calcolo | 0 verifiche sui distributori, routing di base riutilizzato |
| Cambio strategia | Selezione locale | Selezione locale anche durante l’ottimizzazione |

La fixture misura il numero di verifiche, non la latenza di rete. Con 1,1 secondi di separazione, 22 avvii seriali sono distanziati complessivamente di 23,1 s, 8 avvii di 7,7 s e i primi 4 di 3,3 s. OSRM, catalogo e rete possono aggiungere tempo. Il log `FuelPlanner` misura in millisecondi il routing, la calibrazione, la disponibilità del catalogo, il corridoio, la scelta dei prezzi di riferimento, la preselezione, le verifiche, la soluzione e la pubblicazione UI. `FuelMap` misura la consegna dei dati alla sorgente, separata dal rendering nativo. I probe mancanti per dati scaduti o per nuovi vincoli possono essere richiesti esplicitamente. Il limite di frequenza resta invariato e il planner continua a verificare su strada ogni sosta mostrata.

## Collaudo sul dispositivo

1. Aprire la mappa a zoom basso e controllare numeri e cluster. Avviare un piano, passare fra strategie e chiuderlo per verificarne il ripristino.
2. Ripetere il viaggio da 714 km. Annotare fonte, campioni e chilometraggio della previsione. Confrontare il valore atteso e la quantità prudenziale con il consumo a fine viaggio.
3. Ripetere lo stesso itinerario cambiando consumo o riserva. Controllare le misure fredde e calde sul dispositivo e, se necessario, estendere la ricerca. Una stazione che non può essere verificata su strada rimane esclusa.
