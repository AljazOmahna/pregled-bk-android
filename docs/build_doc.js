const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  ImageRun, Header, Footer, AlignmentType, LevelFormat, TableOfContents,
  HeadingLevel, BorderStyle, WidthType, ShadingType, PageNumber, PageBreak,
} = require("docx");

// ===== nastavljivo ob vsaki nadgradnji =====
const BUILD = "114";
const DATE = "16.6.2026";
const SHOTS = path.join(__dirname, "shots");

function img(file, w) {
  w = w || 210;
  const h = Math.round(w * 800 / 480); // posnetki so 480x800
  return new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { before: 120, after: 60 },
    children: [new ImageRun({
      type: "png",
      data: fs.readFileSync(path.join(SHOTS, file)),
      transformation: { width: w, height: h },
      altText: { title: file, description: file, name: file },
    })],
  });
}
function caption(t) {
  return new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { after: 160 },
    children: [new TextRun({ text: t, italics: true, size: 18, color: "666666" })],
  });
}
function h1(t) { return new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(t)] }); }
function h2(t) { return new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(t)] }); }
function p(runs) { return new Paragraph({ spacing: { after: 100 }, children: Array.isArray(runs) ? runs : [new TextRun(runs)] }); }
function b(t) { return new TextRun({ text: t, bold: true }); }
function tx(t) { return new TextRun(t); }
function li(t, runs) {
  return new Paragraph({
    numbering: { reference: "bullets", level: 0 },
    spacing: { after: 40 },
    children: runs || [new TextRun(t)],
  });
}
function num(runs) {
  return new Paragraph({
    numbering: { reference: "steps", level: 0 },
    spacing: { after: 40 },
    children: Array.isArray(runs) ? runs : [new TextRun(runs)],
  });
}

const border = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: border, bottom: border, left: border, right: border };
function cell(text, w, fill, bold) {
  const isHdr = fill === "1F3864";
  return new TableCell({
    borders, width: { size: w, type: WidthType.DXA },
    shading: fill ? { fill, type: ShadingType.CLEAR } : undefined,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({ children: [new TextRun({ text: text, bold: !!bold, size: 18, color: isHdr ? "FFFFFF" : "000000" })] })],
  });
}
function histRow(buildN, date, note) {
  return new TableRow({ children: [
    cell(buildN, 900), cell(date, 1500), cell(note, 6960),
  ]});
}

const doc = new Document({
  styles: {
    default: { document: { run: { font: "Arial", size: 22 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 30, bold: true, font: "Arial", color: "1F3864" },
        paragraph: { spacing: { before: 260, after: 140 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 25, bold: true, font: "Arial", color: "2E5496" },
        paragraph: { spacing: { before: 180, after: 100 }, outlineLevel: 1 } },
    ],
  },
  numbering: {
    config: [
      { reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 600, hanging: 280 } } } }] },
      { reference: "steps", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 600, hanging: 300 } } } }] },
    ],
  },
  sections: [{
    properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 1200, right: 1200, bottom: 1200, left: 1200 } } },
    headers: { default: new Header({ children: [new Paragraph({
      tabStops: [{ type: "right", position: 9506 }],
      border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "2E5496", space: 4 } },
      children: [new TextRun({ text: "Pregled BK – Navodila za uporabo", size: 16, color: "888888" }),
                 new TextRun({ text: "\tBuild " + BUILD, size: 16, color: "888888" })],
    })] }) },
    footers: { default: new Footer({ children: [new Paragraph({
      alignment: AlignmentType.CENTER,
      children: [new TextRun({ text: "Stran ", size: 16, color: "888888" }),
                 new TextRun({ children: [PageNumber.CURRENT], size: 16, color: "888888" }),
                 new TextRun({ text: " / ", size: 16, color: "888888" }),
                 new TextRun({ children: [PageNumber.TOTAL_PAGES], size: 16, color: "888888" })],
    })] }) },
    children: [
      // ===== NASLOVNICA =====
      new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 1600, after: 60 },
        children: [new TextRun({ text: "PREGLED BK", bold: true, size: 64, color: "1F3864" })] }),
      new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 40 },
        children: [new TextRun({ text: "Navodila za uporabo", size: 36, color: "2E5496" })] }),
      new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 600 },
        children: [new TextRun({ text: "Upravljanje zalog kontrolnega in kalibracijskega materiala (BK)", italics: true, size: 22, color: "666666" })] }),
      new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 30 },
        children: [new TextRun({ text: "Naprava: Zebra TC20 · čitalec RFD2000", size: 22 })] }),
      new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 30 },
        children: [b("Različica aplikacije: Build " + BUILD), tx("   ·   "), tx(DATE)] }),
      new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 30 },
        children: [new TextRun({ text: "Avtor: Aljaž Omahna · KO za klinično kemijo in biokemijo, UKC Ljubljana", size: 18, color: "666666" })] }),
      new Paragraph({ children: [new PageBreak()] }),

      // ===== KAZALO =====
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("Kazalo")] }),
      new TableOfContents("Kazalo", { hyperlink: true, headingStyleRange: "1-2" }),
      new Paragraph({ children: [new PageBreak()] }),

      // 1 UVOD
      h1("1. Uvod in namen"),
      p("Pregled BK je aplikacija za vodenje zaloge laboratorijskega materiala (predvsem kontrol in kalibratorjev) na napravah Zebra TC20. Omogoča sprejem materiala s skeniranjem črtne kode (DataMatrix) in RFID oznak, pregled zaloge, spremljanje rokov izteka in lotov ter pripravo tedenskega PDF poročila BK."),
      p([b("Dve napravi: "), tx("aplikacija teče na dveh Zebrah, ki si podatke izmenjujeta prek Quick Share ali OneDrive sinhronizacije.")]),
      p([b("Skeniranje: "), tx("črtne in DataMatrix kode bere vgrajeni Zebra skener (DataWedge), RFID oznake pa zunanji čitalec RFD2000.")]),

      // 2 NAVIGACIJA
      h1("2. Osnovna navigacija"),
      p("Glava na vrhu vsakega zaslona prikazuje ime aplikacije, tri števce, desno zgoraj pa dva preklopna gumba:"),
      li(null, [b("KOS "), tx("– skupno število kosov v zalogi.")]),
      li(null, [new TextRun({ text: "EXP ", bold: true, color: "C00000" }), tx("– število artiklov s pretečenim rokom (rdeče).")]),
      li(null, [b("14D "), tx("– število artiklov, ki potečejo v 14 dneh.")]),
      li(null, [b("RFID "), tx("– vklop/izklop branja RFID oznak.")]),
      li(null, [b("BC "), tx("– način črtne kode (barcode).")]),
      p("Na dnu zaslona je vrstica s šestimi zavihki:"),
      li(null, [b("Sken. "), tx("– začetni zaslon (seje, sprejem materiala)")]),
      li(null, [b("Zaloga "), tx("– trenutna zaloga, izvoz CSV in PDF BK")]),
      li(null, [b("Loti "), tx("– loti in njihovo ovrednotenje")]),
      li(null, [b("Roki "), tx("– roki izteka z barvnimi opozorili")]),
      li(null, [b("GTIN "), tx("– baza šifer izdelkov")]),
      li(null, [b("Več "), tx("– nastavitve, operaterji, sinhronizacija, izvozi")]),

      // 3 SKEN
      h1("3. Začetni zaslon (Sken.)"),
      img("02_sken.png"),
      caption("Zavihek Sken. – začetni zaslon"),
      p("Na začetnem zaslonu so trije gumbi:"),
      li(null, [b("+ Začni novo sejo "), tx("– splošna inventura izbranih tipov materiala.")]),
      li(null, [b("+ Začni pregled BK "), tx("– inventura kontrol in kalibratorjev (privzeti obseg).")]),
      li(null, [b("Sprejmi material "), tx("– sprejem novega materiala v zalogo (glej poglavje 4).")]),

      // 4 SPREJEM
      h1("4. Sprejem materiala"),
      img("08_sprejem.png"),
      caption("Okno Sprejem materiala – korak 1 (skeniranje kode)"),
      h2("4.1 Običajni sprejem (z RFID)"),
      num([b("Korak 1 – črtna koda: "), tx("skenirajte DataMatrix kodo na škatli. Aplikacija prepozna GTIN, lot, rok in serijsko številko.")]),
      num([b("Korak 2 – RFID: "), tx("kliknite “Skeniraj RFID” in pritisnite trigger na RFD2000 – zajame se najbližja RFID oznaka.")]),
      num([b("Potrdite "), tx("z gumbom “Sprejmi”. Artikel se doda v zalogo (količina +1).")]),
      h2("4.2 Sprejem brez RFID/SN (ročno)"),
      p([tx("Nekateri artikli pridejo "), b("brez RFID oznake in brez serijske številke"), tx(" (npr. kontrole RANDOX, občasno tudi kakšna Abbott škatla). Po skeniranju kode (korak 1) v koraku 2 kliknite "), b("“Sprejmi brez RFID/SN (ročno)”"), tx(". Aplikacija ustvari nadomestno serijsko številko (MAN…), EPC ostane prazen, količina se poveča za 1.")]),
      h2("4.3 Počisti vnos"),
      p([tx("Če ste skenirali napačen artikel ali ne morete zajeti RFID, kliknite "), b("“↻ Počisti vnos”"), tx(" – trenutni vnos se zavrže, okno sprejema pa ostane odprto za naslednji artikel (ni treba zaključiti celega sprejema).")]),
      h2("4.4 Serijski sprejem"),
      p([tx("V "), b("Več → Nastavitve"), tx(" lahko vklopite “Serijski sprejem” – okno ostane odprto in lahko zaporedoma sprejemate več artiklov brez vmesnega zapiranja.")]),

      // 5 ZALOGA
      h1("5. Zaloga"),
      img("03_zaloga.png"),
      caption("Zavihek Zaloga – pregled in izvoz"),
      p("Prikazuje skupno število kosov, pretekle artikle, opozorila in število tipov. Spodaj je seznam artiklov z lotom, rokom, količino in statusom. Z iskalnikom in filtri (tip, rok) seznam zožite."),
      li(null, [b("CSV "), tx("– izvoz celotne zaloge v Excel (datoteka v mapi Prenosi).")]),
      li(null, [b("PDF BK "), tx("– tedensko poročilo kontrol in kalibratorjev (glej poglavje 9).")]),

      // 6 LOTI
      h1("6. Loti"),
      img("04_loti.png"),
      caption("Zavihek Loti – ovrednotenje novih lotov"),
      p([tx("Novi loti so označeni z značko "), b("NOV"), tx(". Za kontrole/kalibratorje in reagente je potrebno "), b("ovrednotenje"), tx(" (gumb “Ovrednoti”): vpis datuma in obveznega sklica na dokument. Potrošni material ovrednotenja ne potrebuje.")]),

      // 7 ROKI
      h1("7. Roki izteka"),
      img("05_roki.png"),
      caption("Zavihek Roki – barvna opozorila"),
      p("Artikli so razvrščeni po roku izteka. Filtri in barve:"),
      li(null, [new TextRun({ text: "Pretek ", bold: true, color: "C00000" }), tx("– rok je že potekel (rdeče).")]),
      li(null, [new TextRun({ text: "14 dni ", bold: true, color: "E8730C" }), tx("– poteče v 14 dneh (oranžno).")]),
      li(null, [new TextRun({ text: "28 dni ", bold: true, color: "B8860B" }), tx("– poteče v 28 dneh (rumeno).")]),
      li(null, [new TextRun({ text: "Daljši rok ", bold: true, color: "2E7D32" }), tx("– več kot 28 dni do izteka (zeleno).")]),

      // 8 GTIN
      h1("8. GTIN baza"),
      img("06_gtin.png"),
      caption("Zavihek GTIN – šifre izdelkov"),
      p([tx("Sprejmejo se samo kode, ki so v tej bazi. Nove Abbott GTIN podatke uvozite prek "), b("CSV"), tx(", posamezne pa dodate z gumbom "), b("+ GTIN"), tx(". Gumb “Package insert” odpre navodila izdelka (OneDrive).")]),

      // 9 PDF BK
      h1("9. PDF BK – tedensko poročilo"),
      img("09_pdf_dialog.png"),
      caption("Dialog PDF BK – vnos matične številke s številčnico"),
      p("Priprava tedenskega poročila zaloge kontrol in kalibratorjev:"),
      num([tx("V zavihku "), b("Zaloga"), tx(" kliknite "), b("PDF BK"), tx(".")]),
      num([tx("Vpišite svojo "), b("5-mestno matično številko"), tx(" na številčnici "), b("ali jo skenirajte"), tx(" s priponke (čitalec je vklopljen).")]),
      num([tx("Pod poljem se izpiše "), b("ime operaterja"), tx(" – preverite, da je pravo.")]),
      num([tx("Kliknite "), b("Generiraj PDF"), tx(". Datoteka se shrani v Prenose kot "), b("PregledBK_LLLL-MM-DD.pdf"), tx(".")]),
      p([b("Vsebina PDF: "), tx("datum in ura pregleda, ime operaterja (“Pripravil”), seznam kontrol in kalibratorjev v dveh stolpcih z loti, roki in količino.")]),
      p([b("Barva rokov v PDF "), tx("je enaka kot v aplikaciji: "), new TextRun({ text: "pretečen rdeče", bold: true, color: "C00000" }), tx(", "), new TextRun({ text: "≤14 dni oranžno", bold: true, color: "E8730C" }), tx(", "), new TextRun({ text: "≤28 dni rumeno", bold: true, color: "B8860B" }), tx(".")]),

      // 10 OPERATERJI
      h1("10. Več → Operaterji"),
      img("10_operaterji_head.png"),
      caption("Kartica Operaterji – imenik (matična številka + ime)"),
      p([tx("Imenik vsebuje "), b("vse operaterje"), tx(" (matična številka in ime), ki se uporabljajo pri podpisu PDF poročila. Imenik je ob namestitvi že napolnjen (vir: seznam zaposlenih CS5100).")]),
      li(null, [b("Dodaj: "), tx("vpišite matično (5 mest) in ime, nato “+ Dodaj”.")]),
      li(null, [new TextRun({ text: "Uredi (✎): ", bold: true }), tx("napolni polji z obstoječimi podatki; po popravku kliknite “Shrani”.")]),
      li(null, [new TextRun({ text: "Izbriši (🗑): ", bold: true }), tx("odstrani operaterja iz imenika.")]),
      p([new TextRun({ text: "Opomba: ", italics: true }), new TextRun({ text: "v prihodnosti se bo imenik samodejno sinhroniziral z aplikacijo BIO Alinity.", italics: true })]),

      // 11 SINHRONIZACIJA
      h1("11. Več → Sinhronizacija in izvozi"),
      h2("11.1 Nastavitve"),
      img("07_vec_top.png"),
      caption("Zavihek Več – nastavitve"),
      li(null, [b("Temni način "), tx("– temno ozadje za delo v slabši svetlobi.")]),
      li(null, [b("Serijski sprejem "), tx("– okno sprejema ostane odprto za zaporedni vnos več artiklov.")]),
      li(null, [b("Jezik / Language "), tx("– SL / EN / HR-SR.")]),
      li(null, [b("Moč RFID – Sprejem materiala (dBm): "), tx("priporočeno 0–3 dBm (manjši doseg = manj tujih oznak). Gumb “Test” preveri trenutno moč.")]),
      li(null, [b("Izbira RFID oznake pri sprejemu: "), tx("prag in dominanca poskrbita, da se zajame le najbližja (prava) oznaka.")]),
      li(null, [b("Moč RFID – MultiRFID (dBm): "), tx("priporočeno 15–30 dBm za množično branje pri sejah inventure.")]),
      h2("11.2 Sinhronizacija med napravama"),
      li(null, [b("Quick Share "), tx("– deljenje zaloge neposredno z bližnjo Zebro.")]),
      li(null, [b("OneDrive "), tx("– prijava z računom @kclj.si, nalaganje in združevanje podatkov.")]),
      li(null, [b("Uvozi iz datoteke "), tx("– naloži prejeto .json datoteko.")]),
      h2("11.3 Izvoz podatkov"),
      p("Zaloga CSV, Zaloga BK PDF, Roki CSV, Seje CSV, GTIN CSV."),

      // 12 POGOSTA VPRAŠANJA
      h1("12. Pogosta vprašanja"),
      p([b("Kako zaključim sejo inventure?")]),
      p("Med aktivno sejo v zavihku Sken. izberite zaključek seje – skenirani artikli se zapišejo v zalogo."),
      p([b("Zakaj se zajame napačna RFID oznaka?")]),
      p("Zmanjšajte moč RFID za sprejem (Več → Moč RFID – Sprejem materiala) na 0–3 dBm in oznako prislonite tik ob čitalnik."),
      p([b("Sprejeti artikel nima RFID oznake – kaj zdaj?")]),
      p([tx("Uporabite "), b("“Sprejmi brez RFID/SN (ročno)”"), tx(" (poglavje 4.2); aplikacija ustvari nadomestno serijsko številko (MAN…), količina se vseeno poveča za 1.")]),
      p([b("Kje najdem PDF poročilo?")]),
      p([tx("V mapi Prenosi (Downloads) na Zebri, z imenom "), b("PregledBK_LLLL-MM-DD.pdf"), tx(".")]),
      p([b("RFID skener je ugasnjen – kako ga vključim?")]),
      p("Pridrži trigger (rumeni gumb) na čitalcu RFD2000 5 sekund in se bo skener samodejno prižgal."),

      // 13 NAMESTITEV
      h1("13. Namestitev in posodobitve"),
      p("Aplikacija se gradi samodejno na GitHub Actions (CI). Nova različica (APK) se prenese iz artefaktov in namesti na Zebro prek ADB (install -r, ohrani podatke). Trenutna različica: Build " + BUILD + "."),

      // 14 ZGODOVINA
      h1("14. Zgodovina različic"),
      new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: [900, 1500, 6960],
        rows: [
          new TableRow({ tableHeader: true, children: [
            cell("Build", 900, "1F3864", true), cell("Datum", 1500, "1F3864", true), cell("Spremembe", 6960, "1F3864", true),
          ]}),
          histRow("114", DATE, "OneDrive: device-code za osebne (Hotmail) račune prek tenant “consumers” → koda na microsoft.com/link. Operaterji: samo branje iz OneDrive."),
          histRow("113", "15.6.2026", "Quick Share / sinhronizacija: združevanje zaloge po EPC (pravi unikat RFID) — brez izgubljenih ali podvojenih kosov."),
          histRow("112", "14.6.2026", "PDF BK: barvni roki izteka (rdeče/oranžno/rumeno) z legendo. Operaterji: gumb za urejanje (✎)."),
          histRow("111", "14.6.2026", "Vgrajen imenik 129 operaterjev. PDF dialog: številčnica (PIN) + skeniranje matične; izpis imena."),
          histRow("110", "14.6.2026", "Sprejem brez RFID/SN (ročno) za artikle brez oznake. Gumb “Počisti vnos”."),
          histRow("109", "14.6.2026", "Operaterji + podpis PDF (matična → ime “Pripravil”). Datum pregleda z uro."),
          histRow("108", "14.6.2026", "Gumb PDF BK: tedensko PDF poročilo zaloge kontrol in kalibratorjev."),
        ],
      }),
      new Paragraph({ spacing: { before: 200 }, children: [new TextRun({ text: "Dokument se posodablja ob vsaki nadgradnji aplikacije (zadnja: Build " + BUILD + ", " + DATE + ").", italics: true, size: 18, color: "888888" })] }),

      // header color note (table header text white)
    ],
  }],
});

const outDir = process.argv[2] || __dirname;
const outFile = path.join(outDir, "PregledBK_Navodila.docx");
Packer.toBuffer(doc).then(buf => { fs.writeFileSync(outFile, buf); console.log("WROTE " + outFile + " (" + buf.length + " bytes)"); });
