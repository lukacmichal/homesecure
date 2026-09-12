# HomeSecure

Strážna appka na starý Android telefón. Skenuje domácu Wi-Fi, a keď na nej nie je
ani jedno z tvojich zariadení, zapne kameru s detekciou pohybu a fotky pošle mailom.
Keď sa do nastaveného času po odfotení pripojíš na Wi-Fi, fotky sa zahodia — lebo
to si bol ty.

Telefón pritom môže ďalej slúžiť na hudbu: appka beží ako služba na pozadí,
nie ako kiosk, a kameru drží iba vtedy, keď naozaj stráži.

---

## Ako to funguje

```
sken siete každých 15 s
        │
        ├─ niekto z dôveryhodných zariadení odpovedá  ──►  DOMA (kamera vypnutá)
        │
        └─ 4 kolá po sebe neodpovedá nikto           ──►  STRÁŽI SA
                                                            │
                                              detekcia pohybu│
                                                            ▼
                                                   3 fotky do karantény
                                                            │
                                        ┌───────────────────┴───────────────────┐
                                        │                                       │
                        pripojíš sa na Wi-Fi do 30 s              okno uplynulo
                                        │                                       │
                                        ▼                                       ▼
                              fotky sa zmažú,                        fotky idú mailom
                              stráženie sa vypne
```

Nesymetria je zámerná. **Odchod** sa vyhlási až po štyroch neúspešných kolách,
lebo telefón v spánku občas ping zahodí a falošný poplach je horší než minúta
oneskorenia. **Návrat** stačí jedna úspešná odpoveď — vtedy sa ponáhľame, lebo
beží ochranné okno nad už spravenými fotkami.

Kým je stráženie zapnuté, skenuje sa každé 3 s namiesto 15 s, aby sa tvoj príchod
zachytil čo najskôr.

---

## Zostavenie a inštalácia

```bash
cd C:\NAS\homesecure && .\gradlew.bat assembleDebug
```

APK vypadne ako `app\build\outputs\apk\debug\homesecure.apk`.

Inštalácia cez USB:

```bash
adb install -r C:\NAS\homesecure\app\build\outputs\apk\debug\homesecure.apk
```

> **Dôležité pre self-update:** APK na NAS-e musí byť podpísané **tým istým kľúčom**
> ako to, čo máš nainštalované, inak Android aktualizáciu odmietne s chybou o
> nesúhlasiacom podpise. Najjednoduchšie je zostávať pri `assembleDebug` — debug
> keystore (`~/.android/debug.keystore`) je stabilný a nemení sa. Ak neskôr prejdeš
> na release build, musíš si vyrobiť vlastný keystore a odvtedy ním podpisovať vždy.

---

## Nastavenie telefónu

Po prvom spustení appka sama vypíše, čo ešte chýba, a ku každej položke dá tlačidlo.
Prejsť treba všetkých päť:

| Povolenie | Prečo |
|---|---|
| Kamera | Bez nej sa nedá fotiť. |
| Notifikácie | Bez nich nesmie bežať služba na pozadí. |
| Zobrazovanie cez iné aplikácie | Aby sa po reštarte telefónu rozbehla kamera sama (viď nižšie). |
| Vypnutá optimalizácia batérie | Inak systém službu skôr či neskôr uspí. |
| Inštalácia neznámych aplikácií | Kvôli aktualizáciám z NAS-u. |

Ďalej:

- **Nechaj telefón na nabíjačke.** Nabíjaný telefón nevstupuje do Doze režimu, čo je
  zďaleka najspoľahlivejší spôsob, ako udržať 24/7 beh.
- Ak je to Xiaomi, Samsung alebo Huawei, nájdi si v nastaveniach batérie ešte
  „autostart" / „neobmedzené na pozadí" a povoľ ich. Títo výrobcovia zabíjajú služby
  nad rámec toho, čo robí čistý Android; appka má proti tomu watchdog budík, ale
  prevencia je lepšia.

### Prečo „zobrazovanie cez iné aplikácie"

Android 11+ odopiera prístup ku kamere službám, ktoré vznikli na pozadí — a po
reštarte telefónu je appka práve v takej situácii. Rieši sa to neviditeľnou
aktivitou (`TrampolineActivity`), ktorá proces na zlomok sekundy vytiahne dopredu
a až odtiaľ službu naštartuje. Spustenie aktivity z pozadia ale samo osebe tiež
vyžaduje toto povolenie. Bez neho bude appka po reštarte skenovať sieť, ale kameru
otvorí až keď ju raz ručne otvoríš.

---

## Dôveryhodné zariadenia

V nastaveniach, jeden riadok = jedno zariadenie. Buď s menom, alebo len holá IP:

```
Mobil=192.168.1.50
Mobil-partner=192.168.1.50
192.168.1.50
```

Ak nie je zadané ani jedno zariadenie, appka to napíše červeným na hlavnej
obrazovke — stráženie sa v takom stave zámerne nikdy nezapne, lebo sa nedá
rozhodnúť, či si doma. Pod stavom vždy vidno, ktoré adresy si appka zo zoznamu
naozaj prečítala.

Dve veci, na ktorých to inak stroskotá:

1. **Rezervuj si IP na routeri cez DHCP.** Bez rezervácie sa adresa časom zmení
   a stráženie sa zapne, aj keď si doma.
2. **Vyrieš randomizáciu MAC adresy.** Android si pre každú Wi-Fi generuje náhodnú
   MAC. Pre domácu sieť to v nastaveniach Wi-Fi prepni na *„Použiť MAC adresu
   zariadenia"* — inak nebude mať router čo rezervovať. (Randomizovaná MAC je per-SSID
   stabilná, takže alternatívne si ju môžeš raz odpísať a rezervovať tú.)

Ak máš na Wi-Fi zapnutú **izoláciu klientov** (býva na hosťovských sieťach), telefóny
sa navzájom nevidia a detekcia nebude fungovať vôbec. Vypni ju, alebo daj obe
zariadenia na tú istú neizolovanú sieť.

Overiť sa to dá tlačidlom **„Test: nájdi moje zariadenia"** — vypíše do denníka,
ktoré IP odpovedali.

Detekcia skúša tri nezávislé spôsoby (ICMP ping → TCP zaklopanie → systémový
`ping`), lebo každý sám o sebe niekde zlyhá. Čítanie ARP tabuľky Android bez rootu
nedovolí, preto sa pinguje aktívne.

---

## Mail

Gmail nepustí bežné heslo k účtu — treba **app password**
(Google účet → Zabezpečenie → Dvojstupňové overenie → Heslá aplikácií).

**Použi na odosielanie samostatný Gmail účet.** Keby ti niekto ten telefón zobral,
dostane sa len k odosielaciemu účtu, nie k tvojej hlavnej schránke. Heslo je uložené
v šifrovaných `EncryptedSharedPreferences` a zálohovanie appky je vypnuté
(`allowBackup="false"`), takže telefón neopustí.

Adresa príjemcu je v nastaveniach voľne meniteľná, viac adries oddeľ čiarkou.

Ak je výpadok internetu, fotky sa nestratia — čakajú vo fronte na disku a odošlú sa,
keď sa spojenie vráti. Fronta má strop 60 udalostí / 250 MB, potom sa zahadzujú
najstaršie.

---

## Aktualizácie z NAS-u

Rovnaká konvencia ako WakeNB. Na Synology:

```
\\nas.local\Android\HomeSecure\homesecure.apk
```

Súbor má pevný názov a pri novom vydaní ho len prepíšeš. Verzia sa nečíta z názvu,
ale priamo z APK, takže sa nedá rozísť s realitou.

Vydanie novej verzie:

1. V `app/build.gradle.kts` zvýš `versionCode` (a podľa chuti `versionName`).
2. `.\gradlew.bat assembleDebug`
3. Skopíruj `homesecure.apk` do `Android\HomeSecure\` na NAS-e.

Appka kontroluje NAS každých 6 hodín (nastaviteľné) a keď nájde vyššiu verziu,
pošle notifikáciu. Inštaláciu treba potvrdiť ťuknutím — tichú inštaláciu vie len
device owner, čo tento telefón zámerne nie je, aby mohol ďalej slúžiť na hudbu.
Ručne sa dá skontrolovať tlačidlom v hlavnej obrazovke.

---

## Ladenie

Všetko podstatné ide do **denníka** na hlavnej obrazovke (prežije aj reštart procesu).

| Problém | Čo s tým |
|---|---|
| Chodia fotky vlastného príchodu | Zvýš **ochranné okno** na 45–60 s. 30 s je tesných — kým telefón po vstupe do dosahu asociuje na AP a dostane DHCP, ľahko ubehne 10–20 s. |
| Plané poplachy od tieňov a záclon | Zníž **citlivosť**. Detektor už sám odpočítava globálnu zmenu jasu, takže rozsvietenie ani mrak pred slnkom by ho spúšťať nemali. |
| Stráženie sa zapne, aj keď si doma | Telefón zaspáva hlbšie, než čakáme — zvýš **počet neúspešných kôl**, prípadne over DHCP rezerváciu. |
| Appka tvrdí „doma", hoci tú IP nepinguješ | Pozri sa v denníku, **ktorá metóda** odpovedala — riadok vyzerá `tel odpovedá (ICMP)`. Ak je tam `TCP:80`, odpovedá router za neexistujúcu adresu; vypni v nastaveniach *„Brať odmietnuté TCP spojenie ako prítomnosť"*. Ak je tam `ICMP`, na tej adrese naozaj niečo je — najskôr ju medzitým dostalo iné zariadenie z DHCP. |
| Nočné fotky sú čierne | Zapni **prisvietenie bleskom**. Cena je, že záblesk prezradí, že tam niečo je. |
| Fotky sú malé / neostré | Zvýš **šírku fotky** (0 = maximum kamery). Denník po štarte kamery píše skutočne vybrané rozlíšenie — požadovaná hodnota je len návrh, kamera vracia najbližšie podporované. Na rozoznanie tváre z 3 – 4 m chceš aspoň 1920. |

---

## Čo appka nerieši

- **Prestrihnutý internet.** Fotky sa uložia a odošlú neskôr, ale v tej chvíli
  upozornenie nedostaneš. Ak ti na tom záleží, dá sa dorobiť zrkadlenie fotiek
  priamo na NAS.
- **Vypnutý router alebo prúd.** Bez Wi-Fi sa sken zastaví; už bežiace stráženie
  pokračuje a fotí ďalej, ale stav sa nemení a maily čakajú.
- **Telefón niekto vypne.** Nič proti tomu nemáme.
- **Návšteva, ktorá príde bez teba.** Systém nevie rozlíšiť votrelca od kamaráta
  s kľúčmi — odfotí oboch.

Kamera mieri do tvojho bytu, takže ide o čisto osobnú činnosť a GDPR sa na to
nevzťahuje. Pozor len na to, aby záber nezachytával spoločnú chodbu alebo susedov
pozemok — tam už by to platilo.

---

## Štruktúra

| Súbor | Čo robí |
|---|---|
| `MonitorService.kt` | Orchestrátor — štyri slučky: sken siete, karanténa, odosielanie, aktualizácie |
| `PresenceMonitor.kt` | Prevod výsledkov pingu na „doma / preč" vrátane vyhladzovania |
| `Probe.kt` | Tri nezávislé spôsoby, ako zistiť, či IP žije |
| `MotionDetector.kt` | Porovnávanie jasu snímkov s odpočítaním globálnej zmeny |
| `CameraController.kt` | CameraX — analýza + fotenie |
| `EventStore.kt` | Fotky na disku, presun karanténa → outbox cez atomický rename |
| `MailSender.kt` | SMTP |
| `SmbUpdater.kt` / `ApkInstaller.kt` | Self-update z NAS-u |
| `TrampolineActivity.kt` | Obchádzka obmedzenia kamery po reštarte |
| `WatchdogReceiver.kt` | Budík, ktorý oživí zabitú službu |
