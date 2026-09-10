package com.Job.applybot.bot;

import com.Job.applybot.Driver.DriverFactory;
import com.Job.applybot.model.ApplicationTracker;
import com.Job.applybot.Service.ApplicationResult;
import com.Job.applybot.Service.ApplicationResult.Status;
import com.Job.applybot.model.UserProfile;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import java.nio.file.*;

public class Bot {

    private static final String RECOMMENDED_URL = "https://www.naukri.com/recommendedjobs";
    // In-memory dedup only — prevents re-processing the same job twice within a
    // single run (e.g. if pagination overlaps). Previously this was also persisted
    // to a file across runs; that persistence has been removed on request, so the
    // bot revisits every job listing fresh each time it's started.
    private final Set<String> processedJobUrls = new HashSet<>();

    // ── Bot state: tracks whether bot is running and allows early stop ────────
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;

    public boolean isRunning() { return running; }
    public void requestStop() { stopRequested = true; }

    public void searchjob(String url, UserProfile profile) throws InterruptedException {
        running = true;
        stopRequested = false;
        WebDriver driver = DriverFactory.GetWebDriver();
        ApplicationTracker tracker = new ApplicationTracker(profile.getFullName());

        login(driver, profile);

        try {
            if (profile.isRecommendedOnly()) {
                System.out.println("=== MODE: RECOMMENDED JOBS ONLY ===");
                processRecommendedPage(driver, tracker, profile);
            } else {
                System.out.println("=== MODE: SEARCH — " + url + " ===");
                processSearchPages(driver, tracker, profile, url);
                if (!stopRequested && profile.isIncludeRecommended()) {
                    System.out.println("=== ALSO: RECOMMENDED JOBS ===");
                    processRecommendedPage(driver, tracker, profile);
                }
            }
        } finally {
            tracker.finish();
            printSummary(tracker);
            running = false;
            stopRequested = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RECOMMENDED JOBS
    // ─────────────────────────────────────────────────────────────────────────
    private void processRecommendedPage(WebDriver driver, ApplicationTracker tracker,
                                        UserProfile profile) throws InterruptedException {
        driver.get(RECOMMENDED_URL);
        Thread.sleep(4000);
        keepMinimized(driver);

        JavascriptExecutor js = (JavascriptExecutor) driver;
        String mainWindow     = driver.getWindowHandle();

        // Scroll fully to trigger all lazy-loaded cards
        System.out.println("[Recommended] Loading all cards...");
        long lastHeight = 0;
        for (int i = 0; i < 20; i++) {
            js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
            Thread.sleep(1200);
            long newH = (Long) js.executeScript("return document.body.scrollHeight");
            if (newH == lastHeight) break;
            lastHeight = newH;
        }
        js.executeScript("window.scrollTo(0, 0)");
        Thread.sleep(800);

        // Extract job data via JS — reads data-job-id + title from each article
        @SuppressWarnings("unchecked")
        List<List<String>> extracted = (List<List<String>>) js.executeScript(
                "var r = [];" +
                        "document.querySelectorAll('article.jobTuple[data-job-id]').forEach(function(a) {" +
                        "  var id = a.getAttribute('data-job-id'); if (!id) return;" +
                        "  var tp = a.querySelector('p.title[title]');" +
                        "  var t  = tp ? tp.getAttribute('title') : '';" +
                        "  if (!t) { tp = a.querySelector('p.title'); t = tp ? tp.textContent.trim() : 'Unknown'; }" +
                        "  var cp = a.querySelector('span.subTitle[title]');" +
                        "  var c  = cp ? cp.getAttribute('title') : 'Unknown';" +
                        "  r.push([id, t, c]);" +
                        "}); return r;"
        );

        List<String> hrefs    = new ArrayList<>();
        List<String> titles   = new ArrayList<>();
        List<String> companies= new ArrayList<>();

        if (extracted != null) {
            for (List<String> item : extracted) {
                if (item == null || item.isEmpty()) continue;
                String jobId   = item.get(0);
                String title   = item.size() > 1 ? item.get(1) : "Unknown";
                String company = item.size() > 2 ? item.get(2) : "Unknown";
                if (jobId == null || jobId.isBlank()) continue;
                String href = "https://www.naukri.com/job-listings-" + jobId;
                if (!hrefs.contains(href)) {
                    hrefs.add(href);
                    titles.add(title.toLowerCase());
                    companies.add(company);
                }
            }
        }

        // Fallback: any visible <a href*=job-listings>
        if (hrefs.isEmpty()) {
            System.out.println("[Recommended] No articles via JS — trying anchor fallback");
            for (WebElement a : driver.findElements(By.cssSelector("a[href*='job-listings']"))) {
                try {
                    String href = a.getAttribute("href");
                    if (href == null || href.isBlank() || hrefs.contains(href)) continue;
                    hrefs.add(href);
                    String t = a.getAttribute("title");
                    if (t == null || t.isBlank()) t = a.getText().trim();
                    titles.add((t != null ? t : "Unknown").toLowerCase());
                    companies.add("Unknown");
                } catch (Exception ignored) {}
            }
        }

        System.out.println("[Recommended] Jobs found: " + hrefs.size());
        if (hrefs.isEmpty()) {
            System.out.println("[DEBUG] URL: " + driver.getCurrentUrl());
            System.out.println("[DEBUG] article.jobTuple count: "
                    + driver.findElements(By.cssSelector("article.jobTuple")).size());
            return;
        }

        processJobList(driver, js, mainWindow, hrefs, titles, companies, profile, tracker);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEARCH — paginated
    // ─────────────────────────────────────────────────────────────────────────
    private void processSearchPages(WebDriver driver, ApplicationTracker tracker,
                                    UserProfile profile, String startUrl)
            throws InterruptedException {

        driver.get(startUrl);
        Thread.sleep(3000);
        keepMinimized(driver);

        JavascriptExecutor js = (JavascriptExecutor) driver;
        String mainWindow     = driver.getWindowHandle();
        int page              = 1;
        int maxPages          = profile.getMaxPages() > 0 ? profile.getMaxPages() : 10;
        String prevUrl        = "";

        while (page <= maxPages) {
            System.out.println("===== PAGE " + page + " =====");

            // Scroll to load all job cards
            for (int i = 0; i < 3; i++) {
                js.executeScript("window.scrollBy(0, 1000)");
                Thread.sleep(800);
            }

            List<String> hrefs    = new ArrayList<>();
            List<String> titles   = new ArrayList<>();
            List<String> companies= new ArrayList<>();
            collectSearchJobs(driver, hrefs, titles, companies);

            if (hrefs.isEmpty()) {
                System.out.println("No jobs on page " + page + " — stopping.");
                break;
            }
            System.out.println("Found " + hrefs.size() + " jobs on page " + page);

            processJobList(driver, js, mainWindow, hrefs, titles, companies, profile, tracker);

            if (stopRequested) {
                System.out.println("⛔ Bot stop requested — halting pagination, bot fully stopped.");
                return;
            }

            // ── Pagination ────────────────────────────────────────────────────
            String urlBefore = driver.getCurrentUrl();
            boolean clicked  = goToNextPage(driver, js, page, urlBefore);

            if (!clicked) {
                System.out.println("[Pagination] No next page found — done.");
                break;
            }

            Thread.sleep(3000);

            // Verify URL actually changed to avoid infinite loop
            String urlAfter = driver.getCurrentUrl();
            if (urlAfter.equals(urlBefore) || urlAfter.equals(prevUrl)) {
                System.out.println("[Pagination] URL unchanged — end of results.");
                break;
            }
            prevUrl = urlBefore;
            page++;

            if (!isSessionAlive(driver)) { System.out.println("Session lost."); break; }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Collect jobs from search result page
    // ─────────────────────────────────────────────────────────────────────────
    private void collectSearchJobs(WebDriver driver, List<String> hrefs,
                                   List<String> titles, List<String> companies) {
        // Primary: <a class="title" href="...job-listings...">
        List<WebElement> anchors = driver.findElements(
                By.xpath("//a[contains(@href,'job-listings') and contains(@class,'title')]"));
        if (anchors.isEmpty()) {
            anchors = driver.findElements(By.cssSelector("a[href*='job-listings']"));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (WebElement a : anchors) {
            try {
                String href = a.getAttribute("href");
                if (href == null || href.isBlank() || !seen.add(href)) continue;
                hrefs.add(href);
                String t = a.getText().trim();
                if (t.isBlank()) t = a.getAttribute("title");
                titles.add(t != null ? t.toLowerCase() : "unknown");
                companies.add("Unknown");
            } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Process a list of jobs
    // ─────────────────────────────────────────────────────────────────────────
    private void processJobList(WebDriver driver, JavascriptExecutor js,
                                String mainWindow, List<String> hrefs,
                                List<String> titles, List<String> companies,
                                UserProfile profile, ApplicationTracker tracker)
            throws InterruptedException {

        for (int i = 0; i < hrefs.size(); i++) {
            String title   = i < titles.size()   ? titles.get(i)   : "unknown";
            String link    = hrefs.get(i);
            String company = i < companies.size() ? companies.get(i) : "Unknown";

            // Strip query parameters to normalize the link
            String normalizedLink = link.split("\\?")[0];
            if (processedJobUrls.contains(normalizedLink)) {
                System.out.println("Skipping [" + (i+1) + "/" + hrefs.size() + "]: Already processed in this run -> " + title);
                continue;
            }
            processedJobUrls.add(normalizedLink);

            /* ── TITLE FILTER — uncomment to enable ───────────────────────────
            String rf = profile.getRole() != null
                    ? profile.getRole().toLowerCase().replace("-"," ") : "";
            if (!rf.isBlank() && !matchesRole(title, rf)) {
                System.out.println("Skipping (filter): " + title); continue;
            }
            ─────────────────────────────────────────────────────────────────── */

            System.out.println("Processing [" + (i+1) + "/" + hrefs.size() + "]: " + title);

            if (!isSessionAlive(driver)) { System.out.println("Session lost."); return; }

            applyAndTrack(driver, js, mainWindow, title, link, company, profile, tracker);

            if (stopRequested) {
                System.out.println("⛔ Bot stop requested — halting job loop.");
                return;
            }

            Thread.sleep(1500);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Open job tab, apply, track, close tab
    // ─────────────────────────────────────────────────────────────────────────
    private void applyAndTrack(WebDriver driver, JavascriptExecutor js,
                               String mainWindow, String title, String jobUrl,
                               String knownCompany, UserProfile profile,
                               ApplicationTracker tracker) {
        try {
            // Open in background tab
            js.executeScript("window.open(arguments[0], '_blank');", jobUrl);
            Thread.sleep(1500);

            // Find the new tab handle
            String jobTab = null;
            for (String h : driver.getWindowHandles()) {
                if (!h.equals(mainWindow)) jobTab = h;
            }
            if (jobTab == null) {
                tracker.add(new ApplicationResult(profile.getFullName(), title,
                        knownCompany, jobUrl, null, Status.SKIPPED, "Tab did not open"));
                return;
            }

            // Switch to job tab
            driver.switchTo().window(jobTab);
            keepMinimized(driver);

            String company = extractCompany(driver);
            if ("Unknown".equals(company) && !knownCompany.isBlank()
                    && !"Unknown".equals(knownCompany)) {
                company = knownCompany;
            }

            if (!matchesUserSkills(driver, title, profile)) {
                System.out.println("Skipping [skill mismatch]: " + title);
                tracker.add(new ApplicationResult(profile.getFullName(), title,
                        company, jobUrl, null, Status.SKIPPED, "Key skills didn't match this job"));
                return;
            }

            ApplicationResult result = doApply(driver, title, company, jobUrl, profile);
            tracker.add(result);

        } catch (Exception e) {
            System.out.println("Error on [" + title + "]: " + e.getMessage());
            tracker.add(new ApplicationResult(profile.getFullName(), title,
                    knownCompany, jobUrl, null, Status.FAILED,
                    e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 120)));
        } finally {
            safeCleanupTabs(driver, mainWindow);
            keepMinimized(driver);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core apply logic
    // ─────────────────────────────────────────────────────────────────────────
    private ApplicationResult doApply(WebDriver driver, String title, String company,
                                      String jobUrl, UserProfile profile)
            throws InterruptedException {

        String username = profile.getFullName();

        if (!driver.findElements(By.xpath(
                        "//*[contains(text(),'Already Applied') or contains(text(),'already applied')]"))
                .isEmpty()) {
            System.out.println("Already applied — skipping.");
            return new ApplicationResult(username, title, company, jobUrl, null,
                    Status.SKIPPED, "Already applied");
        }

        WebElement applyBtn;
        try {
            applyBtn = new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//button[contains(.,'Apply')]")));
        } catch (TimeoutException e) {
            System.out.println("Apply button not found.");
            return new ApplicationResult(username, title, company, jobUrl, null,
                    Status.SKIPPED, "Apply button not found");
        }

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", applyBtn);
        System.out.println("Apply clicked.");
        Thread.sleep(3000);

        keepMinimized(driver);

        // Same-tab redirect?
        String currentUrl = driver.getCurrentUrl();
        if (!currentUrl.contains("naukri.com")) {
            System.out.println("Redirected: " + currentUrl);
            return new ApplicationResult(username, title, company, jobUrl, currentUrl,
                    Status.REDIRECTED, "Redirected: " + currentUrl);
        }

        // External tab opened?
        String externalUrl = null, externalTab = null, jobTabHandle = null;
        for (String handle : driver.getWindowHandles()) {
            try {
                driver.switchTo().window(handle);
                keepMinimized(driver);
                String tabUrl = driver.getCurrentUrl();
                if (!tabUrl.contains("naukri.com")) {
                    externalUrl = tabUrl; externalTab = handle;
                } else {
                    jobTabHandle = handle;
                }
            } catch (Exception ignored) {}
        }
        if (externalUrl != null && !externalUrl.isBlank()) {
            System.out.println("External tab: " + externalUrl);
            if (externalTab != null) {
                try { driver.switchTo().window(externalTab); driver.close(); }
                catch (Exception ignored) {}
            }
            if (jobTabHandle != null) {
                try { driver.switchTo().window(jobTabHandle); } catch (Exception ignored) {}
            }
            return new ApplicationResult(username, title, company, jobUrl, externalUrl,
                    Status.REDIRECTED, "External: " + externalUrl);
        }
        if (jobTabHandle != null) {
            try { driver.switchTo().window(jobTabHandle); } catch (Exception ignored) {}
        }

        boolean isChatBot = !driver.findElements(By.cssSelector(".chatbot_Drawer")).isEmpty();
        boolean isPopup   = !driver.findElements(
                By.cssSelector(".apply-layer-container,.question-title")).isEmpty();

        // ── ERROR BANNER CHECK ────────────────────────────────────────────────
        // Detect Naukri's "There was an error while processing your request" banner.
        // CSS: section.styles_error__PZ5op  (contains the error span)
        // When present: log it, mark FAILED, and signal the bot to stop.
        List<org.openqa.selenium.WebElement> errorBanners = driver.findElements(
                By.cssSelector("section.styles_error__PZ5op, .styles_user-msg__YLRsE.styles_error__PZ5op"));
        if (!errorBanners.isEmpty()) {
            System.out.println("⛔ Error banner detected on job page — stopping bot.");
            stopRequested = true;
            return new ApplicationResult(username, title, company, jobUrl, null,
                    Status.FAILED, "Naukri error: processing request failed — bot stopped");
        }
        // ─────────────────────────────────────────────────────────────────────

        try {
            if (isChatBot) {
                System.out.println("-> ChatBot flow");
                ChatBotHandler.handle(driver);
                return new ApplicationResult(username, title, company, jobUrl, null,
                        Status.SUCCESS, "ChatBot answered");
            } else if (isPopup) {
                System.out.println("-> Popup flow");
                PopupHandler.handle(driver);
                return new ApplicationResult(username, title, company, jobUrl, null,
                        Status.SUCCESS, "Popup answered");
            } else {
                System.out.println("-> Direct apply");
                return new ApplicationResult(username, title, company, jobUrl, null,
                        Status.DIRECT_APPLY, "No questions");
            }
        } catch (Exception e) {
            return new ApplicationResult(username, title, company, jobUrl, null,
                    Status.FAILED, "Flow error: " + truncate(e.getMessage(), 120));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAGINATION
    // ─────────────────────────────────────────────────────────────────────────
    private boolean goToNextPage(WebDriver driver, JavascriptExecutor js,
                                 int currentPage, String currentUrl) {
        int next = currentPage + 1;
        System.out.println("[Pagination] Looking for page " + next);

        js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}

        // STRATEGY 1: href ends with -{pageNumber}
        try {
            List<WebElement> anchors = driver.findElements(By.xpath(
                    "//a[substring(@href, string-length(@href) - " + String.valueOf(String.valueOf(next).length()) +
                            ") = '-" + next + "' and normalize-space(text())='" + next + "']"
            ));
            for (WebElement el : anchors) {
                if (el.isDisplayed()) {
                    System.out.println("[Pagination S1] href-ends-with match: "
                            + el.getAttribute("href"));
                    js.executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                    Thread.sleep(300);
                    js.executeScript("arguments[0].click()", el);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // STRATEGY 2: data-page attribute
        try {
            List<WebElement> els = driver.findElements(
                    By.cssSelector("a[data-page='" + next + "']"));
            for (WebElement el : els) {
                if (el.isDisplayed()) {
                    System.out.println("[Pagination S2] data-page match");
                    js.executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                    Thread.sleep(300);
                    js.executeScript("arguments[0].click()", el);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // STRATEGY 3: href inside pagination container
        try {
            List<WebElement> els = driver.findElements(By.xpath(
                    "//*[contains(@class,'pagination') or contains(@class,'page-nav') " +
                            "or contains(@class,'Pagination')]" +
                            "//a[contains(@href,'-" + next + "')]"
            ));
            for (WebElement el : els) {
                if (el.isDisplayed()) {
                    String txt = el.getText().trim();
                    if (txt.equals(String.valueOf(next)) || txt.isBlank()) {
                        System.out.println("[Pagination S3] pagination container match: "
                                + el.getAttribute("href"));
                        js.executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                        Thread.sleep(300);
                        js.executeScript("arguments[0].click()", el);
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}

        // STRATEGY 4: Build URL directly
        try {
            String nextUrl = buildNextPageUrl(currentUrl, next);
            if (nextUrl != null) {
                System.out.println("[Pagination S4] URL-based navigation: " + nextUrl);
                List<WebElement> verify = driver.findElements(
                        By.cssSelector("a[href*='-" + next + "']"));
                if (!verify.isEmpty()) {
                    driver.get(nextUrl);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // STRATEGY 5: Next button
        try {
            List<WebElement> nextBtns = driver.findElements(By.xpath(
                    "//a[@aria-label='Next page' or @aria-label='next' " +
                            "or contains(@class,'next-btn') or contains(@class,'pagination-next')]" +
                            "[not(contains(@class,'disabled'))]"
            ));
            for (WebElement el : nextBtns) {
                if (el.isDisplayed()) {
                    System.out.println("[Pagination S5] Next button");
                    js.executeScript("arguments[0].click()", el);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // Debug output
        System.out.println("[Pagination] Failed. Nearby page links:");
        try {
            List<WebElement> all = driver.findElements(By.xpath(
                    "//a[contains(@href,'-" + next + "') or @data-page]"));
            if (all.isEmpty()) System.out.println("  none found");
            else for (WebElement e : all)
                System.out.println("  text=[" + e.getText().trim()
                        + "] href=[" + safeAttr(e,"href") + "]");
        } catch (Exception ignored) {}

        return false;
    }

    private String buildNextPageUrl(String currentUrl, int nextPage) {
        if (currentUrl == null || currentUrl.isBlank()) return null;

        if (currentUrl.contains("?page=") || currentUrl.contains("&page=")) {
            return currentUrl.replaceAll("[?&]page=\\d+", "")
                    + (currentUrl.contains("?") ? "&" : "?") + "page=" + nextPage;
        }

        String base = currentUrl.replaceAll("-\\d+$", "");
        base = base.replaceAll("/$", "");
        return base + "-" + nextPage;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // keepMinimized
    // ─────────────────────────────────────────────────────────────────────────
    private void keepMinimized(WebDriver driver) {
        /*
        try {
            driver.manage().window().minimize();
        } catch (Exception ignored) {}
        */
    }

    private void safeCleanupTabs(WebDriver driver, String mainWindow) {
        try {
            for (String tab : new ArrayList<>(driver.getWindowHandles())) {
                if (!tab.equals(mainWindow)) {
                    try { driver.switchTo().window(tab); driver.close(); }
                    catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        try { driver.switchTo().window(mainWindow); } catch (Exception ignored) {}
    }

    private boolean isSessionAlive(WebDriver driver) {
        try { driver.getWindowHandles(); return true; }
        catch (Exception e) { return false; }
    }

    private String extractCompany(WebDriver driver) {
        String[] sels = {
                ".jd-header-comp-name a", "[class*='comp-name']",
                ".company-name", ".orgName", "[class*='companyName']"
        };
        for (String sel : sels) {
            try {
                List<WebElement> els = driver.findElements(By.cssSelector(sel));
                if (!els.isEmpty()) {
                    String t = els.get(0).getText().trim();
                    if (!t.isBlank()) return t;
                }
            } catch (Exception ignored) {}
        }
        return "Unknown";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KEY SKILLS MATCHING
    // Reads the "Key Skills" chips off the job description page and compares
    // them against the user's configured key skills (profile.getKeySkillsList()).
    // If the job page doesn't expose key-skills chips, falls back to comparing
    // against the job title instead.
    // If the user hasn't configured any key skills, no filtering happens at all
    // (matches everything) — this keeps old behavior as the default.
    // ─────────────────────────────────────────────────────────────────────────
    private List<String> extractJobKeySkills(WebDriver driver) {
        String[] sels = {
                ".key-skill a", ".key-skill span",
                "[class*='key-skill'] a", "[class*='key-skill'] span",
                ".styles_key-skill__GIPn_ a", ".chip-container .chip"
        };
        List<String> skills = new ArrayList<>();
        for (String sel : sels) {
            try {
                List<WebElement> els = driver.findElements(By.cssSelector(sel));
                for (WebElement el : els) {
                    String t = el.getText().trim();
                    if (!t.isBlank()) skills.add(t.toLowerCase());
                }
                if (!skills.isEmpty()) break; // stop at the first selector that finds anything
            } catch (Exception ignored) {}
        }
        return skills;
    }

    private boolean matchesUserSkills(WebDriver driver, String title, UserProfile profile) {
        List<String> userSkills = profile.getKeySkillsList();
        if (userSkills.isEmpty()) return true; // no filter configured — apply to everything

        List<String> jobSkills = extractJobKeySkills(driver);
        String compareText = !jobSkills.isEmpty()
                ? String.join(" ", jobSkills)
                : (title != null ? title : "");
        compareText = compareText.toLowerCase();

        for (String skill : userSkills) {
            if (compareText.contains(skill)) return true;
        }
        System.out.println("[SkillMatch] No overlap between user skills " + userSkills
                + " and job " + (!jobSkills.isEmpty() ? "key-skills " + jobSkills : "title [" + title + "]"));
        return false;
    }

    private void printSummary(ApplicationTracker tracker) {
        long success = tracker.countByStatus("SUCCESS");
        long direct  = tracker.countByStatus("DIRECT_APPLY");
        long failed  = tracker.countByStatus("FAILED");
        long skipped = tracker.countByStatus("SKIPPED");
        long redir   = tracker.countByStatus("REDIRECTED");
        long total   = success + direct + failed + skipped + redir;

        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║          APPLYBOT — RUN COMPLETE         ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf( "║  Total processed   : %-20d ║%n", total);
        System.out.printf( "║  Applied (chatbot) : %-20d ║%n", success);
        System.out.printf( "║  Applied (direct)  : %-20d ║%n", direct);
        System.out.printf( "║  Total applied     : %-20d ║%n", success + direct);
        System.out.printf( "║  Failed            : %-20d ║%n", failed);
        System.out.printf( "║  Skipped           : %-20d ║%n", skipped);
        System.out.printf( "║  Redirected        : %-20d ║%n", redir);
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  Report saved to ~/applybot-reports/     ║");
        System.out.println("╚══════════════════════════════════════════╝\n");
    }

    private void login(WebDriver driver, UserProfile profile) throws InterruptedException {
        driver.get("https://www.naukri.com/nlogin/login");
        Thread.sleep(2000);
        driver.findElement(By.id("usernameField")).sendKeys(profile.getNaukriEmail());
        driver.findElement(By.id("passwordField")).sendKeys(profile.getNaukriPassword());
        driver.findElement(By.xpath("//button[text()='Login']")).click();
        Thread.sleep(4000);
        keepMinimized(driver);
        System.out.println("Login done: " + profile.getNaukriEmail());
    }

    // private boolean matchesRole(String title, String rf) {
    //     for (String p : rf.split("[\\s-]+")) { if (p.length()>2 && title.contains(p)) return true; }
    //     return false;
    // }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String safeAttr(WebElement el, String attr) {
        try { String v = el.getAttribute(attr); return v == null ? "" : v; }
        catch (Exception e) { return ""; }
    }
}