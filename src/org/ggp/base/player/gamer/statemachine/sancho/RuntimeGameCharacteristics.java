package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;

/**
 * @author steve
 *
 *  Subclass of GameCharacterisics which adds characteristics that are specific to the game's
 *  runtime interpretation and searching by Sancho.  These may vary dynamically
 */
public class RuntimeGameCharacteristics extends GameCharacteristics
{
  private static final Logger STATS_LOGGER = LogManager.getLogger("stats");

  private double                                               explorationBias                             = 1.0;
  private double                                               mExactRolloutSampleSize                     = 4;
  private volatile int                                         mRolloutSampleSize;
  private int                                                  mMaxObservedChoices;
  final double                                                 competitivenessBonus                        = 2;
  private boolean                                              isFixedMoveCount                            = false;
  private int                                                  earliestCompletion                          = 0;
  final private int                                            fixedSampleSize                             = MachineSpecificConfiguration.getCfgVal(CfgItem.FIXED_SAMPLE_SIZE, -1);

  public RuntimeGameCharacteristics(int numRoles)
  {
    super(numRoles);
    setRolloutSampleSize(4);
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
}
