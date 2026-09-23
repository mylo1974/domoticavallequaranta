# Mappa Segnale — rilievo celle e puntamento antenne

App Android per decidere **quale operatore** usare e **dove orientare l'antenna
esterna** di un sistema di amplificazione (o di un router 4G/5G) in una casa
senza copertura.

Funziona senza account, senza chiavi API e senza Google Play Services. Le
mappe sono OpenStreetMap. I dati restano sul telefono.

## Cosa fa

| Scheda | Funzione |
|---|---|
| **Live** | Tutte le celle viste dal modem: operatore, 2G/3G/4G/5G, banda e frequenza, RSRP/RSRQ/SINR, eNB e settore, PCI, TAC, timing advance (distanza dal traliccio). |
| **Rilievo** | Registra GPS + tutte le celle ogni ~2 s mentre cammini. Imposta la casa, raggio di confronto, esporta CSV/KML. |
| **Mappa** | Punti colorati per livello (filtro per operatore o per singolo traliccio), casa, tralicci stimati e linee di puntamento. Pressione lunga = imposta casa. |
| **Analisi** | Classifica degli operatori attorno alla casa, stima della posizione di ogni traliccio con **azimut dalla casa ± incertezza**, bande da coprire, consiglio finale. Si può inserire la posizione reale di un traliccio, se nota. |
| **Punta** | Bussola (nord vero, declinazione corretta) con freccia verso il traliccio scelto, livello in tempo reale con beep, **scansione a 360°** per trovare da dove arriva il segnale stando sul tetto. |

## Come si stima il traliccio

1. Le misure vengono raggruppate in celle di 10 m.
2. **Gradiente**: regressione del livello su est/nord → direzione in cui il
   segnale cresce. Robusta anche con un rilievo piccolo.
3. **Modello di propagazione** log-distanza (esponente 2–4) adattato con
   ricerca a griglia fino a 20 km; il timing advance della cella servente
   vincola la distanza.
4. Regione di confidenza (Δχ² ≤ 6) → incertezza dell'azimut e intervallo di
   distanza visti dalla casa.

Più il rilievo è ampio e in direzioni diverse (200–500 m attorno alla casa,
compresi i punti dove "prende"), più la stima è precisa.

## Limiti da conoscere

- **Un telefono misura solo le celle dell'operatore della propria SIM.** Per
  confrontare gli operatori: telefono doppia SIM (due operatori insieme) e/o
  ripetere il giro con SIM diverse (bastano prepagate).
- Android richiede **posizione precisa attiva** per leggere le celle.
- Colline ed edifici deviano il segnale: la direzione stimata è quella da cui
  il segnale *arriva*, che è comunque quella giusta per l'antenna. Conferma sempre
  sul tetto con la scansione a 360° e, ad antenna montata, ruotandola di 5° per
  volta.
- iOS non espone questi dati: l'app è solo per Android 10+.

## Nota normativa

In Italia i ripetitori/amplificatori cellulari si possono usare solo con il
consenso dell'operatore titolare delle frequenze. Un'alternativa sempre lecita e
spesso più efficace è un **router 4G/5G con antenna direzionale esterna** sul
tetto, più le **chiamate Wi-Fi (VoWiFi)** sui telefoni, se supportate dal tuo
operatore. L'app serve in entrambi i casi: scegliere l'operatore e puntare l'antenna.

## Installazione

L'APK viene compilato da GitHub Actions (workflow *App segnale cellulare*):
Actions → ultima esecuzione → artifact `mappa-segnale-apk`. Sul telefono:
consenti l'installazione da origini sconosciute e apri `app-debug.apk`.

Oppure da Android Studio: apri la cartella `segnale-cellulare/` ed esegui.

Da riga di comando (serve l'SDK Android):

    cd segnale-cellulare
    ./gradlew testDebugUnitTest assembleDebug

## Privacy

I rilievi contengono la posizione esatta della casa. Restano nel database
dell'app e nei file esportati in `Download/MappaSegnale/`. **Non committarli**
in questo repository (anche se privato, la cronologia Git è per sempre): `.gitignore` esclude già `*.csv` e `*.kml`.
