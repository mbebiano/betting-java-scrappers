# Contrato Unificado de Mercados – SuperOdds

## 1. Objetivo

Padronizar um **formato único de evento e mercados** para que qualquer casa (Superbet, BetMGM, Sportingbet, etc.) consiga fazer um **de/para** para o mesmo contrato JSON, permitindo:

- **Um único ID canônico de evento** (`normalizedId`) como chave principal de persistência.
- **Um único `marketCanonical`** para cada tipo de mercado – nada de `end_game`, `final_result`, `resultado_final_xpto` para coisas iguais.
- **Options normalizadas** por tipo de resultado (`HOME`, `DRAW`, `AWAY`, `OVER`, `UNDER`, etc.).
- **Valores de odds (prices)** padronizados.
- **Pagamento antecipado** claramente controlado por evento, por casa e por mercado.

## 2. Convenções Globais

### 2.1 Formato de data/hora

- Todas as datas/horas são em **UTC**, no formato **ISO 8601**:
    - Exemplo: `2025-12-03T00:30:00Z`
- Campos de data/hora no contrato:
    - `eventMeta.startDate`
    - `eventMeta.cutOffDate`
    - `sources.{source}.capturedAt`
    - `sources.{source}.updatedAt`
    - `markets[].updatedAt`
    - `markets[].options[].sources.{source}.capturedAt`
    - `markets[].options[].sources.{source}.updatedAt`

### 2.2 Números, odds e linhas

- Todos os números de **odd decimal** e **linha** devem ser tratados como **decimais**:
    - JSON: `number`
    - Implementação (Java): `BigDecimal`
- Escala recomendada:
    - Odds (`price.decimal`): até **4 casas decimais** (ex.: `2.87`, `1.9500`).
    - Linhas (`line`): até **3 casas decimais** (ex.: `1.5`, `2.25`, `0.75`, `7.500`).

### 2.3 Normalização de texto (geral)

- Nomes de times, ligas, competições: texto livre, porém o `normalizedId` é criado em cima de **versões normalizadas** (ver seção 3).
- `marketCanonical`, `period`, `happening`, `outcome` são controlados por **enum** (texto fixo e documentado).

---

## 3. Identificação de evento e regras de `normalizedId`

### 3.1 Chave primária e compatibilidade com o código Python

A persistência em Mongo segue a lógica do `upsert_normalized` em Python:

- Cada documento tem:
    - `normalizedId` (preferencial)
    - `eventId` (legado / amigável)
- Para gerar a chave de upsert é usado:

```python
norm_id = doc.get("normalizedId") or doc.get("eventId")
```

- Essa chave (`norm_id`) é usada para:
    - Buscar documentos existentes: `{"normalizedId": {"$in": ids}}`
    - Persistir/upsert: `ReplaceOne({"normalizedId": norm_id}, merged, upsert=True)`

**Regras:**

1. **Chave principal** no Mongo é sempre o campo `normalizedId`.
2. Se o documento vier **sem** `normalizedId`, mas com `eventId`, o valor de `eventId` é usado como `normalizedId` na persistência (modo legado, não recomendado para scrapers novos).
3. Para scrapers novos, **é obrigatório** enviar `normalizedId` preenchido de acordo com a regra de negócio abaixo.
4. O campo `_id` do Mongo **não faz parte do contrato** e não deve aparecer no JSON publicado pelo normalizador.

### 3.2 Regras de normalização de texto para o `normalizedId`

Para os componentes textuais (esporte, mandante, visitante):

1. Converter para **maiúsculo**.
2. Remover acentos:
    - `Grêmio` → `GREMIO`
    - `Fluminense` → `FLUMINENSE`
3. Substituir qualquer caractere que não seja `[A-Z0-9]` por `_` ou remover:
    - espaços, hífens, etc.
4. Colapsar múltiplos `_` consecutivos em um único `_`.
5. Remover `_` no início/fim da string.

### 3.3 Regra de geração do `normalizedId` (regra de negócio)

O `normalizedId` deve ser **determinístico** a partir de:

- Esporte
- Data/hora de início em UTC (`eventMeta.startDate`)
- Time mandante (`participants.home`)
- Time visitante (`participants.away`)

Formato:

```text
<SPORT_NORMALIZADO>-<DATAHORA_UTC>-<HOME_NORMALIZADO>-<AWAY_NORMALIZADO>
```

Exemplo:

- `sport`: `"Futebol"`
- `startDate`: `"2025-12-03T00:30:00Z"`
- `home`: `"Grêmio"`
- `away`: `"Fluminense"`

Resultado:

```text
FUTEBOL-20251203T003000Z-GREMIO-FLUMINENSE
```

Esse valor deve ser enviado em:

```json
"normalizedId": "FUTEBOL-20251203T003000Z-GREMIO-FLUMINENSE"
```

> O código Python de persistência **não gera** o `normalizedId`; ele apenas **consome** esse campo e usa `eventId` como fallback. A geração é responsabilidade da camada de normalização / scrapers que publicam o contrato.

---

## 4. Estrutura do JSON do Evento

### 4.1 Estrutura geral

```jsonc
{
  "normalizedId": "FUTEBOL-20251203T003000Z-GREMIO-FLUMINENSE",
  "eventId": "FUTEBOL-20251203-GREMIO-FLU",

  "eventMeta": { ... },
  "participants": { ... },

  "sources": { ... },

  "isPagamentoAntecipado": true,
  "pagamentoAntecipadoPorSource": { ... },

  "tagsBySource": { ... },

  "markets": [ ... ]
}
```

#### Campos

- `normalizedId` (string, obrigatório para novos scrapers)
    - ID canônico do evento (ver seção 3).
- `eventId` (string, opcional)
    - ID “amigável” ou legado.

### 4.2 `eventMeta`

```jsonc
"eventMeta": {
  "startDate": "2025-12-03T00:30:00Z",
  "cutOffDate": "2025-12-03T02:30:00Z",
  "sport": "Futebol",
  "region": "Brasil",
  "competition": "Brasileiro A"
}
```

- `startDate`: início do evento (UTC).
- `cutOffDate`: limite de apostas pré-live (UTC).
- `sport`: nome do esporte (ex.: `Futebol`).
- `region`: região/país (ex.: `Brasil`).
- `competition`: competição/liga (ex.: `Brasileiro A`).

> **Sem IDs numéricos** aqui (não existe base central ainda).

### 4.3 `participants`

```jsonc
"participants": {
  "home": "Grêmio",
  "away": "Fluminense"
}
```

- `home`: nome do time mandante.
- `away`: nome do time visitante.

> **Sem IDs de participante** no contrato unificado.

### 4.4 `sources` (por casa)

```jsonc
"sources": {
  "superbet": {
    "eventSourceId": "8547188",
    "capturedAt": "2025-12-02T23:50:00Z",
    "updatedAt": "2025-12-03T00:10:00Z"
  },
  "sportingbet": {
    "eventSourceId": "190729233",
    "capturedAt": "2025-12-02T23:55:00Z",
    "updatedAt": "2025-12-03T00:12:00Z"
  }
}
```

Para cada casa (`source`):

- `eventSourceId`: ID do evento na casa (string).
- `capturedAt`: primeira vez que o evento foi visto dessa casa.
- `updatedAt`: última vez que qualquer mercado/opção dessa casa foi atualizado para esse evento.

### 4.5 Pagamento antecipado (nível de evento e fonte)

```jsonc
"isPagamentoAntecipado": true,

"pagamentoAntecipadoPorSource": {
  "superbet": true,
  "sportingbet": false
}
```

- `isPagamentoAntecipado`
    - `true` se **alguma** casa tem qualquer mercado/opção com pagamento antecipado nesse evento.
- `pagamentoAntecipadoPorSource.{source}`
    - `true` se a **casa** em questão oferece algum mercado de pagamento antecipado nesse evento (qualquer mercado/option com `pagamentoAntecipado = true` na seção de markets).

### 4.6 Tags por casa (`tagsBySource`)

```jsonc
"tagsBySource": {
  "superbet": {
    "priceBoostCount": 6
  },
  "sportingbet": {
    "priceBoostCount": 2
  }
}
```

- `tagsBySource` é um mapa:
    - chave: nome da casa
    - valor: objeto de tags daquela casa
- Exemplo de tag:
    - `priceBoostCount`: quantos mercados/opções têm algum tipo de boost.

---

## 5. Estrutura dos Mercados e Options Normalizados

### 5.1 Visão geral

**Objetivo**: ter **um único mercado normalizado** por combinação:

- `marketCanonical`
- `period`
- `line`
- `happening`
- `participant`
- `interval`

E dentro desse mercado, **options normalizadas** (`HOME`, `DRAW`, `OVER`, etc.) com **preços por casa**.

### 5.2 Estrutura de `markets`

```jsonc
"markets": [
  {
    "marketCanonical": "resultado_final",
    "period": "RegularTime",
    "line": null,
    "happening": "GOALS",
    "participant": null,
    "interval": "0-90",  // ex.: tempo regulamentar em minutos

    "updatedAt": "2025-12-03T00:11:00Z",

    "options": [ ... ]
  }
]
```

Campos:

- `marketCanonical` (string, ENUM)
    - Tipo de mercado (ver catálogo na seção 6).
- `period` (string, ENUM)
    - Possíveis valores:
        - `RegularTime`
        - `FirstHalf`
        - `SecondHalf`
        - (futuro: `ExtraTime`, `Penalties`, etc.)
- `line` (number ou null)
    - Linha do mercado:
        - Ex.: `2.5` (total de gols), `7.5` (cartões), `1.5` (gols + dupla chance), etc.
- `happening` (string ou null, ENUM opcional)
    - O que está sendo medido:
        - `GOALS`, `CARDS`, `CORNERS`, etc.
- `participant` (string ou null)
    - Quando mercado é específico de um time:
        - `"HOME"` / `"AWAY"` ou identificador interno se o modelo evoluir.
- `interval` (string ou null)
    - Intervalo de tempo, ex.: `"0-60"` para "Resultado até 60:00 minutos".
- `updatedAt`
    - Última vez que qualquer odd/opção desse mercado foi atualizada (considerando todas as casas).
- `options`
    - Lista de **options normalizadas** (ver 5.3).

### 5.3 Estrutura de `options` (normalizadas)

```jsonc
"options": [
  {
    "outcome": "HOME",
    "label": "Grêmio",
    "sources": {
      "superbet": {
        "pagamentoAntecipado": true,
        "capturedAt": "2025-12-02T23:50:10Z",
        "updatedAt": "2025-12-03T00:10:40Z",
        "statusRaw": "active",

        "marketId": "547",
        "optionId": "1470",

        "price": {
          "decimal": 2.87,
          "fractional": null,
          "american": null
        },

        "meta": {
          "matchTags": "v2,price_boost"
        }
      },
      "betmgm": {
        "pagamentoAntecipado": false,
        "capturedAt": "2025-12-02T23:53:00Z",
        "updatedAt": "2025-12-03T00:09:40Z",
        "statusRaw": "OPEN",

        "marketId": "2582761341",
        "optionId": "3973166205",

        "price": {
          "decimal": 2.80,
          "fractional": "9/5",
          "american": "180"
        },

        "meta": {
          "criterionLabel": "Resultado Final",
          "lifetime": "FULL_TIME"
        }
      }
    }
  },

  {
    "outcome": "DRAW",
    "label": "Empate",
    "sources": {
      "superbet": { /* ... */ },
      "betmgm": { /* ... */ },
      "sportingbet": { /* ... */ }
    }
  }
]
```

#### Campos da option normalizada:

- `outcome` (string, ENUM)
    - Tipo de resultado normalizado:
        - Ex.: `HOME`, `DRAW`, `AWAY`, `OVER`, `UNDER`, etc. (ver seção 7).
- `label` (string)
    - Descrição amigável do resultado:
        - Ex.: `"Grêmio"`, `"X"`, `"Mais de 2,5"`, `"Grêmio e BTTS Sim"`.
- `sources.{source}`
    - Para cada casa que oferece essa option, um objeto com:
        - `pagamentoAntecipado` (bool):
            - `true` se essa option, nesse mercado, nessa casa, tem regra de pagamento antecipado.
        - `capturedAt` / `updatedAt`:
            - timestamps da **option** nessa casa.
        - `statusRaw`:
            - string original de status da casa (`"active"`, `"OPEN"`, `"Visible"`, etc.).
        - `marketId`:
            - ID do mercado na casa (string).
        - `optionId`:
            - ID da opção na casa (string).
        - `price`:
            - Objeto:
                - `decimal` (obrigatório): ex.: `2.87`
                - `fractional` (opcional): ex.: `"9/2"`
                - `american` (opcional): ex.: `"450"`
        - `meta`:
            - Objeto opcional para parâmetros brutos da casa (ex. `matchTags`, `criterion`, etc.).

---

## 6. Catálogo de Mercados Canonizados (`marketCanonical`)

Os mercados abaixo foram derivados e padronizados com base no esboço JSON fornecido. fileciteturn1file0L1-L40

### 6.1 `resultado_final`

- **Descrição**: Resultado 3-way (1X2) do jogo dentro do período/intervalo configurado.
- **Period**:
    - `RegularTime` (tempo regulamentar; quando `interval` é `null` assume-se 0–90).
- **Line**:
    - `null`.
- **Happening**:
    - `GOALS` (implícito).
- **`interval`**:
    - `null` → jogo inteiro (0–90 minutos).
    - `"0-3600"` → "Resultado até 60:00 minutos".
    - `"0-4500"` → "Resultado até 75:00 minutos".
- **Outcomes típicos**:
    - `HOME` → vitória do mandante.
    - `DRAW` → empate.
    - `AWAY` → vitória do visitante.
- **Exemplo de options**:
    - `HOME` – label `"Grêmio"` – price 2.87
    - `DRAW` – label `"X"` – price 3.10
    - `AWAY` – label `"Fluminense"` – price 2.62

> Mercados como:
> - `"Resultado Final"`
> - `"Resultado até 60:00 minutos"`
> - `"Resultado até 75:00 minutos"`
> - `"Resultado da Partida - VP (+2)"`
    > Devem ser normalizados todos como `marketCanonical = "resultado_final"`, mudando apenas `interval` e flags de pagamento antecipado conforme necessário.

---

### 6.2 `dupla_chance`

- **Descrição**: Dupla chance 3 combinações (1X, X2, 12) no período configurado.
- **Period**:
    - `RegularTime`.
- **Line**:
    - `null`.
- **Outcomes típicos**:
    - `HOME_OR_DRAW` → 1X
    - `DRAW_OR_AWAY` → X2
    - `HOME_OR_AWAY` → 12
- **Exemplo de options**:
    - `HOME_OR_DRAW` – `"Grêmio ou Empate"` – price 1.49
    - `DRAW_OR_AWAY` – `"Empate ou Fluminense"` – price 1.41
    - `HOME_OR_AWAY` – `"Grêmio ou Fluminense"` – price 1.37

---

### 6.3 `btts` (Both Teams To Score)

- **Descrição**: Ambas as equipes marcam pelo menos um gol no período.
- **Period**:
    - `RegularTime`.
- **Line**:
    - `null`.
- **Outcomes típicos**:
    - `YES`
    - `NO`
- **Exemplo de options**:
    - `YES` – `"Sim"` – price 1.90
    - `NO` – `"Não"` – price 1.80

---

### 6.4 `draw_no_bet`

- **Descrição**: Empate anula aposta (DNB). 2-way: time A ou B. Empate devolve stake.
- **Period**:
    - `RegularTime`, `FirstHalf` ou `SecondHalf`:
        - Ex.: `"Empate anula aposta - 1º Tempo"` → `period = "FirstHalf"`.
        - `"Empate anula aposta - 2º Tempo"` → `period = "SecondHalf"`.
- **Line**:
    - `null`.
- **Outcomes típicos**:
    - `HOME`
    - `AWAY`
- **Exemplo de options**:
    - `HOME` – `"Grêmio"` – price 1.93
    - `AWAY` – `"Fluminense"` – price 1.77

---

### 6.5 `resultado_total_gols`

- **Descrição**: Resultado final (1X2) combinado com total de gols over/under uma linha.
- **Period**:
    - `RegularTime`.
- **Line**:
    - Exemplo: `0.5`, `1.5`, `2.5`, `3.5`, `4.5`.
- **Outcomes típicos** (normalização):
    - `HOME_AND_OVER`
    - `HOME_AND_UNDER`
    - `DRAW_AND_OVER`
    - `DRAW_AND_UNDER`
    - `AWAY_AND_OVER`
    - `AWAY_AND_UNDER`
- **Exemplo**:
    - `"1 e Mais de 0.5"` → `HOME_AND_OVER`
    - `"X e Menos de 0.5"` → `DRAW_AND_UNDER`

---

### 6.6 `handicap_asian_2way`

- **Descrição**: Handicap asiático / handicap 2-way com push (home/away).
- **Period**:
    - `RegularTime`.
- **Line**:
    - Usar a linha específica da option:
        - Ex.: `-1.0`, `-0.75`, `-0.25`, `0`, `+0.25`, `+0.75`, `+1.0`, etc.
- **Outcomes típicos**:
    - `HOME_HANDICAP`
    - `AWAY_HANDICAP`
- **Normalização a partir do nome da option**:
    - `"Grêmio RS (-1)"` → `participant="HOME"`, `line=-1.0`, `outcome="HOME_HANDICAP"`.
    - `"Fluminense (1)"` → `participant="AWAY"`, `line=+1.0`, `outcome="AWAY_HANDICAP"`.

---

### 6.7 `resultado_btts`

- **Descrição**: Resultado final (1X2) combinado com BTTS (ambas marcam) e/ou “sem levar gol”.
- **Period**:
    - `RegularTime`.
- **Line**:
    - `null`.
- **Outcomes típicos**:
    - `HOME_AND_YES`
    - `HOME_AND_NO`
    - `DRAW_AND_YES`
    - `DRAW_AND_NO`
    - `AWAY_AND_YES`
    - `AWAY_AND_NO`
- **Exemplos**:
    - `"Grêmio RS e Sim"` → `HOME_AND_YES`
    - `"Grêmio RS e Não"` → `HOME_AND_NO`
    - `"Empate e Sim"` → `DRAW_AND_YES`
    - `"Fluminense e Não"` → `AWAY_AND_NO`

> Mercados tipo `"Resultado da Partida e Quais Equipes Marcam"` entram aqui, com outcomes de vitória + BTTS, vitória sem sofrer gol, empate com BTTS, etc.

---

### 6.8 `handicap_3way`

- **Descrição**: Handicap 3-way (home/draw/away) em cima de um placar ajustado.
- **Period**:
    - `RegularTime`.
- **Line**:
    - Opcionalmente, pode ser derivada de `(0:3)`, `(1:0)`, `(2:0)`, etc.
        - Pode ser armazenada em `line` como número auxiliar ou mantida apenas na `label`.
- **Outcomes típicos**:
    - `HOME_HCP`
    - `DRAW_HCP`
    - `AWAY_HCP`
- **Exemplo**:
    - `"Grêmio RS (0:3)"` → `HOME_HCP`
    - `"Empate (0:3)"` → `DRAW_HCP`
    - `"Fluminense (0:3)"` → `AWAY_HCP`

---

### 6.9 `dupla_chance_total_gols`

- **Descrição**: Dupla chance (1X, X2, 12) combinada com total de gols over/under uma linha.
- **Period**:
    - `RegularTime`.
- **Line**:
    - Ex.: `1.5`, `2.5`, `3.5`, `4.5`, `5.5`, `6.5`, `7.5`.
- **Outcomes típicos**:
    - `HOME_OR_DRAW_AND_OVER`
    - `HOME_OR_DRAW_AND_UNDER`
    - `DRAW_OR_AWAY_AND_OVER`
    - `DRAW_OR_AWAY_AND_UNDER`
    - `HOME_OR_AWAY_AND_OVER`
    - `HOME_OR_AWAY_AND_UNDER`
- **Exemplo**:
    - `"1X e Mais de 1.5"` → `HOME_OR_DRAW_AND_OVER`
    - `"12 e Menos de 3.5"` → `HOME_OR_AWAY_AND_UNDER`

---

### 6.10 `total_cartoes_over_under`

- **Descrição**: Total de cartões over/under.
- **Period**:
    - `RegularTime`.
- **Line**:
    - Ex.: `2.5`, `3.5`, `7.5`.
- **Happening**:
    - `CARDS`.
- **Participant**:
    - `null` → total geral (ambos times somados).
    - `"HOME"` → total de cartões do mandante.
    - `"AWAY"` → total de cartões do visitante.
- **Outcomes típicos**:
    - `OVER`
    - `UNDER`

---

### 6.11 `total_escanteios_over_under`

- **Descrição**: Total de escanteios over/under.
- **Period**:
    - `RegularTime`, `FirstHalf` ou `SecondHalf`.
- **Line**:
    - Ex.: `0.5`, `6.5`, etc.
- **Happening**:
    - `CORNERS`.
- **Participant**:
    - `null` → total dos dois times.
    - `"HOME"` / `"AWAY"` → total por time.
- **Outcomes típicos**:
    - `OVER`
    - `UNDER`

---

### 6.12 `total_gols_over_under`

- **Descrição**: Total de gols over/under no período configurado.
- **Period**:
    - `RegularTime`.
- **Line**:
    - Ex.: `0.5`, `1.5`, `2.5`, `3.5`, `4.5`, `5.5`, `6.5`, etc.
- **Happening**:
    - `GOALS`.
- **Outcomes típicos**:
    - `OVER`
    - `UNDER`
- **Exemplo**:
    - `"Mais de 6,5"` → `OVER`
    - `"Menos de 6,5"` → `UNDER`

---

## 7. Enum de Outcomes (`outcome`)

### 7.1 Outcomes básicos

- `HOME`
- `DRAW`
- `AWAY`

### 7.2 Dupla chance

- `HOME_OR_DRAW`   (1X)
- `DRAW_OR_AWAY`   (X2)
- `HOME_OR_AWAY`   (12)

### 7.3 Over/Under

- `OVER`
- `UNDER`

### 7.4 BTTS simples

- `YES`
- `NO`

### 7.5 Combinações com gols/btts

- `HOME_AND_OVER`
- `HOME_AND_UNDER`
- `DRAW_AND_OVER`
- `DRAW_AND_UNDER`
- `AWAY_AND_OVER`
- `AWAY_AND_UNDER`

- `HOME_AND_YES`
- `HOME_AND_NO`
- `DRAW_AND_YES`
- `DRAW_AND_NO`
- `AWAY_AND_YES`
- `AWAY_AND_NO`

### 7.6 Handicap

- `HOME_HANDICAP`
- `AWAY_HANDICAP`
- `HOME_HCP`
- `DRAW_HCP`
- `AWAY_HCP`

### 7.7 Fallback

- `OTHER` (somente para debug interno; não deve ser usado em produção – ver regra 9).

---

## 8. Resumo das responsabilidades de cada casa

Para integrar com o contrato:

1. **Gerar `normalizedId`** de acordo com as regras da seção 3 (obrigatório para scrapers novos).
2. Para cada mercado da casa:
    - Mapear o tipo para **um dos `marketCanonical`** da seção 6.
    - Setar `period`, `line`, `happening`, `participant`, `interval`.
3. Para cada opção do mercado:
    - Mapear para um `outcome` da seção 7.
    - Preencher:
        - `label` (nome amigável).
        - `price.decimal` (obrigatório), `fractional`/`american` se existir.
        - `pagamentoAntecipado` (se tiver regra específica).
        - `capturedAt` / `updatedAt`.
        - `marketId` / `optionId`.
        - `statusRaw` e `meta` conforme necessário.
4. Preencher `sources.{source}` no nível do evento com:
    - `eventSourceId`, `capturedAt`, `updatedAt`.
5. Preencher:
    - `pagamentoAntecipadoPorSource.{source}` conforme existência de qualquer mercado antecipado.
    - `isPagamentoAntecipado` se **qualquer** casa tiver `pagamentoAntecipado = true` em alguma option.

---

## 9. Regra de descarte de mercados não mapeados

1. Existe um **catálogo fechado de mercados canônicos** (seção 6).
2. Qualquer mercado de origem que **não consiga ser mapeado** para um dos valores **válidos** de `marketCanonical` deve:
    - Ser **descartado** no processo de normalização.
    - **Não** ser persistido no documento final.
3. Não é permitido:
    - Incluir `marketCanonical` arbitrário (novo) sem atualizar esta documentação e o enum central.
    - Persistir mercado com `marketCanonical = null` ou vazio.
    - Usar `OTHER` como `marketCanonical` em produção (serve apenas como código temporário em pipelines internos de debug).
4. O evento ainda pode ser persistido, mas **sem** esses mercados inválidos.

Essa regra garante:

- Nenhuma casa cria variações livres como `resultado_final_xpto`, `final_result`, `end_game`.
- Todo mercado persistido fala a **mesma língua canônica**, permitindo cálculo correto de EV+, surebet, etc.
