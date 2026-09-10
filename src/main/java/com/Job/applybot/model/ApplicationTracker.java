package com.Job.applybot.model;

import com.Job.applybot.Service.ApplicationResult;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ApplicationTracker — writes every job result to a PER-USER Excel file
 * immediately after each application attempt (no data is lost on crash).
 *
 * Behaviour:
 *  - File: <outputDir>/applybot_results_<username>.xlsx  (one file per user)
 *  - If the user's file does NOT exist → creates it with a fresh header
 *  - If the user's file DOES exist     → opens it and appends a new row
 *    (so each re-run of the same user accumulates in the same workbook)
 *  - If the user's file IS OPEN/LOCKED → falls back to
 *    applybot_results_<username>_backup.xlsx so no data is lost
 *  - The Summary sheet is refreshed every time finish() is called
 *
 * Usage in Bot.java (unchanged):
 *   tracker.add(result);   ← call after every job attempt
 *   tracker.finish();      ← call once at the end of the run
 */
public class ApplicationTracker {


    // ── Sheet names ───────────────────────────────────────────────────────────
    private static final String SHEET_DATA = "Applications";
    private static final String SHEET_SUM  = "Summary";

    // Column layout
    private static final String[] HEADERS = {
            "#", "Timestamp", "Username", "Job Title", "Company",
            "Status", "Job URL", "Final URL (Link Used)", "Notes", "Run ID"
    };
    private static final int[] COL_WIDTHS = {
            5,   22,   20,   38,   22,
            16,   52,   52,   32,   22
    };

    // Column indexes (0-based)
    private static final int COL_SEQ      = 0;
    private static final int COL_TS       = 1;
    private static final int COL_USER     = 2;
    private static final int COL_TITLE    = 3;
    private static final int COL_COMPANY  = 4;
    private static final int COL_STATUS   = 5;
    private static final int COL_JOBURL   = 6;
    private static final int COL_FINALURL = 7;
    private static final int COL_NOTES    = 8;
    private static final int COL_RUN      = 9;

    // Colours
    private static final String C_HEADER  = "1A1A2E";
    private static final String C_TITLE   = "16213E";
    private static final String C_ALT     = "F7F7F7";
    private static final String C_WHITE   = "FFFFFF";
    private static final String C_LINK    = "0563C1";
    private static final String C_BORDER  = "CCCCCC";
    private static final String C_SUM_BG  = "0F3460";

    private static final String C_SUCCESS  = "27AE60";
    private static final String C_DIRECT   = "2980B9";
    private static final String C_FAILED   = "C0392B";
    private static final String C_SKIPPED  = "95A5A6";
    private static final String C_REDIR    = "D68910";
    private static final Logger log = LoggerFactory.getLogger(ApplicationTracker.class);

    // ── Instance fields ───────────────────────────────────────────────────────
    private final String outputDir;
    private final String username;   // used to build the per-user file name
    private final String runId;

    private static final DateTimeFormatter DT_FMT  =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RUN_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── Constructors ──────────────────────────────────────────────────────────

    public ApplicationTracker(String username) {
        this.username  = sanitize(username);
        this.outputDir = System.getProperty("user.home") + File.separator + "applybot-reports";
        this.runId     = username + " @ " + LocalDateTime.now().format(RUN_FMT);
    }

    public ApplicationTracker(String username, String outputDir) {
        this.username  = sanitize(username);
        this.outputDir = outputDir;
        this.runId     = username + " @ " + LocalDateTime.now().format(RUN_FMT);
    }

    // ── File-name helpers ─────────────────────────────────────────────────────

    /** Primary file for this user: applybot_results_<username>.xlsx */
//    private String primaryPath() {
//        return outputDir + File.separator + "applybot_results_" + username + ".xlsx";
//    }

    /** Backup file used when the primary is open/locked: applybot_results_<username>_backup.xlsx */
//    private String backupPath() {
//        return outputDir + File.separator + "applybot_results_" + username + "_backup.xlsx";
//    }

    private String currentdate(){
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
    String test=currentdate();


    private String primaryPath(){
        System.out.println(test);
        return outputDir+File.separator+"applybot_results_"+username+"_"+currentdate()+".xlsx";
    }

    private String backupPath(){
        return outputDir+File.separator+"applybot_results_"+username+"_"+currentdate()+"_backup.xlsx";
    }
    /**
     * Strip characters that are illegal in file names on Windows/macOS/Linux
     * so the username can be embedded safely in a path.
     */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Appends one result row to this user's Excel file and saves immediately.
     * Falls back to the backup file when the primary is locked/open.
     */
    public void add(ApplicationResult result) {
        try {
            Files.createDirectories(Paths.get(outputDir));

            // Always try to write to the primary file first.
            // If the primary is locked (FileNotFoundException on Windows),
            // fall back to the backup.
            boolean savedToPrimary = tryWrite(primaryPath(), result);
            if (!savedToPrimary) {
                System.out.println("[Tracker] Primary file locked — writing to backup: " + backupPath());
                boolean savedToBackup = tryWrite(backupPath(), result);
                if (!savedToBackup) {
                    System.out.println("[Tracker] ERROR: could not save to either primary or backup!");
                }
            }

        } catch (Exception e) {
            System.out.println("[Tracker] ERROR saving result: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Rebuilds the Summary sheet in the user's primary file.
     * Falls back to the backup file if the primary is locked.
     * Call once at the end of a bot run.
     */
    public void finish() {
        // Refresh primary; if locked, refresh backup instead.
        boolean done = tryRefreshSummary(primaryPath());
        if (!done) {
            System.out.println("[Tracker] Primary locked for summary — refreshing backup: " + backupPath());
            tryRefreshSummary(backupPath());
        }
    }

    /** Returns the path of this user's primary Excel file. */
    public String getFilePath() {
        return primaryPath();
    }

    /** Counts rows with a given status label in the primary file. */
    public long countByStatus(String statusLabel) {
        long count = 0;
        try {
            File file = new File(primaryPath());
            if (!file.exists()) return 0;
            ZipSecureFile.setMinInflateRatio(0);
            try (FileInputStream fis = new FileInputStream(file);
                 XSSFWorkbook wb = new XSSFWorkbook(fis)) {
                XSSFSheet sheet = wb.getSheet(SHEET_DATA);
                if (sheet == null) return 0;
                for (int ri = 2; ri <= sheet.getLastRowNum(); ri++) {
                    Row row = sheet.getRow(ri);
                    if (row == null) continue;
                    Cell sc = row.getCell(COL_STATUS);
                    if (sc != null && statusLabel.equals(sc.getStringCellValue().trim())) count++;
                }
            }
        } catch (Exception ignored) {}
        return count;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Opens (or creates) the Excel file at {@code filePath}, appends one row,
     * then saves.  Returns {@code true} on success, {@code false} if the file
     * could not be written (e.g. locked by another process).
     */
    private boolean tryWrite(String filePath, ApplicationResult result) {
        try {
            File file = new File(filePath);
            XSSFWorkbook wb;
            XSSFSheet    dataSheet;

            ZipSecureFile.setMinInflateRatio(0);
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    wb = new XSSFWorkbook(fis);
                }
                dataSheet = wb.getSheet(SHEET_DATA);
                if (dataSheet == null) {
                    dataSheet = wb.createSheet(SHEET_DATA);
                    wb.setSheetOrder(SHEET_DATA, 0);
                    writeHeaderRow(wb, dataSheet, username);
                }
            } else {
                wb = new XSSFWorkbook();
                dataSheet = wb.createSheet(SHEET_DATA);
                wb.createSheet(SHEET_SUM);   // placeholder, rebuilt on finish()
                writeHeaderRow(wb, dataSheet, username);
            }

            int nextRow    = dataSheet.getLastRowNum() + 1;
            int dataRowNum = nextRow - 1;
            appendDataRow(wb, dataSheet, nextRow, dataRowNum, result);

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                wb.write(fos);
            }
            wb.close();

            System.out.println("[Tracker] Saved row #" + dataRowNum
                    + " → " + filePath
                    + " | " + result.getStatusLabel()
                    + " | " + truncate(result.getJobTitle(), 50));
            return true;

        } catch (FileNotFoundException e) {
            // File is open/locked — caller will try the backup
            return false;
        } catch (Exception e) {
            System.out.println("[Tracker] ERROR in tryWrite(" + filePath + "): " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Rebuilds the Summary sheet in the file at {@code filePath}.
     * Returns {@code true} on success, {@code false} if locked/missing.
     */
    private boolean tryRefreshSummary(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return false;

            ZipSecureFile.setMinInflateRatio(0);
            XSSFWorkbook wb;
            try (FileInputStream fis = new FileInputStream(file)) {
                wb = new XSSFWorkbook(fis);
            }

            int idx = wb.getSheetIndex(SHEET_SUM);
            if (idx >= 0) wb.removeSheetAt(idx);
            XSSFSheet sumSheet = wb.createSheet(SHEET_SUM);
            wb.setSheetOrder(SHEET_SUM, 1);

            buildSummarySheet(wb, sumSheet, wb.getSheet(SHEET_DATA));

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                wb.write(fos);
            }
            wb.close();
            System.out.println("[Tracker] Summary refreshed → " + filePath);
            return true;

        } catch (FileNotFoundException e) {
            return false;
        } catch (Exception e) {
            System.out.println("[Tracker] ERROR refreshing summary: " + e.getMessage());
            return false;
        }
    }

    // ── Header / row writers ──────────────────────────────────────────────────

    private void writeHeaderRow(XSSFWorkbook wb, XSSFSheet sheet, String user) {
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(28);
        Cell tc = titleRow.createCell(0);
        tc.setCellValue("  Applybot — Applications for: " + user);
        tc.setCellStyle(titleStyle(wb));
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));

        Row hRow = sheet.createRow(1);
        hRow.setHeightInPoints(20);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell c = hRow.createCell(i);
            c.setCellValue(HEADERS[i]);
            c.setCellStyle(headerStyle(wb));
            sheet.setColumnWidth(i, COL_WIDTHS[i] * 256);
        }

        sheet.createFreezePane(0, 2);
    }

    private void appendDataRow(XSSFWorkbook wb, XSSFSheet sheet,
                               int rowIndex, int seqNum, ApplicationResult r) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(16);

        boolean alt  = (rowIndex % 2 == 0);
        CellStyle base = alt ? altRowStyle(wb) : baseRowStyle(wb);

        setCell(row, COL_SEQ,     String.valueOf(seqNum), base);
        setCell(row, COL_TS,      r.getTimestamp(),       base);
        setCell(row, COL_USER,    r.getUsername(),        base);
        setCell(row, COL_TITLE,   r.getJobTitle(),        base);
        setCell(row, COL_COMPANY, r.getCompany(),         base);

        Cell statusCell = row.createCell(COL_STATUS);
        statusCell.setCellValue(r.getStatusLabel());
        statusCell.setCellStyle(statusStyle(wb, r.getStatus()));

        setUrlCell(wb, row, COL_JOBURL,   r.getJobUrl(),   base);
        setUrlCell(wb, row, COL_FINALURL, r.getFinalUrl(), base);
        setCell(row, COL_NOTES, r.getNotes(), base);
        setCell(row, COL_RUN,   runId,        base);

        sheet.setAutoFilter(new CellRangeAddress(1, rowIndex, 0, HEADERS.length - 1));
    }

    // ── Summary sheet ─────────────────────────────────────────────────────────

    private void buildSummarySheet(XSSFWorkbook wb, XSSFSheet sum, XSSFSheet data) {
        int total = 0, success = 0, direct = 0, failed = 0, skipped = 0, redirected = 0;
        if (data != null) {
            int lastRow = data.getLastRowNum();
            for (int ri = 2; ri <= lastRow; ri++) {
                Row row = data.getRow(ri);
                if (row == null) continue;
                Cell sc = row.getCell(COL_STATUS);
                if (sc == null) continue;
                String st = sc.getStringCellValue().trim();
                total++;
                switch (st) {
                    case "SUCCESS"      -> success++;
                    case "DIRECT_APPLY" -> direct++;
                    case "FAILED"       -> failed++;
                    case "SKIPPED"      -> skipped++;
                    case "REDIRECTED"   -> redirected++;
                }
            }
        }
        int    applied = success + direct;
        double rate    = total > 0 ? (double) applied / total * 100.0 : 0.0;

        sum.setColumnWidth(0, 38 * 256);
        sum.setColumnWidth(1, 16 * 256);
        sum.setColumnWidth(2, 14 * 256);

        Row t = sum.createRow(0); t.setHeightInPoints(28);
        Cell tc = t.createCell(0);
        tc.setCellValue("  Applybot — Summary for: " + username);
        tc.setCellStyle(titleStyle(wb));
        sum.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        Object[][] rows = {
                { "Total applications scanned",           total   },
                { "Applied — chatbot / popup",             success },
                { "Applied — direct (no questions)",       direct  },
                { "Total applied",                         applied },
                { "Failed",                                failed  },
                { "Skipped (already applied / not found)", skipped },
                { "Redirected to company site",            redirected },
                { "Overall success rate",                  String.format("%.1f%%", rate) },
        };

        CellStyle labelSt = sumLabelStyle(wb);
        CellStyle valueSt = sumValueStyle(wb);

        for (int i = 0; i < rows.length; i++) {
            Row row = sum.createRow(i + 2); row.setHeightInPoints(20);
            Cell lc = row.createCell(0);
            lc.setCellValue(rows[i][0].toString());
            lc.setCellStyle(labelSt);
            Cell vc = row.createCell(1);
            if (rows[i][1] instanceof Integer n) vc.setCellValue(n);
            else vc.setCellValue(rows[i][1].toString());
            vc.setCellStyle(valueSt);
        }

        Row sep = sum.createRow(11); sep.setHeightInPoints(10);
        Row bh  = sum.createRow(12); bh.setHeightInPoints(20);
        Cell bhc = bh.createCell(0);
        bhc.setCellValue("Status legend");
        bhc.setCellStyle(headerStyle(wb));
        sum.addMergedRegion(new CellRangeAddress(12, 12, 0, 2));

        Object[][] legend = {
                { "SUCCESS",      "ChatBot or Popup flow completed",    C_SUCCESS },
                { "DIRECT_APPLY", "Applied directly — no questions",    C_DIRECT  },
                { "FAILED",       "Apply attempted but error occurred", C_FAILED  },
                { "SKIPPED",      "Already applied / button not found", C_SKIPPED },
                { "REDIRECTED",   "Naukri sent to company own site",    C_REDIR   },
        };
        for (int i = 0; i < legend.length; i++) {
            Row lr = sum.createRow(13 + i); lr.setHeightInPoints(18);
            Cell badge = lr.createCell(0);
            badge.setCellValue(legend[i][0].toString());
            badge.setCellStyle(statusStyleByHex(wb, legend[i][2].toString()));
            Cell desc = lr.createCell(1);
            desc.setCellValue(legend[i][1].toString());
            desc.setCellStyle(baseRowStyle(wb));
            sum.addMergedRegion(new CellRangeAddress(13 + i, 13 + i, 1, 2));
        }
    }

    // ── Cell helpers ──────────────────────────────────────────────────────────

    private void setCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        c.setCellStyle(style);
    }

    private void setUrlCell(XSSFWorkbook wb, Row row, int col,
                            String url, CellStyle fallback) {
        Cell c = row.createCell(col);
        if (url != null && !url.isBlank() && url.startsWith("http")) {
            c.setCellValue(url);
            XSSFHyperlink link = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
            link.setAddress(url);
            c.setHyperlink(link);
            c.setCellStyle(hyperlinkStyle(wb));
        } else {
            c.setCellValue(url != null ? url : "—");
            c.setCellStyle(fallback);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ── Style factories ───────────────────────────────────────────────────────

    private XSSFCellStyle titleStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(hex(C_TITLE));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.LEFT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 13);
        f.setColor(hex(C_WHITE)); f.setFontName("Arial");
        s.setFont(f); borders(s); return s;
    }

    private XSSFCellStyle headerStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(hex(C_HEADER));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 10);
        f.setColor(hex(C_WHITE)); f.setFontName("Arial");
        s.setFont(f); borders(s); return s;
    }

    private XSSFCellStyle baseRowStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 10);
        s.setFont(f); borders(s); return s;
    }

    private XSSFCellStyle altRowStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(hex(C_ALT));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 10);
        s.setFont(f); borders(s); return s;
    }

    private XSSFCellStyle hyperlinkStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont();
        f.setFontName("Arial"); f.setFontHeightInPoints((short) 10);
        f.setUnderline(FontUnderline.SINGLE);
        f.setColor(hex(C_LINK));
        s.setFont(f); borders(s); return s;
    }

    private XSSFCellStyle statusStyle(XSSFWorkbook wb, ApplicationResult.Status status) {
        return statusStyleByHex(wb, colorOf(status));
    }

    private XSSFCellStyle statusStyleByHex(XSSFWorkbook wb, String hexColor) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(hex(hexColor));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontHeightInPoints((short) 9);
        f.setColor(hex(C_WHITE)); f.setFontName("Arial");
        s.setFont(f); borders(s); return s;
    }

    private XSSFCellStyle sumLabelStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(hex(C_SUM_BG));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont();
        f.setFontName("Arial"); f.setFontHeightInPoints((short) 11);
        f.setColor(hex(C_WHITE));
        s.setFont(f); borders(s); return s;
    }

    private XSSFCellStyle sumValueStyle(XSSFWorkbook wb) {
        XSSFCellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFFont f = wb.createFont();
        f.setBold(true); f.setFontName("Arial"); f.setFontHeightInPoints((short) 13);
        s.setFont(f); borders(s); return s;
    }

    private void borders(XSSFCellStyle s) {
        XSSFColor bc = hex(C_BORDER);
        s.setBorderTop(BorderStyle.THIN);    s.setTopBorderColor(bc);
        s.setBorderBottom(BorderStyle.THIN); s.setBottomBorderColor(bc);
        s.setBorderLeft(BorderStyle.THIN);   s.setLeftBorderColor(bc);
        s.setBorderRight(BorderStyle.THIN);  s.setRightBorderColor(bc);
    }

    private XSSFColor hex(String rgb6) {
        int r = Integer.parseInt(rgb6.substring(0, 2), 16);
        int g = Integer.parseInt(rgb6.substring(2, 4), 16);
        int b = Integer.parseInt(rgb6.substring(4, 6), 16);
        return new XSSFColor(new byte[]{ (byte) r, (byte) g, (byte) b }, null);
    }

    private String colorOf(ApplicationResult.Status status) {
        return switch (status) {
            case SUCCESS      -> C_SUCCESS;
            case DIRECT_APPLY -> C_DIRECT;
            case FAILED       -> C_FAILED;
            case SKIPPED      -> C_SKIPPED;
            case REDIRECTED   -> C_REDIR;
        };
    }
}
