package com.superodds.infrastructure.scraper.sportingbet;

import com.superodds.domain.model.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Sportingbet-specific markets and outcomes to canonical types.
 * Based on sportingbetraw.py reference implementation.
 */
public class SportingbetMarketMapper {

    private static final Pattern LINE_PATTERN = Pattern.compile("(\\d+[.,]\\d+)");
    
    public record MarketMapping(
        MarketType marketType,
        PeriodType period,
        BigDecimal line,
        HappeningType happening,
        ParticipantSide participant,
        String interval
    ) {}

    /**
     * Maps Sportingbet MarketType parameter to canonical market type.
     */
    public static MarketMapping mapMarket(Map<String, String> parameters, String marketName) {
        String marketType = parameters.getOrDefault("MarketType", "");
        String period = parameters.getOrDefault("Period", "RegularTime");
        String happening = parameters.getOrDefault("Happening", "");
        String rangeValue = parameters.getOrDefault("RangeValue", "");
        
        PeriodType periodType = mapPeriod(period);
        BigDecimal line = extractLine(rangeValue, marketName);
        
        return switch (marketType) {
            case "3way" -> {
                // Resultado Final (1X2)
                if (rangeValue != null && !rangeValue.isEmpty()) {
                    // If has range, could be a special market - skip for now
                    yield null;
                }
                yield new MarketMapping(
                    MarketType.RESULTADO_FINAL,
                    periodType,
                    null,
                    HappeningType.GOALS,
                    null,
                    null
                );
            }
            case "BTTS" -> new MarketMapping(
                MarketType.BTTS,
                periodType,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case "DoubleChance" -> new MarketMapping(
                MarketType.DUPLA_CHANCE,
                periodType,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case "DrawNoBet" -> new MarketMapping(
                MarketType.DRAW_NO_BET,
                periodType,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case "Handicap" -> new MarketMapping(
                MarketType.HANDICAP_3WAY,
                periodType,
                line,
                HappeningType.GOALS,
                null,
                null
            );
            case "2wayHandicap" -> new MarketMapping(
                MarketType.HANDICAP_ASIAN_2WAY,
                periodType,
                line,
                HappeningType.GOALS,
                null,
                null
            );
            case "ThreeWayAndBTTS" -> new MarketMapping(
                MarketType.RESULTADO_BTTS,
                periodType,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case "ToWinAndBTTS" -> new MarketMapping(
                MarketType.RESULTADO_BTTS,
                periodType,
                null,
                HappeningType.GOALS,
                null,
                null
            );
            case "ThreeWayAndOverUnder" -> new MarketMapping(
                MarketType.RESULTADO_TOTAL_GOLS,
                periodType,
                line,
                HappeningType.GOALS,
                null,
                null
            );
            case "DoubleChanceAndOverUnder" -> new MarketMapping(
                MarketType.DUPLA_CHANCE_TOTAL_GOLS,
                periodType,
                line,
                HappeningType.GOALS,
                null,
                null
            );
            default -> null; // Unmapped markets are discarded per Rule 9
        };
    }

    private static PeriodType mapPeriod(String period) {
        return switch (period) {
            case "RegularTime" -> PeriodType.REGULAR_TIME;
            case "FirstHalf" -> PeriodType.FIRST_HALF;
            case "SecondHalf" -> PeriodType.SECOND_HALF;
            default -> PeriodType.REGULAR_TIME;
        };
    }

    private static BigDecimal extractLine(String rangeValue, String marketName) {
        if (rangeValue != null && !rangeValue.isEmpty()) {
            try {
                return new BigDecimal(rangeValue.replace(",", "."));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        
        // Try to extract from market name
        if (marketName != null) {
            Matcher matcher = LINE_PATTERN.matcher(marketName);
            if (matcher.find()) {
                try {
                    return new BigDecimal(matcher.group(1).replace(",", "."));
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }
        
        return null;
    }

    /**
     * Maps Sportingbet option code to canonical outcome type.
     */
    public static OutcomeType mapOutcome(String code, String marketType, Map<String, String> parameters) {
        if (code == null || code.isEmpty()) {
            return null;
        }

        // For 3way markets
        if ("3way".equals(marketType)) {
            return switch (code) {
                case "1" -> OutcomeType.HOME;
                case "X", "2" -> code.equals("X") ? OutcomeType.DRAW : OutcomeType.AWAY;
                default -> null;
            };
        }

        // For BTTS
        if ("BTTS".equals(marketType)) {
            return switch (code) {
                case "Yes" -> OutcomeType.YES;
                case "No" -> OutcomeType.NO;
                default -> null;
            };
        }

        // For Double Chance
        if ("DoubleChance".equals(marketType)) {
            return switch (code) {
                case "1X" -> OutcomeType.HOME_OR_DRAW;
                case "X2" -> OutcomeType.DRAW_OR_AWAY;
                case "12" -> OutcomeType.HOME_OR_AWAY;
                default -> null;
            };
        }

        // For Draw No Bet
        if ("DrawNoBet".equals(marketType)) {
            return switch (code) {
                case "1" -> OutcomeType.HOME;
                case "2" -> OutcomeType.AWAY;
                default -> null;
            };
        }

        // For Handicap 3-way
        if ("Handicap".equals(marketType)) {
            return switch (code) {
                case "1" -> OutcomeType.HOME_HCP;
                case "X" -> OutcomeType.DRAW_HCP;
                case "2" -> OutcomeType.AWAY_HCP;
                default -> null;
            };
        }

        // For Asian Handicap 2-way
        if ("2wayHandicap".equals(marketType)) {
            return switch (code) {
                case "1" -> OutcomeType.HOME_HANDICAP;
                case "2" -> OutcomeType.AWAY_HANDICAP;
                default -> null;
            };
        }

        // For combined markets - need to parse option name
        // This is simplified - in production would need more sophisticated parsing
        String optionName = code.toLowerCase();
        
        // ThreeWayAndBTTS / ToWinAndBTTS
        if ("ThreeWayAndBTTS".equals(marketType) || "ToWinAndBTTS".equals(marketType)) {
            if (optionName.contains("1") && optionName.contains("yes")) return OutcomeType.HOME_AND_YES;
            if (optionName.contains("1") && optionName.contains("no")) return OutcomeType.HOME_AND_NO;
            if (optionName.contains("x") && optionName.contains("yes")) return OutcomeType.DRAW_AND_YES;
            if (optionName.contains("x") && optionName.contains("no")) return OutcomeType.DRAW_AND_NO;
            if (optionName.contains("2") && optionName.contains("yes")) return OutcomeType.AWAY_AND_YES;
            if (optionName.contains("2") && optionName.contains("no")) return OutcomeType.AWAY_AND_NO;
        }

        // ThreeWayAndOverUnder
        if ("ThreeWayAndOverUnder".equals(marketType)) {
            if (optionName.contains("1") && optionName.contains("over")) return OutcomeType.HOME_AND_OVER;
            if (optionName.contains("1") && optionName.contains("under")) return OutcomeType.HOME_AND_UNDER;
            if (optionName.contains("x") && optionName.contains("over")) return OutcomeType.DRAW_AND_OVER;
            if (optionName.contains("x") && optionName.contains("under")) return OutcomeType.DRAW_AND_UNDER;
            if (optionName.contains("2") && optionName.contains("over")) return OutcomeType.AWAY_AND_OVER;
            if (optionName.contains("2") && optionName.contains("under")) return OutcomeType.AWAY_AND_UNDER;
        }

        // DoubleChanceAndOverUnder
        if ("DoubleChanceAndOverUnder".equals(marketType)) {
            if (optionName.contains("1x") && optionName.contains("over")) return OutcomeType.HOME_OR_DRAW_AND_OVER;
            if (optionName.contains("1x") && optionName.contains("under")) return OutcomeType.HOME_OR_DRAW_AND_UNDER;
            if (optionName.contains("x2") && optionName.contains("over")) return OutcomeType.DRAW_OR_AWAY_AND_OVER;
            if (optionName.contains("x2") && optionName.contains("under")) return OutcomeType.DRAW_OR_AWAY_AND_UNDER;
            if (optionName.contains("12") && optionName.contains("over")) return OutcomeType.HOME_OR_AWAY_AND_OVER;
            if (optionName.contains("12") && optionName.contains("under")) return OutcomeType.HOME_OR_AWAY_AND_UNDER;
        }

        return null;
    }
}
