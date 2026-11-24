# LionFlow – Revisione Fuel Management

## Funzioni e accesso

La mappa dei veicoli termici e ibridi offre quattro azioni. **Pianifica un viaggio carburante** apre il planner esistente. **Trova distributori vicini** apre un elenco ordinabile con origine telefono, auto, indirizzo o centro della mappa. **Storico rifornimenti** apre il registro dell’auto selezionata, le statistiche e l’esportazione CSV. **Viaggi carburante salvati** apre i piani disponibili e il viaggio attivo. La navigazione globale e il Charging Log seguono i flussi esistenti.

I dettagli e i filtri aggiuntivi si aprono su richiesta. La mappa libera conserva il catalogo nazionale e il selettore delle colonnine. Il piano mostra le soste selezionate sul percorso e le alternative quando si valuta una sostituzione. Il catalogo torna visibile quando si chiude il piano. Le nuove etichette sono disponibili in italiano e in inglese. Le altre lingue usano il fallback dell’app per le nuove stringhe.

### Ricerca e prezzi

L’indice geografico del catalogo MIMIT restringe la ricerca a 2, 5, 10, 20 o 50 km. L’elenco supporta carburante, servizio, marchio, tipo di rete, prezzo massimo, freschezza e preferiti. La convenienza confronta la quantità desiderata con il prezzo mediano fresco nella zona e sottrae il costo stimato del viaggio di andata e ritorno. Il consumo per il confronto è modificabile. Il planner fornisce la media attesa quando disponibile, altrimenti il valore iniziale è 7 unità per 100 km. La verifica stradale su richiesta è disponibile per i primi cinque risultati e condivide la frequenza di richieste del planner. Le distanze geografiche e stradali hanno etichette distinte.

I preferiti conservano l’identificativo stabile MIMIT e una breve descrizione. Un distributore assente dal catalogo resta visibile e rimovibile. Le quotazioni senza prezzo o scadute sono consultabili disattivando il filtro di freschezza. Il supporto a catalogo e quotazioni resta limitato all’Italia. [Dati aperti MIMIT](https://www.mimit.gov.it/it/open-data/elenco-dataset/carburanti-prezzi-praticati-e-anagrafica-degli-impianti)

### Registro, rilevamento e costi

Il registro per veicolo permette di inserire, modificare, confermare ed eliminare i rifornimenti. I metadati della rilevazione includono livelli, autonomia, chilometraggio e posizione quando disponibile. I rifornimenti in metano usano kg. I prezzi osservati da MIMIT e le quantità derivate dal livello restano stime anche quando l’utente conferma che il rifornimento è avvenuto. Un importo pagato entra nella spesa solo dopo inserimento esplicito. I riepiloghi per mese, anno o intero storico raggruppano gli acquisti per carburante e valuta e mostrano il confronto con il periodo precedente. Il valore del carburante consumato è una stima basata sul consumo con unità coerenti nei viaggi disponibili e sul prezzo ponderato degli acquisti confermati. La disponibilità dello storico remoto condiziona i valori chilometrici.

Il rilevamento usa i controlli Stellantis già esistenti in primo piano e nel lavoro periodico in background. Richiede rilevazioni con orari crescenti, un incremento di almeno 8 punti percentuali e di almeno 3 unità quando si conosce la capacità, uno scarto del contachilometri di massimo 4 km e una seconda rilevazione stabile. Scarta confronti oltre 36 ore e mantiene la baseline dopo la chiusura dell’app. Deduplica per veicolo, carburante e finestra temporale. Per associare una stazione servono una posizione con orario compatibile e un impianto MIMIT coerente entro 80 m con sufficiente separazione da eventuali alternative. La mancanza di orari, aggiornamenti, contachilometri, coordinate o capacità può impedire il rilevamento, l’associazione o la stima. La frequenza dei rapporti e la quantizzazione dipendono dall’auto e da Stellantis. [Specifica API Stellantis B2C](https://github.com/Stellantis/stellantis.github.io/blob/master/assets/openapi/api-b2c.yaml)

Per il valore iniziale del carburante il planner usa un costo medio ponderato quando una sequenza coerente di acquisti confermati, livelli e prezzi parte da un serbatoio quasi vuoto. Usa il prezzo di riferimento se la sequenza è incompleta. Le quantità iniziali, la riserva e l’autonomia seguono i parametri del planner.

### Viaggi

Un piano salva i parametri, le soste e le loro quotazioni, il consumo previsto e la data di calcolo senza copiare la geometria stradale. L’originale resta consultabile mentre si modificano i dati o si ricalcola con carburante e catalogo aggiornati. Le soste confermate e gli importi impostati dall’utente vengono riproposti alla verifica. Un’associazione ambigua o una sosta fuori percorso richiedono una revisione esplicita. Il nuovo piano può sostituire l’originale solo con **Aggiorna viaggio salvato**. La duplicazione crea un piano indipendente.

La modalità attiva si ripristina per veicolo dopo la chiusura dell’app. Il confronto resta disponibile fino all’avvio del viaggio successivo. Usa i dati disponibili del veicolo e il suo aggiornamento manuale. Con un contachilometri iniziale valido stima distanza e tempo residui sul piano. Mostra prossima sosta, quantità, livello, autonomia e scostamenti osservabili. L’utente registra l’avvenuto passaggio dalla sosta. L’azione **Rivedi le soste previste** apre una revisione esplicita. Il navigatore esterno gestisce le indicazioni. Una volta concluso il viaggio, si possono associare più tragitti dall’intervallo orario. Il riepilogo confronta le durate di guida, il carburante con unità validate e l’importo confermato degli acquisti nell’intervallo. Il dato di posizione continua a dipendere dagli aggiornamenti Stellantis. Per il modello di consumo resta disponibile il backtesting esistente.

## Cache e ottimizzatore

La cache su disco usa versioni, chiavi SHA-256, file compressi, sostituzione atomica e invalidazione in caso di dati illeggibili o incompatibili. La geometria ha validità di 6 ore, con 16 voci e 24 MiB massimi. Gli accessi positivi hanno validità di 24 ore, gli esiti negativi affidabili di 3 ore. L’archivio degli accessi è limitato a 2.048 voci e 8 MiB. La pulizia automatica controlla le scadenze e libera per prime le voci usate meno di recente. La chiave dell’accesso comprende profilo OSRM, stazione, coordinate e direzione a uscita e rientro, senza progressi o consumi relativi al singolo itinerario. Il fallimento temporaneo resta ritentabile. La cadenza di 1.100 ms e la pausa dopo HTTP 429 seguono il comportamento del router esistente.

La ricerca anticipa i candidati economicamente promettenti su tratti distinti di 500 m, mantenendo i vicini per le verifiche successive. Conserva i migliori risultati già verificati e le soste confermate. Le tre strategie si riferiscono ai candidati e alle finestre stradali provate. L’algoritmo conserva criteri distinti per spesa alla pompa, tempo e valore del carburante usato. La verifica di accesso sottrae il tratto originale sostituito e il solutore applica capacità e riserva anche durante la deviazione.

| Fixture deterministica da 714 km e 917 impianti | Baseline pubblicata | Revisione |
| --- | ---: | ---: |
| Richieste fino al primo piano | 1 | 1 |
| Richieste nella fase automatica | 13 | 13 |
| Gruppo della prima alternativa economica | 4 | 1 |
| Richieste cumulative fino a tale alternativa | 69 | 27 |
| Spesa Strategia Economica | 48,01 € | 48,01 € |
| Spesa Strategia Rapida | 56,48 € | 56,48 € |
| Tempo aggiunto di Economica | 12,01 min | 12,01 min |
| Richieste dopo dodici estensioni | 181 | 181 |

La fixture della cache conta 1 richiesta a freddo e 0 a caldo per lo stesso accesso. Conta 0 dopo ricostruzione dell’istanza su disco, 0 su un itinerario parzialmente sovrapposto con la stessa finestra direzionale e 1 con direzione cambiata. Il test JVM ricostruisce anche la geometria di un itinerario salvato e verifica TTL e corruzione. I numeri misurano richieste simulate, senza latenza di rete o tempi di percorrenza reali. [Benchmark della baseline](FUEL_INCREMENTAL_SEARCH.md)

## App, backup e verifiche

Il backup ZIP esistente include storico, preferiti e impostazioni per veicolo, piani e viaggio attivo. Il formato del contenitore rimane compatibile con le versioni precedenti. L’archivio lascia sul dispositivo cache del router, rilevazioni grezze e credenziali. Le statistiche si ricalcolano dai dati disponibili. La ricerca per indirizzo continua a usare Photon. Le verifiche stradali inviano a OSRM le coordinate necessarie. L’app conserva i dati di gestione sul dispositivo e usa WorkManager con la frequenza esistente.

La baseline `f9feb79` ha superato test JVM, lint e le build debug Android e Wear OS. La revisione `1b1cae5` ha superato 77 test JVM, lint, build debug Android e Wear OS, assemblaggio dei test Android e 9 test strumentali su emulatore Android 11. I test strumentali coprono anche rendering della mappa, preferiti senza catalogo, storico e inclusione dei nuovi dati nel backup. Il collaudo di ripristino su installazione reale e quello su Peugeot 208 Hybrid 100, tablet e foldable restano da eseguire. [Esecuzione GitHub Actions](https://github.com/wi3ste/lionflow-mild/actions/runs/45462163081)

L’archivio di collaudo contiene i risultati strumentali e tre schermate dell’emulatore con dati di prova. `fuel-log-light.png` mostra lo storico, `fuel-statistics-light.png` il riepilogo e `fuel-favorites-dark.png` l’elenco dei preferiti senza il catalogo. [Screenshot e risultati](https://github.com/wi3ste/lionflow-mild/actions/runs/45462163081/artifacts/14953976707)

Per installare, scaricare **LionFlow-fuel-trip-planner-debug**, estrarre `app-debug.apk` e aprirlo sul telefono. L’identificativo Android è `no.lionflow.app.debug`. `apksigner` ha verificato il certificato debug generato dal runner. La sua impronta SHA-256 è `6468e9ccda389e7d8a5d3d5f13c6984b6e682a13df576c3ad4fe083390a425da`. La baseline usava a sua volta un certificato generato dal proprio runner. Prima di sostituire un’installazione con certificato diverso, esportarne il backup. [Scarica l’APK debug](https://github.com/wi3ste/lionflow-mild/actions/runs/45462163081/artifacts/14952764100)

## Funzioni rinviate e fonti

- **Avvisi prezzi.** La soglia assoluta è adatta al lavoro MIMIT periodico. Per una futura implementazione si può notificare solo al passaggio sotto soglia, conservando l’ultimo esito per impianto, carburante e servizio. Le soglie di calo o di convenienza locale dipendono da uno storico coerente e da quotazioni fresche per lo stesso servizio.
- **Apprendimento dal consuntivo.** I confronti del viaggio attivo sono disponibili per il collaudo del modello. La calibrazione andrà rivalutata su un campione omogeneo di previsioni e viaggi associati, prima di usare gli errori per cambiare i comparabili o la riserva.
- **Capacità serbatoi.** L’override resta disponibile e il lookup riconosce la Peugeot 208 Hybrid 100. Il listino italiano e il configuratore Citroën documentano le varianti della C3. I dati veicolo attuali non identificano in modo uniforme allestimento, anno e mercato. L’estensione automatica richiede un’associazione verificabile fra versione e dato ufficiale. [Listino Citroën](https://www.citroen.it/content/dam/citroen/italy/listini/06-26/LISTINO_C3_AGGIORNAMENTO_GIUGNO_2026.pdf)
- **Percorsi alternativi.** OSRM espone un’opzione per proporre alternative. I percorsi più corto e più rapido richiedono profili appropriati, e `exclude=toll` funziona solo se il profilo del server l’ha abilitato. Una futura estensione può passare ciascuna geometria allo stesso ottimizzatore e presentare il costo carburante per percorso, mantenendo il pedaggio come dato indipendente opzionale. [Documentazione OSRM](https://project-osrm.org/docs/v26.6.1/http.html)
- **Pedaggi.** Autostrade per l’Italia pubblica regole e tariffe proprie. A4 Holding fornisce un calcolatore per i propri caselli. Il catalogo nazionale comprende anche dati regionali sui caselli, che non sono un tariffario per itinerario. Manca una fonte nazionale verificata e riutilizzabile con percorsi, concessionari, classi, barriere e aggiornamento. Un futuro campo manuale può riportare un preventivo accanto al costo carburante. [Autostrade per l’Italia](https://www.autostrade.it/it/servizi-al-cliente/pedaggio/come-si-calcola-il-pedaggio), [A4 Holding](https://www.a4holding.it/web/a4-inviaggio/pedaggi-e-pagamenti), [dati.gov.it](https://www.dati.gov.it/node/view-dataset/dataset?id=be8fbe27-2de4-4fda-85fb-d40f7891e327)

## Collaudo Peugeot 208 Hybrid 100

1. Parti con un backup. Verifica che il planner proponga 44 L se il veicolo è riconosciuto e che l’override manuale sia disponibile.
2. Ripeti l’itinerario da 714 km con la stessa riserva e deviazione di 8 km. Registra prezzi, richieste, tempo al primo piano e soste per strategia. Ripeti con cache calda, dopo arresto del processo e con un itinerario che condivide un tratto.
3. Conferma una sosta, amplia la ricerca e cambia strategia. Chiudi il piano e verifica il catalogo libero. Passa alle colonnine e prova il planner elettrico con un veicolo compatibile.
4. Prova telefono, auto, indirizzo e centro mappa in posizioni diverse. Cambia quantità e servizio, verifica su strada un primo risultato, aggiungi e rimuovi un preferito.
5. Inserisci un rifornimento parziale e correggine importo, quantità e chilometraggio. Con un incremento reale, verifica che sia richiesta una seconda lettura, che il suggerimento appaia una volta sola e che il prezzo MIMIT abbia l’etichetta di stima.
6. Controlla spese mensili e annuali, esportazione CSV e separazione per auto. Ripristina il backup su un’installazione di prova e ricontrolla le voci.
7. Salva e duplica il piano. Cambia livello del veicolo, riapri e ricalcola. Avvia il viaggio, chiudi e riapri l’app, aggiorna la telemetria e marca le soste effettuate. Termina il viaggio e associa due tragitti del veicolo. Verifica che le lacune nei dati restino visibili.
