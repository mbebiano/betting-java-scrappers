package com.superodds.infrastructure.scraper.superbet;

import com.superodds.domain.model.*;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Superbet-specific markets and outcomes to canonical types.
 */
public class SuperbetMarketMapper {

    private static final Pattern LINE_PATTERN = Pattern.compile("\\((\\d+\\.?\\d*)\\)");

    public record MarketMapping(
        MarketType marketType,
        PeriodType period,
        BigDecimal line,
        HappeningType happening,
        ParticipantSide participant,
        String interval
    ) {}

    /**
     * Maps Superbet market ID and name to canonical market type.
     */
    public static MarketMapping mapMarket(int marketId, String marketName) {
        return switch (marketId) {
            case 547 -> new MarketMapping(
                MarketType.RESULTADO_FINAL,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case 539 -> new MarketMapping(
                MarketType.BTTS,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case 531 -> new MarketMapping(
                MarketType.DUPLA_CHANCE,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case 555 -> new MarketMapping(
                MarketType.DRAW_NO_BET,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case 546 -> new MarketMapping(
                MarketType.HANDICAP_3WAY,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case 530 -> new MarketMapping(
                MarketType.HANDICAP_ASIAN_2WAY,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case 532 -> new MarketMapping(
                MarketType.RESULTADO_BTTS,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case 542 -> {
                BigDecimal line = extractLineFromName(marketName);
                yield new MarketMapping(
                    MarketType.DUPLA_CHANCE_TOTAL_GOLS,
                    PeriodType.REGULAR_TIME,
                    line,
                    HappeningType.GOALS,
                    null,
                    null
                );
            }
            case 557 -> {
                BigDecimal line = extractLineFromName(marketName);
                yield new MarketMapping(
                    MarketType.RESULTADO_TOTAL_GOLS,
                    PeriodType.REGULAR_TIME,
                    line,
                    HappeningType.GOALS,
                    null,
                    null
                );
            }
            default -> null; // Unmapped market, will be discarded
        };
    }

    /**
     * Maps Superbet option name to canonical outcome type.
     */
    public static OutcomeType mapOutcome(int marketId, String marketName, String optionName) {
        return switch (marketId) {
            case 547 -> // Resultado Final (1X2)
                switch (optionName) {
                    case "1" -> OutcomeType.HOME;
                    case "X" -> OutcomeType.DRAW;
                    case "2" -> OutcomeType.AWAY;
                    default -> OutcomeType.OTHER;
                };
            case 539 -> // Ambas as Equipes Marcam
                switch (optionName) {
                    case "Sim" -> OutcomeType.YES;
                    case "Não" -> OutcomeType.NO;
                    default -> OutcomeType.OTHER;
                };
            case 531 -> // Dupla Chance
                switch (optionName) {
                    case "1X" -> OutcomeType.HOME_OR_DRAW;
                    case "X2" -> OutcomeType.DRAW_OR_AWAY;
                    case "12" -> OutcomeType.HOME_OR_AWAY;
                    default -> OutcomeType.OTHER;
                };
            case 555 -> // Empate Anula Aposta
                // Option name is team name, need to use code or position
                optionName.contains("1") ? OutcomeType.HOME : 
                optionName.contains("2") ? OutcomeType.AWAY : 
                OutcomeType.OTHER;
            case 532 -> // Resultado Final & Ambas Marcam
                mapResultadoBttsOutcome(optionName);
            case 542 -> // Dupla Chance & Total de Gols
                mapDuplaChanceTotalGolsOutcome(optionName);
            case 557 -> // Resultado Final & Total de Gols
                mapResultadoTotalGolsOutcome(optionName);
            default -> OutcomeType.OTHER;
        };
    }

    private static OutcomeType mapResultadoBttsOutcome(String optionName) {
        String normalized = optionName.toLowerCase();
        if (normalized.contains("1") || normalized.contains("home")) {
            if (normalized.contains("sim") || normalized.contains("yes")) {
                return OutcomeType.HOME_AND_YES;
            } else if (normalized.contains("não") || normalized.contains("no")) {
                return OutcomeType.HOME_AND_NO;
            }
        } else if (normalized.contains("x") || normalized.contains("empate") || normalized.contains("draw")) {
            if (normalized.contains("sim") || normalized.contains("yes")) {
                return OutcomeType.DRAW_AND_YES;
            } else if (normalized.contains("não") || normalized.contains("no")) {
                return OutcomeType.DRAW_AND_NO;
            }
        } else if (normalized.contains("2") || normalized.contains("away")) {
            if (normalized.contains("sim") || normalized.contains("yes")) {
                return OutcomeType.AWAY_AND_YES;
            } else if (normalized.contains("não") || normalized.contains("no")) {
                return OutcomeType.AWAY_AND_NO;
            }
        }
        return OutcomeType.OTHER;
    }

    private static OutcomeType mapDuplaChanceTotalGolsOutcome(String optionName) {
        String normalized = optionName.toLowerCase();
        if (normalized.contains("1x")) {
            if (normalized.contains("mais") || normalized.contains("over")) {
                return OutcomeType.HOME_OR_DRAW_AND_OVER;
            } else if (normalized.contains("menos") || normalized.contains("under")) {
                return OutcomeType.HOME_OR_DRAW_AND_UNDER;
            }
        } else if (normalized.contains("x2")) {
            if (normalized.contains("mais") || normalized.contains("over")) {
                return OutcomeType.DRAW_OR_AWAY_AND_OVER;
            } else if (normalized.contains("menos") || normalized.contains("under")) {
                return OutcomeType.DRAW_OR_AWAY_AND_UNDER;
            }
        } else if (normalized.contains("12")) {
            if (normalized.contains("mais") || normalized.contains("over")) {
                return OutcomeType.HOME_OR_AWAY_AND_OVER;
            } else if (normalized.contains("menos") || normalized.contains("under")) {
                return OutcomeType.HOME_OR_AWAY_AND_UNDER;
            }
        }
        return OutcomeType.OTHER;
    }

    private static OutcomeType mapResultadoTotalGolsOutcome(String optionName) {
        String normalized = optionName.toLowerCase();
        if (normalized.contains("1 e")) {
            if (normalized.contains("mais") || normalized.contains("over")) {
                return OutcomeType.HOME_AND_OVER;
            } else if (normalized.contains("menos") || normalized.contains("under")) {
                return OutcomeType.HOME_AND_UNDER;
            }
        } else if (normalized.contains("x e")) {
            if (normalized.contains("mais") || normalized.contains("over")) {
                return OutcomeType.DRAW_AND_OVER;
            } else if (normalized.contains("menos") || normalized.contains("under")) {
                return OutcomeType.DRAW_AND_UNDER;
            }
        } else if (normalized.contains("2 e")) {
            if (normalized.contains("mais") || normalized.contains("over")) {
                return OutcomeType.AWAY_AND_OVER;
            } else if (normalized.contains("menos") || normalized.contains("under")) {
                return OutcomeType.AWAY_AND_UNDER;
            }
        }
        return OutcomeType.OTHER;
    }

    private static BigDecimal extractLineFromName(String marketName) {
        Matcher matcher = LINE_PATTERN.matcher(marketName);
        if (matcher.find()) {
            try {
                return new BigDecimal(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static String getMarketKey(MarketMapping mapping) {
        return String.format("%s|%s|%s|%s|%s|%s",
            mapping.marketType(),
            mapping.period(),
            mapping.line(),
            mapping.happening(),
            mapping.participant(),
            mapping.interval()
        );
    }
}
