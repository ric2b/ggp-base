package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Class giving access to machine-specific configuration.
 */
public class MachineSpecificConfiguration
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * Available configuration items.
   */
  public static enum CfgItem
  {
    /**
     * The number of CPU-intensive threads to use.
     */
    CPU_INTENSIVE_THREADS,

    /**
     * Whether to bind CPU-intensive threads to vCPUs.
     */
    USE_AFFINITY,

    /**
     * Safety margin for submitting moves.
     */
    SAFETY_MARGIN,

    /**
     * Whether to disable the use of piece heuristics
     */
    DISABLE_PIECE_HEURISTIC,

    /**
     * Whether to disable the use of state-similarity detection
     * for node expansion weighting
     */
    DISABLE_STATE_SIMILARITY_EXPANSION_WEIGHTING,

    /**
     * Whether to disable the use of learning (both using stuff already learned and also learning new stuff).
     */
    DISABLE_LEARNING,

    /**
     * Whether to disable node trimming on pool full (if disabled search stalls until the next move
     * reroots the tree).
     */
    DISABLE_NODE_TRIMMING,

    /**
     * Whether to disable greedy rollouts.
     */
    DISABLE_GREEDY_ROLLOUTS,

    /**
     * Player name to report
     */
    PLAYER_NAME,

    /**
     * If specified and strictly positive used as the rollout sample
     * size, without any dynamic tuning
     */
    FIXED_SAMPLE_SIZE,


    /**
     * Explicit transposition table size to use (aka max node count)
     */
    NODE_TABLE_SIZE;
  }

  private static final Properties MACHINE_PROPERTIES = new Properties();
  static
  {
    String lComputerName = System.getenv("COMPUTERNAME");
    if (lComputerName != null)
    {
      try (InputStream lPropStream = new FileInputStream("data/cfg/" + lComputerName + ".properties"))
      {
        MACHINE_PROPERTIES.load(lPropStream);
      }
      catch (IOException lEx)
      {
        System.err.println("Missing/invalid machine-specific configuration for " + lComputerName);
      }
    }
    else
    {
      System.err.println("Failed to identify computer name - no environment variable COMPUTERNAME");
    }
  }

  /**
   * @return the specified String configuration value, or the default if not configured.
   *
   * @param xiKey
   * @param xiDefault
   */
  public static String getCfgVal(CfgItem xiKey, String xiDefault)
  {
    return (MACHINE_PROPERTIES.getProperty(xiKey.toString(), xiDefault));
  }

  /**
   * @return the specified integer configuration value, or the default if not configured.
   *
   * @param xiKey
   * @param xiDefault
   */
  public static int getCfgVal(CfgItem xiKey, int xiDefault)
  {
    return Integer.parseInt(getCfgVal(xiKey, "" + xiDefault));
  }

  /**
   * @return the specified boolean configuration value, or the default if not configured.
   *
   * @param xiKey
   * @param xiDefault
   */
  public static boolean getCfgVal(CfgItem xiKey, boolean xiDefault)
  {
    return Boolean.parseBoolean(getCfgVal(xiKey, xiDefault ? "true" : "false"));
  }

  /**
   * Log all machine-specific configuration.
   */
  public static void logConfig()
  {
    LOGGER.info("Running with machine-specific properties:");
    for (Entry<Object, Object> e : MACHINE_PROPERTIES.entrySet())
    {
      LOGGER.info("\t" + e.getKey() + " = " + e.getValue());
    }
  }
}
