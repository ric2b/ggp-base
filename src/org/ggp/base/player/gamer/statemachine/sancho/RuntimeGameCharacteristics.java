package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.File;
import java.util.Set;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLPropertiesConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.player.gamer.statemachine.sancho.StatsLogUtils.Series;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;

/**
 * Subclass of GameCharacterisics which adds characteristics that are specific to the game's runtime interpretation and
 * searching by Sancho.  These may vary dynamically.
 */
public class RuntimeGameCharacteristics extends GameCharacteristics
{
  private static final Logger LOGGER = LogManager.getLogger();
  private static final Logger STATS_LOGGER = LogManager.getLogger("stats");

  // The properties file in which game characteristics are stored.
  private static final String PROPS_FILE = "characteristics.xml";

  // Keys under which individual characteristics are stored.
  private static final String PLAN_KEY                      = "plan";
  private static final String NUM_ROLES_KEY                 = "num_roles";
  private static final String SIMULTANEOUS_KEY              = "simultaneous";
  private static final String PSEUDO_SIMULTANEOUS_KEY       = "pseudo_simultaneous";
  private static final String ITERATED_KEY                  = "iterated";
  private static final String NUM_FACTORS_KEY               = "num_factors";
  private static final String MAX_FACTOR_FAILURE_TIME       = "max_factor_failure_time";
  private static final String MOVES_IN_MULTIPLE_FACTORS_KEY = "moves_in_multiple_factors";
  private static final String MAX_BRANCHING_FACTOR_KEY      = "max_branching_factor";
  private static final String FIXED_LENGTH_KEY              = "fixed_length";

  private final XMLPropertiesConfiguration mConfigFile;
  private boolean             mLoadedConfig;
  private double              explorationBias         = 1.0;
  private double              mExactRolloutSampleSize = 1;
  private volatile int        mRolloutSampleSize;
  private int                 mMaxObservedChoices;
  final double                competitivenessBonus    = 2;
  private boolean             isFixedMoveCount        = false;
  private boolean             isFixedSum              = false;
  private int                 earliestCompletion      = 0;
  final private int           fixedSampleSize         = MachineSpecificConfiguration.getCfgInt(CfgItem.FIXED_SAMPLE_SIZE);
  private String              mPlan                   = null;
  private int                 mNumFactors             = 0;
  private int                 mMinLength              = 0;
  private int                 mMaxLength              = -1;
  private double              mAverageLength          = -1;
  private double              mStdDeviationLength     = 0;
  private double              mAverageNonDrawLength   = 0;
  private int                 mMinNonDrawLength       = 0;
  private double              mGoalsStability         = 0;
  private double              mLongDrawsProportion    = 0;
  private long                mMaxFactorFailureTime   = 0;

  /**
   * Create game characteristics, loading any state from previous games.
   *
   * @param xiGameDirectory - the directory to load from.
   */
  public RuntimeGameCharacteristics(File xiGameDirectory)
  {
    super();
    setRolloutSampleSize(1);

    if ( xiGameDirectory != null )
    {
      mConfigFile = loadConfig(xiGameDirectory);
    }
    else
    {
      mConfigFile = null;
    }
  }

  /**
   * Load previously saved per-game configuration from disk.
   *
   * @param xiGameDirectory - the directory to look in.
   *
   * @return the game configuration.
   */
  private XMLPropertiesConfiguration loadConfig(File xiGameDirectory)
  {
    if (MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_LEARNING))
    {
      LOGGER.debug("Learning disabled - not loading configuration");
      return null;
    }

    XMLPropertiesConfiguration lConfigFile = null;

    AbstractConfiguration.setDefaultListDelimiter(':');
    if (xiGameDirectory != null)
    {
      // Load any known game information from previous times we've played this game.
      File lPropsFile = new File(xiGameDirectory, PROPS_FILE);
      try
      {
        lConfigFile = new XMLPropertiesConfiguration(lPropsFile);

        numRoles  = lConfigFile.getInt(NUM_ROLES_KEY, 0);
        mLoadedConfig = (numRoles != 0);

        if (mLoadedConfig)
        {
          isSimultaneousMove             = lConfigFile.getBoolean(SIMULTANEOUS_KEY, false);
          isIteratedGame                 = lConfigFile.getBoolean(ITERATED_KEY, false);
          mNumFactors                    = lConfigFile.getInt(NUM_FACTORS_KEY, 0);
          mMaxFactorFailureTime          = lConfigFile.getLong(MAX_FACTOR_FAILURE_TIME, 0);
          moveChoicesFromMultipleFactors = lConfigFile.getBoolean(MOVES_IN_MULTIPLE_FACTORS_KEY, false);
          mMaxObservedChoices            = lConfigFile.getInt(MAX_BRANCHING_FACTOR_KEY, 1);
          isFixedMoveCount               = lConfigFile.getBoolean(FIXED_LENGTH_KEY, false);
          mPlan                          = lConfigFile.getString(PLAN_KEY, null);
        }
      }
      catch (ConfigurationException lEx)
      {
        LOGGER.warn("Corrupt configuration file: " + lEx);
        mLoadedConfig = false;
      }
    }

    return lConfigFile;
  }

  /**
   * @return true if the game characteristics have been loaded from file or false if they're just default
   * characteristics.
   */
  public boolean isConfigLoaded()
  {
    return mLoadedConfig;
  }

  /**
   * Save game characteristics to disk.
   */
  public void saveConfig()
  {
    if (MachineSpecificConfiguration.getCfgBool(CfgItem.DISABLE_LEARNING))
    {
      LOGGER.debug("Learning disabled - not saving configuration");
      return;
    }

    if (mConfigFile == null)
    {
      // We were unable to open the config file (or create a blank one), so we can't save.
      LOGGER.warn("Unable to save game configuration");
      return;
    }

    // Save what we've learned about this game
    mConfigFile.setProperty(NUM_ROLES_KEY,                 numRoles);
    mConfigFile.setProperty(SIMULTANEOUS_KEY,              isSimultaneousMove);
    mConfigFile.setProperty(PSEUDO_SIMULTANEOUS_KEY,       isPseudoSimultaneousMove);
    mConfigFile.setProperty(ITERATED_KEY,                  isIteratedGame);
    mConfigFile.setProperty(MAX_FACTOR_FAILURE_TIME,       mMaxFactorFailureTime);
    mConfigFile.setProperty(NUM_FACTORS_KEY,               mNumFactors);
    mConfigFile.setProperty(MOVES_IN_MULTIPLE_FACTORS_KEY, moveChoicesFromMultipleFactors);
    mConfigFile.setProperty(MAX_BRANCHING_FACTOR_KEY,      mMaxObservedChoices);
    mConfigFile.setProperty(FIXED_LENGTH_KEY,              isFixedMoveCount);
    if (mPlan != null)
    {
      mConfigFile.setProperty(PLAN_KEY, mPlan);
    }

    try
    {
      mConfigFile.save();
    }
    catch (ConfigurationException lEx)
    {
      LOGGER.warn("Failed to save configuration: " + lEx);
    }
  }

  public double getExplorationBias()
  {
    return explorationBias;
  }

  public void setExplorationBias(double value)
  {
    assert(value >= 0 && value <= 5);
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

  public void setIsFixedSum()
  {
    isFixedSum = true;
  }

  public boolean getIsFixedSum()
  {
    return isFixedSum;
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

  /**
   * Record the factors of a game.
   *
   * @param xiFactors - the factors.
   */
  public void setFactors(Set<Factor> xiFactors)
  {
    // For now, just record the number of factors.
    mNumFactors = xiFactors.size();
  }

  public int getNumFactors()
  {
    return mNumFactors;
  }

  public int getMaxLength()
  {
    return mMaxLength;
  }

  public void setMaxLength(int xiMaxLength)
  {
    mMaxLength = xiMaxLength;
  }

  public double getAverageLength()
  {
    return mAverageLength;
  }

  public void setAverageLength(double xiAverageNumTurns)
  {
    mAverageLength = xiAverageNumTurns;
  }

  public double getStdDeviationLength()
  {
    return mStdDeviationLength;
  }

  public void setStdDeviationLength(double xiStdDeviationLength)
  {
    mStdDeviationLength = xiStdDeviationLength;
  }

  public int getMinLength()
  {
    return mMinLength;
  }

  public void setMinLength(int xiMinLength)
  {
    mMinLength = xiMinLength;
  }

  public double getAverageNonDrawLength()
  {
    return mAverageNonDrawLength;
  }

  public void setAverageNonDrawLength(double xiAverageNonDrawLength)
  {
    mAverageNonDrawLength = xiAverageNonDrawLength;
  }

  public int getMinNonDrawLength()
  {
    return mMinNonDrawLength;
  }

  public void setMinNonDrawLength(int xiMinNonDrawLength)
  {
    mMinNonDrawLength = xiMinNonDrawLength;
  }

  @Override
  public void report()
  {
    super.report();

    LOGGER.info("Statistical characteristics");
    LOGGER.info("  Range of lengths of sample games seen:          [" + getMinLength() + "," + getMaxLength() + "]");
    LOGGER.info("  Average num turns:                              " + getAverageLength());
    LOGGER.info("  Std deviation num turns:                        " + getStdDeviationLength());
    LOGGER.info("  Average num turns for non-drawn result:         " + getAverageNonDrawLength());
    LOGGER.info("  Goals stability:                                " + getGoalsStability());
    LOGGER.info("  Proportion of max length games ending in draws: " + getLongDrawsProportion());
    LOGGER.info("  Num factors:                                    " + getNumFactors());
    LOGGER.info("  Max factor failure time (ms)                    " + getMaxFactorFailureTime());
    LOGGER.info("  IsFixedSum                                      " + getIsFixedSum());
  }

  /**
   * @return goal stability factor (how well goals in non-terminal states predict the end result)
   */
  public double getGoalsStability()
  {
    return mGoalsStability;
  }

  /**
   * @param xiGoalsStability - goal stability factor (how well goals in non-terminal states predict the end result)
   */
  public void setGoalsStability(double xiGoalsStability)
  {
    mGoalsStability = xiGoalsStability;
  }

  /**
   * @return proportion of long games that were draws
   */
  public double getLongDrawsProportion()
  {
    return mLongDrawsProportion;
  }

  /**
   * Setter for proportion of long games that were draws
   * @param xiLongDrawProportion
   */
  public void setLongDrawsProportion(double xiLongDrawProportion)
  {
    mLongDrawsProportion = xiLongDrawProportion;
  }

  /**
   * @return the maximum time, in milliseconds, that we've tried but failed to factor the game in.
   */
  public long getMaxFactorFailureTime()
  {
    return mMaxFactorFailureTime;
  }

  /**
   * Record a failure to factor the game in the specified time.
   *
   * @param xiFailureTime - the time in milliseconds.
   */
  public void factoringFailedAfter(long xiFailureTime)
  {
    if (xiFailureTime > mMaxFactorFailureTime)
    {
      mMaxFactorFailureTime = xiFailureTime;
    }
  }
}
