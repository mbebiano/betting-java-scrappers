#!/usr/bin/env python3
"""
Sportingbet scraper v2.

Fluxo:
1) Descobre todas as regioes/competicoes de futebol via /bettingoffer/counts.
2) Para cada competicao, traz a lista de fixtures via widgetdata (CompetitionLobby).
3) Enriquese cada fixture com todos os mercados via /bettingoffer/fixture-view.
4) Salva snapshot bruto por evento em uma colecao dedicada do Mongo ou JSON opcional.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence

import requests
from pymongo import MongoClient, ReplaceOne

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.append(str(PROJECT_ROOT))

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

BASE_URL = "https://www.sportingbet.bet.br"
SPORT_URL = f"{BASE_URL}/pt-br/sports/futebol-4"
COUNT_ENDPOINT = f"{BASE_URL}/cds-api/bettingoffer/counts"
WIDGET_ENDPOINT = f"{BASE_URL}/pt-br/sports/api/widget/widgetdata"
FIXTURE_ENDPOINT = f"{BASE_URL}/cds-api/bettingoffer/fixture-view"
DEFAULT_ACCESS_ID = "YTRhMjczYjctNTBlNy00MWZlLTliMGMtMWNkOWQxMThmZTI2"

DEFAULT_MONGO_URI = (
    "mongodb://flashscore:flashscore@31.220.90.232:27017/"
    "?authSource=admin&connectTimeoutMS=5000&serverSelectionTimeoutMS=5000"
)
DEFAULT_MONGO_DB = "flashscore"
DEFAULT_MONGO_COLLECTION = "events_sportingbet_raw"
SPORT_ID = 4  # Futebol

COMMON_HEADERS = {
    "accept": "application/json, text/plain, */*",
    "accept-language": "pt-BR,pt;q=0.9",
    "user-agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0"
    ),
    "x-device-type": "desktop",
    "x-from-product": "host-app",
}

CDS_HEADERS = {
    "x-bwin-cds-api": "https://row8-cds-api.itsfogo.com",
    "x-bwin-browser-url": SPORT_URL,
    "referer": SPORT_URL,
}

SPORTS_HEADERS = {
    "x-bwin-sports-api": "prod",
    "x-bwin-browser-url": SPORT_URL,
    "referer": SPORT_URL,
}

DEFAULT_MAX_WORKERS = 8
MAX_WORKERS_CAP = 12
DEFAULT_FLUSH_BATCH = 200
DEFAULT_TIMEOUT = 20.0
_THREAD_LOCAL = threading.local()
TEAM_PARTICIPANT_TYPES = {"HomeTeam", "AwayTeam", "Team", "Competitor"}
BASE_ALLOWED_MARKET_TYPES = {
    "3way",
    "BTTS",
    "DoubleChance",
    "DrawNoBet",
    "Handicap",
    "2wayHandicap",
    "ThreeWayAndBTTS",
    "ToWinAndBTTS",
    "ThreeWayAndOverUnder",
    "DoubleChanceAndOverUnder",
}
PROMO_MARKET_SUBTYPES = {
    "Build a Bet - Price Boost",  # price boost cards
    "BigOdd",  # mercado de odd turbinada
    "2Up3wayPricing",  # VP+2 (paga se abrir 2 gols)
}


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scraper Sportingbet v2 (dump bruto por evento)")
    parser.add_argument("--mongo-uri", default=DEFAULT_MONGO_URI, help="URI do MongoDB de destino")
    parser.add_argument("--mongo-db", default=DEFAULT_MONGO_DB, help="Banco do MongoDB")
    parser.add_argument(
        "--mongo-collection",
        default=DEFAULT_MONGO_COLLECTION,
        help="Colecao de destino para os dumps brutos",
    )
    parser.add_argument("--json", type=Path, help="Arquivo opcional para salvar todos os eventos coletados")
    parser.add_argument("--access-id", default=DEFAULT_ACCESS_ID, help="x-bwin-accessid usado nas chamadas CDS")
    parser.add_argument(
        "--hours",
        type=float,
        default=30,
        help="Filtra eventos ate N horas a partir de agora (UTC). Use 0 para desativar filtro.",
    )
    parser.add_argument(
        "--allow-past",
        action="store_true",
        help="Nao descarta eventos ja iniciados quando o filtro de horas estiver ativo.",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=DEFAULT_MAX_WORKERS,
        help="Quantidade de workers paralelos para detalhar eventos (>=1).",
    )
    parser.add_argument(
        "--flush-size",
        type=int,
        default=DEFAULT_FLUSH_BATCH,
        help="Quantidade de eventos enriquecidos antes de salvar no Mongo (>=1).",
    )
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="Timeout das requisicoes (s)")
    parser.add_argument(
        "--max-competitions",
        type=int,
        help="Limita numero de competicoes (debug). Se omitido, processa todas as encontradas.",
    )
    parser.add_argument(
        "--max-fixtures",
        type=int,
        help="Limita numero total de fixtures (debug). Se omitido, processa todos os encontrados.",
    )
    return parser.parse_args(argv)


def build_session() -> requests.Session:
    session = requests.Session()
    session.headers.update(COMMON_HEADERS)
    session.headers.update({"referer": SPORT_URL, "x-bwin-browser-url": SPORT_URL})
    return session


def _session() -> requests.Session:
    session = getattr(_THREAD_LOCAL, "session", None)
    if session is None:
        session = build_session()
        _THREAD_LOCAL.session = session
    return session


def fetch_counts(session: requests.Session, access_id: str, timeout: float) -> list[dict]:
    params = {
        "x-bwin-accessid": access_id,
        "lang": "pt-br",
        "country": "BR",
        "userCountry": "BR",
        "state": "Latest",
        "tagTypes": "Sport,Region,Tournament,Competition,VirtualCompetition,VirtualCompetitionGroup",
        "extendedTags": "Sport,Region,Tournament,Competition,VirtualCompetition,VirtualCompetitionGroup",
        "sportIds": str(SPORT_ID),
        "sortBy": "Tags",
        "participantMapping": "All",
        "includeDynamicCategories": "false",
    }
    headers = dict(COMMON_HEADERS)
    headers.update(CDS_HEADERS)
    resp = session.get(COUNT_ENDPOINT, params=params, headers=headers, timeout=timeout)
    resp.raise_for_status()
    data = resp.json()
    if not isinstance(data, list):
        logging.warning("Resposta inesperada em counts: %s", type(data))
        return []
    return data


def parse_competitions(counts: Iterable[dict]) -> list[dict]:
    regions: Dict[int, str] = {}
    competitions: list[dict] = []
    for item in counts:
        tag = item.get("tag") or {}
        tag_type = tag.get("type")
        if tag_type == "Region":
            region_id = tag.get("id")
            if region_id is not None:
                regions[region_id] = (tag.get("name") or {}).get("value") or ""
        elif tag_type == "Competition":
            competitions.append(tag)
    parsed: list[dict] = []
    for comp in competitions:
        comp_id = comp.get("id")
        region_id = comp.get("parentId")
        if comp_id is None or region_id is None:
            continue
        parsed.append(
            {
                "competitionId": comp_id,
                "compoundId": comp.get("compoundId") or f"{comp.get('sportId')}:{comp_id}",
                "regionId": region_id,
                "regionName": regions.get(region_id, ""),
                "competitionName": (comp.get("name") or {}).get("value") or "",
            }
        )
    return parsed


def extract_fixtures_from_widget(widget_payload: dict) -> list[dict]:
    fixtures: list[dict] = []

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "fixtures" and isinstance(value, list):
                    fixtures.extend([item for item in value if isinstance(item, dict)])
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(widget_payload)
    return fixtures


def fetch_competition_fixtures(session: requests.Session, competition: dict, args: argparse.Namespace) -> list[dict]:
    params = {
        "layoutSize": "Large",
        "page": "CompetitionLobby",
        "sportId": str(SPORT_ID),
        "regionId": str(competition["regionId"]),
        "competitionId": str(competition["competitionId"]),
        "compoundCompetitionId": competition["compoundId"],
        "widgetId": "/mobilesports-v1.0/layout/layout_standards/modules/competition/defaultcontainer",
        "shouldIncludePayload": "true",
    }
    headers = dict(COMMON_HEADERS)
    headers.update(SPORTS_HEADERS)
    resp = session.get(WIDGET_ENDPOINT, params=params, headers=headers, timeout=args.timeout)
    if resp.status_code != 200:
        logging.warning(
            "Competition %s (%s) retornou status %s",
            competition["competitionId"],
            competition["competitionName"],
            resp.status_code,
        )
        return []
    payload = resp.json()
    fixtures = extract_fixtures_from_widget(payload)
    for fx in fixtures:
        fx["_regionId"] = competition["regionId"]
        fx["_regionName"] = competition["regionName"]
        fx["_competitionId"] = competition["competitionId"]
        fx["_competitionName"] = competition["competitionName"]
    return fixtures


def dedupe_fixtures(fixtures: Iterable[dict]) -> list[dict]:
    seen: dict[str, dict] = {}
    for fx in fixtures:
        fixture_id = str(fx.get("id") or fx.get("fixtureId") or "")
        if not fixture_id:
            continue
        if fixture_id not in seen:
            seen[fixture_id] = fx
    return list(seen.values())


def parse_start(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        clean = value.strip()
        if clean.endswith("Z"):
            clean = clean[:-1] + "+00:00"
        return datetime.fromisoformat(clean)
    except Exception:
        return None


def filter_fixtures_by_date(fixtures: list[dict], hours: float, allow_past: bool) -> list[dict]:
    if hours <= 0:
        return fixtures
    lower = datetime.now(timezone.utc)
    upper = lower + timedelta(hours=hours)
    kept: list[dict] = []
    skipped = 0
    for fx in fixtures:
        kickoff = fx.get("startDate") or fx.get("cutOffDate")
        kickoff_dt = parse_start(kickoff)
        if kickoff_dt is None:
            kept.append(fx)
            continue
        if not allow_past and kickoff_dt < lower:
            skipped += 1
            continue
        if kickoff_dt > upper:
            skipped += 1
            continue
        kept.append(fx)
    if skipped:
        logging.info("Fixtures descartadas pelo filtro de datas: %s", skipped)
    return kept


def prune_fixture_raw(raw: dict | Any) -> dict | Any:
    """
    Poda o payload mantendo mercados relevantes e campos que sinalizam promo (boost) e VP+2.
    """
    if not isinstance(raw, dict):
        return raw
    fixture = raw.get("fixture")
    if not isinstance(fixture, dict):
        return raw

    def _market_params(market: dict) -> dict:
        params: dict[str, Any] = {}
        for item in market.get("parameters") or []:
            key = item.get("key")
            if key:
                params[key] = item.get("value")
        return params

    def _has_boost(market: dict) -> bool:
        if any("boost" in json.dumps(opt, ensure_ascii=False).lower() for opt in market.get("options") or []):
            return True
        params = _market_params(market)
        subtype = (params.get("MarketSubType") or "").strip()
        if subtype in PROMO_MARKET_SUBTYPES:
            return True
        return False

    def _should_keep_market(market: dict) -> bool:
        params = _market_params(market)
        mtype = params.get("MarketType") or ""
        period = params.get("Period")
        subtype = params.get("MarketSubType") or ""
        # Mantem se for promo/boost (ex: VP+2 usa MarketSubType 2Up3wayPricing)
        if _has_boost(market):
            return True
        if subtype in PROMO_MARKET_SUBTYPES:
            return True
        if mtype not in BASE_ALLOWED_MARKET_TYPES:
            return False
        if period and period != "RegularTime":
            return False
        if mtype in {"3way", "Handicap", "2wayHandicap", "DoubleChance", "DrawNoBet", "BTTS"}:
            if params.get("Happening") not in {None, "Goal"}:
                return False
        if mtype == "3way" and params.get("RangeValue"):
            return False
        return True

    def _slim_options(market: dict) -> list[dict]:
        slimmed: list[dict] = []
        for opt in market.get("options") or []:
            price = opt.get("price") or {}
            slimmed.append(
                {
                    "id": opt.get("id"),
                    "name": (opt.get("name") or {}).get("value") if isinstance(opt.get("name"), dict) else opt.get("name"),
                    "status": opt.get("status"),
                    "code": opt.get("code"),
                    "price": price,
                    "boostedPrice": opt.get("boostedPrice"),
                }
            )
        return slimmed

    def _slim_market(market: dict) -> dict:
        params = _market_params(market)
        return {
            "id": market.get("id"),
            "name": (market.get("name") or {}).get("value") if isinstance(market.get("name"), dict) else market.get("name"),
            "status": market.get("status"),
            "parameters": params,
            # Mantem boostedPrice para identificar cotas aumentadas / VP+2 (MarketSubType 2Up3wayPricing).
            "options": _slim_options(market),
        }

    option_markets = fixture.get("optionMarkets") or []
    filtered_markets = [_slim_market(m) for m in option_markets if _should_keep_market(m)]
    # Se nada passou no filtro, mantemos todos os mercados (ainda slim) para não perder referência.
    if not filtered_markets:
        filtered_markets = [_slim_market(m) for m in option_markets]

    participants: list[dict] = []
    for item in fixture.get("participants") or []:
        p_type = (item.get("properties") or {}).get("type")
        if p_type in TEAM_PARTICIPANT_TYPES:
            participants.append(
                {
                    "id": item.get("id"),
                    "participantId": item.get("participantId"),
                    "name": item.get("name"),
                    "status": item.get("status"),
                    "image": item.get("image"),
                    "properties": item.get("properties"),
                }
            )

    slim_fixture = {
        "id": fixture.get("id"),
        "sourceId": fixture.get("sourceId"),
        "name": fixture.get("name"),
        "fixtureType": fixture.get("fixtureType"),
        "context": fixture.get("context"),
        "startDate": fixture.get("startDate"),
        "cutOffDate": fixture.get("cutOffDate"),
        "sport": fixture.get("sport"),
        "competition": fixture.get("competition"),
        "region": fixture.get("region"),
        "participants": participants,
        "scoreboard": fixture.get("scoreboard"),
        "totalMarketsCount": fixture.get("totalMarketsCount"),
        "priceBoostCount": fixture.get("priceBoostCount"),
        "addons": fixture.get("addons"),
        "marketGroups": fixture.get("marketGroups"),
        "optionMarkets": filtered_markets,
    }
    return {"fixture": slim_fixture}


def is_valid_fixture(fixture: dict | Any) -> bool:
    if not isinstance(fixture, dict):
        return False
    sport_id = (
        (fixture.get("sport") or {}).get("id")
        or (fixture.get("scoreboard") or {}).get("sportId")
    )
    if sport_id and sport_id != SPORT_ID:
        return False
    participants = fixture.get("participants") or []
    if sum(1 for _ in participants if isinstance(_, dict)) < 2:
        return False
    option_markets = fixture.get("optionMarkets") or []
    if not option_markets:
        return False
    comp_name = ((fixture.get("competition") or {}).get("name") or {}).get("value") or ""
    comp_name_lower = comp_name.lower()
    if "múltiplas" in comp_name_lower or "multipla" in comp_name_lower:
        return False
    fixture_type = (fixture.get("fixtureType") or "").lower()
    if fixture_type and fixture_type != "pairgame":
        return False
    return True


def fetch_fixture_detail(
    session: requests.Session | None, fixture_id: str, access_id: str, timeout: float
) -> dict | None:
    session = session or _session()
    params = {
        "x-bwin-accessid": access_id,
        "lang": "pt-br",
        "country": "BR",
        "userCountry": "BR",
        "offerMapping": "All",
        "scoreboardMode": "Full",
        "fixtureIds": fixture_id,
        "state": "Latest",
        "includePrecreatedBetBuilder": "true",
        "supportVirtual": "true",
        "isBettingInsightsEnabled": "false",
        "useRegionalisedConfiguration": "true",
        "includeRelatedFixtures": "false",
        "statisticsModes": "None",
    }
    headers = dict(COMMON_HEADERS)
    headers.update(CDS_HEADERS)
    for attempt in range(1, 4):
        try:
            resp = session.get(FIXTURE_ENDPOINT, params=params, headers=headers, timeout=timeout)
            if resp.status_code == 200:
                payload = resp.json()
                if isinstance(payload, dict) and payload.get("fixture"):
                    return payload
                logging.warning("Fixture %s sem campo fixture (tentativa %s)", fixture_id, attempt)
            else:
                logging.warning("Status %s ao detalhar fixture %s (tentativa %s)", resp.status_code, fixture_id, attempt)
        except requests.RequestException as exc:
            logging.warning("Erro ao detalhar fixture %s (tentativa %s): %s", fixture_id, attempt, exc)
        if attempt < 3:
            time.sleep(0.5 * attempt)
    return None


def enrich_fixtures(
    fixtures: list[dict],
    access_id: str,
    timeout: float,
    max_workers: int = DEFAULT_MAX_WORKERS,
) -> Iterable[dict]:
    if not fixtures:
        return []
    max_workers = max(1, min(max_workers, MAX_WORKERS_CAP))
    session = _session()
    if max_workers == 1:
        for fx in fixtures:
            fixture_id = str(fx.get("id") or fx.get("fixtureId") or "")
            detail = fetch_fixture_detail(session, fixture_id, access_id, timeout) if fixture_id else None
            if detail:
                fx["raw"] = detail
            yield fx
        return

    with ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="sportingbet") as executor:
        future_map = {}
        for fx in fixtures:
            fixture_id = str(fx.get("id") or fx.get("fixtureId") or "")
            if not fixture_id:
                continue
            future = executor.submit(fetch_fixture_detail, None, fixture_id, access_id, timeout)
            future_map[future] = fx
        for future in as_completed(future_map):
            fx = future_map[future]
            try:
                detail = future.result()
            except Exception as exc:  # pragma: no cover - protecao runtime
                logging.warning("Detalhe falhou para fixture %s: %s", fx.get("id"), exc)
                detail = None
            if detail:
                fx["raw"] = detail
            yield fx


def save_raw(fixtures: list[dict], args: argparse.Namespace, client: MongoClient | None = None, collection=None) -> None:
    if not fixtures:
        return
    close_client = False
    if collection is None:
        if client is None:
            client = MongoClient(args.mongo_uri)
            close_client = True
        collection = client[args.mongo_db][args.mongo_collection]
    operations: list[ReplaceOne] = []
    captured_at = datetime.utcnow()
    skipped_invalid = 0
    for fx in fixtures:
        fixture_id = str(fx.get("id") or fx.get("fixtureId") or "")
        if not fixture_id:
            continue
        raw = fx.get("raw") or fx
        raw = prune_fixture_raw(raw)
        fixture_block = raw.get("fixture", {}) if isinstance(raw, dict) else {}
        if not is_valid_fixture(fixture_block):
            skipped_invalid += 1
            continue
        doc = {
            "eventId": fixture_id,
            "source": "sportingbet",
            "capturedAt": captured_at,
            "regionId": fx.get("_regionId"),
            "regionName": fx.get("_regionName"),
            "competitionId": fx.get("_competitionId"),
            "competitionName": fx.get("_competitionName"),
            "startDate": fixture_block.get("startDate") if isinstance(fixture_block, dict) else fx.get("startDate"),
            "raw": raw,
        }
        operations.append(ReplaceOne({"eventId": fixture_id}, doc, upsert=True))
    if operations:
        result = collection.bulk_write(operations, ordered=False)
        logging.info(
            "Dump salvo em %s.%s (upserted=%s, modified=%s)",
            args.mongo_db,
            args.mongo_collection,
            result.upserted_count,
            result.modified_count,
        )
    if skipped_invalid:
        logging.info("Fixtures descartados por falta de mercados/participantes ou tipo invalido: %s", skipped_invalid)
    if close_client and client:
        client.close()


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)
    session = build_session()
    logging.info("Coletando competicoes de futebol na Sportingbet...")
    counts = fetch_counts(session, args.access_id, args.timeout)
    competitions = parse_competitions(counts)
    if args.max_competitions:
        competitions = competitions[: args.max_competitions]
    logging.info("Competicoes encontradas: %s", len(competitions))

    all_fixtures: list[dict] = []
    for idx, comp in enumerate(competitions, 1):
        fixtures = fetch_competition_fixtures(session, comp, args)
        all_fixtures.extend(fixtures)
        if idx % 10 == 0 or idx == len(competitions):
            logging.info("... %s/%s competicoes processadas (%s fixtures ate agora)", idx, len(competitions), len(all_fixtures))
        time.sleep(0.05)

    fixtures = dedupe_fixtures(all_fixtures)
    logging.info("Fixtures unicas apos dedupe: %s", len(fixtures))
    fixtures = filter_fixtures_by_date(fixtures, args.hours, args.allow_past)
    if args.max_fixtures:
        fixtures = fixtures[: args.max_fixtures]
    logging.info("Fixtures apos filtro: %s", len(fixtures))

    mongo_client = MongoClient(args.mongo_uri)
    collection = mongo_client[args.mongo_db][args.mongo_collection]

    buffer: list[dict] = []
    enriched_all: list[dict] = []
    for fx in enrich_fixtures(fixtures, args.access_id, args.timeout, args.max_workers):
        enriched_all.append(fx)
        buffer.append(fx)
        if len(buffer) >= max(1, args.flush_size):
            save_raw(buffer, args, client=mongo_client, collection=collection)
            buffer.clear()
    if buffer:
        save_raw(buffer, args, client=mongo_client, collection=collection)
    mongo_client.close()
    logging.info("Eventos persistidos no Mongo: %s", len(enriched_all))

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(
            json.dumps(
                {"scraped_at": datetime.utcnow().isoformat(), "events": enriched_all},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        logging.info("Dump salvo em %s", args.json)
    logging.info("Concluido.")


if __name__ == "__main__":
    main()
