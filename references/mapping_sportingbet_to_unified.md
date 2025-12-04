# Guia de mapeamento: Sportingbet → Contrato Unificado

Este documento descreve como transformar o JSON bruto da **Sportingbet** no nosso contrato unificado.

---

## 1. Nível de evento (`UnifiedEvent`)

### 1.1. Identificação

- `UnifiedEvent.normalizedId`
  - Gerado pela regra padrão do projeto usando:
    - esporte (`fixture.sport.name.value`)
    - `fixture.startDate` em UTC
    - nome do time da casa
    - nome do time visitante
  - Sportingbet não fornece esse id diretamente.

- `UnifiedEvent.eventId`
  - Usar `eventId` de topo (ex.: `"2:7712376"`).

### 1.2. Metadados (`EventMeta`)

A partir de `raw.fixture`:

- `startDate`
  - `fixture.startDate` (`"2025-11-27T23:30:00Z"` → `Instant`).
- `cutOffDate`
  - `fixture.cutOffDate` (se presente).
- `sport`
  - `fixture.sport.name.value` (ex.: `"Futebol"`).
- `region`
  - `fixture.region.name.value` (ex.: `"Brasil"`).
- `competition`
  - `fixture.competition.name.value` (ex.: `"Brasileiro A"`).

### 1.3. Participantes (`Participants`)

A partir de `fixture.participants[]`:

- Encontrar o participante com `properties.type = "HomeTeam"` → `Participants.home`.
- Encontrar o participante com `properties.type = "AwayTeam"` → `Participants.away`.

Caso a propriedade não exista, usar ordem do array: índice 0 = casa, índice 1 = fora.

### 1.4. Fonte e tags (`SourceSnapshot`, `SourceTags`)

`UnifiedEvent.sources["sportingbet"]`:

- `eventSourceId` → `raw.fixture.id` (ex.: `"2:7712376"`).
- `capturedAt` → `capturedAt.$date` (topo do documento).
- `updatedAt` → maior valor observado entre:
  - `raw.fixture.startDate`
  - `optionMarkets[].options[].price` ou `boostedPrice` não possuem `changedDate` explícito; por padrão,
    usar `capturedAt` até existir campo de atualização mais adequado.

`UnifiedEvent.tagsBySource["sportingbet"]` (`SourceTags`):

- `priceBoostCount` → `raw.fixture.totalMarketsCount` ou `raw.fixture.priceBoostCount`?
  - O JSON possui `totalMarketsCount` e `priceBoostCount`.
  - **Regra sugerida**: usar `priceBoostCount` neste campo.
  - **Dúvida**: confirmar se `priceBoostCount` realmente representa quantidade de mercados com odd boost.

`UnifiedEvent.isPagamentoAntecipado` / `pagamentoAntecipadoPorSource["sportingbet"]`:

- Sportingbet usa `boostedPrice`, mas isso não é necessariamente pagamento antecipado.
- Regra inicial: considerar `pagamentoAntecipado = false` para todas as opções.
- Se no futuro houver campo específico para cashout/antecipado, atualizar regra.

---

## 2. Mercados (`UnifiedMarket`) a partir de `raw.optionMarkets[]`

Cada `optionMarkets[]` é um mercado.

### 2.1. Campos base do mercado

Para cada `market` em `raw.optionMarkets`:

- `marketCanonical` (`MarketType`):
  - Depende de `market.parameters.MarketType`, `market.name` e `parameters` auxiliares.

  Exemplos do JSON:

  1. `name = "Chance Dupla"`,
     `parameters.MarketType = "DoubleChance"` →
     - `marketCanonical = MarketType.DUPLA_CHANCE`
     - `happening = HappeningType.GOALS`
     - `period = PeriodType.REGULAR_TIME` (de `parameters.Period = "RegularTime"`).

  2. `name = "Empate Anula Aposta"`,
     `parameters.MarketType = "DrawNoBet"` →
     - `marketCanonical = MarketType.DRAW_NO_BET`
     - `happening = HappeningType.GOALS`
     - `period = PeriodType.REGULAR_TIME`.

  3. `name = "Ambas equipes marcam gol"`,
     `parameters.MarketType = "BTTS"` →
     - `marketCanonical = MarketType.BTTS`
     - `happening = HappeningType.GOALS`
     - `period = PeriodType.REGULAR_TIME`.

  4. `name = "Handicap – Resultado Final"`,
     `parameters.MarketType = "Handicap"` →
     - `marketCanonical = MarketType.HANDICAP_3WAY` (pois há três opções: casa, empate, fora).
     - `happening = HappeningType.GOALS`
     - `line` → `parameters.Handicap` (`"-1.0000"` → `-1.0`).
     - `period = PeriodType.REGULAR_TIME`.

  5. `name` iniciando com `"Resultado do jogo e Total de gols"` e
     `parameters.MarketType = "ThreeWayAndOverUnder"` →
     - `marketCanonical = MarketType.RESULTADO_TOTAL_GOLS`
     - `happening = HappeningType.GOALS`
     - `line` a partir de `parameters.DecimalValue` (ex.: `"2.5000"` → `2.5`).

  6. Mercados de "Criar Aposta - Cotas Aumentadas" / "Craque Sportingbet - Cotas Aumentadas":
     - `MarketType = "FreeFormed"`.
     - Inicialmente **não mapeados** em nenhum `MarketType` canônico → DEVEM SER DESCARTADOS.

- `period` (`PeriodType`)
  - A partir de `parameters.Period`:
    - `"RegularTime"` → `PeriodType.REGULAR_TIME`
    - `"FirstHalf"` → `PeriodType.FIRST_HALF`
    - `"SecondHalf"` → `PeriodType.SECOND_HALF`
  - Se ausente, assumir `REGULAR_TIME` por padrão.

- `line` (`BigDecimal`)
  - Quando houver `parameters.Handicap` ou `parameters.DecimalValue`:
    - Converter string com 4 casas decimais para `BigDecimal` (ex.: `"2.5000"` → `2.5`).
  - Para mercados sem linha (1x2, dupla chance, BTTS), usar `null`.

- `happening` (`HappeningType`)
  - Mapear de `parameters.Happening`:
    - `"Goal"` → `HappeningType.GOALS`.
    - Em futuros exemplos, `"Card"` ou `"Cards"` → `HappeningType.CARDS`, etc.

- `participant`
  - Para todos estes mercados globais (resultado, total de gols etc.), `null`.

- `interval`
  - Sportingbet neste exemplo não traz limite de minutos → `null`.

- `updatedAt`
  - Não há `changedDate` no exemplo; usar `capturedAt` até que exista campo mais adequado.

### 2.2. Opções (`UnifiedMarketOption`) a partir de `market.options[]`

Para cada `option` em `market.options`:

- `label`
  - `option.name` (ex.: `"Fluminense ou Empate"`).

- `outcome` (`OutcomeType`)
  - Depende de `marketCanonical` e do texto da opção:

  **Dupla Chance (`MarketType.DUPLA_CHANCE`):**
  - `"Fluminense ou Empate"` → `OutcomeType.HOME_OR_DRAW`
  - `"Empate ou São Paulo"` → `OutcomeType.DRAW_OR_AWAY`
  - `"Fluminense ou São Paulo"` → `OutcomeType.HOME_OR_AWAY`

  **Draw No Bet (`MarketType.DRAW_NO_BET`):**
  - `"Fluminense"` → `OutcomeType.HOME`
  - `"São Paulo"` → `OutcomeType.AWAY`

  **BTTS (`MarketType.BTTS`):**
  - `"Sim"` → `OutcomeType.YES`
  - `"Não"` → `OutcomeType.NO`

  **Handicap 3-way (`MarketType.HANDICAP_3WAY`):**
  - `"Fluminense (-1)"` → `OutcomeType.HOME_HCP`
  - `"Handicap Empate - Fluminense (-1)"` → `OutcomeType.DRAW_HCP`
  - `"São Paulo (1)"` → `OutcomeType.AWAY_HCP`

  **Resultado + Total de Gols (`MarketType.RESULTADO_TOTAL_GOLS`):**
  - Nome segue o padrão:
    - `"Fluminense e mais de 2,5 gols marcados"` → `OutcomeType.HOME_AND_OVER`
    - `"São Paulo e mais de 2,5 gols marcados"` → `OutcomeType.AWAY_AND_OVER`
    - `"Empate e mais de 2,5 gols marcados"` → `OutcomeType.DRAW_AND_OVER`
    - `"Fluminense e menos de 2,5 gols marcados"` → `OutcomeType.HOME_AND_UNDER`
    - `"São Paulo e menos de 2,5 gols marcados"` → `OutcomeType.AWAY_AND_UNDER`
    - `"Empate e menos de 2,5 gols marcados"` → `OutcomeType.DRAW_AND_UNDER`

  - **Dúvida**: em mercados combinados mais complexos (ex.: incluir BTTS junto), será
    necessário mapear para outros `OutcomeType` combinados ou estender o enum.

- `sources["sportingbet"]` (`OptionSourceData`):
  - `pagamentoAntecipado` → inicialmente `false`.
  - `capturedAt` → `capturedAt.$date`.
  - `updatedAt` → mesmo valor de `capturedAt` (não há `changedDate`).
  - `statusRaw` → `option.status` (ex.: `"Visible"`).
  - `marketId` → `market.id`.
  - `optionId` → `option.id`.
  - `price` (`Price`):
    - `decimal` → `option.price.odds` (ex.: `2.3`).
    - `fractional` → `f"{numerator}/{denominator}"` (ex.: `"13/10"`).
    - `american` → `option.price.americanOdds` (string ou conversão para string).
  - `meta`:
    - Se `boostedPrice` não for nulo, incluir:
      - `"hasBoostedPrice": true`
      - `"boostedDecimal"`, `"boostedFractional"`, `"boostedAmerican"`
    - Copiar também `parameters` do mercado se for útil para debug.

### 2.3. Descarte de mercados não mapeados

- Mercados com `parameters.MarketType = "FreeFormed"` (ex.: "Criar Aposta - Cotas Aumentadas") não
  possuem representação clara no contrato atual → DEVEM SER DESCARTADOS.
- Qualquer `optionMarkets[]` cujo conjunto (`name`, `parameters`) não permita inferir um
  `MarketType` conhecido também deve ser descartado.

---

## 3. Resumo das regras principais

1. Evento usa `raw.fixture` para meta (datas, esporte, região, competição).
2. Participantes vêm de `fixture.participants` com `HomeTeam` / `AwayTeam`.
3. `optionMarkets` vira `UnifiedMarket`; `options` vira `UnifiedMarketOption`.
4. `MarketType`, `Happening`, `Period` são derivados de `parameters`.
5. Mercados de boost/free-form não mapeados são descartados.
6. `boostedPrice` é armazenado em `meta`, e odds padrão vão para `Price`.
7. Pagamento antecipado por enquanto é sempre `false` para Sportingbet.
