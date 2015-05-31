
package org.ggp.base.util.logging;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.codec.binary.Base64OutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;

/**
 * Log parser and formatter for uploading to Tiltyard / the local log viewer.
 */
public class LogSummaryGenerator
{
  private static final File LOGS_DIRECTORY = new File("logs");

  // Dumping of logs is disabled because they're too big for Tiltyard.
  private static final boolean DUMP_LOGS = true;

  /**
   * @return the logs for the specified match.
   *
   * @param xiMatchID - the match.
   */
  public synchronized String getLogSummary(final String xiMatchID)
  {
    System.out.println("Generating logs for " + xiMatchID + " at " + System.currentTimeMillis());
    FilenameFilter lFilter = new FilenameFilter()
    {
      @Override
      public boolean accept(File xiDir, String xiName)
      {
        return xiName.startsWith(xiMatchID + "-9147") ||
               xiName.startsWith(xiMatchID + "-0");
      }
    };
    String[] lLogFiles = LOGS_DIRECTORY.list(lFilter);

    if (lLogFiles.length == 0)
    {
      System.err.println("No logs for match: " + xiMatchID);
      return "No logs for match: " + xiMatchID;
    }

    String lSummary = getSummaryFromLogsDirectory(lLogFiles, true);
    if (lSummary.length() > (980 * 1024))
    {
      // Likely to exceed the Tiltyard archive limit.  Try again, omitting the logs and just including the graphs.
      System.out.println("Omitting logs because they're too large");
      lSummary = getSummaryFromLogsDirectory(lLogFiles, false);
    }
    return lSummary;
  }

  private static String getSummaryFromLogsDirectory(String[] xiLogFiles, boolean xiIncludeLogs)
  {
    StringBuffer lBuffer = new StringBuffer(1024 * 1024);

    lBuffer.append('{');
    for (String lLogFile : xiLogFiles)
    {
      if (lLogFile.endsWith(".csv"))
      {
        // This is the statistics file
        lBuffer.append("\"statistics\":[");
        formatStatistics(lBuffer, lLogFile);
        lBuffer.append("],");
      }
      else if (lLogFile.endsWith(".json"))
      {
        // This is the regular log file
        lBuffer.append("\"compressed_logs\":\"");

        if (xiIncludeLogs)
        {
          try (ByteArrayOutputStream lBOS = new ByteArrayOutputStream();
               Base64OutputStream lB64OS = new Base64OutputStream(lBOS);
               BZip2CompressorOutputStream lCompressor = new BZip2CompressorOutputStream(lB64OS))
          {
            List<String> lLines = Files.readAllLines(Paths.get(LOGS_DIRECTORY.getPath(), lLogFile),
                                                     StandardCharsets.UTF_8);

            lCompressor.write("[".getBytes(StandardCharsets.UTF_8));
            for (String lLine : lLines)
            {
              lCompressor.write(lLine.getBytes(StandardCharsets.UTF_8));
              lCompressor.write("\n".getBytes(StandardCharsets.UTF_8));
            }
            lCompressor.write("]".getBytes(StandardCharsets.UTF_8));

            lCompressor.close();
            lB64OS.close();
            lBOS.close();

            lBuffer.append(new String(lBOS.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", ""));
          }
          catch (IOException lEx)
          {
            System.err.println("Failed to read log file: " + lLogFile);
            lEx.printStackTrace();
          }
        }

        lBuffer.append("\",");
      }
    }
    lBuffer.append("\"version\":1}");

    return lBuffer.toString();
  }

  private static void formatStatistics(StringBuffer xiBuffer, String xiLogFilename)
  {
    List<String> lLines = null;

    try
    {
      lLines = Files.readAllLines(Paths.get(LOGS_DIRECTORY.getPath(), xiLogFilename), StandardCharsets.UTF_8);
    }
    catch (IOException lEx)
    {
      System.err.println("Failed to read statistics file: " + xiLogFilename);
      lEx.printStackTrace();
      return;
    }

    for (String lLine : lLines)
    {
      Series.loadDataPoint(lLine);
    }

    for (Series lSeries : Series.values())
    {
      if (!lSeries.isEmpty())
      {
        lSeries.appendToJSON(xiBuffer);
        lSeries.reset();
        xiBuffer.append(',');
      }
    }
    xiBuffer.setLength(xiBuffer.length() - 1);
  }
}