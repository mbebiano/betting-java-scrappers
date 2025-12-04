# Guia de mapeamento: BetMGM → Contrato Unificado

Este documento descreve como transformar o JSON bruto da **BetMGM** no nosso contrato unificado
(`UnifiedEvent`, `UnifiedMarket`, `UnifiedMarketOption`, etc.).

O objetivo é que qualquer implementação (Java) siga estas regras e produza **sempre o mesmo resultado**
para o mesmo JSON de entrada.

---

## 1. Nível de evento (`UnifiedEvent`)

### 1.1. Identificação

- `UnifiedEvent.normalizedId`
  - Gerado pela nossa regra padrão (descrita em `DocumentacaoContrato.md`), usando:
    - esporte
    - data/hora em UTC
    - time da casa
    - time visitante
  - A BetMGM **não fornece** diretamente esse id. Ele deve ser calculado.
  - Em caso de dúvida, seguir o mesmo algoritmo usado no pipeline Python.

- `UnifiedEvent.eventId`
  - Preencher com o campo de topo:
    - `eventId` (string ou número) do documento raiz da BetMGM.

### 1.2. Metadados do evento (`EventMeta`)

Preencher `UnifiedEvent.eventMeta` usando o objeto `raw.events[0]`:

- `startDate`
  - Usar `raw.events[0].start` (ex.: `"2025-11-30T21:30:00Z"`).
  - Converter para `Instant` (ISO 8601 UTC).
- `cutOffDate`
  - Usar `raw.events[0].prematchEnd` quando existir. Caso contrário, `null`.
- `sport`
  - Usar `raw.events[0].sport` (ex.: `"FOOTBALL"`), podendo normalizar para `"Futebol"` se desejado.
- `region`
  - Usar `raw.events[0].path` procurando o nó com `termKey` de país (ex.: `"brazil"` → `"Brasil"`).
- `competition`
  - Usar `raw.events[0].group` (ex.: `"Brasileirão Série A"`).

### 1.3. Participantes (`Participants`)

Preencher `UnifiedEvent.participants` com base em `raw.events[0]`:

- `home`
  - `raw.events[0].homeName`
- `away`
  - `raw.events[0].awayName`

Caso falte `homeName` / `awayName`, usar `matchName` e fazer o split por separadores definidos
(`split_match_name` da lib Python).

### 1.4. Fontes (`SourceSnapshot` e tags)

No campo `UnifiedEvent.sources["betmgm"]`:

- `eventSourceId`
  - `eventId` da BetMGM (mesmo valor de topo).
- `capturedAt`
  - Converter `capturedAt.$date` do documento raiz para `Instant`.
- `updatedAt`
  - Maior `changedDate` entre todos `raw.betOffers[].outcomes[].changedDate`.
  - Se não houver `changedDate`, usar o `capturedAt`.

Em `UnifiedEvent.tagsBySource["betmgm"]` (`SourceTags`):

- `priceBoostCount`
  - Não está diretamente disponível na estrutura de exemplo de BetMGM.
  - Por padrão: `null` ou `0`.
  - **Dúvida**: se houver outro campo em BetMGM representando boosts, revisar para preencher aqui.

`UnifiedEvent.isPagamentoAntecipado` e `pagamentoAntecipadoPorSource["betmgm"]`:

- BetMGM possui campos relacionados a `cashOutStatus`, mas não há indicação clara de pagamento
  antecipado “garantido” como nas casas brasileiras.
- Por padrão:
  - `isPagamentoAntecipado` só será `true` se alguma `OptionSourceData.pagamentoAntecipado == true`.
  - Para BetMGM, inicialmente, marcar **sempre `false`**, até existir regra clara de identificação.

---

## 2. Mercados (`UnifiedMarket`) a partir de `raw.betOffers[]`

Cada item de `raw.betOffers[]` representa um **mercado** da BetMGM.

### 2.1. Identificação do mercado

Para cada `betOffer` em `raw.betOffers`:

- Criar um `UnifiedMarket` com:
  - `marketCanonical` (enum `MarketType`)
    - Depende de `criterion` e `betOfferType`:
      - Exemplo do JSON fornecido:
        - `criterion.label = "Total de Cartões"`
        - `criterion.occurrenceType = "CARDS"`
        - `betOfferType.englishName = "Over/Under"`
      - Nesse caso:
        - `marketCanonical = MarketType.TOTAL_CARTOES_OVER_UNDER`
        - `happening = HappeningType.CARDS`
  - `period` (`PeriodType`)
    - Usar `criterion.lifetime`:
      - `"FULL_TIME"` → `PeriodType.REGULAR_TIME`
      - `"FIRST_HALF"` → `PeriodType.FIRST_HALF`
      - `"SECOND_HALF"` → `PeriodType.SECOND_HALF`
  - `line` (`BigDecimal`)
    - Para bet offers com `line` em `outcomes`:
      - Exemplo: `outcomes[].line = 7500` → `7.5`
      - Regra: dividir por 1000 e converter para `BigDecimal`.
    - Se não houver linha (mercados 1x2, dupla chance etc.), deixar `null`.
  - `happening` (`HappeningType`)
    - Usar `criterion.occurrenceType`:
      - `"GOALS"` → `HappeningType.GOALS`
      - `"CARDS"` → `HappeningType.CARDS`
      - `"CORNERS"` → `HappeningType.CORNERS`
  - `participant` (`ParticipantSide`)
    - Para mercados gerais, usar `null`.
    - Para futuros mercados por time (ex.: cartões por time), definir regra quando JSON aparecer.
  - `interval`
    - BetMGM não traz intervalo detalhado neste exemplo (0–60, etc).
    - Deixar `null` até existir campo específico.
  - `updatedAt`
    - Maior `changedDate` entre os `outcomes` deste `betOffer`.

### 2.2. Opções do mercado (`UnifiedMarketOption`)

Cada `betOffer.outcomes[]` vira um `UnifiedMarketOption`.

- `label`
  - Usar `outcome.englishLabel` se existir, senão `outcome.label`.
- `outcome` (`OutcomeType`)
  - Mapear a partir de `outcome.type` ou `outcome.label`:
    - Para mercado Over/Under:
      - `type = "OT_OVER"` ou label "Mais"/"Over" → `OutcomeType.OVER`
      - `type = "OT_UNDER"` ou label "Menos"/"Under" → `OutcomeType.UNDER`
    - Para mercado 1x2 (quando mapeado):
      - label `Home` ou similar → `OutcomeType.HOME`
      - label `Draw` → `OutcomeType.DRAW`
      - label `Away` → `OutcomeType.AWAY`
  - **Dúvida**: é necessário mapear todos os possíveis `type` de BetMGM para os enums.
    Criar uma tabela de conversão quando expandirmos mais exemplos.
- `sources["betmgm"]` (`OptionSourceData`)
  - `pagamentoAntecipado`
    - Não há campo explícito; inicialmente `false`.
  - `capturedAt`
    - Igual ao `UnifiedEvent.sources["betmgm"].capturedAt`.
  - `updatedAt`
    - `outcome.changedDate` (convertido para `Instant`).
  - `statusRaw`
    - `outcome.status` (ex.: `"OPEN"`).
  - `marketId`
    - `betOffer.id` (conversão para string).
  - `optionId`
    - `outcome.id` (conversão para string).
  - `price` (`Price`)
    - `decimal`
      - Regra adotada: dividir `outcome.odds` por 1000 (ex.: `2380` → `2.38`).
      - **Dúvida**: confirmar se BetMGM usa sempre odds * 1000.
    - `fractional`
      - `outcome.oddsFractional`.
    - `american`
      - `outcome.oddsAmerican`.
  - `meta`
    - Armazenar:
      - `cashOutStatus` do outcome
      - `betOfferType`, `criterion` completos (como JSON aninhado ou campos principais).

### 2.3. Descarte de mercados não mapeados

- Se não for possível identificar um `MarketType` a partir de `criterion` + `betOfferType`:
  - **NÃO** criar `UnifiedMarket`.
  - Seguir regra 9 do contrato: mercado não mapeado é descartado e não persiste.

---

## 3. PrePacks (`raw.prePacks[]`)

A BetMGM traz estruturas `prePacks` que representam combinações prontas de seleções.

- No contrato atual **não há suporte explícito** para esse tipo de combo estruturado.
- Regra inicial:
  - Ignorar `prePacks` (não gerar mercados/unified options a partir deles).
  - Se futuramente quisermos suportar combos, será necessário estender o domínio.

### Dúvidas abertas sobre PrePacks

- Como representar múltiplos outcomes combinados em um único preço dentro do domínio atual?
- Devemos criar um novo tipo de entidade (ex.: `UnifiedCombo`) ao invés de forçar em `UnifiedMarket`?

---

## 4. Resumo das principais regras

1. `UnifiedEvent` é montado a partir de `eventId`, `matchName` e `raw.events[0]`.
2. `normalizedId` é calculado pela regra padrão do projeto.
3. `SourceSnapshot` usa `capturedAt` do topo e `changedDate` mais recente dos outcomes.
4. Cada `betOffers[]` vira um `UnifiedMarket`; cada `outcomes[]` vira um `UnifiedMarketOption`.
5. `MarketType`, `HappeningType`, `PeriodType` são derivados de `criterion` + `betOfferType`.
6. Odds em inteiro (ex.: 2380) são convertidas para decimal dividindo por 1000.
7. Pagamento antecipado para BetMGM, por enquanto, é sempre `false`.
8. Mercados que não mapearem para um `MarketType` conhecido são descartados.
