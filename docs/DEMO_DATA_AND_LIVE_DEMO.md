# Test podaci i živa demonstracija: kako da prezentacija "živi"

> Ideja u jednoj rečenici: **istorija od 30 dana se generiše jednom komandom (seed), a tokom same prezentacije
> skripta pravi *pravi* saobraćaj. Publika vidi grafikone sa podacima, a zatim gleda kako se brojevi menjaju uživo.**

```mermaid
flowchart LR
    subgraph Pre["⏰ Ujutru, na dan prezentacije"]
        S["▶ AetherGate Seed demo data<br/>(--aethergate.tool=seed)"]
    end
    subgraph Tokom["🎤 Tokom prezentacije"]
        L["scripts/live-demo.sh<br/>5 scena"]
        T["scripts/live-demo.sh traffic 40<br/>pozadinski saobraćaj"]
    end
    DB[("Supabase Postgres<br/>ledger · logovi · keš")]
    UI["React konzola<br/>Overview · Ledger · Playground"]
    S -->|"briše i ponovo puni<br/>SAMO 3 seed tenanta"| DB
    L -->|"pravi /v1 zahtevi"| DB
    T --> DB
    DB --> UI
```

---

## 1. Istorijski podaci: seed alat

### Šta generiše

| | Vortex AI Labs | Helix Robotics | Nimbus Health |
|---|---|---|---|
| Zahteva u 30 dana | 1.450 | 900 | 1.200 |
| Provajderi (primarni → rezervni) | gpt-4o → claude-sonnet-4-5 | claude-haiku-4-5 → gemini-2.5-flash | gpt-4o-mini → gemini-2.5-flash |
| Keš HIT-ova (realno ~20%) | ~300 | ~190 | (vidi izlaz seed-a) |
| Failover-a (~3%) i ispada oba provajdera (~0,4%) | ✔ | ✔ | ✔ |
| Dopune kredita | $100 početno + $50 karticom | $100 | $100 |
| Projekti (cost centri) | Support Copilot $5 · Docs Search $1,50 · Growth Experiments bez limita · Legacy Chatbot *arhiviran* | Fleet Telemetry $2 · Maintenance Assistant $0,80 | Patient Messaging $0,25 · Clinical Coding $0,12 · Research Sandbox |

### Zašto podaci izgledaju *stvarno*, a ne nasumično

```
 saobraćaj po satu (radni dan)                 cena po zahtevu
  ▁▁▁▂▃▅▇██▇▆▇██▇▅▃▂▁▁                          tačno po cenovniku modela
  00h      12h      23h                         (CostCalculator, ISTA klasa kao gateway)
```

- **Dnevni ritam:** vrh oko 14h (UTC), noću ~15%, a vikendom ~45% saobraćaja radnog dana.
- **Rast:** saobraćaj blago raste kroz 30 dana (70% → 130%), kao kod proizvoda koji dobija korisnike.
- **Realan keš:** 60% promptova dobija jedinstven sufiks `(ref #48213)`, kao korisnik koji pita o konkretnom
  tiketu. Takav prompt se nikad ne ponavlja i ne može biti keširan. Rezultat je ~20% HIT-ova, a ne nerealnih 70%.
- **Ista pravila kao produkcija:** seed rezerviše po najskupljem provajderu u lancu, pa naplaćuje i vraća ostatak.
  Svaki zahtev u ledger-u ima tačno `RESERVE = SETTLE + RELEASE`.
- **Deterministički:** `Random` ima fiksni seed po tenantu, pa svaki put nastaju isti brojevi.
- **Budžeti projekata:** seed ih poštuje isto kao gateway. Zahtev koji bi prešao budžet se preskače, jer bi gateway
  vratio 402. Zato su neki projekti blizu limita (npr. Docs Search ~99%), a nijedan nije preko.
- **Arhiviran projekat:** "Legacy Chatbot" dobija saobraćaj samo prvih ~35% meseca, pa se arhivira; posle toga nema hold-ova.
- **Topli keš:** promptovi iz poslednjih 24h ostaju u semantičkom kešu, pa ih Playground odmah vraća kao HIT.

### Bezbednost: šta seed briše

Seed briše i ponovo puni **samo tri seed tenanta** (Vortex, Helix, Nimbus), po fiksnim UUID-ovima, u jednoj transakciji.
Pravi tenanti i drugi projekti u istoj bazi se ne diraju. Na kraju se automatski pokreće
**audit od 15 provera**, na primer:
- da li je ledger izbalansiran;
- da li se saldo poklapa sa zbirom stavki;
- da li RLS skriva tuđe redove.

```
>> Invariant audit
  PASS  1. Funds equation: TOP_UP - SETTLE = available + reserved (every tenant)
  PASS  2. Holds: RESERVE = SETTLE + RELEASE + open reservations
  ...
  PASS  7. RLS isolation across all tenant tables     2136 foreign ledger rows exist, 0 visible
  PASS  8. Ledger is append-only (UPDATE rejected by trigger)
  ...
  10/10 checks passed
```

### Kako se pokreće (3 načina)

| Gde | Kako |
|---|---|
| **IntelliJ** | Run konfiguracija **AetherGate Seed demo data**: ▶ (proces ispiše izveštaj i audit, pa se sam ugasi) |
| Terminal | `cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=--aethergate.tool=seed` |
| Produkcijski server | `ssh ubuntu@138.2.169.195 'cd ~/aethergate/deploy && sudo docker compose run --rm gateway --aethergate.tool=seed'` |

Samo provera, bez upisa: konfiguracija **AetherGate Audit ledger** (`--aethergate.tool=audit`).

> ⏰ **Pokreni seed ujutru na dan prezentacije.** Podaci su relativni u odnosu na *sada*: poslednjih 30 dana,
> a keš važi 24h. Stari seed bi pokazivao "prazan danas" na grafikonu od 24h.

---

## 2. Živa demonstracija: `scripts/live-demo.sh`

Skripta ima 5 scena. Svaka dokazuje jednu tvrdnju i čeka **Enter**, da bi ti pričao dok publika gleda.

```bash
./scripts/live-demo.sh                             # nad živim Oracle API-jem
API=http://localhost:8088 ./scripts/live-demo.sh   # nad lokalnim backend-om iz IntelliJ-a
./scripts/live-demo.sh traffic 40                  # samo 40 zahteva, da se dashboard pomera
```

| Scena | Šta publika vidi (stvaran izlaz) | Šta dokazuje |
|---|---|---|
| **1 · Streaming i metering** | tekst se "kuca" token po token, zatim `model=gpt-4o RESERVE $0.00775200 = SETTLE $0.00159000 + RELEASE $0.00616200` | Dvofazna naplata, tačna do 8 decimala |
| **2 · Semantički keš** | `X-Cache: HIT`, cena $0 | pgvector pronalazi isto pitanje |
| **3 · Haos** | `served by: claude-sonnet-4-5 (primary is gpt-4o)` · `attempts: OPENAI: circuit OPEN, skipped` | Failover pre prvog tokena; circuit breaker |
| **4 · Rate limit** | `#1-3 → 200`, `#4 → 429 Retry-After: 14s` | Token bucket po API ključu (privremeni ključ se zatim briše) |
| **5 · Dokaz u brojevima** | saldo, rezervisano i `2082 foreign rows in DB · 0 visible` | Row-Level Security u bazi |

```mermaid
sequenceDiagram
    participant P as Prezenter (terminal)
    participant G as Gateway
    participant UI as Konzola (browser, drugi ekran)
    P->>G: scena 1: novi prompt
    G-->>P: stream + RESERVE/SETTLE/RELEASE
    Note over UI: Ledger stranica: pojavljuju se 3 nove stavke
    P->>G: scena 2: isti prompt
    G-->>P: X-Cache: HIT · $0
    Note over UI: Overview: "Saved by cache" raste
    P->>G: scena 3: X-Simulate-Provider-Failure
    G-->>P: failover → claude-sonnet-4-5
    Note over UI: Playground: OpenAI breaker = OPEN (30 s)
```

> **Savet za scenu:** otvori konzolu na drugom ekranu (Ledger ili Overview) i osvežavaj je posle svake scene.
> Publika vidi da terminal i dashboard pričaju istu priču iz iste baze.

---

## 3. Bez terminala: sve se može i kliktanjem u konzoli

1. https://aethergate-console.vercel.app → **Continue with Demo Workspace** otvara Vortex tenant sa 30 dana istorije.
2. **Playground & Chaos:** pošalji prompt, pa isti ponovo (keš HIT ⚡). Zatim uključi *Simulate provider failure*,
   pa breaker prelazi u OPEN i dolazi do failover-a.
3. **Ledger Audit:** filtriraj po `request_id` i pokaži tri stavke istog zahteva.
4. **Settings (ključevi i rutiranje):** prevuci provajdere da promeniš prioritet, napravi ključ sa malim RPM-om, pa ga opozovi.

---

## 4. Tri tenanta, tri odvojena login-a: dokaz multi-tenancy-ja uživo

| Tenant | Kako se ulazi | Provajderi (primarni → rezervni) | Istorija |
|---|---|---|---|
| **Vortex AI Labs** | dugme *Continue with Demo Workspace* | gpt-4o → claude-sonnet-4-5 | 1.450 zahteva |
| **Helix Robotics** | email + lozinka (`helix.admin@…`) | claude-haiku-4-5 → gemini-2.5-flash | 900 zahteva |
| **Nimbus Health** | email + lozinka (`nimbus.admin@…`) | gpt-4o-mini → gemini-2.5-flash | 1.200 zahteva |

> Lozinke se **ne nalaze u repou**. Čuvaju se lokalno, van gita.

```mermaid
flowchart LR
    U1["👤 demo@…"] -->|"JWT sub = A"| G
    U2["👤 helix.admin@…"] -->|"JWT sub = B"| G
    U3["👤 nimbus.admin@…"] -->|"JWT sub = C"| G
    G["Spring Security<br/>proverava ES256 potpis (JWKS)"] --> R["tenants.owner_user_id = sub<br/>→ tenant_id"]
    R --> TX["transakcija:<br/>SET app.current_tenant_id<br/>SET ROLE aethergate_app"]
    TX --> DB[("PostgreSQL RLS<br/>USING tenant_id = current_tenant_id()")]
```

**Kako to radi:**
- Supabase Auth izdaje JWT čiji je `sub` ID korisnika.
- Backend proverava potpis i pronalazi tenant preko `tenants.owner_user_id`. Unique indeks garantuje jedan tenant po korisniku.
- Zatim **u istoj transakciji** prebacuje bazu na tog tenanta i ulogu bez `BYPASSRLS`.
- Čak i da upit u kodu zaboravi `WHERE tenant_id = …`, PostgreSQL sam ne vraća tuđe redove.

**Scenario za prezentaciju (2 minuta):**
1. Normalan prozor: login kao **Helix**. Pokaži saldo, modele (claude-haiku) i Ledger.
2. Incognito prozor: login kao **Nimbus**. Pokaži potpuno druge brojeve i modele (gpt-4o-mini).
3. U oba prozora na Overview-u stoji *"N foreign ledger rows in DB · 0 visible"*. Podaci su u istoj tabeli, a ipak nevidljivi.
4. U Helix-u pošalji prompt iz Playground-a, pa osveži Nimbus: tamo se ništa nije promenilo.
5. Bonus: pošalji isti prompt u oba tenanta. Keš je **po tenantu**, pa drugi tenant dobija MISS, a ne tuđi odgovor.

---

## 5. Ček-lista pre prezentacije

- [ ] Ujutru: ▶ **AetherGate Seed demo data** i proveri da je audit `10/10 checks passed`.
- [ ] `curl https://138-2-169-195.sslip.io/actuator/health` vraća `UP`.
- [ ] Otvori https://aethergate-console.vercel.app i klikni *Continue with Demo Workspace* (prvi login malo traje).
- [ ] Jednom "na suvo" pokreni `./scripts/live-demo.sh` (između pokretanja sačekaj 30 s, da se breaker zatvori).
- [ ] Rezervni plan bez interneta: backend iz IntelliJ-a i `API=http://localhost:8088`.
