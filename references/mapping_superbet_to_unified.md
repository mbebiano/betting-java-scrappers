# Guia de mapeamento: Superbet → Contrato Unificado

Este documento descreve como transformar o JSON bruto da **Superbet** no contrato unificado.

---

## 1. Nível de evento (`UnifiedEvent`)

### 1.1. Identificação

- `UnifiedEvent.normalizedId`
  - Gerado pela regra padrão usando:
    - esporte (mapeado de `sportId`)
    - `raw.matchDate` (UTC)
    - time da casa
    - time visitante.

- `UnifiedEvent.eventId`
  - Usar `eventId` de topo (ex.: `8910651`).

### 1.2. Metadados (`EventMeta`)

- `startDate`
  - `raw.matchDate` (string `"YYYY-MM-DD HH:mm:ss"`).
  - Converter para UTC ISO 8601 (assumindo timezone Brasil conforme regra global).
- `cutOffDate`
  - Não há campo explícito: usar `null` por enquanto.
- `sport`
  - Mapear `sportId = 5` para `"Futebol"` (tabela de esportes do projeto).
- `region`
  - Derivar de `categoryId`/`tournamentId` se houver tabela; caso contrário `null` ou `"UNK"`.
  - **Dúvida**: precisamos de dicionário fixo Superbet → região/competição.
- `competition`
  - Derivar de `tournamentId` via tabela; no JSON bruto não vem o nome.

### 1.3. Participantes (`Participants`)

- `Participants.home`
  - Derivar do `matchName`:
    - Exemplo: `"Palmeiras·Flamengo"` → dividir por `·` (ou outros separadores definidos) e pegar a primeira parte.
- `Participants.away`
  - Segunda parte do `matchName` após o separador.

---

## 2. Fonte, tags e pagamento antecipado

`UnifiedEvent.sources["superbet"]`:

- `eventSourceId` → `raw.eventId` ou `eventId` de topo (mesmo valor).
- `capturedAt` → `capturedAt.$date` (topo do documento).
- `updatedAt` → timestamp mais recente disponível no documento (não há exemplo no trecho; usar
  `capturedAt` até que exista outro campo).

`UnifiedEvent.tagsBySource["superbet"]` (`SourceTags`):

- `priceBoostCount`
  - Pode ser inferido de `raw.matchTags` contendo `"price_boost"`:
    - Se contiver `"price_boost"` → pelo menos `1`.
    - **Dúvida**: precisamos de campo separado no JSON para a contagem real.

`UnifiedEvent.isPagamentoAntecipado` / `pagamentoAntecipadoPorSource["superbet"]`:

- O JSON de exemplo não traz informação direta de pagamento antecipado.
- Regra inicial: marcar `pagamentoAntecipado = false` para todas as opções.
- Quando houver regra/campo específico (ex.: tags, marketName ou status), atualizar este documento.

---

## 3. Mercados (`UnifiedMarket`) a partir de `raw.odds[]`

Na Superbet, cada entrada de `raw.odds[]` representa uma **opção** dentro de um mercado
(`marketId` + `marketName`).

### 3.1. Agrupamento por mercado

1. Agrupar `raw.odds[]` por `(marketId, marketName)`.
2. Para cada grupo, criar um `UnifiedMarket`.

### 3.2. Campos de `UnifiedMarket`

Para cada grupo `(marketId, marketName)`:

- `marketCanonical` (`MarketType`):
  - `"Resultado Final"` → `MarketType.RESULTADO_FINAL`
  - `"Dupla Chance"` → `MarketType.DUPLA_CHANCE`
  - `"Ambas as Equipes Marcam"` → `MarketType.BTTS`
  - `"Empate Anula Aposta"` → `MarketType.DRAW_NO_BET`
  - `"Resultado Final & Total de Gols (X.Y)"` → `MarketType.RESULTADO_TOTAL_GOLS`
  - `"Handicap Asiático"` → `MarketType.HANDICAP_ASIAN_2WAY`
  - Outros valores devem ser avaliados caso a caso. Se não houver mapeamento claro → descartar.

- `period` (`PeriodType`):
  - Para todos os exemplos, considerar `PeriodType.REGULAR_TIME`.
  - Se futuramente houver mercados de 1º tempo, etc., ajustar.

- `line` (`BigDecimal`):
  - Para mercados de total de gols:
    - Extrair o valor entre parênteses do `marketName`.
    - Exemplos:
      - `"Resultado Final & Total de Gols (0.5)"` → `0.5`
      - `"Resultado Final & Total de Gols (1.5)"` → `1.5`, etc.
  - Para `Handicap Asiático`:
    - Extrair do `odds.name`, ex.: `"Palmeiras (-0.75)"` → `-0.75`.
  - Para `Resultado Final`, `Dupla Chance`, BTTS, Draw No Bet → `null`.

- `happening` (`HappeningType`):
  - Para mercados de gols → `HappeningType.GOALS`.
  - Ainda não há exemplo de cartões/escanteios no trecho; quando aparecer, mapear.

- `participant` (`ParticipantSide`):
  - Em mercados gerais (resultado, dupla chance, BTTS, total de gols) → `null`.
  - Em handicaps asiáticos por time:
    - Se `name` começa com time da casa → `ParticipantSide.HOME`.
    - Se começa com time visitante → `ParticipantSide.AWAY`.

- `interval`:
  - Não há segmento de tempo no JSON → `null`.

- `updatedAt`:
  - Sem campo explícito; usar `capturedAt` até haver outro melhor.

---

## 4. Opções do mercado (`UnifiedMarketOption`) a partir de `raw.odds[]`

Para cada item `odds` dentro de um grupo `(marketId, marketName)`:

- `label`
  - Usar `odds.name` (ex.: `"1"`, `"X"`, `"2"`, `"1X"`, `"Sim"`, `"Palmeiras (-0.75)"`).

- `outcome` (`OutcomeType`):

  **Resultado Final (`MarketType.RESULTADO_FINAL`):**
  - `name = "1"` ou `code = "1"` → `OutcomeType.HOME`
  - `name = "X"` ou `code = "0"` → `OutcomeType.DRAW`
  - `name = "2"` ou `code = "2"` → `OutcomeType.AWAY`

  **Dupla Chance (`MarketType.DUPLA_CHANCE`):**
  - `name = "1X"` → `OutcomeType.HOME_OR_DRAW`
  - `name = "X2"` → `OutcomeType.DRAW_OR_AWAY`
  - `name = "12"` → `OutcomeType.HOME_OR_AWAY`

  **BTTS (`MarketType.BTTS`):**
  - `name = "Sim"` → `OutcomeType.YES`
  - `name = "Não"` → `OutcomeType.NO`

  **Empate Anula Aposta (`MarketType.DRAW_NO_BET`):**
  - `name = "Palmeiras"` (time da casa) → `OutcomeType.HOME`
  - `name = "Flamengo"` (time visitante) → `OutcomeType.AWAY`

  **Resultado Final & Total de Gols (`MarketType.RESULTADO_TOTAL_GOLS`):**
  - Nome segue padrão `"1 e Mais de X.Y"` etc.
  - Mapear de forma genérica:
    - Se começa com `"1 e Mais de"` → `OutcomeType.HOME_AND_OVER`
    - `"X e Mais de"` → `OutcomeType.DRAW_AND_OVER`
    - `"2 e Mais de"` → `OutcomeType.AWAY_AND_OVER`
    - `"1 e Menos de"` → `OutcomeType.HOME_AND_UNDER`
    - `"X e Menos de"` → `OutcomeType.DRAW_AND_UNDER`
    - `"2 e Menos de"` → `OutcomeType.AWAY_AND_UNDER`

  **Handicap Asiático (`MarketType.HANDICAP_ASIAN_2WAY`):**
  - Exemplo:
    - `"Palmeiras (-0.75)"` → `OutcomeType.HOME_HANDICAP`
    - `"Flamengo (0.75)"` → `OutcomeType.AWAY_HANDICAP`

  - **Dúvida**: quando houver 4-way asian ou mercados com push parcial, ver se precisamos
    refinar o enum de outcome.

- `sources["superbet"]` (`OptionSourceData`):

  - `pagamentoAntecipado` → `false` (por enquanto).
  - `capturedAt` → `capturedAt.$date` do evento.
  - `updatedAt` → `capturedAt` (até existir campo específico).
  - `statusRaw` → `odds.status` (ex.: `"active"`).
  - `marketId` → `odds.marketId` (string).
  - `optionId` → `odds.outcomeId` (string).
  - `price` (`Price`):
    - `decimal` → `odds.price` (já é decimal, ex.: `3.3`).
    - `fractional` → `null` (não disponível).
    - `american` → `null` (não disponível).
  - `meta`:
    - Incluir pelo menos:
      - `offerStateId`
      - `marketGroupOrder`
      - `offerStateStatus` eventualmente relacionado.

---

## 5. Descarte de mercados não mapeados

- Qualquer `(marketName, marketId)` cujo `marketName` não se encaixe nas regras acima e
  não possa ser associado a um `MarketType` conhecido deve ser **descartado** (regra 9).
- Exemplo: mercados muito específicos que não representamos ainda (ex.: “Resultado correto exato”)
  não entram no contrato até que seja criada uma modelagem apropriada.

---

## 6. Resumo

1. Agrupar `raw.odds` por `(marketId, marketName)` → `UnifiedMarket`.
2. `marketCanonical` definido principalmente por `marketName`.
3. `OutcomeType` deriva de `name` + contexto do mercado.
4. `Price` usa `odds.price` direto como decimal.
5. Pagamento antecipado para Superbet é `false` até nova regra.
6. Mercados fora da lista de mapeamentos conhecidos são descartados.
