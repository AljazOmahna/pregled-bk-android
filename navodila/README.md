# Navodila Pregled BK — sistem za samodejno posodabljanje

Navodila se sestavijo iz besedilnega vira in posnetkov zaslonov, zajetih
neposredno iz aplikacije, zato ostanejo usklajena z vsako novo različico.

## Datoteke

| Datoteka | Namen |
|---|---|
| `NAVODILA.md` | Vir besedila navodil (urejaj tukaj). |
| `screens/*.png` | Posnetki zaslonov, zajeti iz aplikacije. |
| `capture_screens.js` | Zajme zaslone iz `pregled_bk.html` (puppeteer-core). |
| `generate_navodila.py` | Sestavi `Navodila_PregledBK.docx` iz vira + posnetkov. |
| `Navodila_PregledBK.docx` | Končni Word dokument. |

## Samodejno posodabljanje (GitHub Actions)

Ob vsakem `push` v `main`, ki spremeni `navodila/**` ali
`app/src/main/assets/pregled_bk.html`, se sproži `.github/workflows/navodila.yml`:
zajame posnetke, zgradi `.docx` in ga naloži kot artifact **Navodila_PregledBK**.
Različica aplikacije (build) se samodejno prebere iz `pregled_bk.html`.

## Ročna izdelava (lokalno)

```bash
cd navodila
npm install puppeteer-core      # enkratno
node capture_screens.js         # osveži posnetke (uporabi nameščen Chrome/Edge)
python generate_navodila.py     # zgradi Navodila_PregledBK.docx
```

Za zajem brez sistemskega brskalnika nastavi `PUPPETEER_EXECUTABLE_PATH`.
