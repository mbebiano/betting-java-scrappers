package com.superodds.infrastructure.scraper.betmgm;

import com.superodds.domain.model.*;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps BetMGM (Kambi) specific markets and outcomes to canonical types.
 * Based on betmgmraw.py reference implementation.
 */
public class BetMGMMarketMapper {

    private static final Pattern LINE_PATTERN = Pattern.compile("([+-]?\\d+[.,]?\\d*)");
    
    public record MarketMapping(
        MarketType marketType,
        PeriodType period,
        BigDecimal line,
        HappeningType happening,
        ParticipantSide participant,
        String interval
    ) {}

    /**
     * Maps BetMGM criterion label to canonical market type.
     * Kambi uses "criterion" to describe the market type.
     */
    public static MarketMapping mapMarket(String criterionLabel, String label, String betOfferType) {
        if (criterionLabel == null) {
            criterionLabel = "";
        }
        
        String criterionLower = criterionLabel.toLowerCase();
        String labelLower = label != null ? label.toLowerCase() : "";
        
        // Match Resultado Final / Full Time Result / Match Result
        if (criterionLower.contains("resultado") && criterionLower.contains("final") ||
            criterionLower.contains("full") && criterionLower.contains("time") ||
            criterionLower.contains("match") && criterionLower.contains("result") ||
            criterionLower.contains("3-way") || 
            criterionLower.equals("1x2")) {
            return new MarketMapping(
                MarketType.RESULTADO_FINAL,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Ambas Marcam / Both Teams To Score / BTTS
        if (criterionLower.contains("ambas") && criterionLower.contains("marcam") ||
            criterionLower.contains("both") && criterionLower.contains("teams") ||
            criterionLower.contains("btts")) {
            return new MarketMapping(
                MarketType.BTTS,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Dupla Chance / Double Chance
        if (criterionLower.contains("dupla") && criterionLower.contains("chance") ||
            criterionLower.contains("double") && criterionLower.contains("chance")) {
            return new MarketMapping(
                MarketType.DUPLA_CHANCE,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Draw No Bet / Empate Anula Aposta
        if (criterionLower.contains("draw") && criterionLower.contains("no") && criterionLower.contains("bet") ||
            criterionLower.contains("empate") && criterionLower.contains("anula")) {
            return new MarketMapping(
                MarketType.DRAW_NO_BET,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Asian Handicap / Handicap Asiático
        if (criterionLower.contains("asian") && criterionLower.contains("handicap") ||
            criterionLower.contains("handicap") && criterionLower.contains("asiático") ||
            (criterionLower.contains("handicap") && betOfferType != null && betOfferType.contains("OT_TWO"))) {
            BigDecimal line = extractLine(label);
            return new MarketMapping(
                MarketType.HANDICAP_ASIAN_2WAY,
                PeriodType.REGULAR_TIME,
                line,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Handicap 3-way / European Handicap
        if (criterionLower.contains("handicap") && 
            (betOfferType == null || !betOfferType.contains("OT_TWO"))) {
            BigDecimal line = extractLine(label);
            return new MarketMapping(
                MarketType.HANDICAP_3WAY,
                PeriodType.REGULAR_TIME,
                line,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Total Goals Over/Under / Total de Gols
        if (criterionLower.contains("total") && (criterionLower.contains("goals") || criterionLower.contains("gols")) &&
            (criterionLower.contains("over") || criterionLower.contains("under") || labelLower.contains("over") || labelLower.contains("under"))) {
            BigDecimal line = extractLine(label);
            return new MarketMapping(
                MarketType.TOTAL_GOLS_OVER_UNDER,
                PeriodType.REGULAR_TIME,
                line,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Result and Total Goals / Resultado e Total de Gols
        if ((criterionLower.contains("result") || criterionLower.contains("resultado")) && 
            (criterionLower.contains("total") || labelLower.contains("over") || labelLower.contains("under")) &&
            !criterionLower.contains("double") && !criterionLower.contains("dupla")) {
            BigDecimal line = extractLine(label);
            return new MarketMapping(
                MarketType.RESULTADO_TOTAL_GOLS,
                PeriodType.REGULAR_TIME,
                line,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Result and BTTS / Resultado e Ambas Marcam
        if ((criterionLower.contains("result") || criterionLower.contains("resultado")) && 
            (criterionLower.contains("btts") || criterionLower.contains("ambas") || 
             criterionLower.contains("both teams"))) {
            return new MarketMapping(
                MarketType.RESULTADO_BTTS,
                PeriodType.REGULAR_TIME,
                null,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Double Chance and Total Goals / Dupla Chance e Total de Gols
        if ((criterionLower.contains("double") || criterionLower.contains("dupla")) && 
            criterionLower.contains("chance") &&
            (criterionLower.contains("total") || labelLower.contains("over") || labelLower.contains("under"))) {
            BigDecimal line = extractLine(label);
            return new MarketMapping(
                MarketType.DUPLA_CHANCE_TOTAL_GOLS,
                PeriodType.REGULAR_TIME,
                line,
                HappeningType.GOALS,
                null,
                null
            );
        }
        
        // Total Corners / Total de Escanteios
        if (criterionLower.contains("corner") || criterionLower.contains("escanteio")) {
            BigDecimal line = extractLine(label);
            return new MarketMapping(
                MarketType.TOTAL_ESCANTEIOS_OVER_UNDER,
                PeriodType.REGULAR_TIME,
                line,
                HappeningType.CORNERS,
                null,
                null
            );
        }
        
        // Total Cards / Total de Cartões
        if (criterionLower.contains("card") || criterionLower.contains("cartão") || criterionLower.contains("cartao")) {
            BigDecimal line = extractLine(label);
            return new MarketMapping(
                MarketType.TOTAL_CARTOES_OVER_UNDER,
                PeriodType.REGULAR_TIME,
                line,
                HappeningType.CARDS,
                null,
                null
            );
        }
        
        return null; // Unmapped markets are discarded per Rule 9
    }

    private static BigDecimal extractLine(String text) {
        if (text == null) return null;
        
        Matcher matcher = LINE_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                String lineStr = matcher.group(1).replace(",", ".");
                // Remove + sign if present
                if (lineStr.startsWith("+")) {
                    lineStr = lineStr.substring(1);
                }
                return new BigDecimal(lineStr);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        
        return null;
    }

    /**
     * Maps BetMGM outcome label to canonical outcome type.
     */
    public static OutcomeType mapOutcome(String label, String criterionLabel, MarketType marketType) {
        if (label == null || label.isEmpty()) {
            return null;
        }

        String labelLower = label.toLowerCase();
        
        // For basic 3-way markets
        if (marketType == MarketType.RESULTADO_FINAL) {
            if (labelLower.contains("home") || labelLower.equals("1") || labelLower.contains("mandante")) {
                return OutcomeType.HOME;
            }
            if (labelLower.contains("draw") || labelLower.equals("x") || labelLower.contains("empate")) {
                return OutcomeType.DRAW;
            }
            if (labelLower.contains("away") || labelLower.equals("2") || labelLower.contains("visitante")) {
                return OutcomeType.AWAY;
            }
        }
        
        // For BTTS
        if (marketType == MarketType.BTTS) {
            if (labelLower.contains("yes") || labelLower.contains("sim")) {
                return OutcomeType.YES;
            }
            if (labelLower.contains("no") || labelLower.contains("não") || labelLower.contains("nao")) {
                return OutcomeType.NO;
            }
        }
        
        // For Double Chance
        if (marketType == MarketType.DUPLA_CHANCE) {
            if (labelLower.contains("1x") || (labelLower.contains("home") && labelLower.contains("draw"))) {
                return OutcomeType.HOME_OR_DRAW;
            }
            if (labelLower.contains("x2") || (labelLower.contains("draw") && labelLower.contains("away"))) {
                return OutcomeType.DRAW_OR_AWAY;
            }
            if (labelLower.contains("12") || (labelLower.contains("home") && labelLower.contains("away"))) {
                return OutcomeType.HOME_OR_AWAY;
            }
        }
        
        // For Draw No Bet
        if (marketType == MarketType.DRAW_NO_BET) {
            if (labelLower.contains("home") || labelLower.equals("1")) {
                return OutcomeType.HOME;
            }
            if (labelLower.contains("away") || labelLower.equals("2")) {
                return OutcomeType.AWAY;
            }
        }
        
        // For Over/Under markets
        if (marketType == MarketType.TOTAL_GOLS_OVER_UNDER ||
            marketType == MarketType.TOTAL_ESCANTEIOS_OVER_UNDER ||
            marketType == MarketType.TOTAL_CARTOES_OVER_UNDER) {
            if (labelLower.contains("over") || labelLower.contains("mais") || labelLower.contains("acima")) {
                return OutcomeType.OVER;
            }
            if (labelLower.contains("under") || labelLower.contains("menos") || labelLower.contains("abaixo")) {
                return OutcomeType.UNDER;
            }
        }
        
        // For Asian Handicap
        if (marketType == MarketType.HANDICAP_ASIAN_2WAY) {
            if (labelLower.contains("home") || labelLower.contains("1") || labelLower.startsWith("1")) {
                return OutcomeType.HOME_HANDICAP;
            }
            if (labelLower.contains("away") || labelLower.contains("2") || labelLower.startsWith("2")) {
                return OutcomeType.AWAY_HANDICAP;
            }
        }
        
        // For European Handicap (3-way)
        if (marketType == MarketType.HANDICAP_3WAY) {
            if (labelLower.contains("home") || labelLower.equals("1")) {
                return OutcomeType.HOME_HCP;
            }
            if (labelLower.contains("draw") || labelLower.equals("x")) {
                return OutcomeType.DRAW_HCP;
            }
            if (labelLower.contains("away") || labelLower.equals("2")) {
                return OutcomeType.AWAY_HCP;
            }
        }
        
        // For combined markets - Result + Total Goals
        if (marketType == MarketType.RESULTADO_TOTAL_GOLS) {
            if (labelLower.contains("home") || labelLower.contains("1")) {
                if (labelLower.contains("over") || labelLower.contains("mais")) return OutcomeType.HOME_AND_OVER;
                if (labelLower.contains("under") || labelLower.contains("menos")) return OutcomeType.HOME_AND_UNDER;
            }
            if (labelLower.contains("draw") || labelLower.contains("x") || labelLower.contains("empate")) {
                if (labelLower.contains("over") || labelLower.contains("mais")) return OutcomeType.DRAW_AND_OVER;
                if (labelLower.contains("under") || labelLower.contains("menos")) return OutcomeType.DRAW_AND_UNDER;
            }
            if (labelLower.contains("away") || labelLower.contains("2")) {
                if (labelLower.contains("over") || labelLower.contains("mais")) return OutcomeType.AWAY_AND_OVER;
                if (labelLower.contains("under") || labelLower.contains("menos")) return OutcomeType.AWAY_AND_UNDER;
            }
        }
        
        // For combined markets - Result + BTTS
        if (marketType == MarketType.RESULTADO_BTTS) {
            if (labelLower.contains("home") || labelLower.contains("1")) {
                if (labelLower.contains("yes") || labelLower.contains("sim")) return OutcomeType.HOME_AND_YES;
                if (labelLower.contains("no") || labelLower.contains("não")) return OutcomeType.HOME_AND_NO;
            }
            if (labelLower.contains("draw") || labelLower.contains("x")) {
                if (labelLower.contains("yes") || labelLower.contains("sim")) return OutcomeType.DRAW_AND_YES;
                if (labelLower.contains("no") || labelLower.contains("não")) return OutcomeType.DRAW_AND_NO;
            }
            if (labelLower.contains("away") || labelLower.contains("2")) {
                if (labelLower.contains("yes") || labelLower.contains("sim")) return OutcomeType.AWAY_AND_YES;
                if (labelLower.contains("no") || labelLower.contains("não")) return OutcomeType.AWAY_AND_NO;
            }
        }
        
        // For combined markets - Double Chance + Total Goals
        if (marketType == MarketType.DUPLA_CHANCE_TOTAL_GOLS) {
            if (labelLower.contains("1x")) {
                if (labelLower.contains("over") || labelLower.contains("mais")) return OutcomeType.HOME_OR_DRAW_AND_OVER;
                if (labelLower.contains("under") || labelLower.contains("menos")) return OutcomeType.HOME_OR_DRAW_AND_UNDER;
            }
            if (labelLower.contains("x2")) {
                if (labelLower.contains("over") || labelLower.contains("mais")) return OutcomeType.DRAW_OR_AWAY_AND_OVER;
                if (labelLower.contains("under") || labelLower.contains("menos")) return OutcomeType.DRAW_OR_AWAY_AND_UNDER;
            }
            if (labelLower.contains("12")) {
                if (labelLower.contains("over") || labelLower.contains("mais")) return OutcomeType.HOME_OR_AWAY_AND_OVER;
                if (labelLower.contains("under") || labelLower.contains("menos")) return OutcomeType.HOME_OR_AWAY_AND_UNDER;
            }
        }
        
        return null;
    }
}
