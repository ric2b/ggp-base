
package org.ggp.base.apps.player;

import java.io.IOException;

import org.ggp.base.player.GamePlayer;
import org.ggp.base.player.gamer.statemachine.sample.Sancho;

public class SanchoPlayer
{
  private static Sancho thePlayer = new Sancho();

  public static void main(String[] args) throws IOException
  {
    int port = 9147;
    int numThreads = 0;
    int tableSize = 200000;
    boolean syntaxError = false;

    for (String arg : args)
    {
      if (arg.startsWith("-"))
      {
        String normalizedArg = arg.toLowerCase();
        int sepIndex = normalizedArg.indexOf(':');
        String argStem;
        String argValue;

        if (sepIndex == -1)
        {
          argStem = normalizedArg.substring(1);
          argValue = "";
        }
        else
        {
          argStem = normalizedArg.substring(1, sepIndex);
          argValue = normalizedArg.substring(sepIndex + 1);
        }

        switch (argStem)
        {
          case "port":
            port = Integer.parseInt(argValue);
            break;
          case "threads":
            numThreads = Integer.parseInt(argValue);
            break;
          case "tablesize":
            tableSize = Integer.parseInt(argValue);
            break;
          default:
            syntaxError = true;
            System.out
                .println("Valid parameters are -port:<portnum> -threads:<#rollout_threads> -tableSize<size in entries>");
            break;
        }
      }
      else
      {
        syntaxError = true;
        System.out
            .println("Arguments must begin with a '-': valid parameters are -port:<portnum> -threads:<#rollout_threads> -tableSize<size in entries>");
      }
    }

    if (!syntaxError)
    {
      thePlayer.setNumThreads(numThreads);
      thePlayer.setTranspositionTableSize(tableSize);

      new GamePlayer(port, thePlayer).start();
    }
  }
}