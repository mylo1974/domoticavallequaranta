# Domotica Valle Quaranta

Configurazione della domotica di casa: Home Assistant, lettura dei microchip
degli animali d'affezione, videosorveglianza e controllo accessi.

## Struttura

    homeassistant/     configurazione HA (automazioni, packages)
    esphome/           firmware dei nodi ESP32
      chip-reader/     lettore microchip ISO 11784/11785 FDX-B 134,2 kHz
      accessi/         controllo varchi e biometria
    frigate/           configurazione NVR e rilevamento oggetti
    docs/              schemi di collegamento e note hardware

## Segreti

Nessuna credenziale va versionata. I valori reali stanno in `secrets.yaml`
(escluso da Git); nei file `secrets.yaml.example` sono elencate le chiavi
attese con valori segnaposto.

Restano fuori dal repository anche i template biometrici, gli embedding
facciali e i codici microchip reali degli animali.

## Stato

Progetto in fase iniziale.
