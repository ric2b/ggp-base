package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.PrintStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @author steve
 *
 */
public class StdOutErrLog
{
  private static final Logger logger = LogManager.getLogger();

  public static void tieSystemOutAndErrToLog() {
      System.setOut(createLoggingProxy(System.out));
      System.setErr(createLoggingProxy(System.err));
  }

  public static PrintStream createLoggingProxy(final PrintStream realPrintStream)
  {
      return new PrintStream(realPrintStream)
        {
          @Override
          public void print(final String string)
          {
              realPrintStream.print(string);
              logger.info(string);
          }
          @Override
          public void println(final String string)
          {
            realPrintStream.println(string);
            logger.warn(string);
          }
        };
  }
}