#!/usr/bin/env python3
"""
Scraper BetMGM (Kambi) para eventos de futebol.

Fluxo:
1) Usa a GraphQL AllLeaguesPaginatedQuery para listar eventos de futebol até N dias à frente.
2) Para cada eventId, baixa o detalhe completo via offering-api (betoffer/event/{id}.json).
3) Salva o raw em uma coleção dedicada no Mongo ou em JSON opcional.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set

import requests
from pymongo import MongoClient, ReplaceOne

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

GRAPHQL_URL = "https://www.betmgm.bet.br/api/lmbas"
OFFERING_EVENT_URL = (
    "https://us1.offering-api.kambicdn.com/offering/v2018/betmgmbr/betoffer/event/{event_id}.json"
)

DEFAULT_MONGO_URI = (
    "mongodb://flashscore:flashscore@31.220.90.232:27017/"
    "?authSource=admin&connectTimeoutMS=5000&serverSelectionTimeoutMS=5000"
)
DEFAULT_MONGO_DB = "flashscore"
DEFAULT_MONGO_COLLECTION = "betmgm"
DEFAULT_DAYS = 4
DEFAULT_FIRST = 50
DEFAULT_MAX_WORKERS = 8

GRAPHQL_HEADERS = {
    "content-type": "application/json",
    "x-app-id": "sportsbook",
    "x-client-id": "sportsbook",
    "x-app-version": "3.57.0",
    "x-client-version": "3.57.0",
    "x-kambi-env": "PROD",
}

OFFERING_HEADERS = {
    "accept": "*/*",
    "origin": "https://www.betmgm.bet.br",
    "referer": "https://www.betmgm.bet.br/",
    "user-agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    ),
}


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scraper BetMGM (Kambi) - futebol")
    parser.add_argument("--days", type=int, default=DEFAULT_DAYS, help="Dias futuros a coletar (upcomingDays)")
    parser.add_argument("--mongo-uri", default=DEFAULT_MONGO_URI, help="URI do MongoDB de destino")
    parser.add_argument("--mongo-db", default=DEFAULT_MONGO_DB, help="Banco no MongoDB")
    parser.add_argument(
        "--mongo-collection",
        default=DEFAULT_MONGO_COLLECTION,
        help="Coleção de destino para os dumps brutos",
    )
    parser.add_argument("--json", help="Arquivo opcional para salvar todos os eventos coletados")
    parser.add_argument(
        "--first",
        type=int,
        default=DEFAULT_FIRST,
        help="Tamanho da página na query GraphQL (primeiros N itens por página)",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=DEFAULT_MAX_WORKERS,
        help="Workers para detalhar eventos em paralelo",
    )
    return parser.parse_args(argv)


def graph_payload(after: str, first: int, days: int) -> dict:
    variables = {
        "after": after,
        "filter": {"eventType": "MATCH", "sport": "football", "upcomingDays": days},
        "first": first,
        "grouping": ["COUNTRY_AZ", "LEAGUE_POPULARITY"],
        "lang": "pt_BR",
        "market": "BR",
        "offering": "betmgmbr",
        "popularEventsGroup": [{"country": "brazil", "league": "brasileirao_serie_a", "sport": "football"}],
        "skipAllLeaguesSportsQuery": False,
    }
    return {
        "operationName": "AllLeaguesPaginatedQuery",
        "variables": variables,
        "extensions": {
            "persistedQuery": {
                "version": 1,
                "sha256Hash": "b858aece8798aeb4f1d93bfd29d95ac3dc0932f609a1710dd2d55bd5988eb954",
            }
        },
    }


def fetch_events_graphql(session: requests.Session, days: int, first: int) -> List[dict]:
    events: List[dict] = []
    after = "0"
    page = 0
    while True:
        payload = graph_payload(after, first, days)
        resp = session.post(GRAPHQL_URL, headers=GRAPHQL_HEADERS, json=payload, timeout=15)
        resp.raise_for_status()
        data = resp.json()
        edges = (
            data.get("data", {})
            .get("viewer", {})
            .get("sports", {})
            .get("sportsEventsConnection", {})
            .get("edges", [])
        )
        page_info = (
            data.get("data", {})
            .get("viewer", {})
            .get("sports", {})
            .get("sportsEventsConnection", {})
            .get("pageInfo", {})
        )
        page += 1
        logging.info("Página %s: edges=%s", page, len(edges))
        for edge in edges:
            node = edge.get("node") or {}
            for group in node.get("groups") or []:
                for event in group.get("events") or []:
                    events.append(event)
        has_next = page_info.get("hasNextPage")
        end_cursor = page_info.get("endCursor")
        if not has_next or end_cursor is None:
            break
        after = str(end_cursor)
    return events


def dedupe_events(events: Iterable[dict]) -> List[dict]:
    seen: Set[str] = set()
    deduped: List[dict] = []
    for evt in events:
        eid = str(evt.get("id") or evt.get("eventId") or "")
        if not eid or eid in seen:
            continue
        seen.add(eid)
        deduped.append(evt)
    return deduped


def fetch_event_detail(session: requests.Session, event_id: str) -> Optional[dict]:
    url = OFFERING_EVENT_URL.format(event_id=event_id)
    params = {
        "channel_id": "1",
        "client_id": "200",
        "includeParticipants": "true",
        "lang": "pt_BR",
        "market": "BR",
        "range_size": "1",
    }
    resp = session.get(url, headers=OFFERING_HEADERS, params=params, timeout=15)
    if resp.status_code != 200:
        logging.warning("Detalhe %s status %s", event_id, resp.status_code)
        return None
    try:
        return resp.json()
    except ValueError:
        logging.warning("JSON inválido no detalhe %s", event_id)
        return None


def enrich_events(events: List[dict], max_workers: int) -> List[dict]:
    session = requests.Session()
    enriched: List[dict] = []
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        future_map = {
            executor.submit(fetch_event_detail, session, str(evt.get("id") or evt.get("eventId"))): evt for evt in events
        }
        for idx, future in enumerate(as_completed(future_map), 1):
            evt = future_map[future]
            detail = None
            try:
                detail = future.result()
            except Exception as exc:  # pragma: no cover
                logging.warning("Erro detalhe %s: %s", evt.get("id"), exc)
            if detail:
                evt["raw"] = detail
                enriched.append(evt)
            if idx % 100 == 0:
                logging.info("... %s/%s eventos enriquecidos", idx, len(events))
    return enriched


def save_raw(events: List[dict], args: argparse.Namespace) -> None:
    if not events:
        logging.warning("Nenhum evento para salvar")
        return
    client = MongoClient(args.mongo_uri)
    collection = client[args.mongo_db][args.mongo_collection]
    operations: List[ReplaceOne] = []
    captured_at = datetime.now(tz=timezone.utc)
    for evt in events:
        raw = evt.get("raw") or evt
        event_id = str(evt.get("id") or evt.get("eventId") or raw.get("eventId") or "")
        if not event_id:
            continue
        doc = {
            "eventId": event_id,
            "source": "betmgm",
            "capturedAt": captured_at,
            "matchName": evt.get("name") or evt.get("englishName"),
            "start": evt.get("start"),
            "sport": evt.get("sport"),
            "raw": raw,
        }
        operations.append(ReplaceOne({"eventId": event_id}, doc, upsert=True))
    if operations:
        result = collection.bulk_write(operations, ordered=False)
        logging.info(
            "Dump salvo em %s.%s (upserted=%s, modified=%s)",
            args.mongo_db,
            args.mongo_collection,
            result.upserted_count,
            result.modified_count,
        )
    client.close()


def save_json(events: List[dict], path: str) -> None:
    payload = {"scraped_at": datetime.now(tz=timezone.utc).isoformat(), "events": events}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    logging.info("Dump salvo em %s", path)


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)
    session = requests.Session()
    logging.info("Coletando eventos BetMGM | days=%s | page_size=%s", args.days, args.first)
    events = fetch_events_graphql(session, args.days, args.first)
    events = dedupe_events(events)
    logging.info("Eventos coletados (dedupe): %s", len(events))
    enriched = enrich_events(events, args.max_workers)
    logging.info("Eventos enriquecidos: %s", len(enriched))
    save_raw(enriched, args)
    if args.json:
        save_json(enriched, args.json)
    logging.info("Concluído.")


if __name__ == "__main__":
    main()
