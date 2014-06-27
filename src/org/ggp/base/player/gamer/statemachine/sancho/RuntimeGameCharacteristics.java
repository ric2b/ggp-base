package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;

/**
 * Subclass of GameCharacterisics which adds characteristics that are specific to the game's runtime interpretation and
 * searching by Sancho.  These may vary dynamically.
 */
public class RuntimeGameCharacteristics extends GameCharacteristics
{
  private static final Logger LOGGER = LogManager.getLogger();
  private static final Logger STATS_LOGGER = LogManager.getLogger("stats");

  private static final String PROPS_FILE = "characteristics.properties";

  private static final String PLAN_KEY = "plan";

  private double        explorationBias         = 1.0;
  private double        mExactRolloutSampleSize = 4;
  private volatile int  mRolloutSampleSize;
  private int           mMaxObservedChoices;
  final double          competitivenessBonus    = 2;
  private boolean       isFixedMoveCount        = false;
  private int           earliestCompletion      = 0;
  final private int     fixedSampleSize         = MachineSpecificConfiguration.getCfgVal(CfgItem.FIXED_SAMPLE_SIZE, -1);
  private String        mPlan                   = null;

  /**
   * Create game characteristics, loading any state from previous games.
   *
   * @param xiNumRoles      - the number of roles in the game.
   * @param xiGameDirectory - the directory to load from.
   */
  public RuntimeGameCharacteristics(int xiNumRoles, File xiGameDirectory)
  {
    super(xiNumRoles);
    setRolloutSampleSize(4);

    if (xiGameDirectory != null)
    {
      // Attempt to load information from disk.
      File lPropsFile = new File(xiGameDirectory, PROPS_FILE);
      Properties lProperties = new Properties();

      try
      {
        lProperties.load(new FileInputStream(lPropsFile));
        mPlan = lProperties.getProperty(PLAN_KEY);
      }
      catch (IOException lEx)
      {
        if (lPropsFile.exists())
        {
          LOGGER.warn("Couldn't load properties file " + lPropsFile + " because of " + lEx);
        }
        else
        {
          LOGGER.debug("No properties file in " + xiGameDirectory);
        }
      }
    }
  }

  /**
   * Save game characteristics to disk.
   *
   * @param xiGameDirectory - the directory to load from.
   */
  public void save(File xiGameDirectory)
  {
    // !! ARR Implement game saving.
  }

  public double getExplorationBias()
  {
    return explorationBias;
  }

  public void setExplorationBias(double value)
  {
    explorationBias = value;
  }

  public double getExactRolloutSampleSize()
  {
    return mExactRolloutSampleSize;
  }

  public int getRolloutSampleSize()
  {
    return mRolloutSampleSize;
  }

  public void setRolloutSampleSize(double xiExactSampleSize)
  {
    int lOldSampleSize = mRolloutSampleSize;

    if (fixedSampleSize <= 0)
    {
      mExactRolloutSampleSize = xiExactSampleSize;

      mRolloutSampleSize = (int)(xiExactSampleSize + 0.5);
    }
    else
    {
      mRolloutSampleSize = fixedSampleSize;
    }

    if (lOldSampleSize != mRolloutSampleSize )
    {
      // Log the new sample rate.
      StringBuffer lLogBuf = new StringBuffer(1024);
      Series.SAMPLE_RATE.logDataPoint(lLogBuf, System.currentTimeMillis(), mRolloutSampleSize);
      STATS_LOGGER.info(lLogBuf.toString());
    }
  }

  public double getCompetitivenessBonus()
  {
    return competitivenessBonus;
  }

  public void setIsFixedMoveCount()
  {
    isFixedMoveCount = true;
  }

  public boolean getIsFixedMoveCount()
  {
    return isFixedMoveCount;
  }

  public void setEarliestCompletionDepth(int value)
  {
    earliestCompletion = value;
  }

  public int getEarliestCompletionDepth()
  {
    return earliestCompletion;
  }

  /**
   * @param xiNumChoices - the number of choices just observed.
   *
   * @return the maximum number of choices that any role has been observed to have.
   */
  public int getChoicesHighWaterMark(int xiNumChoices)
  {
    if (xiNumChoices > mMaxObservedChoices)
    {
      mMaxObservedChoices = xiNumChoices;
    }
    return mMaxObservedChoices;
  }

  /**
   * @return a pre-prepared plan guaranteed to score the highest possible score (for a puzzle).
   */
  public String getPlan()
  {
    return mPlan;
  }

  /**
   * Record a plan, from beginning to end, that's guaranteed to score the highest possible score.
   *
   * @param xiPlan - the plan.
   */
  public void setPlan(String xiPlan)
  {
    mPlan = xiPlan;
  }
}
