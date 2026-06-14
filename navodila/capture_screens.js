/*
 * Zajem zaslonov aplikacije Pregled BK za navodila.
 * Naloži pregled_bk.html v glavo brez glave (Edge/Chromium), vstavi vzorčne podatke
 * in shrani posnetek vsakega zaslona v navodila/screens/*.png.
 *
 * Zagon:  node capture_screens.js
 * V CI:   nastavi PUPPETEER_EXECUTABLE_PATH na nameščen Chromium.
 *
 * Posnetki se uporabijo v generate_navodila.py za sestavo Word datoteke.
 */
const fs = require('fs');
const path = require('path');
const http = require('http');
const puppeteer = require('puppeteer-core');

const ASSETS = path.resolve(__dirname, '..', 'app', 'src', 'main', 'assets');
const OUT = path.resolve(__dirname, 'screens');
const PORT = 8799;

// Najdi Chromium/Chrome/Edge
function findBrowser() {
  if (process.env.PUPPETEER_EXECUTABLE_PATH) return process.env.PUPPETEER_EXECUTABLE_PATH;
  const cands = [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium-browser', '/usr/bin/chromium',
  ];
  for (const c of cands) { if (fs.existsSync(c)) return c; }
  throw new Error('Ni najden Chromium/Chrome/Edge. Nastavi PUPPETEER_EXECUTABLE_PATH.');
}

const MIME = { '.html':'text/html', '.js':'application/javascript', '.css':'text/css',
  '.png':'image/png', '.json':'application/json', '.svg':'image/svg+xml', '.ico':'image/x-icon' };

function serve() {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      let p = decodeURIComponent(req.url.split('?')[0]);
      if (p === '/') p = '/pregled_bk.html';
      const fp = path.join(ASSETS, p);
      if (!fp.startsWith(ASSETS) || !fs.existsSync(fp)) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream' });
      fs.createReadStream(fp).pipe(res);
    });
    srv.listen(PORT, () => resolve(srv));
  });
}

// Vzorčni podatki — različni roki za prikaz barvnih oznak
function sampleData() {
  const today = new Date();
  const fmt = (o) => { const d = new Date(today); d.setDate(d.getDate() + o); return d.toISOString().slice(0, 10); };
  const inv = [
    { gtin:'07613336123456', name:'ALT (GPT) Reagent', type:'reagent',    lot:'A2401', expiry:fmt(-6),  qty:3 },
    { gtin:'07613336123463', name:'Glukoza Reagent',   type:'reagent',    lot:'G1182', expiry:fmt(9),   qty:5 },
    { gtin:'07613336123470', name:'Kreatinin Reagent', type:'reagent',    lot:'K3320', expiry:fmt(22),  qty:8 },
    { gtin:'07613336123487', name:'TSH Reagent',       type:'reagent',    lot:'T9901', expiry:fmt(120), qty:12 },
    { gtin:'07613336123494', name:'Multichem kontrola L1', type:'control', lot:'C5501', expiry:fmt(45),  qty:4 },
    { gtin:'07613336123500', name:'Kalibrator CK',     type:'calibrator', lot:'KAL77', expiry:fmt(200), qty:2 },
  ];
  return {
    gtins: inv.map(i => ({ gtin:i.gtin, name:i.name, type:i.type })),
    inventory: inv,
    received: inv.map((i, n) => ({ gtin:i.gtin, name:i.name, type:i.type, lot:i.lot, expiry:i.expiry,
      receivedAt: new Date(today.getTime() - n*86400000).toISOString() })),
    lots: inv.map(i => ({ gtin:i.gtin, lot:i.lot, name:i.name, type:i.type, expiry:i.expiry })),
    sessions: [], activeSessionId: null, lastUsed: [], operators: [],
  };
}

const SCREENS = [
  { nav:'scan',      file:'01_skeniranje.png' },
  { nav:'inventory', file:'02_zaloga.png' },
  { nav:'lots',      file:'03_loti.png' },
  { nav:'expiry',    file:'04_roki.png' },
  { nav:'gtins',     file:'05_gtin.png' },
  { nav:'more',      file:'06_vec.png' },
];

(async () => {
  if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });
  const srv = await serve();
  const userDataDir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'pbk-cap-'));
  const browser = await puppeteer.launch({
    executablePath: findBrowser(), headless: 'new', userDataDir,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--no-first-run', '--no-default-browser-check'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 411, height: 731, deviceScaleFactor: 2 });
  await page.goto(`http://127.0.0.1:${PORT}/pregled_bk.html`, { waitUntil: 'networkidle0' });

  // Vstavi vzorčne podatke in osveži
  await page.evaluate((data) => { localStorage.setItem('pbk_tc20', JSON.stringify(data)); }, sampleData());
  await page.reload({ waitUntil: 'networkidle0' });

  for (const s of SCREENS) {
    await page.evaluate((n) => { if (typeof nav === 'function') nav(n); }, s.nav);
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: path.join(OUT, s.file) });
    console.log('zajeto:', s.file);
  }

  await browser.close();
  srv.close();
  console.log('Končano. Posnetki v', OUT);
})().catch((e) => { console.error(e); process.exit(1); });
