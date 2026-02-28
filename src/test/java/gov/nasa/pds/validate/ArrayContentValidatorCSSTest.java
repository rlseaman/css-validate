// Copyright 2026, Catalina Sky Survey / University of Arizona.
// Offered to NASA-PDS under the same open-source terms as the validate tool.
//
// Integration tests for the CSS-local ArrayContentValidator fast path.
// See src/main/java/.../content/array/ArrayContentValidator.java (CSS-LOCAL block).

package gov.nasa.pds.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import gov.nasa.pds.validate.constants.TestConstants;

/**
 * Integration tests for the CSS-local O(1) fast path in ArrayContentValidator.
 *
 * <p>The fast path substitutes a single {@code Files.size()} stat for the
 * 27.8-million-iteration pixel loop on CSS FITS images (SignedMSB2 5280×5280,
 * no Special_Constants or Object_Statistics).  These tests verify correctness
 * across the five cases that determine whether the fast path fires:
 *
 * <ol>
 *   <li>Fast path taken, correct file size  → PASS (0 errors)</li>
 *   <li>Fast path taken, truncated file     → FAIL (ARRAY_DATA_FILE_READ_ERROR)</li>
 *   <li>Fast path skipped: Object_Statistics present → original loop, PASS</li>
 *   <li>Fast path skipped: unsigned type (UnsignedMSB2) → original loop, PASS</li>
 *   <li>Fast path skipped: Special_Constants present   → original loop, PASS</li>
 * </ol>
 *
 * <p>Test data: 10×10 arrays of SignedMSB2 (or UnsignedMSB2) zero-filled
 * pixels in {@code src/test/resources/css-testdata/}.
 * Labels use IM 1G00 (PDS4 v1.16.0.0) with {@code --skip-context-validation}
 * so LID references to non-existent context products do not cause failures.
 * Schema resolution uses the local catalog at
 * {@code ~/Claude/psi-catalina/schemas/catalog_all.xml}.
 */
class ArrayContentValidatorCSSTest {

  /** Path to the OASIS catalog that maps PDS4 URLs to local schema files. */
  private static final String CATALOG = System.getProperty("css.catalog",
      System.getProperty("user.home") + "/Claude/psi-catalina/schemas/catalog_all.xml");

  /** Directory containing test labels and data files. */
  private static final String TEST_DATA =
      TestConstants.TEST_DATA_DIR + File.separator + "css-testdata";

  private ValidateLauncher launcher;

  @BeforeEach
  void setUp() throws Exception {
    FileUtils.forceMkdir(new File(TestConstants.TEST_OUT_DIR));
    System.setProperty("resources.home", TestConstants.RESOURCES_DIR);
    this.launcher = new ValidateLauncher();
  }

  @AfterEach
  void tearDown() throws Exception {
    this.launcher.flushValidators();
  }

  // ── Test 1 ────────────────────────────────────────────────────────────────

  /**
   * Fast path fires for SignedMSB2 with no Special_Constants / Object_Statistics
   * and a correctly-sized data file (200 bytes = 10×10×2).  Expects 0 errors.
   */
  @Test
  void testFastPathValid() {
    try {
      File report = reportFile("css_fastpath_valid.json");
      String[] args = baseArgs(report, "css_fastpath_valid.xml");
      this.launcher.processMain(args);
      assertTotalErrors(report, 0, "Fast path, valid file");
    } catch (Exception e) {
      fail("Unexpected exception: " + e.getMessage());
    }
  }

  // ── Test 2 ────────────────────────────────────────────────────────────────

  /**
   * Fast path fires but detects that the data file (199 bytes) is 1 byte short
   * of the expected 200 bytes.  Expects exactly 1 ARRAY_DATA_FILE_READ_ERROR.
   */
  @Test
  void testFastPathTruncated() {
    try {
      File report = reportFile("css_fastpath_trunc.json");
      String[] args = baseArgs(report, "css_fastpath_trunc.xml");
      this.launcher.processMain(args);
      // Report is written before validate returns; check for fast-path error message.
      assertTrue(report.exists(), "Report file must exist: " + report.getAbsolutePath());
      String text = new String(Files.readAllBytes(report.toPath()));
      assertTrue(text.contains("smaller than expected array"),
          "Expected fast-path ARRAY_DATA_FILE_READ_ERROR message in report.\nReport:\n" + text);
    } catch (Exception e) {
      fail("Unexpected exception: " + e.getMessage());
    }
  }

  // ── Test 3 ────────────────────────────────────────────────────────────────

  /**
   * Fast path is bypassed because the label declares Object_Statistics.
   * The original pixel loop runs on the 200-byte all-zeros file.
   * All pixels (value 0) are within the declared min=0 / max=0 range.
   * Expects 0 errors.
   */
  @Test
  void testFallThroughObjectStatistics() {
    try {
      File report = reportFile("css_objstats.json");
      String[] args = baseArgs(report, "css_fallthrough_objstats.xml");
      this.launcher.processMain(args);
      assertTotalErrors(report, 0, "Fall-through: Object_Statistics");
    } catch (Exception e) {
      fail("Unexpected exception: " + e.getMessage());
    }
  }

  // ── Test 4 ────────────────────────────────────────────────────────────────

  /**
   * Fast path is bypassed because the data type is UnsignedMSB2 (not a signed
   * integer type).  The original pixel loop runs; all pixels (value 0) are
   * within the valid UnsignedMSB2 range [0, 65535].
   * Expects 0 errors.
   */
  @Test
  void testFallThroughUnsignedType() {
    try {
      File report = reportFile("css_unsigned.json");
      String[] args = baseArgs(report, "css_fallthrough_unsigned.xml");
      this.launcher.processMain(args);
      assertTotalErrors(report, 0, "Fall-through: UnsignedMSB2 type");
    } catch (Exception e) {
      fail("Unexpected exception: " + e.getMessage());
    }
  }

  // ── Test 5 ────────────────────────────────────────────────────────────────

  /**
   * Fast path is bypassed because the label declares Special_Constants
   * (missing_constant=-32768).  The original pixel loop runs; pixels are
   * value 0, which is not the missing constant and is within the SignedMSB2
   * range.  Expects 0 errors.
   */
  @Test
  void testFallThroughSpecialConstants() {
    try {
      File report = reportFile("css_special.json");
      String[] args = baseArgs(report, "css_fallthrough_special.xml");
      this.launcher.processMain(args);
      assertTotalErrors(report, 0, "Fall-through: Special_Constants");
    } catch (Exception e) {
      fail("Unexpected exception: " + e.getMessage());
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private File reportFile(String name) {
    return new File(TestConstants.TEST_OUT_DIR + File.separator + name);
  }

  /** Build the standard validate command-line args for a single label. */
  private String[] baseArgs(File report, String labelName) {
    return new String[] {
        "-r", report.getAbsolutePath(),
        "-s", "json",
        "-E", "99",
        "--skip-context-validation",
        "-C", CATALOG,
        "-t", TEST_DATA + File.separator + labelName
    };
  }

  /** Parse the JSON report and assert the summary totalErrors value. */
  private void assertTotalErrors(File report, int expected, String context) throws IOException {
    assertTrue(report.exists(), context + ": report file must exist: " + report.getAbsolutePath());
    Gson gson = new Gson();
    JsonObject root = gson.fromJson(new FileReader(report), JsonObject.class);
    JsonObject summary = root.getAsJsonObject("summary");
    assertNotNull(summary, context + ": report must have a 'summary' section");
    int actual = summary.get("totalErrors").getAsInt();
    assertEquals(expected, actual,
        context + ": expected " + expected + " totalErrors but got " + actual
            + "\nReport summary: " + summary);
  }
}
