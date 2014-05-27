
package org.ggp.base.util.logging;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;

/**
 * Log parser and formatter for uploading to Tiltyard / the local log viewer.
 */
public class LogSummaryGenerator
{
  private static final File LOGS_DIRECTORY = new File("logs");

  // Dumping of logs is disabled because they're too big for Tiltyard.
  private static final boolean DUMP_LOGS = false;

  /**
   * @return the logs for the specified match.
   *
   * @param xiMatchID - the match.
   */
  public synchronized String getLogSummary(String xiMatchID)
  {
    System.out.println("Generating logs for " + xiMatchID + " at " + System.currentTimeMillis());
    final String lFilePrefix = xiMatchID + "-9147";
    FilenameFilter lFilter = new FilenameFilter()
    {
      @Override
      public boolean accept(File xiDir, String xiName)
      {
        return xiName.startsWith(lFilePrefix);
      }
    };
    String[] lLogFiles = LOGS_DIRECTORY.list(lFilter);

    if (lLogFiles.length == 0)
    {
      System.err.println("No logs for match: " + xiMatchID);
      return null;
    }

    String lSummary = getSummaryFromLogsDirectory(lLogFiles);
    System.out.println("Finished generating logs for " + xiMatchID + " at " + System.currentTimeMillis());
    return lSummary;
  }

  private static String getSummaryFromLogsDirectory(String[] xiLogFiles)
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
        lBuffer.append("\"logs\":[");

        if (DUMP_LOGS)
        {
          try
          {
            List<String> lLines = Files.readAllLines(Paths.get(LOGS_DIRECTORY.getPath(), lLogFile),
                                                     StandardCharsets.UTF_8);
            for (String lLine : lLines)
            {
              lBuffer.append(lLine);
              lBuffer.append('\n');
            }
          }
          catch (IOException lEx)
          {
            System.err.println("Failed to read log file: " + lLogFile);
            lEx.printStackTrace();
          }
        }

        lBuffer.append("],");
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