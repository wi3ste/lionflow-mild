# Fuel Trip Planner – ricerca incrementale dei distributori

## Riscontro sul dispositivo

Il collaudo su Peugeot 208 Hybrid 100 ha riportato un itinerario di 714 km, 917 distributori nel corridoio, 8 candidati esaminati e 2 verificati. Le due quotazioni spaziavano da 1,940 a 1,949 €/L. Con un limite di 8 km, tutte le strategie proponevano Lucignano Est, Agip Eni.

L'estensione precedente ripassava dai candidati già esaminati. Il solutore poteva fermarsi dopo una variazione inferiore a 0,20 prima di arrivare a nuove stazioni. La lista estesa terminava a 36 proiezioni, e il controllo stradale ripartiva da una lista vuota affidandosi alla cache per ritrovare le verifiche precedenti. Timeout, risposte temporanee e assenza di un percorso condividevano lo stesso risultato. La preselezione escludeva inoltre i candidati con una distanza in linea d'aria di andata e ritorno superiore al limite, anche quando una deviazione in avanti poteva aggiungere meno strada. Il rientro a valle era disponibile solo per poche stazioni e a breve distanza.

Le funzioni obiettivo di Economica, Bilanciata e Rapida erano già distinte. Due sole stazioni con quotazioni quasi identiche offrivano poco spazio per ottenere un compromesso diverso. La coincidenza sul viaggio reale resta da verificare nuovamente sullo stesso itinerario, con le stesse impostazioni e con dati di prezzo e routing disponibili al momento del test.

## Comportamento aggiornato

- La ricerca automatica inizia con una stazione vicina e raggiungibile in via preliminare. Pubblica il primo piano fattibile verificato, poi prova un altro gruppo. Il budget automatico è di 16 richieste di accesso stradale.
- Ogni pressione su **Verifica altri distributori** prende un gruppo nuovo dal corridoio completo, con un massimo di 8 candidati e 14 richieste al router, inclusi i fallback. La ricerca può avanzare anche dopo un gruppo senza miglioramenti. Le verifiche positive, le esclusioni definitive e gli errori ritentabili persistono durante la pianificazione. I risultati e le soste confermate si conservano. Ciascuna strategia accetta un nuovo piano solo se migliora il suo punteggio.
- La preselezione stima il beneficio di sostituire una sosta vicina lungo l'itinerario. Considera prezzo, quantità plausibile, margine di autonomia, freschezza, distanza laterale e posizione. Una parte del gruppo è riservata alle alternative economiche e una ai candidati vicini o ai tentativi recuperabili.
- Il router prova un accesso direzionale con uscita precedente e rientro successivo, attraverso le coordinate del distributore. Prova fino a due finestre in avanti e, quando necessario, il rientro al punto di proiezione. Controlla la direzione di aggancio nei punti sulla strada principale e la distanza di aggancio alla stazione. Conserva i punti intermedi e i limiti dei tratti in traghetto. La deviazione è la distanza del tratto alternativo meno quella del tratto sostituito. La riserva è verificata prima e dopo il rifornimento usando entrambi i rami della deviazione.
- Le richieste Fuel Planner restano distanziate di almeno 1.100 ms. Un HTTP 429 ferma la serie e rispetta il maggiore tra 60 secondi e il valore di `Retry-After`. I timeout e gli altri errori temporanei possono essere ritentati al gruppo successivo dopo 20 secondi. La cache in memoria riusa per 24 ore le risposte stradali positive e le assenze di percorso confermate.
- La diagnostica debug mostra minimo, mediana e massimo delle quotazioni, conteggi delle verifiche e degli scarti, richieste HTTP e risposte riusate. La tabella dei candidati esaminati mostra nome, prezzo, posizione sul viaggio, distanza geografica, deviazione stradale ed esito. Un messaggio accompagna il progresso del gruppo e avvisa quando il piano resta uguale.

## Benchmark riproducibile

`FuelIncrementalSearchTest` usa la proiezione sul corridoio, la preselezione progressiva, il verificatore stradale e il solutore di produzione. Il trasporto OSRM è simulato, con esiti e tempi deterministici, su 714 km e 917 impianti sintetici.

| Risultato | Misura |
|---|---:|
| Richieste alle stazioni fino al primo piano fattibile | 1 |
| Richieste alle stazioni nella ricerca automatica completa | 13 |
| Prima estensione | Stazioni nuove, piano invariato |
| Estensione che trova l'alternativa economica | 4ª |
| Richieste cumulative fino al miglioramento | 69 |
| Spesa prevista alla pompa, strategia Economica | 48,01 € |
| Spesa prevista alla pompa, strategia Rapida | 56,48 € |
| Risparmio di Economica rispetto a Rapida | 8,47 € |
| Tempo aggiunto della soluzione Economica rispetto a Rapida | 12,01 min |

La fixture include undici impianti con quotazioni più basse e prive di accesso nel grafo simulato. Il test insiste per dodici pressioni per verificare il superamento del vecchio tetto, arrivando a 62 candidati e lasciandone più di 800 da verificare. In quei dodici gruppi conta 181 richieste simulate e 10 risposte da cache. Il tempo di un router pubblico, la disponibilità delle strade e i prezzi reali richiedono un collaudo distinto.

I cinque nuovi test coprono anche il recupero dopo un timeout a piano già fattibile, il riuso della geometria, l'intervallo di attesa dopo un 429, la classificazione delle risposte OSRM e un distributore raggiungibile soltanto con rientro in avanti. Per il limite di 8 km il test accetta 7,50 km aggiunti e rifiuta 8,01 km.

## Verifica e installazione

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :wear:assembleDebug
gradle :app:connectedDebugAndroidTest
```

Il workflow **Compile check** pubblica un archivio ZIP contenente `app-debug.apk`. Accedi a GitHub, scarica l'artifact e apri l'APK estratto sul telefono. La build usa l'identificativo `no.lionflow.app.debug`. Se Android segnala un certificato incompatibile, esporta un backup dei dati prima di sostituire l'installazione. Il workflow supporta una chiave debug stabile in un segreto GitHub Actions. Il certificato generato su un runner pulito può cambiare tra esecuzioni.

Per il collaudo su dispositivo:

1. Ripeti il viaggio di 714 km con la stessa partenza, destinazione, riserva e il limite di 8 km. Annota prezzo e nome della prima sosta.
2. Apri **Mostra diagnostica**. Premi **Verifica altri distributori** e controlla il numero di nuovi candidati, le verifiche riuscite e il messaggio finale. Ripeti e confronta i risultati con la tabella.
3. Confronta Economica, Bilanciata e Rapida. Verifica che la selezione aggiorni il piano preparato. Per una nuova sosta fuori autostrada, controlla deviazione, arrivo e ripartenza in un navigatore.
4. Conferma una sosta e ripeti la ricerca. Poi chiudi il piano e verifica il ritorno del catalogo sulla mappa. Prova anche l'apertura del Trip Planner elettrico con un veicolo compatibile.

La scelta si basa sulle stazioni verificate e sulle finestre stradali provate. La precisione delle coordinate, la copertura dei dati stradali e la disponibilità del servizio pubblico limitano la ricerca.
