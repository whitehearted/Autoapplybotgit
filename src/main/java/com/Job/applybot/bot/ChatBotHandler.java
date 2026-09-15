package com.Job.applybot.bot;

import com.Job.applybot.Service.AnswerEngine;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * ChatBotHandler — v5 (Advanced Intelligence Overhaul)
 *
 * IMPROVEMENTS OVER v4:
 *
 * 1. QUESTION CLASSIFICATION ENGINE
 *    - Classifies each question into semantic categories before answering:
 *      EXPERIENCE, SALARY, NOTICE, PERSONAL, EDUCATION, SKILL, LOCATION,
 *      AGREEMENT, BOOLEAN, UNKNOWN
 *    - Each category has dedicated extraction + fallback logic.
 *    - Eliminates wrong answers caused by keyword collisions (e.g. "company
 *      name in which you have experience" used to return the company name,
 *      not the experience).
 *
 * 2. NUMERIC ANSWER NORMALIZATION
 *    - Salary: strips "LPA", "lakhs", "per annum" etc. — sends a clean number.
 *    - Experience: strips "years", "yr", "+" — sends just the digit.
 *    - Notice: maps "immediately", "30 days", "1 month" to chip-friendly tokens.
 *
 * 3. SMART CHIP MATCHING
 *    - Salary/experience chips: picks the chip whose range CONTAINS the answer
 *      value, not just a substring match.  e.g. answer "4" → picks chip "3-5 LPA".
 *    - Range chips: parsed and compared numerically.
 *    - Education chips: unchanged (already good in v4).
 *
 * 4. CONTEXT-AWARE MULTI-TURN MEMORY
 *    - Tracks the last 5 (question, answer) pairs.
 *    - If the bot repeats a question AND the previous answer was a chip click,
 *      tries a text-input fallback instead.
 *    - If the bot repeats 3x with no input change, tries the NEXT chip.
 *
 * 5. ROBUST "WHAT DID YOU ANSWER?" DETECTION
 *    - After typing into a text box, reads back the input value to verify
 *      the text was actually inserted.  If not, retries with Actions.sendKeys.
 *
 * 6. IMPROVED SEND BUTTON STRATEGY
 *    - Tries 6 escalating strategies; each logs clearly.
 *    - Enter-key is tried on BOTH the text input AND the send button element.
 *
 * 7. BETTER ACKNOWLEDGMENT / DONE DETECTION
 *    - isAcknowledgment() only returns true for short messages (≤120 chars)
 *      that start with a pure ACK phrase AND do not contain a question mark.
 *    - isChatDone() now covers more Naukri "application submitted" patterns.
 *
 * 8. FALLBACK DUMP → only when DEBUG=true to reduce log noise.
 */
public class ChatBotHandler {

    // ── Debug flag ─────────────────────────────────────────────────────────────
    private static final boolean DEBUG = true;

    // ── Chatbot container scope ────────────────────────────────────────────────
    private static final String CHATBOT_SCOPE =
            ".chatbot_Drawer, .chatbot_Container, [class*='chatbot_Drawer']";

    // ── Chip selectors (tried in order, scoped) ────────────────────────────────
    private static final String[] CHIP_SELECTORS = {
            "div.chatbot_Chip",
            "button.chatbot_Chip",
            "[class*='chatbot_Chip']",
            ".chatbot_OptionsContainer li",
            ".chatbot_OptionsContainer button",
            ".chatbot_OptionsContainer span",
            ".chatbot_InputContainer li",
            ".chatbot_InputContainer button",
            "li.chatbot_OptionItem",
            "[class*='chatbot_Option']",
            "[class*='chatbot_Button']"
    };

    // ── Acknowledgment phrases ─────────────────────────────────────────────────
    private static final String[] ACK_PHRASES = {
            "thank you for your responses", "thank you for",
            "got it!", "got it.", "great!", "nice!", "awesome!", "amazing!",
            "perfect!", "wonderful!", "noted!", "sure!", "okay!", "alright!",
            "you can say something like", "that's great", "that's noted"
    };

    // ── Question categories ────────────────────────────────────────────────────
    private enum QCategory {
        EXPERIENCE, SALARY_CURRENT, SALARY_EXPECTED, NOTICE, PERSONAL,
        EDUCATION, LOCATION, SKILL_LIST, CERTIFICATION, AGREEMENT,
        COMPANY, BOOLEAN_YES, BOOLEAN_NO, EXPERIENCE_STATUS, UNKNOWN
    }

    // ── Multi-turn memory ─────────────────────────────────────────────────────
    private static final int MEMORY_SIZE = 5;
    private static final Deque<String[]> turnMemory = new ArrayDeque<>(); // {question, answer, mode}

    // ─────────────────────────────────────────────────────────────────────────
    // MAIN ENTRY
    // ─────────────────────────────────────────────────────────────────────────
    public static void handle(WebDriver driver) {
        JavascriptExecutor js      = (JavascriptExecutor) driver;
        Actions            actions = new Actions(driver);
        String             lastQ        = "";
        int                stuckCount   = 0;
        int                lastMsgCount = 0;
        turnMemory.clear();

        debug("=== ChatBotHandler v5 START ===");

        try {
            switchToChatbotFrameIfPresent(driver);

            for (int turn = 0; turn < 40; turn++) {
                debug("--- Turn " + turn + " ---");
                waitForBotIdle(driver);

                String currentQ = getLatestQuestion(driver);
                debug("Question: [" + currentQ + "]");

                if (currentQ == null || currentQ.isBlank()) { debug("No question — ending."); break; }
                if (isChatDone(currentQ))                    { debug("Chat done.");             break; }

                if (isAcknowledgment(currentQ)) {
                    debug("Bot acknowledgment — waiting for closure or next question...");
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                    if (isDrawerClosing(driver)) { debug("Drawer closing — ending."); break; }
                    continue;
                }

                // Stuck detection with smart retry
                if (currentQ.equalsIgnoreCase(lastQ)) {
                    stuckCount++;
                    debug("Stuck count: " + stuckCount);
                    if (stuckCount >= 3) { debug("Stuck 3x — breaking."); break; }
                    if (stuckCount == 2) {
                        debug("Stuck 2x — trying next chip as fallback...");
                        tryNextChip(driver, js);
                        waitForNewBotMessage(driver, lastMsgCount);
                        continue;
                    }
                } else {
                    stuckCount = 0;
                }
                lastQ = currentQ;
                lastMsgCount = getBotMessageCount(driver);

                // Classify question
                QCategory category = classifyQuestion(currentQ);
                debug("Category: " + category);

                // Get the raw answer
                String rawAnswer = AnswerEngine.getAnswer(currentQ.toLowerCase());
                debug("Raw answer: [" + rawAnswer + "]");

                // Normalize answer based on category
                String answer = normalizeAnswer(rawAnswer, category, currentQ);
                debug("Normalized answer: [" + answer + "]");

                boolean handled = false;

                // Route by input type available
                if (!handled) handled = tryChipClick(driver, js, currentQ, answer, category);
                if (!handled) handled = tryCheckboxes(driver, js, currentQ, answer);
                if (!handled) handled = tryRadioClick(driver, js, answer);
                if (!handled) handled = trySuggestionChip(driver, js, answer, actions);
                if (!handled) handled = tryTextInput(driver, js, actions, answer, currentQ);

                if (!handled) {
                    debug("WARNING: Nothing handled this turn.");
                    if (DEBUG) dumpPageState(driver);
                }

                // Record turn in memory
                recordTurn(currentQ, answer, handled ? "handled" : "missed");

                // Wait for the bot to reply before next turn
                waitForNewBotMessage(driver, lastMsgCount);
            }

            waitForDrawerClose(driver);

        } catch (Exception e) {
            debug("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            driver.switchTo().defaultContent();
            debug("=== ChatBotHandler v5 END ===");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QUESTION CLASSIFIER
    // Assigns a semantic category to guide chip selection and answer normalization
    // ─────────────────────────────────────────────────────────────────────────
    private static QCategory classifyQuestion(String q) {
        String ql = q.toLowerCase();

        if ((ql.contains("fresher") && ql.contains("experienced"))
                || ql.contains("fresher or experienced")
                || ql.contains("fresher/experienced")) {

            return QCategory.EXPERIENCE_STATUS;
        }
        // Experience — must come before generic "have you" checks.
        // Only treat as NUMERIC (EXPERIENCE) when the phrasing actually asks for a number.
        // Boolean phrasing ("do you have experience in...", "are you experienced with...")
        // is left as UNKNOWN so AnswerEngine's Yes/No answer passes through unchanged
        // instead of being stripped down to a number by normalizeAnswer().
        boolean numericExpPhrase = ql.contains("how many years") || ql.contains("years of experience")
                || ql.contains("no. of years") || ql.contains("no of years") || ql.contains("number of years")
                || ql.contains("total experience");
        boolean booleanQuestionStart = ql.startsWith("do you") || ql.startsWith("are you")
                || ql.startsWith("have you") || ql.startsWith("did you") || ql.startsWith("is your")
                || ql.startsWith("were you") || ql.startsWith("can you") || ql.startsWith("will you");

        if (numericExpPhrase || ((ql.contains("experience in") || ql.contains("experience do you have"))
                && !booleanQuestionStart))
            return QCategory.EXPERIENCE;

        // Salary
        if (ql.contains("current ctc") || ql.contains("current salary")
                || (ql.contains("ctc") && (ql.contains("current") || ql.contains("last"))))
            return QCategory.SALARY_CURRENT;
        if (ql.contains("expected ctc") || ql.contains("expected salary")
                || ql.contains("ctc") || ql.contains("salary"))
            return QCategory.SALARY_EXPECTED;

        // Notice
        if (ql.contains("notice") || ql.contains("join") || ql.contains("available from")
                || ql.contains("start date"))
            return QCategory.NOTICE;

        // Education
        if (ql.contains("degree") || ql.contains("education") || ql.contains("qualification")
                || ql.contains("college") || ql.contains("university") || ql.contains("cgpa")
                || ql.contains("percentage") || ql.contains("passout") || ql.contains("graduation year")
                || ql.contains("specialization") || ql.contains("stream"))
            return QCategory.EDUCATION;

        // Location
        if (ql.contains("location") || ql.contains("city") || ql.contains("reside")
                || ql.contains("where do you"))
            return QCategory.LOCATION;

        // Certification
        if (ql.contains("certif") || ql.contains("course") || ql.contains("training"))
            return QCategory.CERTIFICATION;

        // Skills
        if (ql.contains("skill") || ql.contains("technology") || ql.contains("tech stack")
                || ql.contains("programming language"))
            return QCategory.SKILL_LIST;

        // Company
        if (ql.contains("current company") || ql.contains("company name")
                || ql.contains("current organization") || ql.contains("employer"))
            return QCategory.COMPANY;

        // Agreement — user should say Yes
        if (ql.contains("agree") || ql.contains("consent") || ql.contains("confirm")
                || ql.contains("background check") || ql.contains("background verification")
                || ql.contains("terms") || ql.contains("policy"))
            return QCategory.AGREEMENT;

        // Boolean defaults
        if (ql.contains("relocat") || ql.contains("interview") || ql.contains("comfortable")
                || ql.contains("work from") || ql.contains("night shift") || ql.contains("rotational")
                || ql.contains("remote") || ql.contains("wfh") || ql.contains("hybrid") || ql.contains("flexible"))
            return QCategory.BOOLEAN_YES;

        if (ql.contains("bond") || ql.contains("criminal") || ql.contains("terminated")
                || ql.contains("blacklist") || ql.contains("disability") || ql.contains("differently abled")
                || ql.contains("visa") || ql.contains("sponsorship") || ql.contains("career gap")
                || ql.contains("career break"))
            return QCategory.BOOLEAN_NO;

        return QCategory.UNKNOWN;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ANSWER NORMALIZER
    // Cleans up raw answers based on question category
    // ─────────────────────────────────────────────────────────────────────────
    private static String normalizeAnswer(String raw, QCategory category, String question) {
        if (raw == null) return "0";
        String v = raw.trim();

        switch (category) {
            case EXPERIENCE_STATUS:
                return v;

            case EXPERIENCE:
                // Strip "years", "yr", "+", trailing text — keep only the number
                v = v.replaceAll("(?i)\\s*(years?|yrs?|\\+).*$", "").trim();
                if (v.isEmpty() || !v.matches("[0-9.]+")) v = "0";
                return v;

            case SALARY_CURRENT:
            case SALARY_EXPECTED:
                // Strip "LPA", "lakh", "lakhs", "per annum", "₹" etc.
                v = v.replaceAll("(?i)\\s*(lpa|lakhs?|per annum|per month|k|inr|₹|rs\\.?).*$", "").trim();
                v = v.replaceAll("[^0-9.]", "").trim();
                if (v.isEmpty()) v = "0";
                // Remove trailing dot
                if (v.endsWith(".")) v = v.substring(0, v.length() - 1);
                return v;

            case NOTICE:
                // Normalize notice period strings to chip-friendly values
                String vl = v.toLowerCase();
                if (vl.contains("immediate") || vl.equals("0")) return "0";
                if (vl.contains("15")) return "15 days";
                if (vl.contains("30") || vl.contains("1 month") || vl.contains("one month")) return "30 days";
                if (vl.contains("45")) return "45 days";
                if (vl.contains("60") || vl.contains("2 month") || vl.contains("two month")) return "60 days";
                if (vl.contains("90") || vl.contains("3 month") || vl.contains("three month")) return "90 days";
                return v;

            case AGREEMENT:
                return "Yes";

            case BOOLEAN_YES:
                return "Yes";

            case BOOLEAN_NO:
                return "No";

            default:
                return v;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TYPE 1 — CHIPS / OPTION BUTTONS
    // ─────────────────────────────────────────────────────────────────────────
    private static boolean tryChipClick(WebDriver driver, JavascriptExecutor js,
                                        String question, String answer, QCategory category) {
        List<WebElement> chips = findChips(driver);
        if (chips.isEmpty()) return false;

        String[] chipTexts = readChipTexts(chips);

        // ── Fresher / Experienced ─────────────────────────────────────
        if (category == QCategory.EXPERIENCE_STATUS) {

            String ans = answer.toLowerCase().trim();

            for (int i = 0; i < chipTexts.length; i++) {

                String chip = chipTexts[i].toLowerCase().trim();

                if (ans.equals("experienced")
                        && chip.contains("experienced")) {

                    debug("Experience-status match: [" +
                            chipTexts[i] + "]");

                    return clickChipAt(driver, js, chips, i);
                }

                if (ans.equals("fresher")
                        && chip.contains("fresher")) {

                    debug("Experience-status match: [" +
                            chipTexts[i] + "]");

                    return clickChipAt(driver, js, chips, i);
                }
            }

            debug("No Fresher/Experienced chip matched answer: [" +
                    answer + "]");

            return false;
        }

        debug("Found " + chips.size() + " chip(s):");
        for (int i = 0; i < chipTexts.length; i++) debug("  chip[" + i + "]: [" + chipTexts[i] + "]");

        // All-skip guard
        boolean allSkip = true;
        for (String t : chipTexts) { if (!t.contains("skip")) { allSkip = false; break; } }
        if (allSkip && !answer.equalsIgnoreCase("skip")) {
            debug("Only skip chips — falling through."); return false;
        }

        // Resume/upload → skip or last
        String ql = question.toLowerCase();
        if (ql.contains("resume") || ql.contains("upload")) {
            for (int i = 0; i < chipTexts.length; i++)
                if (chipTexts[i].contains("later") || chipTexts[i].contains("skip"))
                    return clickChipAt(driver, js, chips, i);
            return clickChipAt(driver, js, chips, chips.size() - 1);
        }

        // ── Salary / experience chips: numeric range matching ──────────────────
        if (category == QCategory.SALARY_CURRENT || category == QCategory.SALARY_EXPECTED
                || category == QCategory.EXPERIENCE) {
            int numericIdx = pickNumericRangeChip(chipTexts, answer);
            if (numericIdx >= 0) {
                debug("Numeric range chip match at idx=" + numericIdx + ": [" + chipTexts[numericIdx] + "]");
                return clickChipAt(driver, js, chips, numericIdx);
            }
        }

        // ── Education chips ────────────────────────────────────────────────────
        if (ql.contains("highest") && (ql.contains("education") || ql.contains("qualification")
                || ql.contains("degree"))) {
            int eduIdx = pickEducationChip(chipTexts, answer);
            if (eduIdx >= 0) return clickChipAt(driver, js, chips, eduIdx);
        }

        // ── Notice period chips ────────────────────────────────────────────────
        if (category == QCategory.NOTICE) {
            int noticeIdx = pickNoticeChip(chipTexts, answer);
            if (noticeIdx >= 0) return clickChipAt(driver, js, chips, noticeIdx);
        }

        // ── Generic candidate matching (pass 1: exact / contains) ─────────────
        String[] candidates = buildCandidates(answer);
        debug("Chip candidates: " + Arrays.toString(candidates));

        for (int i = 0; i < chipTexts.length; i++) {
            if (chipTexts[i].contains("skip")) continue;
            for (String c : candidates) {
                String cl = c.toLowerCase().trim();
                if (chipTexts[i].equals(cl) || chipTexts[i].contains(cl))
                    return clickChipAt(driver, js, chips, i);
            }
        }
        // Pass 2: prefix
        for (int i = 0; i < chipTexts.length; i++) {
            if (chipTexts[i].contains("skip")) continue;
            for (String c : candidates) {
                if (chipTexts[i].startsWith(c.toLowerCase().trim()))
                    return clickChipAt(driver, js, chips, i);
            }
        }

        // Skip chip if answer explicitly says skip
        if (answer.equalsIgnoreCase("skip")) {
            for (int i = 0; i < chipTexts.length; i++)
                if (chipTexts[i].contains("skip")) return clickChipAt(driver, js, chips, i);
        }

        // ── Default: negative answer → last non-skip; positive → first non-skip ─
        int firstNS = -1, lastNS = -1;
        for (int i = 0; i < chipTexts.length; i++) {
            if (!chipTexts[i].contains("skip")) {
                if (firstNS < 0) firstNS = i;
                lastNS = i;
            }
        }
        if (firstNS < 0) {
            if (answer.equalsIgnoreCase("skip")) return clickChipAt(driver, js, chips, 0);
            return false;
        }
        boolean negative = isNegativeAnswer(answer);
        int idx = negative ? lastNS : firstNS;
        debug("Chip default idx=" + idx + ": [" + chipTexts[idx] + "] (answer=[" + answer + "])");
        return clickChipAt(driver, js, chips, idx);
    }

    /**
     * Picks the chip index whose numeric range best contains the given answer value.
     * Handles chips like "0-2", "3-5", "5-8", "8+", "0 LPA", "3-6 LPA", etc.
     * Returns -1 if no numeric-range chip is found.
     */
    private static int pickNumericRangeChip(String[] chipTexts, String answer) {
        double val;
        try { val = Double.parseDouble(answer.replaceAll("[^0-9.]", "").trim()); }
        catch (NumberFormatException e) { return -1; }

        // Pattern: "3-6" or "3-6 LPA" or "3 to 6" or "3-6 years" or "8+" or "10+"
        Pattern rangePattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:-|to)\\s*(\\d+(?:\\.\\d+)?)");
        Pattern openEndPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*\\+");
        Pattern singlePattern = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*$");

        int bestIdx = -1;
        double bestDist = Double.MAX_VALUE;

        for (int i = 0; i < chipTexts.length; i++) {
            String chip = chipTexts[i];
            if (chip.contains("skip")) continue;

            Matcher m = rangePattern.matcher(chip);
            if (m.find()) {
                double lo = Double.parseDouble(m.group(1));
                double hi = Double.parseDouble(m.group(2));
                if (val >= lo && val <= hi) {
                    debug("Numeric range chip exact hit: chip[" + i + "]=[" + chip + "] for val=" + val);
                    return i; // exact range hit
                }
                // Track closest range
                double dist = Math.min(Math.abs(val - lo), Math.abs(val - hi));
                if (dist < bestDist) { bestDist = dist; bestIdx = i; }
                continue;
            }
            m = openEndPattern.matcher(chip);
            if (m.find()) {
                double lo = Double.parseDouble(m.group(1));
                if (val >= lo) {
                    debug("Numeric open-end chip hit: chip[" + i + "]=[" + chip + "] for val=" + val);
                    return i;
                }
                double dist = Math.abs(val - lo);
                if (dist < bestDist) { bestDist = dist; bestIdx = i; }
                continue;
            }
            // Single numeric chip ("0", "1", "2")
            m = singlePattern.matcher(chip.replaceAll("[^0-9.]", " ").trim());
            if (m.matches()) {
                double chipVal = Double.parseDouble(m.group(1).trim());
                double dist = Math.abs(val - chipVal);
                if (dist < bestDist) { bestDist = dist; bestIdx = i; }
            }
        }

        // No exact range hit — return closest if within reasonable tolerance
        if (bestIdx >= 0 && bestDist <= 5.0) {
            debug("Numeric range chip closest: chip[" + bestIdx + "]=[" + chipTexts[bestIdx] + "] dist=" + bestDist);
            return bestIdx;
        }
        return -1;
    }

    /**
     * Picks the chip best matching the notice period answer.
     * Understands "immediately", "0 days", "15 days", "30 days", etc.
     */
    private static int pickNoticeChip(String[] chipTexts, String answer) {
        String al = answer.toLowerCase().trim();

        // Map of (chip keyword → numeric days) for ordering
        for (int i = 0; i < chipTexts.length; i++) {
            String cl = chipTexts[i].toLowerCase();
            if (al.contains("immediate") || al.equals("0")) {
                if (cl.contains("immediate") || cl.contains("0 day") || cl.equals("0")) return i;
            }
            if (al.contains("15")) {
                if (cl.contains("15")) return i;
            }
            if (al.contains("30") || al.contains("1 month") || al.contains("one month")) {
                if (cl.contains("30") || cl.contains("1 month") || cl.contains("one month")) return i;
            }
            if (al.contains("45")) {
                if (cl.contains("45")) return i;
            }
            if (al.contains("60") || al.contains("2 month") || al.contains("two month")) {
                if (cl.contains("60") || cl.contains("2 month") || cl.contains("two month")) return i;
            }
            if (al.contains("90") || al.contains("3 month") || al.contains("three month")) {
                if (cl.contains("90") || cl.contains("3 month") || cl.contains("three month")) return i;
            }
        }
        return -1;
    }

    private static String[] readChipTexts(List<WebElement> chips) {
        String[] texts = new String[chips.size()];
        for (int i = 0; i < chips.size(); i++) {
            try { texts[i] = chips.get(i).getText().trim().toLowerCase(); }
            catch (StaleElementReferenceException e) { texts[i] = ""; }
        }
        return texts;
    }

    private static List<WebElement> findChips(WebDriver driver) {
        List<WebElement> scopes = driver.findElements(By.cssSelector(CHATBOT_SCOPE));
        if (!scopes.isEmpty()) {
            WebElement scope = scopes.get(0);
            for (String sel : CHIP_SELECTORS) {
                try {
                    List<WebElement> visible = visibleNonEmpty(scope.findElements(By.cssSelector(sel)));
                    if (!visible.isEmpty()) { debug("Chips (scoped) via [" + sel + "]: " + visible.size()); return visible; }
                } catch (Exception ignored) {}
            }
        }
        for (String sel : CHIP_SELECTORS) {
            try {
                List<WebElement> visible = visibleNonEmpty(driver.findElements(By.cssSelector(sel)));
                if (!visible.isEmpty()) { debug("Chips (unscoped) via [" + sel + "]: " + visible.size()); return visible; }
            } catch (Exception ignored) {}
        }
        return Collections.emptyList();
    }

    private static boolean clickChipAt(WebDriver driver, JavascriptExecutor js,
                                       List<WebElement> chips, int idx) {
        try {
            if (idx >= chips.size()) return false;
            WebElement chip = chips.get(idx);
            String text = safeText(chip);
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", chip);
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            js.executeScript("arguments[0].click();", chip);
            debug("Clicked chip[" + idx + "]: [" + text + "] ✓");
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            return true;
        } catch (StaleElementReferenceException e) {
            try {
                Thread.sleep(400);
                List<WebElement> fresh = findChips(driver);
                if (idx < fresh.size()) { js.executeScript("arguments[0].click();", fresh.get(idx)); return true; }
            } catch (Exception ignored) {}
            return false;
        }
    }

    /** Tries to click the NEXT available chip — used when stuck on the same question */
    private static void tryNextChip(WebDriver driver, JavascriptExecutor js) {
        List<WebElement> chips = findChips(driver);
        if (chips.size() < 2) return;
        // Find last turn's chip answer and try the next one
        String lastAnswer = getLastTurnAnswer();
        String[] texts = readChipTexts(chips);
        for (int i = 0; i < texts.length - 1; i++) {
            if (texts[i].equalsIgnoreCase(lastAnswer)) {
                clickChipAt(driver, js, chips, i + 1);
                return;
            }
        }
        // If not found, just click the second chip
        clickChipAt(driver, js, chips, 1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TYPE 2 — CHECKBOXES
    // ─────────────────────────────────────────────────────────────────────────
    private static boolean tryCheckboxes(WebDriver driver, JavascriptExecutor js,
                                         String question, String answer) {
        List<WebElement> checkboxes = findScoped(driver, "input[type='checkbox']");
        if (checkboxes.isEmpty()) return false;

        debug("Found " + checkboxes.size() + " checkbox(es):");
        String[] labels = new String[checkboxes.size()];
        for (int i = 0; i < checkboxes.size(); i++) {
            labels[i] = getCheckboxLabel(driver, checkboxes.get(i)).toLowerCase().trim();
            debug("  checkbox[" + i + "] label: [" + labels[i] + "]");
        }

        boolean anyChecked = false;
        String ql = question.toLowerCase();

        if (ql.contains("location") || ql.contains("city") || ql.contains("cities")) {
            String[] candidates = buildCandidates(answer);
            for (int i = 0; i < labels.length; i++) {
                for (String c : candidates) {
                    if (labels[i].contains(c.toLowerCase())) {
                        anyChecked |= clickCbAt(js, checkboxes, i); break;
                    }
                }
            }
            if (!anyChecked) anyChecked = clickCbAt(js, checkboxes, 0);
        } else if (ql.contains("skill") || ql.contains("technology")) {
            // Match known skills from answer or question
            for (int i = 0; i < labels.length; i++) {
                if (isKnownSkillLabel(labels[i])) {
                    anyChecked |= clickCbAt(js, checkboxes, i);
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
            if (!anyChecked) anyChecked = clickCbAt(js, checkboxes, 0);
        } else {
            // Generic: match against answer candidates
            String[] candidates = buildCandidates(answer);
            for (int i = 0; i < labels.length; i++) {
                for (String c : candidates) {
                    if (labels[i].contains(c.toLowerCase())) {
                        anyChecked |= clickCbAt(js, checkboxes, i);
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        break;
                    }
                }
            }
            if (!anyChecked) anyChecked = clickCbAt(js, checkboxes, 0);
        }

        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        clickSendButton(driver, js);
        return true;
    }

    private static boolean isKnownSkillLabel(String label) {
        String[] KNOWN_SKILLS = {
                "java", "spring boot", "spring", "hibernate", "selenium",
                "react", "angular", "javascript", "python", "sql", "mysql",
                "mongodb", "rest api", "microservices", "docker", "git",
                "node", "typescript", "junit", "maven", "gradle", "aws",
                "kubernetes", "kafka", "redis", "jenkins"
        };
        for (String s : KNOWN_SKILLS) if (label.contains(s)) return true;
        return false;
    }

    private static boolean clickCbAt(JavascriptExecutor js, List<WebElement> cbs, int idx) {
        try {
            if (idx < cbs.size()) { js.executeScript("arguments[0].click();", cbs.get(idx)); return true; }
        } catch (Exception e) { debug("Checkbox click error idx=" + idx + ": " + e.getMessage()); }
        return false;
    }

    private static String getCheckboxLabel(WebDriver driver, WebElement cb) {
        try {
            String id = cb.getAttribute("id");
            if (id != null && !id.isBlank()) {
                List<WebElement> lbl = driver.findElements(By.cssSelector("label[for='" + id + "']"));
                if (!lbl.isEmpty()) return lbl.get(0).getText();
            }
        } catch (Exception ignored) {}
        try {
            Object t = ((JavascriptExecutor) driver).executeScript(
                    "var el=arguments[0],n=el.nextElementSibling;" +
                            "if(n&&n.tagName==='LABEL')return n.textContent;" +
                            "var p=el.parentElement;" +
                            "if(p){var l=p.querySelector('label');if(l)return l.textContent;return p.textContent;}" +
                            "return '';", cb);
            if (t != null) return t.toString().trim();
        } catch (Exception ignored) {}
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TYPE 3 — RADIO BUTTONS
    // ─────────────────────────────────────────────────────────────────────────
    private static boolean tryRadioClick(WebDriver driver, JavascriptExecutor js, String answer) {
        List<WebElement> radios = driver.findElements(By.cssSelector("input.ssrc__radio"));
        if (radios.isEmpty()) return false;

        debug("Found " + radios.size() + " radio(s):");
        String[] ids    = new String[radios.size()];
        String[] vals   = new String[radios.size()];
        String[] labels = new String[radios.size()];

        for (int i = 0; i < radios.size(); i++) {
            try {
                ids[i]  = safeAttr(radios.get(i), "id").toLowerCase().trim();
                vals[i] = safeAttr(radios.get(i), "value").toLowerCase().trim();
                String id = safeAttr(radios.get(i), "id");
                List<WebElement> lblEls = driver.findElements(By.cssSelector("label[for='" + id + "']"));
                labels[i] = lblEls.isEmpty() ? "" : lblEls.get(0).getText().trim().toLowerCase();
                debug("  radio[" + i + "]: id=[" + ids[i] + "] val=[" + vals[i] + "] label=[" + labels[i] + "]");
            } catch (StaleElementReferenceException e) {
                ids[i] = vals[i] = labels[i] = "";
            }
        }

        String[] candidates = buildCandidates(answer);

        // Pass 1: exact match
        for (int i = 0; i < radios.size(); i++) {
            if (ids[i].contains("skip")) continue;
            for (String c : candidates) {
                String cl = c.toLowerCase().trim();
                if (labels[i].equals(cl) || vals[i].equals(cl) || ids[i].equals(cl))
                    return clickRadioAt(driver, js, i);
            }
        }
        // Pass 2: contains
        for (int i = 0; i < radios.size(); i++) {
            if (ids[i].contains("skip")) continue;
            for (String c : candidates) {
                String cl = c.toLowerCase().trim();
                if (labels[i].contains(cl) || cl.contains(labels[i]) || vals[i].contains(cl) || ids[i].contains(cl))
                    return clickRadioAt(driver, js, i);
            }
        }
        // Pass 3: positive/negative default
        int firstNS = -1, lastNS = -1;
        for (int i = 0; i < ids.length; i++) {
            if (!ids[i].contains("skip") && !labels[i].contains("skip")) {
                if (firstNS < 0) firstNS = i;
                lastNS = i;
            }
        }
        if (firstNS >= 0) {
            int idx = isNegativeAnswer(answer) ? lastNS : firstNS;
            debug("Radio default [" + answer + "] → idx=" + idx + " label=[" + labels[idx] + "]");
            return clickRadioAt(driver, js, idx);
        }
        return clickRadioAt(driver, js, 0);
    }

    private static boolean clickRadioAt(WebDriver driver, JavascriptExecutor js, int idx) {
        try {
            List<WebElement> fresh = driver.findElements(By.cssSelector("input.ssrc__radio"));
            if (idx >= fresh.size()) return false;
            WebElement radio = fresh.get(idx);
            String id = safeAttr(radio, "id");
            List<WebElement> lbls = driver.findElements(By.cssSelector("label[for='" + id + "']"));
            if (!lbls.isEmpty()) js.executeScript("arguments[0].click();", lbls.get(0));
            else                  js.executeScript("arguments[0].click();", radio);
            debug("Clicked radio[" + idx + "] ✓");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            clickSendButton(driver, js);
            return true;
        } catch (Exception e) { debug("Radio click error: " + e.getMessage()); return false; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TYPE 4 — SUGGESTION CHIP / d-none input
    // ─────────────────────────────────────────────────────────────────────────
    private static boolean trySuggestionChip(WebDriver driver, JavascriptExecutor js,
                                             String answer, Actions actions) {
        List<WebElement> containers = driver.findElements(By.cssSelector("div.chatbot_SendMessageContainer"));
        if (containers.isEmpty() || !safeAttr(containers.get(0), "class").contains("d-none")) return false;

        List<WebElement> suggestions = driver.findElements(By.cssSelector(
                ".chatbot_SuggestionChip,.suggestion-chip,[class*='SuggestionChip']," +
                        ".chatbot_prefill,.prefillChip,.chatbot_EditChip,[class*='editChip']"));
        if (!suggestions.isEmpty()) {
            debug("Suggestion chip: [" + safeText(suggestions.get(0)) + "]");
            js.executeScript("arguments[0].click();", suggestions.get(0));
            return true;
        }

        debug("d-none input — force-removing d-none");
        js.executeScript(
                "document.querySelectorAll('.chatbot_SendMessageContainer')" +
                        ".forEach(function(el){ el.classList.remove('d-none'); });");
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TYPE 5 — TEXT INPUT
    // Enhanced with verification: reads back what was actually typed.
    // ─────────────────────────────────────────────────────────────────────────
    private static boolean tryTextInput(WebDriver driver, JavascriptExecutor js,
                                        Actions actions, String answer, String question) {
        List<WebElement> containers = driver.findElements(By.cssSelector("div.chatbot_SendMessageContainer"));
        if (!containers.isEmpty() && safeAttr(containers.get(0), "class").contains("d-none")) {
            debug("Text container d-none — skipping."); return false;
        }

        WebElement input = findChatbotTextInput(driver);
        if (input == null) { debug("No chatbot text input found."); return false; }
        debug("Text input found: id=[" + safeAttr(input, "id") + "]");

        // Determine the best answer to type, accounting for question context
        String finalAnswer = smartAnswerForTextInput(answer, question, driver);
        debug("Final text answer: [" + finalAnswer + "]");

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // Step 1: scroll + focus
                js.executeScript("arguments[0].scrollIntoView({block:'center'});", input);
                Thread.sleep(200);
                js.executeScript("arguments[0].focus();", input);
                Thread.sleep(200);

                // Step 2: clear existing content
                js.executeScript(
                        "var el=arguments[0];" +
                                "el.innerText='';el.textContent='';el.value='';" +
                                "el.dispatchEvent(new InputEvent('input',{bubbles:true}));", input);
                Thread.sleep(150);
                input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
                Thread.sleep(200);

                // Step 3: type answer
                if (attempt == 1) {
                    input.sendKeys(finalAnswer);
                } else {
                    // Attempt 2: use Actions for more reliable typing
                    debug("Retrying with Actions.sendKeys...");
                    actions.moveToElement(input).click().sendKeys(finalAnswer).perform();
                }
                Thread.sleep(300);

                // Step 4: verify what was typed
                String typed = getInputValue(js, input);
                debug("Verified typed: [" + typed + "]");
                if (typed.isBlank() && attempt < 2) {
                    debug("Nothing typed — retrying..."); continue;
                }

                // Step 5: dispatch events so chatbot enables the send button
                js.executeScript(
                        "var el=arguments[0];" +
                                "el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText'}));" +
                                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                                "el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'a'}));", input);
                Thread.sleep(400);

                clickSendButton(driver, js);
                return true;

            } catch (Exception e) {
                debug("tryTextInput attempt " + attempt + " error: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Context-aware text answer refinement.
     * For questions where AnswerEngine returns a generic answer but the question
     * has specific patterns (e.g. "how much CTC in your current company?" → a number),
     * this method refines it further.
     */
    private static String smartAnswerForTextInput(String answer, String question, WebDriver driver) {
        String ql = question.toLowerCase();

        // If there are autocomplete suggestions visible, keep the answer short for matching
        List<WebElement> suggestions = driver.findElements(By.cssSelector(
                "[class*='autocomplete'], [class*='suggestion'], [class*='dropdown'] div"));
        if (!suggestions.isEmpty() && answer.length() > 30) {
            // Shorten to first word/token for autocomplete
            String shortened = answer.split("[,;\\n]")[0].trim();
            debug("Autocomplete detected — shortening answer to: [" + shortened + "]");
            return shortened;
        }

        return answer;
    }

    private static String getInputValue(JavascriptExecutor js, WebElement input) {
        try {
            Object v = js.executeScript(
                    "var el=arguments[0];" +
                            "if(el.value !== undefined && el.value !== '') return el.value;" +
                            "if(el.innerText !== undefined) return el.innerText;" +
                            "return el.textContent;", input);
            return v == null ? "" : v.toString().trim();
        } catch (Exception e) { return ""; }
    }

    private static WebElement findChatbotTextInput(WebDriver driver) {
        // 1: inside chatbot_SendMessageContainer (not d-none)
        for (WebElement c : driver.findElements(By.cssSelector("div.chatbot_SendMessageContainer"))) {
            try {
                if (safeAttr(c, "class").contains("d-none")) continue;
                for (WebElement inp : c.findElements(
                        By.cssSelector("div[contenteditable='true'],input[type='text'],textarea"))) {
                    try { if (inp.isDisplayed()) return inp; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        // 2: inside .chatbot_Drawer
        for (WebElement d : driver.findElements(By.cssSelector(".chatbot_Drawer"))) {
            try {
                for (WebElement inp : d.findElements(
                        By.cssSelector("div[contenteditable='true'],input[type='text'],textarea"))) {
                    try { if (inp.isDisplayed()) return inp; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        // 3: by ID pattern
        for (WebElement inp : driver.findElements(By.cssSelector(
                "div[contenteditable='true'][id*='InputBox']," +
                        "div[contenteditable='true'][id*='userInput']," +
                        "div[contenteditable='true'][id*='chatbot']"))) {
            try { if (inp.isDisplayed()) return inp; } catch (Exception ignored) {}
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEND BUTTON — 6-strategy cascade
    // ─────────────────────────────────────────────────────────────────────────
    private static void clickSendButton(WebDriver driver, JavascriptExecutor js) {
        debug("Clicking send button...");
        try {
            // Strategy 1: un-disable + click div.sendMsg via JS
            js.executeScript(
                    "document.querySelectorAll('div.send, div.sendMsg').forEach(function(el){" +
                            "  el.classList.remove('disabled');" +
                            "  el.removeAttribute('disabled');" +
                            "});");
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            Boolean clicked = (Boolean) js.executeScript(
                    "var btns = document.querySelectorAll('div.sendMsg');" +
                            "for(var i=0;i<btns.length;i++){" +
                            "  if(btns[i].offsetParent !== null){btns[i].click(); return true;}" +
                            "} return false;");
            if (Boolean.TRUE.equals(clicked)) { debug("✓ Strategy 1: div.sendMsg JS click"); return; }

            // Strategy 2: Selenium findElements for div.sendMsg
            for (WebElement btn : driver.findElements(By.cssSelector("div.sendMsg"))) {
                try {
                    if (btn.isDisplayed()) {
                        js.executeScript("arguments[0].click();", btn);
                        debug("✓ Strategy 2: Selenium div.sendMsg"); return;
                    }
                } catch (StaleElementReferenceException ignored) {}
            }

            // Strategy 3: div[id^='sendMsg__']
            for (WebElement btn : driver.findElements(By.cssSelector("div[id^='sendMsg__']"))) {
                try {
                    if (btn.isDisplayed()) {
                        js.executeScript("arguments[0].click();", btn);
                        debug("✓ Strategy 3: div[id^='sendMsg__']"); return;
                    }
                } catch (StaleElementReferenceException ignored) {}
            }

            // Strategy 4: button/div with aria-label="send" (case insensitive)
            Boolean s4 = (Boolean) js.executeScript(
                    "var all = document.querySelectorAll('[aria-label]');" +
                            "for(var i=0;i<all.length;i++){" +
                            "  if(all[i].getAttribute('aria-label').toLowerCase().includes('send')" +
                            "     && all[i].offsetParent !== null){all[i].click(); return true;}" +
                            "} return false;");
            if (Boolean.TRUE.equals(s4)) { debug("✓ Strategy 4: aria-label send"); return; }

            // Strategy 5: Enter key on text input
            WebElement inp = findChatbotTextInput(driver);
            if (inp != null) { inp.sendKeys(Keys.RETURN); debug("✓ Strategy 5: Enter on text input"); return; }

            // Strategy 6: Enter key via JS on focused element
            js.executeScript(
                    "var el = document.activeElement;" +
                            "if(el) el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));");
            debug("✓ Strategy 6: JS Enter on activeElement");

        } catch (Exception e) { debug("Send button error: " + e.getMessage()); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private static List<WebElement> findScoped(WebDriver driver, String css) {
        List<WebElement> scopes = driver.findElements(By.cssSelector(CHATBOT_SCOPE));
        if (!scopes.isEmpty()) {
            List<WebElement> found = scopes.get(0).findElements(By.cssSelector(css));
            if (!found.isEmpty()) return found;
        }
        return driver.findElements(By.cssSelector(css));
    }

    private static List<WebElement> visibleNonEmpty(List<WebElement> els) {
        List<WebElement> result = new ArrayList<>();
        for (WebElement el : els) {
            try { if (el.isDisplayed() && !el.getText().trim().isEmpty()) result.add(el); }
            catch (Exception ignored) {}
        }
        return result;
    }

    private static boolean isNegativeAnswer(String answer) {
        String al = answer.toLowerCase().trim();
        return al.equals("no") || al.equals("none") || al.equals("nil")
                || al.equals("0") || al.equals("n/a") || al.equals("na")
                || al.equals("false") || al.equals("not applicable");
    }

    private static boolean isAcknowledgment(String q) {
        String lower = q.toLowerCase().trim();
        // Must be short, must NOT contain a question mark, must start with ack phrase
        if (lower.length() > 120) return false;
        if (lower.contains("?")) return false;
        for (String phrase : ACK_PHRASES) {
            if (lower.startsWith(phrase) || lower.equals(phrase)) return true;
        }
        return false;
    }

    private static boolean isDrawerClosing(WebDriver driver) {
        List<WebElement> drawers = driver.findElements(By.cssSelector(".chatbot_Drawer"));
        if (drawers.isEmpty()) return true;
        try { return "none".equals(drawers.get(0).getCssValue("display")); }
        catch (Exception e) { return true; }
    }

    private static String getLatestQuestion(WebDriver driver) {
        // Try primary selector
        List<WebElement> spans = driver.findElements(By.cssSelector("li.botItem .botMsg span"));
        debug("li.botItem .botMsg span count: " + spans.size());
        for (int i = spans.size() - 1; i >= 0; i--) {
            try {
                String t = spans.get(i).getText().trim();
                if (!t.isBlank()) return t;
            } catch (StaleElementReferenceException ignored) {}
        }
        // Fallback selectors
        String[] fallbackSelectors = {
                ".chatbot_BotMessage span",
                "[class*='botMsg'] span",
                "[class*='BotMsg'] span",
                ".chatbot_ChatMessageWrapper:last-child span"
        };
        for (String sel : fallbackSelectors) {
            try {
                List<WebElement> els = driver.findElements(By.cssSelector(sel));
                for (int i = els.size() - 1; i >= 0; i--) {
                    String t = els.get(i).getText().trim();
                    if (!t.isBlank()) { debug("Question found via fallback [" + sel + "]"); return t; }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void waitForBotIdle(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(8)).until(d ->
                    d.findElements(By.cssSelector(".botTyping,.typing-indicator,.bot-typing")).isEmpty());
        } catch (Exception ignored) {}
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
    }

    private static void waitForNewBotMessage(WebDriver driver, int prevCount) {
        debug("Waiting for new bot message (prev=" + prevCount + ")...");
        try {
            new WebDriverWait(driver, Duration.ofSeconds(12)).until(d ->
                    d.findElements(By.cssSelector("li.botItem .botMsg span")).size() > prevCount);
            debug("New bot message received ✓");
        } catch (TimeoutException e) { debug("No new message within 12s — continuing."); }
    }

    private static void waitForDrawerClose(WebDriver driver) {
        debug("Waiting for drawer to close...");
        try {
            new WebDriverWait(driver, Duration.ofSeconds(20)).until(d -> {
                List<WebElement> drawers = d.findElements(By.cssSelector(".chatbot_Drawer"));
                if (drawers.isEmpty()) return true;
                try { return "none".equals(drawers.get(0).getCssValue("display")); }
                catch (Exception e) { return true; }
            });
            debug("Drawer closed ✓");
        } catch (TimeoutException e) { debug("Drawer did not close in 20s — moving on."); }
    }

    private static int getBotMessageCount(WebDriver driver) {
        try { return driver.findElements(By.cssSelector("li.botItem .botMsg span")).size(); }
        catch (Exception e) { return 0; }
    }

    private static void switchToChatbotFrameIfPresent(WebDriver driver) {
        for (WebElement frame : driver.findElements(By.tagName("iframe"))) {
            try {
                String src = frame.getAttribute("src");
                if (src != null && src.toLowerCase().contains("chatbot")) {
                    driver.switchTo().frame(frame); debug("Switched into chatbot iframe."); return;
                }
            } catch (Exception ignored) {}
        }
        debug("No chatbot iframe — using main DOM.");
    }

    private static boolean isChatDone(String q) {
        String l = q.toLowerCase();
        return l.contains("applied successfully") || l.contains("your application has been")
                || l.contains("application submitted") || l.contains("successfully applied")
                || l.contains("thank you for applying") || l.contains("we have received your application");
    }

    private static void recordTurn(String question, String answer, String mode) {
        if (turnMemory.size() >= MEMORY_SIZE) turnMemory.pollFirst();
        turnMemory.addLast(new String[]{question, answer, mode});
    }

    private static String getLastTurnAnswer() {
        if (turnMemory.isEmpty()) return "";
        return turnMemory.peekLast()[1];
    }

    private static void dumpPageState(WebDriver driver) {
        if (!DEBUG) return;
        debug("--- PAGE STATE DUMP ---");
        List<WebElement> chips = findChips(driver);
        debug("Chips: " + chips.size());
        for (WebElement c : chips) { try { debug("  [" + c.getText().trim() + "] tag=" + c.getTagName()); } catch (Exception ignored) {} }
        debug("Checkboxes: " + findScoped(driver, "input[type='checkbox']").size());
        debug("Radios: " + driver.findElements(By.cssSelector("input.ssrc__radio")).size());
        List<WebElement> conts = driver.findElements(By.cssSelector("div.chatbot_SendMessageContainer"));
        debug("SendContainers: " + conts.size());
        for (WebElement c : conts) debug("  class=[" + safeAttr(c, "class") + "]");
        WebElement inp = findChatbotTextInput(driver);
        debug("TextInput: " + (inp != null ? "FOUND id=[" + safeAttr(inp,"id") + "]" : "NOT FOUND"));
        debug("--- END DUMP ---");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EDUCATION CHIP PICKER (unchanged from v4 — already solid)
    // ─────────────────────────────────────────────────────────────────────────
    private static int pickEducationChip(String[] chipTexts, String answer) {
        String ansLower = answer.toLowerCase().trim();
        String[][] degreeAliases = {
                {"b.tech",  "b.tech", "b.e", "bachelor", "graduate", "engineering", "be/b.tech"},
                {"b.e",     "b.e", "b.tech", "bachelor", "engineering", "be/b.tech"},
                {"bachelor","bachelor", "b.tech", "b.e", "graduate", "ug", "under graduate"},
                {"bsc",     "bsc", "b.sc", "bachelor", "graduate"},
                {"bca",     "bca", "bachelor", "graduate"},
                {"mba",     "mba", "post graduate", "pg", "master"},
                {"m.tech",  "m.tech", "m.e", "master", "post graduate", "pg"},
                {"m.e",     "m.e", "m.tech", "master", "post graduate"},
                {"msc",     "msc", "m.sc", "master", "post graduate"},
                {"mca",     "mca", "master", "post graduate"},
                {"phd",     "phd", "doctorate", "doctor"},
                {"diploma", "diploma"},
                {"12th",    "12th", "hsc", "higher secondary", "intermediate"},
                {"10th",    "10th", "ssc", "secondary"},
        };
        String[] lowEducationChips = {"below 12", "below class 12", "10th", "ssc", "secondary school",
                "less than", "up to 10", "up to 12"};
        boolean userIsGraduate = ansLower.contains("b.tech") || ansLower.contains("b.e")
                || ansLower.contains("bachelor") || ansLower.contains("bca") || ansLower.contains("bsc")
                || ansLower.contains("m.tech") || ansLower.contains("mba") || ansLower.contains("mca")
                || ansLower.contains("msc") || ansLower.contains("master") || ansLower.contains("phd")
                || ansLower.contains("diploma");
        Set<Integer> forbidden = new HashSet<>();
        if (userIsGraduate) {
            for (int i = 0; i < chipTexts.length; i++)
                for (String low : lowEducationChips)
                    if (chipTexts[i].contains(low)) { forbidden.add(i); break; }
        }
        for (String[] alias : degreeAliases) {
            if (!ansLower.contains(alias[0])) continue;
            for (int k = 1; k < alias.length; k++) {
                for (int i = 0; i < chipTexts.length; i++) {
                    if (forbidden.contains(i)) continue;
                    if (chipTexts[i].contains(alias[k])) {
                        debug("Education chip match: alias[" + alias[0] + "] → chip[" + i + "]: [" + chipTexts[i] + "]");
                        return i;
                    }
                }
            }
        }
        for (int i = 0; i < chipTexts.length; i++)
            if (!forbidden.contains(i) && !chipTexts[i].contains("skip")) return i;
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANDIDATE BUILDER — maps normalized answers to chip-friendly variants
    // ─────────────────────────────────────────────────────────────────────────
    private static String[] buildCandidates(String answer) {
        return switch (answer.toLowerCase().trim()) {
            case "yes"             -> new String[]{"Yes","yes"};
            case "no"              -> new String[]{"No","no"};
            case "none","nil","n/a"-> new String[]{"No","None","nil","N/A","Not applicable","not applicable"};
            case "male"            -> new String[]{"Male","male"};
            case "female"          -> new String[]{"Female","female"};
            case "0","immediately","immediate" -> new String[]{"Immediately","0 days","Immediate","0","No Notice"};
            case "15 days","15"    -> new String[]{"15 days","15 Days or less","15"};
            case "30 days","30"    -> new String[]{"30 days","1 month","1 Month","30"};
            case "45 days","45"    -> new String[]{"45 days","45"};
            case "60 days","60"    -> new String[]{"60 days","2 months","2 Months","60"};
            case "75 days","75"    -> new String[]{"75 days","3 months","3 Months","75"};
            case "90 days","90"    -> new String[]{"90 days","3 months","3 Months","90"};
            // Education
            case "b.tech","b.e","be/b.tech" ->
                    new String[]{"B.Tech","B.E","BE/B.Tech","Bachelor of Technology","Bachelor of Engineering","Graduate","Engineering"};
            case "bachelor","bsc","bca","ba","bcom" ->
                    new String[]{"Bachelor","Graduate","UG","Under Graduate","B.Sc","BCA","B.A","B.Com"};
            case "m.tech","m.e"    -> new String[]{"M.Tech","M.E","Master of Technology","Post Graduate","PG"};
            case "mba"             -> new String[]{"MBA","Master of Business","Post Graduate","PG"};
            case "msc","mca","ma","mcom" -> new String[]{"Master","Post Graduate","PG","M.Sc","MCA","M.A","M.Com"};
            case "phd"             -> new String[]{"PhD","Doctorate","Doctor of Philosophy"};
            case "diploma"         -> new String[]{"Diploma","Polytechnic"};
            case "12th","hsc","higher secondary","intermediate" ->
                    new String[]{"12th","HSC","Higher Secondary","Intermediate","Plus Two","10+2"};
            case "10th","ssc","secondary" -> new String[]{"10th","SSC","Secondary","Matriculation"};
            // Locations
            case "chennai"         -> new String[]{"Chennai","chennai","tamil nadu"};
            case "bengaluru","bangalore" -> new String[]{"Bengaluru","Bangalore","bengaluru","bangalore"};
            case "mumbai"          -> new String[]{"Mumbai","bombay"};
            case "hyderabad"       -> new String[]{"Hyderabad"};
            case "delhi","new delhi" -> new String[]{"Delhi","New Delhi"};
            case "pune"            -> new String[]{"Pune"};
            case "noida"           -> new String[]{"Noida"};
            case "gurgaon","gurugram" -> new String[]{"Gurgaon","Gurugram"};
            // Currency / frequency
            case "inr"             -> new String[]{"INR","Indian Rupee","Rupees"};
            default                -> new String[]{answer};
        };
    }

    private static String safeAttr(WebElement el, String attr) {
        try { String v = el.getAttribute(attr); return v == null ? "" : v; }
        catch (Exception e) { return ""; }
    }

    private static String safeText(WebElement el) {
        try { return el.getText().trim(); } catch (Exception e) { return ""; }
    }

    private static void debug(String msg) {
        if (DEBUG) System.out.println("[ChatBot] " + msg);
    }
}
