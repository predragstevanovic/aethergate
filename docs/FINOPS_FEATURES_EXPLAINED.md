# FinOps za AI tokene: 10 novih funkcija (V8)

> Tehnički detalji: [SYSTEM_DESIGN.md §4.10–4.13, ADR-020…024](../SYSTEM_DESIGN.md) · Prethodno:
> [TEAM_WEBHOOKS_KEYS_PROMPTS.md](TEAM_WEBHOOKS_KEYS_PROMPTS.md) · [PROJECTS_EXPLAINED.md](PROJECTS_EXPLAINED.md)

Krajem 2026. platforme za praćenje AI potrošnje (Helicone, Langfuse, Portkey, LiteLLM, OpenMeter, Vantage, CloudZero)
takmiče se u istih desetak stvari. Tenanti hoće da znaju **ko** troši (end user, agent, feature) i da potrošnja
**ne može da pobegne** (capovi, runaway agenti), da **unapred vide** kraj meseca, da ih neko **probudi** kad nešto
eksplodira, da dobiju **predlog kako da uštede**, i da troškove izvezu u **FinOps standardu (FOCUS)**.

| # | Funkcija | Gde u konzoli | "Pametno" pravilo koje vredi pokazati |
|---|---|---|---|
| 1 | Cost tagovi + showback | **Cost allocation** | tagovi iz headera ili OpenAI `metadata`; KPI *tag coverage* = koliki deo troška je raspoređen |
| 2 | End-user analitika + dnevni cap | Cost allocation → End users, Settings → Cost controls | cap se proverava **u ledger-u pod lock-om tenanta** → `402 end-user-cap-exceeded` |
| 3 | Agent sesije (tracing) | **Sessions** | jedan agent run = jedna sesija: koraci, modeli, kumulativni trošak po koraku |
| 4 | Runaway-agent guard | Settings → Cost controls | limit koraka → `429 session-step-limit`, budžet sesije → `402` |
| 5 | Mesečni budžet po API ključu | Settings → API keys → Policy | `402 key-budget-exceeded`, i dok workspace još ima para |
| 6 | Soft limit: automatski jeftiniji model | Settings → Cost controls | posle N % limita jeftin model odgovara prvi; skupi ostaje kao fallback |
| 7 | Forecast kraja meseca | **FinOps** | MTD + 7-dnevni run rate × preostali dani, 80 % pojas, datum probijanja limita |
| 8 | Detekcija anomalija | FinOps → Cost anomalies | robusni z-score (medijana + MAD); webhook **tačno jednom** po satu |
| 9 | Savings advisor | FinOps → Savings advisor | uštede računate iz **pravih tokena × cene iz kataloga** |
| 10 | FOCUS 1.2 export + Request explorer | Cost allocation → FOCUS, **Requests** | `BilledCost` u CSV-u = settled iz ledger-a (test) |

---

## 1. Atribucija: ko je potrošio

Svaki zahtev može da nosi tri opciona podatka:

```bash
curl https://138-2-169-195.sslip.io/v1/chat/completions \
  -H "Authorization: Bearer ag_live_demo_vortex_5c1e8a2f9b7d4630" \
  -H "X-AetherGate-User: acme-corp" \
  -H "X-AetherGate-Session: agent-run-42" \
  -H "X-AetherGate-Tags: feature=search,env=prod" \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"user","content":"Summarise the ledger design"}]}'
```

Ko koristi OpenAI SDK bez custom headera, šalje isto kroz standardna polja: `user` i
`metadata: {"session_id": "...", "feature": "..."}`. Header ima prednost nad body-jem. Neispravna vrednost
(razmak u id-ju, 11 tagova, ključ `Feature!`) vraća `400` **pre** nego što se išta rezerviše.

```mermaid
flowchart LR
    REQ["/v1 zahtev<br/>user · session · tags"] --> VAL{validacija}
    VAL -- loše --> E400["400"]
    VAL --> LED["ledger linije<br/>api_key · end_user · session"]
    VAL --> LOG["llm_request_logs<br/>+ tags JSONB"]
    LED --> CAPS["capovi (#2, #4, #5)"]
    LOG --> SHOW["showback · sessions · explorer · FOCUS"]
```

Bitna odluka (ADR-020): dimenzije se pišu i na **ledger** linije, ne samo u log. Capovi se proveravaju iz ledger-a,
jer je to izvor istine za novac. Audit provera 12 dokazuje da se log i ledger slažu za svaki zahtev.

## 2–5. Capovi koji ne mogu da se probiju

Svi capovi se proveravaju u `reserveBudget`, **pod istim `SELECT … FOR UPDATE` lock-om tenanta** kao i balans:

```
balans → workspace limit → budžet projekta → budžet ključa → dnevni cap end usera → koraci + budžet sesije → RESERVE
```

Formula je svuda ista: *settled u periodu + holdovi koji su još u toku + ovaj hold ≤ cap*. Zato ni 20 paralelnih
zahteva ne mogu da pređu nijedan nivo.

| Cap | Period | Odgovor |
|---|---|---|
| Ključ (`monthly_budget_usd`) | kalendarski mesec | `402 key-budget-exceeded` |
| End user | UTC dan | `402 end-user-cap-exceeded` |
| Budžet sesije | ceo život sesije | `402 session-budget-exceeded` |
| Koraci sesije | broj RESERVE linija | `429 session-step-limit` |

Svaka blokada upisuje red u `cost_control_events` (`blocked_count + 1`). Prvi blok u periodu (insert koji je **napravio**
red, `RETURNING xmax = 0`) šalje webhook `spend_cap.reached` — **tačno jednom**, bez obzira koliko je zahteva odbijeno.

## 6. Soft limit (budget-aware downgrade)

Umesto da posle 100 % limita svi zahtevi padnu sa 402, Owner/Admin podesi npr. *„na 90 % limita pređi na gpt-4o-mini"*.
Gateway tada stavlja jeftin model na prvo mesto u lancu, a ostale ostavlja kao fallback (ADR-024). Odgovor nosi
`X-AetherGate-Downgraded-To`, telemetrija u Playground-u prikazuje **"downgraded to …"**, a u explorer-u se filtrira sa
*Routing = Downgraded*. Ključ kome model nije dozvoljen (allow-list) zadržava svoj normalan lanac.

## 7. Forecast

- **Projekcija** = MTD + (potrošnja zadnjih 7 dana / 7) × preostali dani.
- **80 % pojas** = ±1,28 σ dnevne potrošnje zadnjih 14 dana × √preostalih dana.
- Uz to: naivna linearna projekcija (za poređenje), datum kad se probija limit, koliko dana traje kredit, trend
  nedelja-na-nedelju i prognoza po projektu (ON_TRACK / AT_RISK / OVER_BUDGET / NO_BUDGET).

Grafikon: kumulativni stvarni trošak (puna linija), projekcija (isprekidana) sa pojasom, i crvena linija hard limita.

## 8. Anomalije

Svaki sat se poredi sa prethodnih 168 sati (nedelja):

```
z = (trošak sata − medijana) / (1,4826 × MAD)
anomalija ako: z ≥ 4  i  trošak ≥ 2 × medijana  i  trošak ≥ $0,01      (CRITICAL kad z ≥ 8)
```

Zašto medijana i MAD, a ne prosek i σ (ADR-023): sam spike bi podigao prosek i σ i tako "sakrio" sam sebe; medijana
i MAD ne reaguju na jedan ekstremni sat. Sken radi posle settlement-a (najviše jednom u minuti po tenantu, van puta
odgovora). `UNIQUE (tenant_id, bucket_start)` garantuje da `cost.anomaly_detected` stiže jednom, sa glavnim krivcem:
*„gpt-4o · session agent-research-7f3a · 98 %"*.

## 9. Savings advisor

Preporuke su rangirane po mesečnoj uštedi; svaka ima dokaz i link na mesto gde se primenjuje.

| Vrsta | Kad se pojavi | Kako se računa ušteda |
|---|---|---|
| Model right-sizing | prosečno ≤ 500 completion tokena na skupom modelu | isti tokeni × cena najjeftinijeg uključenog modela (mora biti ≥ 30 % jeftinije) |
| Prompt bloat | prompt ≥ 6× duži od odgovora | 30 % ulaznog troška |
| Cache opportunity | hit ratio < 15 % (≥ 100 zahteva) | procena za hit ratio 25 % |
| Unreliable primary | ≥ 5 % failover-a | — (pouzdanost) |
| Idle keys / uncapped keys / projekti bez budžeta / bez alerta | governance i bezbednost | — |
| Enable soft limit | forecast ≥ 90 % limita, a downgrade nije podešen | — |

## 10. FOCUS export i Request explorer

**FOCUS 1.2** (FinOps Open Cost and Usage Specification, FinOps Foundation) je format u kom AWS, Azure, GCP i Oracle
izvoze račune. AetherGate izvozi mesec u istim kolonama, pa AI trošak ulazi u isti FinOps alat kao cloud račun:
`BilledCost`, `EffectiveCost`, `ListCost` (= billed + ušteda keša), `ConsumedQuantity` u tokenima, `PricingUnit = 1M Tokens`,
`SubAccountId/Name` = projekat, `Tags` kao JSON, plus `x_` kolone (prompt/completion tokeni, zahtevi, cache hitovi).
Test dokazuje da je zbir `BilledCost` jednak settled potrošnji iz ledger-a. Ćelije su RFC 4180 i zaštićene od
formula injection-a (`=SUM(...)` → `'=SUM(...)`).

**Request explorer** (`/requests`) pokazuje svaki zahtev: ko (end user, sesija, ključ, projekat), tagovi, tokeni,
trošak do 8 decimala, latencija/TTFT, cache, failover i routing. Svaki red u Cost allocation i Sessions vodi ovde sa
već postavljenim filterom.

---

## Scenario za prezentaciju (≈ 7 min)

Seed je pripremio sve u **Vortex AI Labs** (demo login): 7 end usera, ~1.100 sesija, tagove `feature/env/team`, i
**runaway research agenta** od pre ~5 sati.

1. **FinOps** → *Cost anomalies*: crveni stubić pre ~5 h, CRITICAL, z ≈ desetine; krivac
   `gpt-4o · session agent-research-7f3a`. *„Ovo je webhook koji bi probudio dežurnog — tačno jednom."*
2. Klik na **Sessions** → `agent-research-7f3a`: 60 koraka, kontekst raste (prompt tokeni rastu po koraku), crvena
   traka *„Session step limit stopped this session: 23 further calls refused"*. *„Agent je upao u petlju; gateway ga je
   zaustavio na 60 poziva, pod istim lock-om kao i balans."*
3. **FinOps** → *Spend forecast*: projekcija kraja meseca, pojas, datum limita; tabela projekata sa outlook-om.
4. **Savings advisor**: npr. *„Route short answers from gpt-4o to gpt-4o-mini"* sa iznosom u $/mesec, izračunatim
   iz pravih tokena.
5. **Cost allocation** → *Group by: Cost tag → feature*: koliko košta `support-reply` vs `docs-qa`; *tag coverage*.
   Ispod: End users sa trakom „today vs daily cap". Klik na korisnika → explorer filtriran na njega.
6. **FOCUS 1.2 export** → *Download CSV* → poruka „N rows · billed $X". Otvoriti u Excel-u: kolone kao AWS CUR/FOCUS.
7. **Settings → Cost controls**: pokazati capove i *Downgrade at 90 % → gpt-4o-mini* (demo je read-only; u svom
   workspace-u se menja). U **Playground**-u uneti End user / Session / Tags i pokrenuti; u telemetriji se vidi
   atribucija, a u Sessions novi run.

Za live demo sa capom u sopstvenom workspace-u: Settings → Cost controls → *Session step limit = 2*, u Playground-u
postaviti session `demo-1` i poslati 3 puta → treći put **429 session-step-limit**, a u Cost controls se pojavi
„Blocked by caps".

## Provera

- `mvn verify`: 163 testa (novi: `FinOpsIntegrationTest`, `FinOpsEdgeCasesIntegrationTest`, `FinOpsUnitTest`),
  JaCoCo 96,3 % linija / 86,4 % grana (gate 85 %).
- `--aethergate.tool=seed` pa audit: **17/17** provera (nove: atribucija ledger = log; anomalije najviše jednom po satu).
