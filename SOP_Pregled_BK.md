# Navodila za uporabo — Pregled BK

**Aplikacija:** si.kclj.pregledbk  
**Naprava:** Zebra TC200J + RFD2000 RFID bralnik  
**Namen:** Upravljanje zalog reagentov, kalibra­torjev, kontrol in potrošnega materiala v laboratoriju

---

## 1. Strojna oprema

| Komponenta | Opis |
|---|---|
| Zebra TC200J | Ročni skener z zaslonom na dotik |
| RFD2000 | Snap-on RFID bralnik (priklopi na spodnji del TC200J) |
| RFD2000 trigger | Fizični sprožilec na strani RFD2000 |

**Priklop RFD2000:** Natisni RFD2000 na spodnji del TC200J do klika. Aplikacija zazna bralnik samodejno v ~5 sekundah in prikaže obvestilo **"Povezan: RFD2000"**.

---

## 2. Zagon aplikacije

1. Odpri aplikacijo **Pregled BK** na TC200J.
2. Počakaj na obvestilo **"Povezan: RFD2000"** (do 10 s).
3. Aplikacija se odpre na zavihku **Sken.** v RFID načinu.

> **Opomba:** Če se RFD2000 ne poveže, poskusi **Več → Ponastavi čitalca** in počakaj 5 s.

---

## 3. Navigacija (zavihki)

| Zavihek | Ikona | Namen |
|---|---|---|
| **Sken.** | 📡 | Skeniranje (RFID / QR / DataMatrix) |
| **Zaloga** | 📦 | Pregled zalog in sprejetih materialov |
| **Loti** | 🏷 | Sledenje lotov po artiklih, akcije |
| **Roki** | ⚠️ | Artikli z bližajočim/pretečenim rokom |
| **GTIN** | 🏷️ | Baza artiklov (GTIN katalog) |
| **Več** | ⋯ | Nastavitve, seje, izvoz, diagnostika |

---

## 4. Seja inventure

Seja inventure se uporablja za periodično preverjanje zaloge z RFID ali barcode skeniranjem.

### 4.1 Začetek seje

1. Na zavihku **Sken.** tapni **Začni sejo**.
2. Izberi način skeniranja: **RFID** ali **QR / DataMatrix**.
3. Tapni **Začni**.

### 4.2 RFID skeniranje

1. Zagotovi, da je prikazan način **RFID** (modra ikona 📡).
2. Pritisni in drži **trigger** na RFD2000 — bralnik začne iskati oznake.
3. Prinesi bralnik blizu RFID oznake (do ~30 cm, odvisno od nastavitve moči).
4. Ob zaznavi se artikel prikaže v seznamu.
5. Spusti trigger za zaključek skeniranja.

### 4.3 QR / DataMatrix skeniranje

1. Tapni gumb **QR / DataMatrix** v vrstici načinov.
2. Prikaže se vnosno polje — usmeri skener TC200J v kodo.
3. DataWedge samodejno vnese kodo.
4. Artikel se prepozna in doda v sejo.

### 4.4 Pregled rezultatov seje

- **Zeleno** — artikel prepoznan in v zalogi.
- **Rdeče** — artikel ne najden v bazi ali ni v zalogi.
- Tapni artikel za podrobnosti.

### 4.5 Zaključek seje

1. Tapni **Zaključi sejo**.
2. Prikaže se povzetek: skupaj skeniranih, prepoznanih, zavrnjenih.
3. Po potrebi izvozi rezultate (Več → **Seje CSV**).

---

## 5. Sprejem materiala

Sprejem se uporablja za evidentiranje novega materiala ob prevzemu (z barcode + RFID oznako).

### 5.1 Odpiranje sprejem dialoga

- **Zaloga** → tapni gumb **+ Sprejmi** (desno zgoraj v sekciji Prejeto).

### 5.2 Korak 1 — Skeniranje barcode (QR / DataMatrix)

1. Usmeri TC200J v GS1 DataMatrix ali QR kodo na embalaži.
2. Aplikacija samodejno prebere kodo.
3. Preverita se naslednji podatki:
   - **GTIN** — mora biti v GTIN bazi
   - **Lot** — serija materiala
   - **Rok uporabe** — datum izteka
   - **Serijska številka (SN)** — edinstvena oznaka kosa
4. Dokler niso izpolnjeni vsi štirje podatki, je gumb **Skeniraj RFID** onemogočen. Status prikaže kateri podatki manjkajo (npr. *"Manjka: SN — skenirajte kodo"*).
5. Skeniraj dodatne kode istega artikla, dokler niso vsi podatki izpolnjeni.

> **Katalog (AI 240)** ni obvezen.

### 5.3 Korak 2 — Skeniranje RFID

1. Ko so vsi podatki izpolnjeni, tapni **📡 Skeniraj RFID**.
2. Prinesi RFD2000 do ~5 cm od RFID nalepke na embalaži.
3. Pritisni trigger — bralnik zajame EPC kodo.
4. Ob uspešnem branju se prikaže **"✓ RFID zajeto"** z EPC kodo.

### 5.4 Potrditev

1. Tapni **✓ Sprejmi**.
2. Material se shrani v zalogi in v tabeli Prejeto.
3. Če je to **nov lot** tega artikla, se samodejno prikaže opomba glede na tip:

| Tip | Opomba |
|---|---|
| Kalibrator | *Vnesi vrednosti novega Lot-a kalibratorja v Alinity analizator.* |
| Reagent | *Napravi Lot-to-Lot primerjavo. Testiraj nov Lot s QC materialom.* |
| Kontrola | *Preveri nov Lot QC materiala.* |

### 5.5 Serijski sprejem (več kosov istega artikla)

- V **Nastavitve** (Več) vklopi **Serijski sprejem**.
- Po potrditvi prvega kosa se dialog samodejno resetira za naslednji kos istega artikla.

---

## 6. Zavihek Zaloga

Prikazuje celotno zalogo in tabelo sprejetih materialov.

| Element | Opis |
|---|---|
| **Stat strip** | Skupaj kosov, pretečeni, opozorila (<28d), tipi |
| **Iskanje** | Filtriraj po imenu ali GTIN |
| **Filter tip** | Kalibr., reagenti, kontrole, potrošni |
| **Filter rok** | Pretečeni / <14d / <28d / OK |
| **Tabela Zaloga** | Artikel, lot, rok, količina, status |
| **Tabela Prejeto** | Posamezni sprejemi s SN, RFID, datumom |

Gumb **CSV** izvozi tabelo zaloge.

---

## 7. Zavihek Loti

Prikazuje vse lote po tipu artikla. Nepreverjeni loti so označeni **modro**.

### Vrstni red prikaza

1. Kalibratorji
2. Reagenti Bio
3. Reagenti IMUNO
4. Kontrole
5. Potrošni material

### Pomen modre barve

Lot je moder, dokler ni potrjena akcija (Lot-to-Lot primerjava, vnos vrednosti v Alinity, testiranje QC). Ko je akcija opravljena, označi lot s **kljukico** (checkbox) — modro ozadje izgine.

### Podatki za vsak lot

- Lot številka, rok izteka, količina v zalogi
- Datum prvega sprejema
- Tip-specifična opomba (samo za nepreverjene)

### Filtriranje

- Po imenu artikla (iskalno polje)
- Po tipu artikla
- Po statusu: Čakajo / Preverjeni

---

## 8. Zavihek Roki

Prikazuje artikle glede na rok izteka.

| Pod-zavihek | Vsebina |
|---|---|
| **Pretek** | Že pretečeni artikli |
| **14d** | Iztečejo v 14 dneh |
| **28d** | Iztečejo v 28 dneh |
| **Vsi** | Celoten seznam z roki |

---

## 9. Zavihek GTIN

Baza artiklov, ki jih aplikacija prepozna pri skeniranju.

### Dodajanje artikla

1. GTIN → tapni **+ Dodaj GTIN**.
2. Vnesi:
   - **GTIN** (14-mestna koda)
   - **Ime artikla**
   - **Tip** (kalibrator / reagent-bio / reagent-imuno / kontrola / potrošni)
   - Opcijsko: katalog, ident, analizator, opomba
3. Tapni **Shrani**.

> Brez vnosa v GTIN bazo aplikacija ne bo prepoznala artikla pri skeniranju.

---

## 10. Nastavitve (zavihek Več)

### Moč RFID (dBm)

| Način | Priporočeno | Namen |
|---|---|---|
| Sprejem | 0.0 – 10.0 dBm | Natančno branje ene oznake (~5 cm) |
| Seja inventure | 15.0 – 30.0 dBm | Branje več oznak naenkrat (~1 m) |

Gumb **Test** zraven polja: preveri doseg pri nastavljeni moči — pri pritisku triggerja se EPC kode prikazujejo v živo.

### Serijski sprejem

Vklopi za zaporedno sprejemanje več kosov istega artikla brez ponovnega odpiranja dialoga.

### Gumbi za diagnostiko

| Gumb | Funkcija |
|---|---|
| 📡 Preveri RFD2000 | Preveri ali je RFID bralnik dosegljiv |
| ⬛ Preveri barcode | Test DataWedge barcode skeniranja |
| 🔄 Ponastavi čitalca | Force-reset RFD2000 (če se zacikla) |

### Izvoz podatkov

| Gumb | Vsebina |
|---|---|
| 📦 Zaloga CSV | Trenutna zaloga |
| ⚠ Roki CSV | Artikli z datumi izteka |
| 📋 Seje CSV | Zgodovina sej inventure |
| 🏷 GTIN CSV | Baza artiklov |

---

## 11. Reševanje pogostih težav

| Težava | Rešitev |
|---|---|
| RFD2000 se ne poveže | Preveri fizični priklop → **Ponastavi čitalca** → počakaj 10 s |
| Barcode se ne prebere | Preveri da je TC200J usmerjen v kodo; preizkusi s **Preveri barcode** |
| RFID gumb onemogočen pri sprejemu | Skeniraj dodatne kode — manjkajo lot, rok ali SN |
| Artikel ni prepoznan | Dodaj GTIN v bazo (zavihek **GTIN**) |
| Duplikat serijske številke | Ta SN je že v aktivni zalogi; preveri v **Zaloga → Prejeto** |
| Lot-to-Lot primerjava ni opravljena | V **Loti** zavihku poišči moder lot in postavi kljukico ko je akcija opravljena |

---

## 12. Varnostne kopije

Podatki se shranjujejo lokalno na napravi (localStorage). Priporoča se redni izvoz CSV datotek za arhiviranje.

**Lokacija datoteke** je vidna v Več → sekcija *Lokacija datoteke*.

---

*Dokument velja za build 68+. KCLJ — Laboratorij.*
