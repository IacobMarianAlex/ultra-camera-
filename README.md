# UltraCam 4K: fotografie computațională pentru Android

Aplicație independentă (fără internet, fără servere, fără biblioteci externe). Când apeși declanșatorul, face o **rafală de 8–28 de cadre** și le combină într-o singură poză **4K** (3840×2880 la 4:3 sau 3840×2160 la 16:9, landscape sau portret automat).

## Ce face procesarea, pas cu pas
1. **Captură inteligentă**: măsoară lumina și alege singură modul: *Zi HDR* (cadre subexpuse cu -1 EV ca să nu se ardă cerul), *Lumină slabă* sau *Noapte* (expuneri manuale mai lungi la ISO mai mic, dacă telefonul permite).
2. **Cadru de referință**: dintre primele cadre îl alege pe cel mai clar.
3. **Aliniere pe 5 niveluri** (piramidă, pe zone de 32 px, precizie sub-pixel): anulează tremurul mâinii.
4. **Fuziune robustă**: fiecare pixel e mediat doar acolo unde cadrele se potrivesc (luminozitate și culoare), așa că obiectele în mișcare nu lasă „fantome”. Cadrele mișcate contează mai puțin.
5. **Zero zgomot**: denoise pe mai multe scări (4 benzi, zgomotul măsurat din imagine) plus eliminarea punctelor izolate, pe luminozitate și pe culoare.
6. **Hartă de adâncime** (aproape/fundal): din paralaxa dintre cadre și din harta de focalizare.
7. **Recuperare claritate**: deconvoluție Richardson-Lucy pe subiect, plus sharpening care nu scoate zgomotul înapoi.
8. **Bokeh full-frame**: amplifică estomparea fundalului doar unde e deja ieșit din focus.
9. **HDR local + randare filmică**: umbre ridicate, lumini păstrate, tranziție a luminilor ca la camerele mari, balans de alb fin, vibrance.
10. **Profil per telefon**: RAM, nuclee, mărimea pixelului, OIS și nivelul Camera2 decid câte cadre, ce expunere și cât denoise. Galaxy A05 (SM-A055F) are profil dedicat.

## Cum obții APK-ul

### Varianta 1: GitHub (fără nimic instalat pe PC)
1. Creează un repository nou pe GitHub și urcă tot conținutul acestui folder (inclusiv `.github/`).
2. Mergi la tab-ul **Actions**. Build-ul pornește singur (sau apasă *Run workflow*).
3. După ~5 minute, deschide rularea și descarcă **UltraCam-APK** de la *Artifacts*.
4. Dezarhivează, copiază `app-release.apk` pe telefon și instalează-l (permite „surse necunoscute”).
   Sau de pe PC: `adb install -r app-release.apk`

### Varianta 2: Android Studio
Deschide folderul → așteaptă sincronizarea Gradle → *Build → Build APK(s)*.

## Folosire
- **AUTO / NOAPTE / MAX**: MAX face cele mai multe cadre și are cea mai lungă procesare.
- **4K 4:3 / 4K 16:9**, **ZERO ZGOMOT**, **BOKEH FF**, **⟲** (camera frontală).
- Ține telefonul nemișcat ~1–3 secunde cât face rafala. După aceea îl poți mișca liber, procesarea continuă (30–90 s pe un telefon de buget).
- Atinge miniatura: **ține apăsat pe poză** ca să vezi originalul (un singur cadru) față de rezultat.
- Pozele se salvează în **DCIM/UltraCam**.

## Testare pe PC (opțional)
`tools/simulate_burst.py` generează o rafală simulată (tremur, zgomot, obiect în mișcare), iar `tools/RunPipeline.java` rulează exact motorul din aplicație pe ea.
