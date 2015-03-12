package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

/**
 * @author steve
 * Information structure detailing local search results
 */
public class LocalSearchResults implements LocalRegionDefiner
{
  /**
   * Role for whom a (local) forced win was found or -1 if none
   */
  public int winForRole;
  /**
   * Role for whom a (local) tenuki is a loss or -1 if none
   */
  public int tenukiLossForRole;
  /**
   * Seeding move for the local space
   */
  public ForwardDeadReckonLegalMoveInfo seedMove;
  /**
   * State from which the search began
   */
  public ForwardDeadReckonInternalMachineState startState;
  /**
   * Move found to be a win (or null if none)
   */
  public ForwardDeadReckonLegalMoveInfo winningMove;
  /**
   * Depth of search result found at
   */
  public int atDepth;

  ForwardDeadReckonLegalMoveInfo jointSearchSecondarySeed;
  int searchRadius;
  LocalRegionSearcher searchProvider;

  /**
   * Make this object a shallow copy of source
   * @param source
   */
  public void copyFrom(LocalSearchResults source)
  {
    winForRole = source.winForRole;
    tenukiLossForRole = source.tenukiLossForRole;
    seedMove = source.seedMove;
    startState = source.startState;
    winningMove = source.winningMove;
    atDepth = source.atDepth;
    jointSearchSecondarySeed = source.jointSearchSecondarySeed;
    searchRadius = source.searchRadius;
    searchProvider = source.searchProvider;
  }

  @Override
  public boolean isLocal(ForwardDeadReckonLegalMoveInfo xiMove)
  {
    boolean result = (seedMove == null ? null : (searchProvider.getMoveDistance(seedMove, xiMove) <= searchRadius));
    if ( jointSearchSecondarySeed != null )
    {
      result |= (searchProvider.getMoveDistance(jointSearchSecondarySeed, xiMove) <= searchRadius);
    }
    return result;
  }
}