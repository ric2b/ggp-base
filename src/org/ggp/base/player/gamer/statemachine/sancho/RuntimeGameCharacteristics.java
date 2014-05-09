package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 *
 *  Subclass of GameCharacterisics which adds characteristics that are specific to the game's
 *  runtime interpretation and searching by Sancho.  These may vary dynamically
 */
public class RuntimeGameCharacteristics extends GameCharacteristics
{
  private final boolean                                        enableMoveActionHistory                     = false;
  private double                                               explorationBias                             = 1.0;
  private double                                               moveActionHistoryBias                       = 0;
  private volatile int                                         rolloutSampleSize                           = 4;
  final double                                                 competitivenessBonus                        = 2;
  private boolean                                              isFixedMoveCount                            = false;

  public RuntimeGameCharacteristics(int numRoles)
  {
    super(numRoles);
  }

  public double getExplorationBias()
  {
    return explorationBias;
  }

  public void setExplorationBias(double value)
  {
    explorationBias = value;
  }

  public boolean getMoveActionHistoryEnabled()
  {
    return enableMoveActionHistory;
  }

  public double getMoveActionHistoryBias()
  {
    return moveActionHistoryBias;
  }

  public void setMoveActionBias(double value)
  {
    moveActionHistoryBias = value;
  }

  public int getRolloutSampleSize()
  {
    return rolloutSampleSize;
  }

  public void setRolloutSampleSize(int value)
  {
    rolloutSampleSize = value;
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
}
