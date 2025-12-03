# Betting Scrapers Microservice

Java 17+ microservice for scraping betting data from multiple providers and normalizing it to a unified contract.

> **Note**: While the requirements specify Java 21, this implementation uses Java 17 for compatibility with the build environment. The code is forward-compatible with Java 21.

## Architecture

This project follows **Hexagonal Architecture** (Ports and Adapters) principles:

```
src/main/java/com/superodds/
├── domain/                    # Core business logic
│   ├── model/                # Domain entities (UnifiedEvent, UnifiedMarket, etc.)
│   └── ports/                # Interfaces for adapters (EventRepository, ScraperGateway)
├── application/              # Use cases
│   └── usecase/             # RefreshEventsUseCase
└── infrastructure/           # External adapters
    ├── config/              # Spring configuration
    ├── persistence/         # MongoDB implementation
    ├── rest/                # REST API controllers
    └── scraper/             # Scraper implementations
        ├── superbet/        # Superbet scraper
        ├── sportingbet/     # Sportingbet scraper (TODO)
        └── betmgm/          # BetMGM scraper (TODO)
```

## Domain Contract

The unified contract is defined in the domain model classes based on `DocumentacaoContrato.md`:

- **UnifiedEvent**: Root entity representing a betting event
- **UnifiedMarket**: Normalized market (resultado_final, btts, etc.)
- **UnifiedMarketOption**: Normalized option (HOME, DRAW, AWAY, OVER, UNDER, etc.)
- **MarketType**: Enum of canonical market types
- **OutcomeType**: Enum of canonical outcome types

All scrapers must map their provider-specific data to this contract. Markets that cannot be mapped are **discarded** (Rule 9 from the documentation).

## Requirements

- Java 21+
- Maven 3.6+
- MongoDB (default: `mongodb://flashscore:flashscore@31.220.90.232:27017/`)

## Build

```bash
mvn clean compile
```

## Run

```bash
mvn spring-boot:run
```

The application will start on port 8080.

## API Endpoints

### POST /events/refresh

Triggers parallel scraping from all configured providers and persists normalized events to MongoDB.

**Response:**
```json
{
  "eventsByProvider": {
    "superbet": 150,
    "sportingbet": 85,
    "betmgm": 120
  },
  "errors": {},
  "totalUpserted": 355
}
```

## Configuration

Configuration is in `src/main/resources/application.properties`:

```properties
# MongoDB Configuration
mongodb.uri=mongodb://flashscore:flashscore@31.220.90.232:27017/?authSource=admin&connectTimeoutMS=5000&serverSelectionTimeoutMS=5000
mongodb.database=flashscore
mongodb.collection=betsv2

# Server Configuration
server.port=8080
```

These can be overridden with environment variables:
- `MONGODB_URI`
- `MONGODB_DATABASE`
- `MONGODB_COLLECTION`

## MongoDB Persistence

The microservice persists normalized events to the `betsv2` collection following these rules:

1. **Primary Key**: `normalizedId` (generated from sport, date, home, away)
2. **Upsert Logic**: Existing events are merged with incoming data:
   - Sources are merged by provider name
   - Markets are merged by canonical key (marketCanonical + period + line + happening + participant + interval)
   - Options are merged by outcome type
   - Per-source data always updates to latest
3. **Discard Rule**: Markets that don't map to a valid `MarketType` are discarded

## Normalized ID Generation

Following the contract documentation (Section 3):

```
Format: <SPORT_NORMALIZADO>-<DATAHORA_UTC>-<HOME_NORMALIZADO>-<AWAY_NORMALIZADO>
Example: FUTEBOL-20251203T003000Z-GREMIO-FLUMINENSE
```

Normalization rules:
1. Convert to uppercase
2. Remove accents (Grêmio → GREMIO)
3. Replace non-alphanumeric with underscore
4. Collapse multiple underscores
5. Remove leading/trailing underscores

## Implemented Scrapers

### Superbet ✅

Fully implemented scraper that:
- Fetches events from Superbet API
- Maps markets 547, 539, 531, 555, 546, 530, 532, 542, 557 to canonical types
- Normalizes outcomes (HOME, DRAW, AWAY, YES, NO, HOME_AND_YES, etc.)
- Generates normalized IDs
- Handles price boost tags

### Sportingbet ✅

Fully implemented scraper based on sportingbetraw.py that:
- Fetches competitions via /bettingoffer/counts endpoint
- Retrieves fixtures from CompetitionLobby widget
- Enriches events with detailed markets via /bettingoffer/fixture-view
- Maps Sportingbet MarketType parameters to canonical market types:
  - 3way → resultado_final
  - BTTS → btts
  - DoubleChance → dupla_chance
  - DrawNoBet → draw_no_bet
  - Handicap → handicap_3way
  - 2wayHandicap → handicap_asian_2way
  - ThreeWayAndBTTS, ToWinAndBTTS → resultado_btts
  - ThreeWayAndOverUnder → resultado_total_gols
  - DoubleChanceAndOverUnder → dupla_chance_total_gols
- Normalizes outcomes with proper code mapping
- Handles price boost detection via boostedPrice field
- Supports parallel enrichment with configurable workers

### BetMGM ✅

Fully implemented scraper based on betmgmraw.py that:
- Uses GraphQL AllLeaguesPaginatedQuery to list football events
- Fetches detailed markets from Kambi offering-api
- Maps Kambi criterion labels to canonical market types
- Supports all major market types:
  - Full Time Result / Match Result → resultado_final
  - Both Teams To Score → btts
  - Double Chance → dupla_chance
  - Draw No Bet → draw_no_bet
  - Asian Handicap → handicap_asian_2way
  - European Handicap → handicap_3way
  - Total Goals Over/Under → total_gols_over_under
  - Result + Total Goals → resultado_total_gols
  - Result + BTTS → resultado_btts
  - Double Chance + Total Goals → dupla_chance_total_gols
  - Total Corners → total_escanteios_over_under
  - Total Cards → total_cartoes_over_under
- Handles decimal, fractional, and American odds formats
- Supports parallel event enrichment

## Parallel Execution

The `RefreshEventsUseCase` executes all scrapers in parallel using a fixed thread pool:
- Each scraper runs independently
- Failures in one scraper don't affect others
- Results are collected and merged before persistence

## Market Mapping

Each scraper must map provider-specific markets to canonical types defined in `MarketType`:

- `resultado_final` - 1X2 result
- `dupla_chance` - Double chance
- `btts` - Both teams to score
- `draw_no_bet` - Draw no bet
- `resultado_total_gols` - Result + total goals
- `handicap_asian_2way` - Asian handicap
- `resultado_btts` - Result + BTTS
- `handicap_3way` - 3-way handicap
- `dupla_chance_total_gols` - Double chance + total goals
- `total_cartoes_over_under` - Total cards over/under
- `total_escanteios_over_under` - Total corners over/under
- `total_gols_over_under` - Total goals over/under

See `SuperbetMarketMapper.java` for a reference implementation.

## Development

### Adding a New Scraper

1. Create a new package in `infrastructure/scraper/<provider>/`
2. Implement `ScraperGateway` interface
3. Annotate with `@Component` for Spring auto-detection
4. Map provider markets to canonical types
5. Map provider outcomes to canonical outcomes
6. Generate proper normalized IDs

### Testing

```bash
# Run all tests
mvn test

# Package
mvn package
```

## References

- **DocumentacaoContrato.md**: Complete contract specification
- **references/objectscontract/**: Domain model reference
- **references/python-scrappers/**: Python scraper references
- **references/commons/**: Normalization utilities reference

## License

Copyright © 2025 SuperOdds
