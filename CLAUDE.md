# CLAUDE.md — Domotica Valle Quaranta

Guida per AI assistant che lavorano su questo repository.

## Cos'è questo progetto

Configurazione domotica di un'abitazione privata in Italia. I componenti principali sono:

- **Home Assistant** — hub centrale, automazioni, interfaccia utente
- **ESPHome** — firmware per nodi ESP32 (lettori microchip, controllo accessi)
- **Frigate** — NVR locale con rilevamento oggetti e riconoscimento facciale

Funzionalità previste: lettura microchip animali domestici (ISO 11784/11785 FDX-B 134,2 kHz), videosorveglianza, controllo accessi biometrico.

**Stato**: progetto in fase iniziale — molti componenti sono ancora da implementare.

## Struttura del repository

```
homeassistant/          Configurazione Home Assistant
  secrets.yaml.example  Chiavi attese (valori segnaposto)
esphome/                Firmware nodi ESP32
  secrets.yaml.example  Chiavi attese (valori segnaposto)
docs/
  hardware.md.          Note hardware e vincoli tecnici
README.md
CLAUDE.md               (questo file)
```

Cartelle pianificate ma non ancora presenti:
- `esphome/chip-reader/` — lettore microchip
- `esphome/accessi/` — controllo varchi e biometria
- `frigate/` — configurazione NVR

## Regola fondamentale: segreti

**Mai versionare credenziali o dati identificativi.** Il `.gitignore` esclude già:

- `secrets.yaml` (HA e ESPHome) — contengono credenziali reali
- Dati biometrici: `faces/`, `fingerprints/`, `embeddings/`
- Codici microchip reali degli animali (`.chipid`)
- Database, log, cache di runtime

Quando si aggiunge una nuova chiave a `secrets.yaml`, aggiornare **solo** il corrispondente `secrets.yaml.example` con un valore segnaposto.

## Convenzioni

### Lingua
La documentazione e i commenti nei file di configurazione sono in **italiano**. I messaggi di commit possono essere in italiano o inglese.

### Segreti nei file di configurazione
Usare sempre `!secret nome_chiave` in YAML (Home Assistant / ESPHome) anziché valori inline.

```yaml
# Corretto
wifi:
  ssid: !secret wifi_ssid

# Sbagliato
wifi:
  ssid: "MiaRete"
```

### ESPHome
- Un file `.yaml` per nodo fisico
- Includere sempre i componenti `wifi`, `api`, `ota`, `logger`
- La chiave `api.encryption.key` va in `secrets.yaml`
- I sensori esposti a Home Assistant tramite API nativa ESPHome (non MQTT salvo necessità)

### Home Assistant
- Usare `packages/` per organizzare le automazioni per area/funzione
- Ogni package è un file YAML autonomo che raggruppa entità correlate
- `configuration.yaml` dovrebbe restare snello, con `homeassistant.packages` che include i package

### Frigate
- Non affiancare servizi di riconoscimento facciale di terze parti: usare solo quello nativo (dalla versione 0.16)
- Definire zone di mascheratura per escludere strada pubblica e proprietà confinanti prima di attivare la registrazione

### Lettore microchip
- Frequenza obbligatoria: **134,2 kHz FDX-B** (ISO 11784/11785)
- Moduli **non compatibili**: RC522 (13,56 MHz), RDM6300/EM4100 (125 kHz)
- Il codice letto ha 15 cifre; il prefisso `380` indica registrazione italiana
- I codici reali non vanno mai scritti nei file versionati

## Workflow di sviluppo

1. Lavorare sempre su un branch dedicato, mai direttamente su `main`
2. Prima di aggiungere un nuovo componente hardware, documentarne i vincoli in `docs/`
3. Testare la configurazione ESPHome localmente con `esphome compile <file>.yaml` prima di fare OTA
4. Per Home Assistant, validare con `ha core check` o `homeassistant --script check_config`

## Cosa non è ancora implementato

Queste sezioni sono pianificate e vanno create quando si inizia il lavoro:

| Componente | Directory | Stato |
|---|---|---|
| Lettore microchip (chip-reader) | `esphome/chip-reader/` | Da fare |
| Controllo accessi (accessi) | `esphome/accessi/` | Da fare |
| Configurazione Frigate | `frigate/` | Da fare |
| Package HA per animali | `homeassistant/packages/animali.yaml` | Da fare |
| Package HA per accessi | `homeassistant/packages/accessi.yaml` | Da fare |

## Hardware da definire

Secondo `docs/hardware.md.`, rimane ancora da scegliere:
- Modello specifico del lettore microchip a 134,2 kHz
- Numero e posizione delle telecamere
- Hardware su cui gira Home Assistant (e acceleratore per inferenza Frigate)
- Sensore biometrico per il varco d'accesso
